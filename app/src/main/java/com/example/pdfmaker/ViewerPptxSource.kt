package com.example.pdfmaker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.util.Log
import kotlinx.coroutines.flow.FlowCollector
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.util.zip.ZipInputStream

private const val PRESENTATION_WIDTH_EMU = 9_144_000f
private const val PRESENTATION_HEIGHT_EMU = 5_143_500f

private data class ViewerSlideText(
    val bounds: RectF,
    val text: String,
)

private data class ViewerSlideImage(
    val bounds: RectF,
    val relationshipId: String,
)

internal suspend fun FlowCollector<Bitmap>.emitPptxPages(
    file: File,
    width: Int,
) {
    val slideXml = sortedMapOf<Int, String>()
    val relationshipXml = mutableMapOf<Int, String>()
    val media = mutableMapOf<String, ByteArray>()
    val budget = ViewerArchiveBudget()

    ZipInputStream(file.inputStream().buffered()).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            val name = entry.name
            budget.beginEntry(name)
            val slideNumber = viewerSlideNumber(name)
            val relationshipNumber = viewerSlideRelationshipNumber(name)
            when {
                slideNumber != null -> slideXml[slideNumber] = budget.readXml(zip)
                relationshipNumber != null -> relationshipXml[relationshipNumber] = budget.readXml(zip)
                name.startsWith("ppt/media/") && media.size < MAX_VIEWER_MEDIA_ITEMS -> {
                    media[name.substringAfterLast('/')] =
                        budget.readEntry(zip, MAX_VIEWER_MEDIA_BYTES)
                }
                else -> budget.skipEntry(zip)
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
    }

    val height = (width * 0.5625f).toInt().coerceAtLeast(1)
    slideXml.entries.take(MAX_VIEWER_RENDERED_PAGES).forEach { (number, xml) ->
        val relationships = relationshipXml[number]?.let(::parseViewerRelationships).orEmpty()
        emit(renderViewerSlide(xml, relationships, media, width, height))
    }
}

private fun renderViewerSlide(
    xml: String,
    relationships: Map<String, String>,
    media: Map<String, ByteArray>,
    width: Int,
    height: Int,
): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.parseColor("#F5F5F5"))
    val (texts, images) = parseViewerSlideElements(xml, width, height)

    images.forEach { image ->
        val mediaName = relationships[image.relationshipId] ?: return@forEach
        val bytes = media[mediaName] ?: return@forEach
        val destination =
            image.bounds.takeIf { it.width() > 2 && it.height() > 2 }
                ?: RectF(0f, 0f, width.toFloat(), height.toFloat())
        val source = ThumbnailInput.decodeImage(
            bytes,
            maxOf(destination.width(), destination.height()).toInt().coerceIn(1, 2_048),
        ) ?: return@forEach
        canvas.drawBitmap(source, null, destination, null)
        source.recycle()
    }
    texts.forEach { text ->
        if (text.bounds.width() < 4 || text.bounds.height() < 4) return@forEach
        val fontSize = (text.bounds.height() * 0.18f).coerceIn(width * 0.018f, width * 0.055f)
        val paint =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = fontSize
            }
        val layout = buildViewerStaticLayout(text.text, paint, text.bounds.width().toInt())
        canvas.save()
        canvas.translate(text.bounds.left, text.bounds.top + (text.bounds.height() - layout.height) / 2f)
        layout.draw(canvas)
        canvas.restore()
    }
    return bitmap
}

private fun parseViewerSlideElements(
    xml: String,
    width: Int,
    height: Int,
): Pair<List<ViewerSlideText>, List<ViewerSlideImage>> {
    val texts = mutableListOf<ViewerSlideText>()
    val images = mutableListOf<ViewerSlideImage>()
    try {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(xml.reader())
        var inProperties = false
        var inText = false
        var offsetX = 0f
        var offsetY = 0f
        var extentWidth = 0f
        var extentHeight = 0f
        var relationshipId = ""
        val text = StringBuilder()

        fun bounds() =
            RectF(
                offsetX * width / PRESENTATION_WIDTH_EMU,
                offsetY * height / PRESENTATION_HEIGHT_EMU,
                (offsetX + extentWidth) * width / PRESENTATION_WIDTH_EMU,
                (offsetY + extentHeight) * height / PRESENTATION_HEIGHT_EMU,
            )

        fun reset() {
            inProperties = false
            offsetX = 0f
            offsetY = 0f
            extentWidth = 0f
            extentHeight = 0f
            relationshipId = ""
            text.clear()
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT && texts.size + images.size < MAX_VIEWER_SLIDE_ELEMENTS) {
            val name = parser.name.orEmpty()
            when (event) {
                XmlPullParser.START_TAG ->
                    when (name) {
                        "sp", "pic" -> reset()
                        "spPr" -> inProperties = true
                        "off" ->
                            if (inProperties) {
                                offsetX = viewerCoordinate(parser.attributeByLocalName("x"))
                                offsetY = viewerCoordinate(parser.attributeByLocalName("y"))
                            }
                        "ext" ->
                            if (inProperties) {
                                extentWidth = viewerCoordinate(parser.attributeByLocalName("cx"))
                                extentHeight = viewerCoordinate(parser.attributeByLocalName("cy"))
                            }
                        "blip" -> relationshipId = parser.relationshipId().orEmpty()
                        "t" -> inText = true
                    }
                XmlPullParser.TEXT -> if (inText && text.length < MAX_VIEWER_CELL_CHARACTERS) {
                    text.append(parser.text.take(MAX_VIEWER_CELL_CHARACTERS - text.length))
                }
                XmlPullParser.END_TAG ->
                    when (name) {
                        "spPr" -> inProperties = false
                        "t" -> inText = false
                        "p" -> if (text.isNotEmpty() && !text.endsWith("\n")) text.append('\n')
                        "sp" -> {
                            val value = text.toString().trim()
                            if (value.isNotEmpty()) texts += ViewerSlideText(bounds(), value)
                            if (relationshipId.isNotEmpty()) images += ViewerSlideImage(bounds(), relationshipId)
                        }
                        "pic" -> {
                            if (relationshipId.isNotEmpty()) images += ViewerSlideImage(bounds(), relationshipId)
                        }
                    }
            }
            event = parser.next()
        }
    } catch (ignoredError: Exception) {
        Log.w("PdfViewer", "Unable to parse PowerPoint slide", ignoredError)
    }
    return texts to images
}
