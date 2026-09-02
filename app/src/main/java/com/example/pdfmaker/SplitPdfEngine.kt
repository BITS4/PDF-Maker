package com.example.pdfmaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@Suppress("TooGenericExceptionCaught")
internal suspend fun loadSplitPdfPreview(source: StagedPdfSource): SplitPdfPreview {
    val operationContext = currentCoroutineContext()
    return withSplitPdfRenderer(source) { renderer ->
        val pageCount = PageEditPolicy.requireSupportedPageCount(renderer.pageCount)
        val loadedPages = mutableListOf<Bitmap>()
        try {
            repeat(pageCount) { pageIndex ->
                operationContext.ensureActive()
                renderer.openPage(pageIndex).use { page ->
                    val plan = SplitPreviewPolicy.plan(pageCount, page.width, page.height)
                    val bitmap = Bitmap.createBitmap(
                        plan.thumbnailSize.width,
                        plan.thumbnailSize.height,
                        Bitmap.Config.ARGB_8888,
                    )
                    try {
                        Canvas(bitmap).drawColor(Color.WHITE)
                        val transform = Matrix().apply {
                            setScale(
                                plan.thumbnailSize.width.toFloat() / page.width.toFloat(),
                                plan.thumbnailSize.height.toFloat() / page.height.toFloat(),
                            )
                        }
                        page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        operationContext.ensureActive()
                        loadedPages += bitmap
                    } catch (error: Throwable) {
                        bitmap.recycle()
                        throw error
                    }
                }
            }
            operationContext.ensureActive()
            SplitPdfPreview(pageCount, loadedPages)
        } catch (error: Throwable) {
            BitmapOwnership.retire(loadedPages)
            throw error
        }
    }
}

internal suspend fun doSplitPdf(
    context: Context,
    source: StagedPdfSource,
    pages: List<Int>,
    baseName: String,
): Pair<String, String> {
    val operationContext = currentCoroutineContext()
    val outputFile = withSplitPdfRenderer(source) { renderer ->
        PageEditPolicy.requireSupportedPageCount(renderer.pageCount)
        val selectedPages = PageSelectionPolicy.resolve(
            totalPages = renderer.pageCount,
            selection = PageSelection.Custom(pages),
        )
        require(selectedPages.isNotEmpty() && selectedPages.size == pages.distinct().size) {
            "Select only pages that belong to this PDF"
        }

        val outputDocument = PdfDocument()
        try {
            selectedPages.forEachIndexed { outputIndex, pageNumber ->
                operationContext.ensureActive()
                renderer.openPage(pageNumber - 1).use { sourcePage ->
                    val target = RenderSizing.fitWithin(
                        sourceWidth = sourcePage.width,
                        sourceHeight = sourcePage.height,
                        maxDimension = 1_600,
                        allowUpscale = true,
                    ) ?: error("PDF page has invalid dimensions")
                    val bitmap = Bitmap.createBitmap(
                        target.width,
                        target.height,
                        Bitmap.Config.ARGB_8888,
                    )
                    try {
                        Canvas(bitmap).drawColor(Color.WHITE)
                        val transform = Matrix().apply {
                            setScale(
                                target.width.toFloat() / sourcePage.width.toFloat(),
                                target.height.toFloat() / sourcePage.height.toFloat(),
                            )
                        }
                        sourcePage.render(
                            bitmap,
                            null,
                            transform,
                            PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                        )
                        operationContext.ensureActive()
                        val pageInfo = PdfDocument.PageInfo.Builder(
                            target.width,
                            target.height,
                            outputIndex + 1,
                        ).create()
                        val outputPage = outputDocument.startPage(pageInfo)
                        outputPage.canvas.drawBitmap(bitmap, 0f, 0f, null)
                        outputDocument.finishPage(outputPage)
                    } finally {
                        bitmap.recycle()
                    }
                }
            }

            val label = if (selectedPages.size == 1) {
                "p${selectedPages.first()}"
            } else {
                "p${selectedPages.first()}-${selectedPages.last()}"
            }
            OutputStore.writeUnique(
                directory = getPdfMakerDir(context),
                requestedBaseName = "${baseName}_split_$label",
                extension = "pdf",
                beforeCommit = { operationContext.ensureActive() },
            ) { output ->
                operationContext.ensureActive()
                outputDocument.writeTo(BoundedIo.limit(output, SplitPreviewPolicy.MAX_OUTPUT_BYTES) {
                    operationContext.ensureActive()
                })
            }
        } finally {
            outputDocument.close()
        }
    }
    return outputFile.absolutePath to outputFile.name
}

@Suppress("TooGenericExceptionCaught")
private inline fun <T> withSplitPdfRenderer(
    source: StagedPdfSource,
    block: (PdfRenderer) -> T,
): T {
    val descriptor = source.openDescriptor()
    val renderer = try {
        PdfRenderer(descriptor)
    } catch (error: Throwable) {
        descriptor.close()
        throw error
    }
    return renderer.use(block)
}
