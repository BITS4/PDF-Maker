package com.example.pdfmaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.StyleSpan
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

// Rendering branches mirror the supported DOCX block variants; parsing and limits are separate and tested.
@Suppress("CyclomaticComplexMethod")
internal suspend fun renderDocxPdf(
    context: Context,
    blocks: List<DocBlock>,
    mediaFiles: Map<String, File>,
    baseName: String,
    onProgress: (Int, String) -> Unit,
): DocxPdfResult {
    val operationContext = currentCoroutineContext()
    val pageWidth = 595
    val pageHeight = 842
    val leftMargin = 56f
    val rightMargin = 56f
    val topMargin = 60f
    val bottomMargin = 60f
    val contentWidth = pageWidth - leftMargin - rightMargin
    val document = PdfDocument()
    var pageNumber = 1
    var activePage: PdfDocument.Page? =
        document.startPage(
            PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create(),
        )
    var canvas = requireNotNull(activePage).canvas
    var currentY = topMargin

    fun newPage() {
        operationContext.ensureActive()
        document.finishPage(requireNotNull(activePage))
        activePage = null
        pageNumber = DocxConversionPolicy.nextPage(pageNumber)
        activePage =
            document.startPage(
                PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create(),
            )
        canvas = requireNotNull(activePage).canvas
        currentY = topMargin
    }

    fun ensureSpace(requiredHeight: Float) {
        require(requiredHeight.isFinite() && requiredHeight >= 0f) { "DOCX block size is invalid" }
        if (currentY + requiredHeight > pageHeight - bottomMargin) newPage()
    }

    try {
        val totalBlocks = blocks.size.coerceAtLeast(1)
        blocks.forEachIndexed { index, block ->
            operationContext.ensureActive()
            onProgress(50 + index * 45 / totalBlocks, "Rendering…")
            when (block) {
                is DocBlock.PageBreak -> {
                    newPage()
                }

                is DocBlock.Paragraph -> {
                    if (block.runs.isEmpty()) {
                        currentY += 8f
                    } else {
                        val text = styledDocxText(block)
                        val paint =
                            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                                color = Color.BLACK
                                textSize = docxFontSize(block)
                                if (block.headingLevel > 0) typeface = Typeface.DEFAULT_BOLD
                            }
                        val layout = buildDocxLayout(text, paint, contentWidth.toInt())
                        val blockHeight = layout.height.toFloat() + if (block.headingLevel > 0) 8f else 4f
                        ensureSpace(blockHeight)
                        canvas.save()
                        canvas.translate(leftMargin, currentY)
                        layout.draw(canvas)
                        canvas.restore()
                        if (block.headingLevel == 1) {
                            val linePaint =
                                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                    color = Color.parseColor("#CCCCCC")
                                    strokeWidth = 0.5f
                                }
                            canvas.drawLine(
                                leftMargin,
                                currentY + layout.height + 3,
                                leftMargin + contentWidth,
                                currentY + layout.height + 3,
                                linePaint,
                            )
                        }
                        currentY += blockHeight + if (block.headingLevel > 0) 4f else 2f
                    }
                }

                is DocBlock.ImageBlock -> {
                    val mediaFile =
                        requireNotNull(mediaFiles[block.name]) {
                            "DOCX refers to missing media content"
                        }
                    val bitmap =
                        requireNotNull(decodeBoundedDocxImage(mediaFile)) {
                            "DOCX contains an unsupported image"
                        }
                    try {
                        val scale =
                            minOf(
                                1f,
                                contentWidth / bitmap.width.toFloat(),
                                (pageHeight - topMargin - bottomMargin) / bitmap.height.toFloat(),
                            )
                        val displayWidth = bitmap.width * scale
                        val displayHeight = bitmap.height * scale
                        ensureSpace(displayHeight + 8f)
                        canvas.drawBitmap(
                            bitmap,
                            null,
                            RectF(
                                leftMargin,
                                currentY,
                                leftMargin + displayWidth,
                                currentY + displayHeight,
                            ),
                            null,
                        )
                        currentY += displayHeight + 10f
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }

        document.finishPage(requireNotNull(activePage))
        activePage = null
        onProgress(97, "Saving…")
        val output =
            OutputStore.writeUnique(
                directory = getPdfMakerDir(context),
                requestedBaseName = baseName,
                extension = "pdf",
                beforeCommit = { operationContext.ensureActive() },
            ) { destination ->
                document.writeTo(
                    BoundedIo.limit(destination, DocxConversionPolicy.MAX_OUTPUT_BYTES) {
                        operationContext.ensureActive()
                    },
                )
            }
        operationContext.ensureActive()
        onProgress(100, "Done!")
        return DocxPdfResult(output, pageNumber)
    } finally {
        try {
            activePage?.let(document::finishPage)
        } finally {
            document.close()
        }
    }
}

private fun styledDocxText(block: DocBlock.Paragraph): SpannableStringBuilder =
    SpannableStringBuilder().apply {
        block.runs.forEach { run ->
            val start = length
            append(run.text)
            val end = length
            if (run.bold) {
                setSpan(StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (run.italic) {
                setSpan(StyleSpan(Typeface.ITALIC), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

private fun docxFontSize(block: DocBlock.Paragraph): Float =
    when (block.headingLevel) {
        1 -> 22f
        2 -> 17f
        3 -> 14f
        else -> block.runs.firstOrNull()?.fontSize ?: 11f
    }

private fun buildDocxLayout(
    text: CharSequence,
    paint: TextPaint,
    width: Int,
): StaticLayout =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
        StaticLayout.Builder
            .obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(2f, 1.2f)
            .setIncludePad(false)
            .build()
    } else {
        @Suppress("DEPRECATION")
        StaticLayout(text, paint, width, Layout.Alignment.ALIGN_NORMAL, 1.2f, 2f, false)
    }

private fun decodeBoundedDocxImage(file: File): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    val target = RenderSizing.fitWithin(bounds.outWidth, bounds.outHeight, 2_000) ?: return null
    var sampleSize = 1
    while (bounds.outWidth / sampleSize > target.width * 2 || bounds.outHeight / sampleSize > target.height * 2) {
        sampleSize *= 2
    }
    return BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = sampleSize },
    )
}
