package com.example.pdfmaker

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.style.StyleSpan
import kotlinx.coroutines.flow.FlowCollector
import java.io.File
import java.util.zip.ZipInputStream

internal suspend fun FlowCollector<Bitmap>.emitDocxPages(
    file: File,
    width: Int,
) {
    var documentXml: String? = null
    var relationshipsXml: String? = null
    val media = mutableMapOf<String, ByteArray>()
    ZipInputStream(file.inputStream().buffered()).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            when {
                entry.name == "word/document.xml" -> documentXml = zip.readViewerXml()
                entry.name == "word/_rels/document.xml.rels" -> relationshipsXml = zip.readViewerXml()
                entry.name.startsWith("word/media/") -> {
                    media[entry.name.substringAfterLast('/')] =
                        readBoundedViewerEntry(zip, MAX_VIEWER_MEDIA_BYTES)
                }
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
    }
    val xml = documentXml ?: return
    val relationships = relationshipsXml?.let(::parseViewerRelationships).orEmpty()
    val blocks = parseViewerDocument(xml, relationships)
    val pageHeight = (width * 1.414f).toInt()
    val margin = (width * 0.07f).toInt()
    val contentWidth = width - margin * 2
    var bitmap = newViewerPage(width, pageHeight)
    var canvas = Canvas(bitmap)
    var currentY = margin.toFloat()

    suspend fun flushPage() {
        emit(bitmap)
        bitmap = newViewerPage(width, pageHeight)
        canvas = Canvas(bitmap)
        currentY = margin.toFloat()
    }

    for (block in blocks) {
        when (block) {
            is DocBlock.PageBreak -> flushPage()
            is DocBlock.Paragraph -> {
                if (block.runs.isEmpty()) {
                    currentY += width * 0.015f
                    continue
                }
                val text = SpannableStringBuilder()
                block.runs.forEach { run ->
                    val start = text.length
                    text.append(run.text)
                    val end = text.length
                    if (run.bold) {
                        text.setSpan(
                            StyleSpan(Typeface.BOLD),
                            start,
                            end,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                    }
                    if (run.italic) {
                        text.setSpan(
                            StyleSpan(Typeface.ITALIC),
                            start,
                            end,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                    }
                }
                val fontSize =
                    when (block.headingLevel) {
                        1 -> width * 0.045f
                        2 -> width * 0.034f
                        3 -> width * 0.028f
                        else -> (block.runs.firstOrNull()?.fontSize ?: 11f) / 72f * 96f
                    }
                val paint =
                    TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.BLACK
                        textSize = fontSize
                        if (block.headingLevel > 0) typeface = Typeface.DEFAULT_BOLD
                    }
                val layout = buildViewerStaticLayout(text, paint, contentWidth)
                val blockHeight = layout.height + width * 0.01f
                if (currentY + blockHeight > pageHeight - margin) flushPage()
                canvas.save()
                canvas.translate(margin.toFloat(), currentY)
                layout.draw(canvas)
                canvas.restore()
                currentY += blockHeight + if (block.headingLevel > 0) width * 0.008f else width * 0.003f
            }
            is DocBlock.ImageBlock -> {
                val bytes = media[block.name] ?: continue
                val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                val scale = (contentWidth.toFloat() / source.width.coerceAtLeast(1)).coerceAtMost(1f)
                val destinationWidth = source.width * scale
                val destinationHeight = source.height * scale
                if (currentY + destinationHeight > pageHeight - margin) flushPage()
                canvas.drawBitmap(
                    source,
                    null,
                    RectF(
                        margin.toFloat(),
                        currentY,
                        margin + destinationWidth,
                        currentY + destinationHeight,
                    ),
                    null,
                )
                source.recycle()
                currentY += destinationHeight + width * 0.012f
            }
        }
    }
    if (currentY > margin + 10) emit(bitmap) else bitmap.recycle()
}
