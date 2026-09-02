package com.example.pdfmaker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlinx.coroutines.flow.FlowCollector

private data class TablePaints(
    val headerBackground: Paint,
    val evenBackground: Paint,
    val oddBackground: Paint,
    val border: Paint,
    val text: TextPaint,
    val headerText: TextPaint,
)

internal suspend fun FlowCollector<Bitmap>.emitViewerTablePages(
    rows: List<List<String>>,
    columnCount: Int,
    width: Int,
    hasHeader: Boolean,
) {
    require(columnCount > 0) { "A table must contain at least one column" }
    TablePageRenderer(this, columnCount, width, hasHeader).render(rows)
}

private class TablePageRenderer(
    private val collector: FlowCollector<Bitmap>,
    private val columnCount: Int,
    private val width: Int,
    private val hasHeader: Boolean,
) {
    private val pageHeight = (width * 1.414f).toInt()
    private val margin = (width * 0.04f).toInt()
    private val columnWidth = ((width - margin * 2) / columnCount.toFloat()).toInt().coerceAtLeast(60)
    private val rowHeight = (width * 0.045f).toInt()
    private val textSize = width * 0.022f
    private val paints = tablePaints(textSize)
    private var bitmap = newViewerPage(width, pageHeight)
    private var canvas = Canvas(bitmap)
    private var currentY = margin

    suspend fun render(rows: List<List<String>>) {
        rows.forEachIndexed { rowIndex, row ->
            if (currentY + rowHeight > pageHeight - margin) flushPage()
            drawRow(rowIndex, row)
            currentY += rowHeight
        }
        if (currentY >= margin + rowHeight) collector.emit(bitmap) else bitmap.recycle()
    }

    private fun drawRow(
        rowIndex: Int,
        row: List<String>,
    ) {
        canvas.drawRect(
            margin.toFloat(),
            currentY.toFloat(),
            (width - margin).toFloat(),
            (currentY + rowHeight).toFloat(),
            rowBackground(rowIndex),
        )
        repeat(columnCount) { column -> drawCell(rowIndex, row, column) }
        canvas.drawLine(
            margin.toFloat(),
            (currentY + rowHeight).toFloat(),
            (width - margin).toFloat(),
            (currentY + rowHeight).toFloat(),
            paints.border,
        )
    }

    private fun rowBackground(rowIndex: Int): Paint =
        when {
            rowIndex == 0 && hasHeader -> paints.headerBackground
            rowIndex % 2 == 0 -> paints.evenBackground
            else -> paints.oddBackground
        }

    private fun drawCell(
        rowIndex: Int,
        row: List<String>,
        column: Int,
    ) {
        val cell = row.getOrElse(column) { "" }
        val x = margin + column * columnWidth
        val textPaint = if (rowIndex == 0 && hasHeader) paints.headerText else paints.text
        canvas.drawText(displayCellValue(cell), x + 6f, currentY + rowHeight * 0.65f, textPaint)
        canvas.drawLine(
            (x + columnWidth).toFloat(),
            currentY.toFloat(),
            (x + columnWidth).toFloat(),
            (currentY + rowHeight).toFloat(),
            paints.border,
        )
    }

    private fun displayCellValue(value: String): String {
        val maximumCharacters = (columnWidth / (textSize * 0.55f)).toInt().coerceAtLeast(3)
        return if (value.length > maximumCharacters) value.take(maximumCharacters - 1) + "…" else value
    }

    private suspend fun flushPage() {
        collector.emit(bitmap)
        bitmap = newViewerPage(width, pageHeight)
        canvas = Canvas(bitmap)
        currentY = margin
    }
}

private fun tablePaints(textSize: Float): TablePaints =
    TablePaints(
        headerBackground = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1565C0") },
        evenBackground = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F5F8FF") },
        oddBackground = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE },
        border =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#DDDDEE")
                strokeWidth = 1f
            },
        text =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                this.textSize = textSize
            },
        headerText =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                this.textSize = textSize
                typeface = Typeface.DEFAULT_BOLD
            },
    )

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
    StaticLayout.Builder
        .obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setLineSpacing(2f, 1.25f)
        .setIncludePad(false)
        .build()
