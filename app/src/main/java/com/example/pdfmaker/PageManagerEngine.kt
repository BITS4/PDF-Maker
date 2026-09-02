package com.example.pdfmaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import timber.log.Timber
import java.io.File
import java.io.IOException
import kotlin.coroutines.CoroutineContext

internal suspend fun loadPageStates(source: StagedPdfSource): List<PageState> {
    val operationContext = currentCoroutineContext()
    return withPageManagerRenderer(source) { renderer ->
        val pageCount = PageEditPolicy.requireSupportedPageCount(renderer.pageCount)
        val loadedPages = mutableListOf<PageState>()
        var completed = false
        try {
            repeat(pageCount) { pageIndex ->
                operationContext.ensureActive()
                loadedPages += renderPreviewPage(renderer, pageIndex, operationContext)
            }
            operationContext.ensureActive()
            completed = true
            loadedPages
        } finally {
            if (!completed) recyclePageStates(loadedPages)
        }
    }
}

internal suspend fun savePages(
    context: Context,
    source: StagedPdfSource,
    edits: List<PageEdit>,
    baseName: String,
    beforeOutputCommit: () -> Unit = {},
): PageManagerSaveResult =
    try {
        savePagesOrThrow(context, source, edits, baseName, beforeOutputCommit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: IOException) {
        PageManagerSaveResult.Failed(PageManagerFailureStage.SAVE, error)
    } catch (error: SecurityException) {
        PageManagerSaveResult.Failed(PageManagerFailureStage.SAVE, error)
    } catch (error: IllegalArgumentException) {
        PageManagerSaveResult.Failed(PageManagerFailureStage.VERIFY, error)
    } catch (error: IllegalStateException) {
        PageManagerSaveResult.Failed(PageManagerFailureStage.SAVE, error)
    }

internal fun recyclePageStates(pages: Iterable<PageState>) {
    pages.forEach { page ->
        if (!page.bitmap.isRecycled) page.bitmap.recycle()
    }
}

private fun renderPreviewPage(
    renderer: PdfRenderer,
    pageIndex: Int,
    operationContext: CoroutineContext,
): PageState =
    renderer.openPage(pageIndex).use { page ->
        val target =
            RenderSizing.fitWithin(page.width, page.height, 200)
                ?: error("PDF page has invalid dimensions")
        val bitmap = Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
        var completed = false
        try {
            Canvas(bitmap).drawColor(Color.WHITE)
            val scale =
                requireNotNull(RenderSizing.scaleTo(page.width, page.height, target)) {
                    "PDF preview has invalid dimensions"
                }
            operationContext.ensureActive()
            page.render(
                bitmap,
                null,
                Matrix().apply { setScale(scale.scaleX, scale.scaleY) },
                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
            )
            operationContext.ensureActive()
            PageState(bitmap).also { completed = true }
        } finally {
            if (!completed) bitmap.recycle()
        }
    }

private suspend fun savePagesOrThrow(
    context: Context,
    source: StagedPdfSource,
    edits: List<PageEdit>,
    baseName: String,
    beforeOutputCommit: () -> Unit,
): PageManagerSaveResult.Saved {
    val operationContext = currentCoroutineContext()
    var unverifiedOutput: File? = null
    try {
        val output =
            withPageManagerRenderer(source) { renderer ->
                PageEditPolicy.requireSupportedPageCount(renderer.pageCount)
                require(edits.size == renderer.pageCount) { "Page edits do not match the staged PDF" }
                val retainedIndexes = PageEditPolicy.retainedIndexes(edits.map(PageEdit::deleted))
                require(retainedIndexes.isNotEmpty()) { "At least one page must be retained" }
                writeEditedPdf(
                    context = context,
                    renderer = renderer,
                    edits = edits,
                    retainedIndexes = retainedIndexes,
                    baseName = baseName,
                    operationContext = operationContext,
                    beforeOutputCommit = beforeOutputCommit,
                )
            }
        unverifiedOutput = output
        operationContext.ensureActive()
        require(ImportedDocumentInspector.inspect(output) == IncomingDocumentKind.PDF) {
            "Edited output is not a valid PDF"
        }
        val outputPageCount = PdfFileMetadata.pageCount(output)
        require(outputPageCount == edits.count { !it.deleted }) { "Edited output page count is inconsistent" }
        operationContext.ensureActive()
        unverifiedOutput = null
        return PageManagerSaveResult.Saved(output, outputPageCount)
    } finally {
        unverifiedOutput?.let(::deleteUnverifiedOutput)
    }
}

private fun writeEditedPdf(
    context: Context,
    renderer: PdfRenderer,
    edits: List<PageEdit>,
    retainedIndexes: List<Int>,
    baseName: String,
    operationContext: CoroutineContext,
    beforeOutputCommit: () -> Unit,
): File {
    val outputDocument = PdfDocument()
    try {
        retainedIndexes.forEachIndexed { outputIndex, sourceIndex ->
            operationContext.ensureActive()
            appendEditedPage(
                renderer = renderer,
                outputDocument = outputDocument,
                sourceIndex = sourceIndex,
                outputIndex = outputIndex,
                rotation = edits[sourceIndex].rotation,
                operationContext = operationContext,
            )
        }
        operationContext.ensureActive()
        return OutputStore.writeUnique(
            directory = getPdfMakerDir(context),
            requestedBaseName = "${baseName}_edited",
            extension = "pdf",
            beforeCommit = {
                operationContext.ensureActive()
                beforeOutputCommit()
            },
        ) { output ->
            outputDocument.writeTo(
                BoundedIo.limit(
                    output = output,
                    maximumBytes = PageManagerPolicy.MAX_OUTPUT_BYTES,
                    beforeWrite = { operationContext.ensureActive() },
                ),
            )
        }
    } finally {
        outputDocument.close()
    }
}

private fun appendEditedPage(
    renderer: PdfRenderer,
    outputDocument: PdfDocument,
    sourceIndex: Int,
    outputIndex: Int,
    rotation: Int,
    operationContext: CoroutineContext,
) {
    renderer.openPage(sourceIndex).use { sourcePage ->
        val target =
            requireNotNull(
                RenderSizing.fitWithin(
                    sourceWidth = sourcePage.width,
                    sourceHeight = sourcePage.height,
                    maxDimension = 1_600,
                    allowUpscale = true,
                ),
            ) { "PDF page has invalid dimensions" }
        val sourceBitmap = Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
        var outputBitmap = sourceBitmap
        try {
            Canvas(sourceBitmap).drawColor(Color.WHITE)
            val scale =
                requireNotNull(RenderSizing.scaleTo(sourcePage.width, sourcePage.height, target)) {
                    "PDF page has invalid dimensions"
                }
            operationContext.ensureActive()
            sourcePage.render(
                sourceBitmap,
                null,
                Matrix().apply { setScale(scale.scaleX, scale.scaleY) },
                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
            )
            operationContext.ensureActive()
            val normalizedRotation = PageEditPolicy.normalizeRotation(rotation)
            if (normalizedRotation != 0) {
                outputBitmap =
                    Bitmap.createBitmap(
                        sourceBitmap,
                        0,
                        0,
                        sourceBitmap.width,
                        sourceBitmap.height,
                        Matrix().apply { postRotate(normalizedRotation.toFloat()) },
                        true,
                    )
            }
            operationContext.ensureActive()
            appendBitmapPage(outputDocument, outputBitmap, outputIndex + 1)
        } finally {
            if (outputBitmap !== sourceBitmap && !outputBitmap.isRecycled) outputBitmap.recycle()
            if (!sourceBitmap.isRecycled) sourceBitmap.recycle()
        }
    }
}

private fun appendBitmapPage(
    outputDocument: PdfDocument,
    bitmap: Bitmap,
    pageNumber: Int,
) {
    val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, pageNumber).create()
    val outputPage = outputDocument.startPage(pageInfo)
    try {
        outputPage.canvas.drawBitmap(bitmap, 0f, 0f, null)
    } finally {
        outputDocument.finishPage(outputPage)
    }
}

private inline fun <T> withPageManagerRenderer(
    source: StagedPdfSource,
    block: (PdfRenderer) -> T,
): T {
    val descriptor = source.openDescriptor()
    val renderer =
        try {
            PdfRenderer(descriptor)
        } catch (expected: Exception) {
            descriptor.close()
            throw expected
        }
    return renderer.use(block)
}

private fun deleteUnverifiedOutput(file: File) {
    if (file.exists() && !file.delete()) {
        Timber.tag("PageManager").w("event=unverified_output_delete_failed")
    }
}
