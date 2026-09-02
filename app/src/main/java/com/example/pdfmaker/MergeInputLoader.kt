package com.example.pdfmaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

// Provider implementations fail in many platform-specific ways; each failure is returned to the UI.
@Suppress("TooGenericExceptionCaught")
internal suspend fun loadMergeInputs(
    context: Context,
    uris: List<Uri>,
    currentPageCount: Int,
): MergeInputBatch {
    val operationContext = currentCoroutineContext()
    val loaded = mutableListOf<MergeItem>()
    val rejected = mutableListOf<String>()
    var runningPageCount = currentPageCount
    var ownershipTransferred = false
    try {
        for (uri in uris) {
            operationContext.ensureActive()
            try {
                SafePdfInput.fromUri(context, uri).use { source ->
                    withMergeInputRenderer(source) { renderer ->
                        runningPageCount = MergePdfPolicy.updatedTotalPages(
                            runningPageCount,
                            renderer.pageCount,
                        )
                        val preview = renderMergeInputPreview(renderer)
                        loaded += MergeItem(
                            uri = uri,
                            name = uri.lastPathSegment
                                ?.substringAfterLast("/")
                                ?.substringAfterLast("%2F")
                                ?.removeSuffix(".pdf")
                                ?.take(40)
                                ?: "document",
                            sizeKb = source.file.length() / 1024,
                            pageCount = renderer.pageCount,
                            thumb = preview,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                rejected +=
                    UserVisibleFailureReporter.message(
                        UserFailureStage.MERGE_INPUT_LOAD,
                        error,
                    )
            }
        }
        ownershipTransferred = true
        return MergeInputBatch(loaded, rejected)
    } finally {
        if (!ownershipTransferred) {
            BitmapOwnership.retire(loaded.mapNotNull(MergeItem::thumb))
        }
    }
}

private fun renderMergeInputPreview(renderer: PdfRenderer): Bitmap =
    renderer.openPage(0).use { page ->
        val size = RenderSizing.fitWithin(
            page.width,
            page.height,
            300,
            allowUpscale = true,
        ) ?: error("PDF page has invalid dimensions")
        val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        var completed = false
        try {
            Canvas(bitmap).drawColor(Color.WHITE)
            val transform = Matrix().apply {
                setScale(
                    size.width.toFloat() / page.width.toFloat(),
                    size.height.toFloat() / page.height.toFloat(),
                )
            }
            page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            completed = true
            bitmap
        } finally {
            if (!completed) bitmap.recycle()
        }
    }

private inline fun <T> withMergeInputRenderer(
    source: StagedPdfSource,
    block: (PdfRenderer) -> T,
): T {
    val descriptor = source.openDescriptor()
    return descriptor.use {
        PdfRenderer(it).use(block)
    }
}
