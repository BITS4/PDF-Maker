package com.example.pdfmaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

// ── Core merge logic ──────────────────────────────────────────────────────────

internal suspend fun mergePdfs(
    context  : Context,
    uris     : List<Uri>,
    baseName : String,
    onProg   : (Int, String) -> Unit
): File {
    val operationContext = currentCoroutineContext()
    val pdfDocument = PdfDocument()
    try {
        MergePdfPolicy.requireSourceCount(uris.size)
        var pageNum = 1
        val total   = uris.size

        uris.forEachIndexed { fileIdx, uri ->
            operationContext.ensureActive()
            onProg(
                (fileIdx * 90 / total),
                "Processing file ${fileIdx + 1} of $total…"
            )
            SafePdfInput.fromUri(context, uri).use { source ->
                val descriptor = source.openDescriptor()
                val renderer = try {
                    PdfRenderer(descriptor)
                } catch (error: Exception) {
                    descriptor.close()
                    throw error
                }
                try {
                    MergePdfPolicy.updatedTotalPages(pageNum - 1, renderer.pageCount)
                    for (index in 0 until renderer.pageCount) {
                        operationContext.ensureActive()
                        val page = renderer.openPage(index)
                        try {
                            val size = RenderSizing.fitWithin(page.width, page.height, 2_000)
                                ?: error("PDF page has invalid dimensions")
                            val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
                            try {
                                android.graphics.Canvas(bitmap).drawColor(android.graphics.Color.WHITE)
                                val matrix = android.graphics.Matrix().apply {
                                    setScale(
                                        size.width.toFloat() / page.width.toFloat(),
                                        size.height.toFloat() / page.height.toFloat(),
                                    )
                                }
                                page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                operationContext.ensureActive()
                                val info = PdfDocument.PageInfo.Builder(size.width, size.height, pageNum++).create()
                                val outputPage = pdfDocument.startPage(info)
                                outputPage.canvas.drawBitmap(bitmap, 0f, 0f, null)
                                pdfDocument.finishPage(outputPage)
                            } finally {
                                bitmap.recycle()
                            }
                        } finally {
                            page.close()
                        }
                    }
                } finally {
                    renderer.close()
                }
            }
        }

        onProg(95, "Saving…")
        val output = OutputStore.writeUnique(
            directory = getPdfMakerDir(context),
            requestedBaseName = baseName,
            extension = "pdf",
            beforeCommit = { operationContext.ensureActive() },
        ) {
            operationContext.ensureActive()
            pdfDocument.writeTo(BoundedIo.limit(it, MergePdfPolicy.MAX_OUTPUT_BYTES) {
                operationContext.ensureActive()
            })
        }
        onProg(100, "Done!")
        return output
    } finally {
        pdfDocument.close()
    }
}

// ── Share merged file ─────────────────────────────────────────────────────────

internal fun shareMergedFile(context: Context, file: File): Result<Unit> =
    runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.provider", file
        )
        context.startActivity(
            android.content.Intent.createChooser(
                android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Share merged PDF"
            )
        )
    }

// ── Helpers ───────────────────────────────────────────────────────────────────

internal fun mergeFormatSize(kb: Long): String = when {
    kb >= 1024 -> "%.1f MB".format(kb / 1024f)
    else       -> "$kb KB"
}
