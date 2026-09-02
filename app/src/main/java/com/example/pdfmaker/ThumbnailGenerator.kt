package com.example.pdfmaker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.graphics.toColorInt
import java.io.File

/** Selects a bounded renderer for one validated source file. */
internal object ThumbnailGenerator {
    fun generate(
        filePath: String,
        sizePx: Int,
        checkCancellation: () -> Unit,
    ): Bitmap? {
        ThumbnailGenerationPolicy.requireValidSize(sizePx)
        val file = File(filePath)
        if (!ThumbnailInput.isAllowedSource(file)) return null
        checkCancellation()

        return when (ThumbnailGenerationPolicy.classify(filePath)) {
            ThumbnailSourceKind.PDF -> renderPdf(file, sizePx, checkCancellation)
            ThumbnailSourceKind.IMAGE -> renderImage(file, sizePx, checkCancellation)
            ThumbnailSourceKind.WORD_PROCESSING -> ThumbnailOfficeSource.renderWord(file, sizePx, checkCancellation)
            ThumbnailSourceKind.PRESENTATION -> ThumbnailOfficeSource.renderPresentation(file, sizePx, checkCancellation)
            ThumbnailSourceKind.SPREADSHEET -> ThumbnailOfficeSource.renderSpreadsheet(file, sizePx, checkCancellation)
            ThumbnailSourceKind.DELIMITED_TEXT -> renderDelimitedText(file, sizePx, checkCancellation)
            ThumbnailSourceKind.PLAIN_TEXT -> renderPlainText(file, sizePx, checkCancellation)
            ThumbnailSourceKind.UNSUPPORTED -> null
        }
    }

    private fun renderPdf(
        file: File,
        sizePx: Int,
        checkCancellation: () -> Unit,
    ): Bitmap? =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                if (renderer.pageCount == 0) return@use null
                renderer.openPage(0).use { page ->
                    checkCancellation()
                    renderPdfPage(page, sizePx)?.validateCancellation(checkCancellation)
                }
            }
        }

    private fun renderPdfPage(
        page: PdfRenderer.Page,
        sizePx: Int,
    ): Bitmap? {
        val target = RenderSizing.fitWithin(page.width, page.height, sizePx, allowUpscale = true) ?: return null
        val scale = RenderSizing.scaleTo(page.width, page.height, target) ?: return null
        val transform = Matrix().apply { setScale(scale.scaleX, scale.scaleY) }
        val bitmap = createBitmap(target.width, target.height)
        var completed = false
        try {
            Canvas(bitmap).drawColor(Color.WHITE)
            page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            completed = true
            return bitmap
        } finally {
            if (!completed) bitmap.recycle()
        }
    }

    private fun renderImage(
        file: File,
        sizePx: Int,
        checkCancellation: () -> Unit,
    ): Bitmap? {
        val source = file.inputStream().use { input -> ThumbnailInput.decodeImage(input, sizePx) } ?: return null
        var keepSource = false
        return try {
            checkCancellation()
            val target = RenderSizing.fitWithin(source.width, source.height, sizePx, allowUpscale = true) ?: return null
            if (source.width == target.width && source.height == target.height) {
                keepSource = true
                return source
            }
            source.scale(target.width, target.height)
        } finally {
            if (!keepSource) source.recycle()
        }
    }

    private fun Bitmap.validateCancellation(checkCancellation: () -> Unit): Bitmap {
        var accepted = false
        try {
            checkCancellation()
            accepted = true
            return this
        } finally {
            if (!accepted && !isRecycled) recycle()
        }
    }

    private fun renderDelimitedText(
        file: File,
        sizePx: Int,
        checkCancellation: () -> Unit,
    ): Bitmap {
        val rows =
            ThumbnailGenerationPolicy.delimitedRows(
                ThumbnailInput.readTextPrefix(file),
                ThumbnailGenerationPolicy.delimiter(file.path),
            )
        checkCancellation()
        return ThumbnailCanvasRenderer.table(sizePx, rows)
    }

    private fun renderPlainText(
        file: File,
        sizePx: Int,
        checkCancellation: () -> Unit,
    ): Bitmap {
        val lines = ThumbnailGenerationPolicy.visibleTextLines(ThumbnailInput.readTextPrefix(file))
        checkCancellation()
        return ThumbnailCanvasRenderer.document(
            sizePx = sizePx,
            badgeLabel = "TXT",
            badgeColor = "#37474F".toColorInt(),
            texts = lines,
            image = null,
        )
    }
}
