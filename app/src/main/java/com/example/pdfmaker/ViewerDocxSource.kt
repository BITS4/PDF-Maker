package com.example.pdfmaker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.style.StyleSpan
import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.coroutines.flow.FlowCollector

private data class DocxViewerSource(
    val documentXml: String,
    val relationshipsXml: String?,
    val media: Map<String, ByteArray>,
)

internal suspend fun FlowCollector<Bitmap>.emitDocxPages(file: File, width: Int) {
    val source = loadDocxViewerSource(file) ?: return
    val relationships = source.relationshipsXml?.let(::parseViewerRelationships).orEmpty()
    val blocks = parseViewerDocument(source.documentXml, relationships)
    DocxPageRenderer(this, width, source.media).render(blocks)
}

private fun loadDocxViewerSource(file: File): DocxViewerSource? {
    var documentXml: String? = null
    var relationshipsXml: String? = null
    val media = mutableMapOf<String, ByteArray>()
    val budget = ViewerArchiveBudget()
    ZipInputStream(file.inputStream().buffered()).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            val name = entry.name
            budget.beginEntry(name)
            when {
                name == "word/document.xml" -> documentXml = budget.readXml(zip)
                name == "word/_rels/document.xml.rels" -> relationshipsXml = budget.readXml(zip)
                isAcceptedWordMedia(name, media.size) -> {
                    media[name.substringAfterLast('/')] = budget.readEntry(zip, ViewerResourceLimits.MAX_MEDIA_BYTES)
                }
                else -> budget.skipEntry(zip)
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
    }
    return documentXml?.let { xml -> DocxViewerSource(xml, relationshipsXml, media) }
}

private fun isAcceptedWordMedia(name: String, mediaCount: Int): Boolean =
    name.startsWith("word/media/") && mediaCount < ViewerResourceLimits.MAX_MEDIA_ITEMS

private class DocxPageRenderer(
    private val collector: FlowCollector<Bitmap>,
    private val width: Int,
    private val media: Map<String, ByteArray>,
) {
    private val pageHeight = (width * 1.414f).toInt()
    private val margin = (width * 0.07f).toInt()
    private val contentWidth = width - margin * 2
    private var bitmap = newViewerPage(width, pageHeight)
    private var canvas = Canvas(bitmap)
    private var currentY = margin.toFloat()
    private var emittedPages = 0

    suspend fun render(blocks: List<DocBlock>) {
        var reachedPageLimit = false
        val iterator = blocks.iterator()
        while (iterator.hasNext() && !reachedPageLimit) {
            reachedPageLimit = !renderBlock(iterator.next())
        }
        finish(reachedPageLimit)
    }

    private suspend fun renderBlock(block: DocBlock): Boolean =
        when (block) {
            is DocBlock.PageBreak -> flushPage()
            is DocBlock.Paragraph -> renderParagraph(block)
            is DocBlock.ImageBlock -> renderImage(block)
        }

    private suspend fun renderParagraph(block: DocBlock.Paragraph): Boolean {
        if (block.runs.isEmpty()) {
            currentY += width * 0.015f
            return true
        }
        val text = styledParagraph(block.runs)
        val paint = paragraphPaint(block)
        val layout = buildViewerStaticLayout(text, paint, contentWidth)
        val blockHeight = layout.height + width * 0.01f
        if (!ensureVerticalSpace(blockHeight)) return false
        canvas.save()
        try {
            canvas.translate(margin.toFloat(), currentY)
            layout.draw(canvas)
        } finally {
            canvas.restore()
        }
        currentY += blockHeight + paragraphSpacing(block.headingLevel)
        return true
    }

    private fun styledParagraph(runs: List<DocRun>): SpannableStringBuilder {
        val text = SpannableStringBuilder()
        runs.forEach { run ->
            val start = text.length
            text.append(run.text)
            applyRunStyle(text, run, start, text.length)
        }
        return text
    }

    private fun applyRunStyle(text: SpannableStringBuilder, run: DocRun, start: Int, end: Int) {
        if (run.bold) text.setSpan(StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (run.italic) text.setSpan(StyleSpan(Typeface.ITALIC), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun paragraphPaint(block: DocBlock.Paragraph): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = paragraphFontSize(block)
            if (block.headingLevel > 0) typeface = Typeface.DEFAULT_BOLD
        }

    private fun paragraphFontSize(block: DocBlock.Paragraph): Float =
        when (block.headingLevel) {
            1 -> width * 0.045f
            2 -> width * 0.034f
            3 -> width * 0.028f
            else -> (block.runs.firstOrNull()?.fontSize ?: 11f) / 72f * 96f
        }

    private fun paragraphSpacing(headingLevel: Int): Float =
        if (headingLevel > 0) width * 0.008f else width * 0.003f

    private suspend fun renderImage(block: DocBlock.ImageBlock): Boolean {
        val bytes = media[block.name] ?: return true
        val source = ThumbnailInput.decodeImage(bytes, contentWidth.coerceIn(1, 2_048)) ?: return true
        try {
            val scale = (contentWidth.toFloat() / source.width.coerceAtLeast(1)).coerceAtMost(1f)
            val destinationWidth = source.width * scale
            val destinationHeight = source.height * scale
            if (!ensureVerticalSpace(destinationHeight)) return false
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
            currentY += destinationHeight + width * 0.012f
            return true
        } finally {
            source.recycle()
        }
    }

    private suspend fun ensureVerticalSpace(blockHeight: Float): Boolean =
        currentY + blockHeight <= pageHeight - margin || flushPage()

    private suspend fun flushPage(): Boolean {
        collector.emit(bitmap)
        emittedPages += 1
        if (emittedPages >= ViewerResourceLimits.MAX_RENDERED_PAGES) return false
        bitmap = newViewerPage(width, pageHeight)
        canvas = Canvas(bitmap)
        currentY = margin.toFloat()
        return true
    }

    private suspend fun finish(reachedPageLimit: Boolean) {
        if (!reachedPageLimit) {
            if (currentY > margin + 10) collector.emit(bitmap) else bitmap.recycle()
        }
    }
}
