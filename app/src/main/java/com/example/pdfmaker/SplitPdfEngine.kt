package com.example.pdfmaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri

internal fun doSplitPdf(
    context: Context,
    uri: Uri,
    pages: List<Int>,
    baseName: String,
): Pair<String, String>? = runCatching {
    val outputFile = withSafePdfRenderer(context, uri) { renderer ->
            val selectedPages = PageSelectionPolicy.resolve(
                totalPages = renderer.pageCount,
                selection = PageSelection.Custom(pages),
            )
            require(selectedPages.isNotEmpty()) { "Select at least one valid page" }

            val outputDocument = PdfDocument()
            try {
                selectedPages.forEachIndexed { outputIndex, pageNumber ->
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
                            sourcePage.render(
                                bitmap,
                                null,
                                null,
                                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                            )
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
                ) { outputDocument.writeTo(it) }
            } finally {
                outputDocument.close()
            }
        }
    outputFile.absolutePath to outputFile.name
}.getOrNull()



