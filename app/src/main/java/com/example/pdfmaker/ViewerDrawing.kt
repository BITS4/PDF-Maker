package com.example.pdfmaker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlinx.coroutines.flow.FlowCollector

internal suspend fun FlowCollector<Bitmap>.emitViewerTablePages(
    rows: List<List<String>>,
    columnCount: Int,
    width: Int,
    hasHeader: Boolean,
) {
    val pageHeight = (width * 1.414f).toInt()
    val margin = (width * 0.04f).toInt()
    val columnWidth = ((width - margin * 2) / columnCount.toFloat()).toInt().coerceAtLeast(60)
    val rowHeight = (width * 0.045f).toInt()
    val textSize = width * 0.022f
    val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1565C0") }
    val evenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F5F8FF") }
    val oddPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    val borderPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#DDDDEE")
            strokeWidth = 1f
        }
    val textPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            this.textSize = textSize
        }
    val headerTextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            typeface = Typeface.DEFAULT_BOLD
        }
    var bitmap = newViewerPage(width, pageHeight)
    var canvas = Canvas(bitmap)
    var currentY = margin

    suspend fun flushPage() {
        emit(bitmap)
        bitmap = newViewerPage(width, pageHeight)
        canvas = Canvas(bitmap)
        currentY = margin
    }

    rows.forEachIndexed { rowIndex, row ->
        if (currentY + rowHeight > pageHeight - margin) flushPage()
        val background =
            when {
                rowIndex == 0 && hasHeader -> headerPaint
                rowIndex % 2 == 0 -> evenPaint
                else -> oddPaint
            }
        canvas.drawRect(
            margin.toFloat(),
            currentY.toFloat(),
            (width - margin).toFloat(),
            (currentY + rowHeight).toFloat(),
            background,
        )
        repeat(columnCount) { column ->
            val cell = row.getOrElse(column) { "" }
            val x = margin + column * columnWidth
            val paint = if (rowIndex == 0 && hasHeader) headerTextPaint else textPaint
            val maxCharacters = (columnWidth / (textSize * 0.55f)).toInt().coerceAtLeast(3)
            val display = if (cell.length > maxCharacters) cell.take(maxCharacters - 1) + "…" else cell
            canvas.drawText(display, x + 6f, currentY + rowHeight * 0.65f, paint)
            canvas.drawLine(
                (x + columnWidth).toFloat(),
                currentY.toFloat(),
                (x + columnWidth).toFloat(),
                (currentY + rowHeight).toFloat(),
                borderPaint,
            )
        }
        canvas.drawLine(
            margin.toFloat(),
            (currentY + rowHeight).toFloat(),
            (width - margin).toFloat(),
            (currentY + rowHeight).toFloat(),
            borderPaint,
        )
        currentY += rowHeight
    }
    if (currentY >= margin + rowHeight) emit(bitmap) else bitmap.recycle()
}

internal fun newViewerPage(
    width: Int,
    height: Int,
): Bitmap =
    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
        Canvas(it).drawColor(Color.WHITE)
    }

internal fun buildViewerStaticLayout(
    text: CharSequence,
    paint: TextPaint,
    width: Int,
): StaticLayout =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        StaticLayout.Builder
            .obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(2f, 1.25f)
            .setIncludePad(false)
            .build()
    } else {
        @Suppress("DEPRECATION")
        StaticLayout(
            text,
            paint,
            width.coerceAtLeast(1),
            Layout.Alignment.ALIGN_NORMAL,
            1.25f,
            2f,
            false,
        )
    }
