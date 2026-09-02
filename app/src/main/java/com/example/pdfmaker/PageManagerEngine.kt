package com.example.pdfmaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal suspend fun loadPageStates(context: Context, uri: Uri): List<PageState> {
    val loadContext = currentCoroutineContext()
    return withSafePdfRenderer(context, uri) { renderer ->
        val pageCount = PageEditPolicy.requireSupportedPageCount(renderer.pageCount)
        val loadedPages = mutableListOf<PageState>()
        try {
            repeat(pageCount) { pageIndex ->
                loadContext.ensureActive()
                renderer.openPage(pageIndex).use { page ->
                    val target = RenderSizing.fitWithin(page.width, page.height, 200)
                        ?: error("PDF page has invalid dimensions")
                    val bitmap = Bitmap.createBitmap(
                        target.width,
                        target.height,
                        Bitmap.Config.ARGB_8888,
                    )
                    try {
                        Canvas(bitmap).drawColor(Color.WHITE)
                        val scale = requireNotNull(
                            RenderSizing.scaleTo(page.width, page.height, target),
                        ) { "PDF preview has invalid dimensions" }
                        val transform = Matrix().apply { setScale(scale.scaleX, scale.scaleY) }
                        loadContext.ensureActive()
                        page.render(
                            bitmap,
                            null,
                            transform,
                            PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                        )
                        loadContext.ensureActive()
                        loadedPages += PageState(bitmap)
                    } catch (error: Throwable) {
                        bitmap.recycle()
                        throw error
                    }
                }
            }
            loadContext.ensureActive()
            loadedPages
        } catch (error: Throwable) {
            recyclePageStates(loadedPages)
            throw error
        }
    }
}

internal fun recyclePageStates(pages: Iterable<PageState>) {
    pages.forEach { page ->
        if (!page.bitmap.isRecycled) page.bitmap.recycle()
    }
}

internal fun savePages(
    context: Context,
    uri: Uri,
    pages: List<PageState>,
    baseName: String,
): Pair<String, String>? = runCatching {
    val outputFile = withSafePdfRenderer(context, uri) { renderer ->
        PageEditPolicy.requireSupportedPageCount(renderer.pageCount)
        require(pages.size == renderer.pageCount) {
            "The PDF changed after its page preview was loaded"
        }
        val retainedIndexes = PageEditPolicy.retainedIndexes(pages.map { it.deleted })
        require(retainedIndexes.isNotEmpty()) { "At least one page must be retained" }

        val outputDocument = PdfDocument()
        try {
            retainedIndexes.forEachIndexed { outputIndex, sourceIndex ->
                renderer.openPage(sourceIndex).use { sourcePage ->
                    val target = RenderSizing.fitWithin(
                        sourceWidth = sourcePage.width,
                        sourceHeight = sourcePage.height,
                        maxDimension = 1_600,
                        allowUpscale = true,
                    ) ?: error("PDF page has invalid dimensions")
                    val sourceBitmap = Bitmap.createBitmap(
                        target.width,
                        target.height,
                        Bitmap.Config.ARGB_8888,
                    )
                    var outputBitmap = sourceBitmap
                    try {
                        Canvas(sourceBitmap).drawColor(Color.WHITE)
                        val scale = requireNotNull(
                            RenderSizing.scaleTo(sourcePage.width, sourcePage.height, target),
                        ) { "PDF page has invalid dimensions" }
                        val transform = Matrix().apply { setScale(scale.scaleX, scale.scaleY) }
                        sourcePage.render(
                            sourceBitmap,
                            null,
                            transform,
                            PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                        )
                        val rotation = PageEditPolicy.normalizeRotation(pages[sourceIndex].rotation)
                        if (rotation != 0) {
                            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                            outputBitmap = Bitmap.createBitmap(
                                sourceBitmap,
                                0,
                                0,
                                sourceBitmap.width,
                                sourceBitmap.height,
                                matrix,
                                true,
                            )
                        }
                        val pageInfo = PdfDocument.PageInfo.Builder(
                            outputBitmap.width,
                            outputBitmap.height,
                            outputIndex + 1,
                        ).create()
                        val outputPage = outputDocument.startPage(pageInfo)
                        outputPage.canvas.drawBitmap(outputBitmap, 0f, 0f, null)
                        outputDocument.finishPage(outputPage)
                    } finally {
                        if (outputBitmap !== sourceBitmap && !outputBitmap.isRecycled) {
                            outputBitmap.recycle()
                        }
                        if (!sourceBitmap.isRecycled) sourceBitmap.recycle()
                    }
                }
            }
            OutputStore.writeUnique(
                directory = getPdfMakerDir(context),
                requestedBaseName = "${baseName}_edited",
                extension = "pdf",
            ) { outputDocument.writeTo(it) }
        } finally {
            outputDocument.close()
        }
    }
    outputFile.absolutePath to outputFile.name
}.getOrNull()
