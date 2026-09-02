package com.example.pdfmaker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt

/** Draws already-bounded preview data and owns any input bitmap passed to it. */
internal object ThumbnailCanvasRenderer {
    fun document(
        sizePx: Int,
        badgeLabel: String,
        badgeColor: Int,
        texts: List<String>,
        image: Bitmap?,
    ): Bitmap {
        var output: Bitmap? = null
        var completed = false
        try {
            val height = (sizePx * DOCUMENT_ASPECT).toInt().coerceAtLeast(1)
            val bitmap = createBitmap(sizePx, height)
            output = bitmap
            val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
            drawPageRules(canvas, sizePx, height)
            val textStart = drawDocumentImage(canvas, image, sizePx, height)
            drawDocumentText(canvas, texts, sizePx, height, textStart)
            drawBadge(canvas, badgeLabel, badgeColor, sizePx * BADGE_INSET, sizePx * BADGE_INSET, sizePx * BADGE_TEXT)
            completed = true
            return bitmap
        } finally {
            recycleIfNeeded(image)
            if (!completed) recycleIfNeeded(output)
        }
    }

    fun presentation(
        sizePx: Int,
        texts: List<String>,
        image: Bitmap?,
    ): Bitmap {
        var output: Bitmap? = null
        var completed = false
        try {
            val height = (sizePx * PRESENTATION_ASPECT).toInt().coerceAtLeast(1)
            val bitmap = createBitmap(sizePx, height)
            output = bitmap
            val canvas = Canvas(bitmap).apply { drawColor("#1A1A2E".toColorInt()) }
            drawPresentationImage(canvas, image, sizePx, height)
            drawBadge(canvas, "PPTX", "#E65100".toColorInt(), 6f, 6f, sizePx * BADGE_TEXT)
            drawPresentationText(canvas, texts, sizePx, height)
            completed = true
            return bitmap
        } finally {
            recycleIfNeeded(image)
            if (!completed) recycleIfNeeded(output)
        }
    }

    fun table(
        sizePx: Int,
        rows: List<List<String>>,
        badgeLabel: String = "CSV",
        headerColor: Int = "#6A1B9A".toColorInt(),
        badgeColor: Int = "#4A148C".toColorInt(),
    ): Bitmap {
        var output: Bitmap? = null
        var completed = false
        try {
            val height = (sizePx * TABLE_ASPECT).toInt().coerceAtLeast(1)
            val bitmap = createBitmap(sizePx, height)
            output = bitmap
            val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
            if (rows.isNotEmpty()) drawTableRows(canvas, rows, sizePx, height, headerColor)
            drawBadge(canvas, badgeLabel, badgeColor, sizePx * 0.04f, sizePx * 0.04f, sizePx * 0.06f)
            completed = true
            return bitmap
        } finally {
            if (!completed) recycleIfNeeded(output)
        }
    }

    private fun drawPageRules(
        canvas: Canvas,
        width: Int,
        height: Int,
    ) {
        val paint =
            Paint().apply {
                color = "#F0F0F0".toColorInt()
                strokeWidth = 1f
            }
        val step = height * 0.07f
        var y = height * 0.12f
        while (y < height * 0.92f) {
            canvas.drawLine(width * 0.08f, y, width * 0.92f, y, paint)
            y += step
        }
    }

    private fun drawDocumentImage(
        canvas: Canvas,
        image: Bitmap?,
        width: Int,
        height: Int,
    ): Float {
        if (image == null) return height * 0.1f
        val top = height * 0.1f
        val bottom = top + height * 0.28f
        canvas.drawBitmap(image, null, RectF(width * 0.08f, top, width * 0.92f, bottom), null)
        return bottom + height * 0.03f
    }

