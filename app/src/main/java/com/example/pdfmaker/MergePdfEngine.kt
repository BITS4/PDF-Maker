package com.example.pdfmaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

// ── Core merge logic ──────────────────────────────────────────────────────────

internal fun mergePdfs(
    context  : Context,
    uris     : List<Uri>,
    baseName : String,
    onProg   : (Int, String) -> Unit
): File? {
    return try {
        val pdfDoc  = PdfDocument()
        var pageNum = 1
        val total   = uris.size

        uris.forEachIndexed { fileIdx, uri ->
            onProg(
                (fileIdx * 90 / total),
                "Processing file ${fileIdx + 1} of $total…"
            )
            val fd  = context.contentResolver.openFileDescriptor(uri, "r") ?: return@forEachIndexed
            val rdr = PdfRenderer(fd)
            val wPx = context.resources.displayMetrics.widthPixels

            for (i in 0 until rdr.pageCount) {
                val page  = rdr.openPage(i)
                val w     = page.width.coerceAtLeast(1)
                val h     = page.height.coerceAtLeast(1)
                val bmp   = android.graphics.Bitmap.createBitmap(w, h,
                                android.graphics.Bitmap.Config.ARGB_8888)
                android.graphics.Canvas(bmp).drawColor(android.graphics.Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()

                val info  = PdfDocument.PageInfo.Builder(w, h, pageNum++).create()
                val pg    = pdfDoc.startPage(info)
                pg.canvas.drawBitmap(bmp, 0f, 0f, null)
                pdfDoc.finishPage(pg)
                bmp.recycle()
            }
            rdr.close()
            fd.close()
        }

        onProg(95, "Saving…")
        val dir     = getPdfMakerDir(context)
        val outFile = File(dir, "$baseName.pdf")
        outFile.outputStream().use { pdfDoc.writeTo(it) }
        pdfDoc.close()
        onProg(100, "Done!")
        outFile
    } catch (_: Exception) { null }
}

// ── Share merged file ─────────────────────────────────────────────────────────

internal fun shareMergedFile(context: Context, file: File) {
    try {
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
    } catch (_: Exception) {}
}

// ── Helpers ───────────────────────────────────────────────────────────────────

internal fun mergeFormatSize(kb: Long): String = when {
    kb >= 1024 -> "%.1f MB".format(kb / 1024f)
    else       -> "$kb KB"
}