    private fun drawDocumentText(
        canvas: Canvas,
        texts: List<String>,
        width: Int,
        height: Int,
        startY: Float,
    ) {
        val body =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = width * 0.055f
            }
        val heading =
            Paint(body).apply {
                typeface = Typeface.DEFAULT_BOLD
                textSize = width * 0.065f
            }
        var y = startY
        for ((index, line) in texts.take(MAX_DOCUMENT_LINES).withIndex()) {
            val paint = if (index == 0) heading else body
            val maximumCharacters = characterCapacity(width * 0.84f, paint.textSize)
            val display = ThumbnailGenerationPolicy.displayText(line, maximumCharacters)
            canvas.drawText(display, width * 0.08f, y + paint.textSize, paint)
            y += paint.textSize * 1.55f
            if (y > height * 0.88f) break
        }
    }

    private fun drawPresentationImage(
        canvas: Canvas,
        image: Bitmap?,
        width: Int,
        height: Int,
    ) {
        if (image == null) return
        val scaledHeight = (image.height * (width.toFloat() / image.width.coerceAtLeast(1))).coerceAtLeast(1f)
        canvas.drawBitmap(image, null, RectF(0f, 0f, width.toFloat(), scaledHeight), null)
        val overlay = Paint().apply { color = Color.argb(100, 0, 0, 0) }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlay)
    }

    private fun drawPresentationText(
        canvas: Canvas,
        texts: List<String>,
        width: Int,
        height: Int,
    ) {
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = height * 0.12f
                typeface = Typeface.DEFAULT_BOLD
            }
        texts.firstOrNull()?.let { title ->
            canvas.drawText(ThumbnailGenerationPolicy.displayText(title, 30), width * 0.06f, height * 0.55f, paint)
        }
        texts.getOrNull(1)?.let { subtitle ->
            paint.textSize = height * 0.09f
            paint.typeface = Typeface.DEFAULT
            canvas.drawText(ThumbnailGenerationPolicy.displayText(subtitle, 40), width * 0.06f, height * 0.72f, paint)
        }
    }

    private fun drawTableRows(
        canvas: Canvas,
        rows: List<List<String>>,
        width: Int,
        height: Int,
        headerColor: Int,
    ) {
        val visibleRows = rows.take(ThumbnailGenerationPolicy.MAX_PREVIEW_ROWS)
        val columnCount = visibleRows.maxOf { row -> row.size }.coerceIn(1, ThumbnailGenerationPolicy.MAX_PREVIEW_COLUMNS)
        val rowHeight = height / (visibleRows.size + 1).coerceAtLeast(4).toFloat()
        val columnWidth = width.toFloat() / columnCount
        val header = Paint().apply { color = headerColor }
        val even = Paint().apply { color = "#F8FFF8".toColorInt() }
        val odd = Paint().apply { color = Color.WHITE }
        val border =
            Paint().apply {
                color = "#CCDDCC".toColorInt()
                strokeWidth = 0.5f
            }
        val bodyText =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = rowHeight * 0.48f
            }
        val headerText =
            Paint(bodyText).apply {
                color = Color.WHITE
                typeface = Typeface.DEFAULT_BOLD
            }
        visibleRows.forEachIndexed { rowIndex, row ->
            drawTableRow(canvas, row, rowIndex, width, rowHeight, columnWidth, columnCount, header, even, odd, border, bodyText, headerText)
        }
    }

    @Suppress("LongParameterList")
    private fun drawTableRow(
        canvas: Canvas,
        row: List<String>,
        rowIndex: Int,
        width: Int,
        rowHeight: Float,
        columnWidth: Float,
        columnCount: Int,
        header: Paint,
        even: Paint,
        odd: Paint,
        border: Paint,
        bodyText: Paint,
        headerText: Paint,
    ) {
        val top = rowIndex * rowHeight
        val bottom = top + rowHeight
        val background =
            when {
                rowIndex == 0 -> header
                rowIndex % 2 == 0 -> even
                else -> odd
            }
        canvas.drawRect(0f, top, width.toFloat(), bottom, background)
        for (column in 0 until columnCount) {
            val paint = if (rowIndex == 0) headerText else bodyText
            val maximumCharacters = characterCapacity(columnWidth, paint.textSize)
            val display = ThumbnailGenerationPolicy.displayText(row.getOrElse(column) { "" }, maximumCharacters)
            canvas.drawText(display, column * columnWidth + columnWidth * 0.05f, top + rowHeight * 0.7f, paint)
            canvas.drawLine((column + 1) * columnWidth, top, (column + 1) * columnWidth, bottom, border)
        }
        canvas.drawLine(0f, bottom, width.toFloat(), bottom, border)
    }

    private fun drawBadge(
        canvas: Canvas,
        label: String,
        backgroundColor: Int,
        x: Float,
        y: Float,
        textSize: Float,
    ) {
        val text =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                this.textSize = textSize
                typeface = Typeface.DEFAULT_BOLD
            }
        val padding = textSize * 0.4f
        val background = Paint().apply { color = backgroundColor }
        canvas.drawRoundRect(
            RectF(x, y, x + text.measureText(label) + padding * 2, y + textSize + padding * 1.2f),
            textSize * 0.3f,
            textSize * 0.3f,
            background,
        )
        canvas.drawText(label, x + padding, y + textSize, text)
    }

    private fun characterCapacity(
        availableWidth: Float,
        textSize: Float,
    ): Int = (availableWidth / (textSize * APPROXIMATE_GLYPH_WIDTH)).toInt().coerceAtLeast(2)

    private fun recycleIfNeeded(bitmap: Bitmap?) {
        if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
    }

    private const val DOCUMENT_ASPECT = 1.33f
    private const val PRESENTATION_ASPECT = 0.5625f
    private const val TABLE_ASPECT = 1.1f
    private const val BADGE_INSET = 0.06f
    private const val BADGE_TEXT = 0.07f
    private const val APPROXIMATE_GLYPH_WIDTH = 0.52f
    private const val MAX_DOCUMENT_LINES = 9
}
