package com.example.pdfmaker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream
import kotlinx.coroutines.flow.FlowCollector
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import timber.log.Timber

private const val PRESENTATION_WIDTH_EMU = 9_144_000f
private const val PRESENTATION_HEIGHT_EMU = 5_143_500f

internal data class ViewerSlideText(
    val bounds: RectF,
    val text: String,
)

internal data class ViewerSlideImage(
    val bounds: RectF,
    val relationshipId: String,
)

internal data class ViewerSlideElements(
    val texts: List<ViewerSlideText>,
    val images: List<ViewerSlideImage>,
)

internal suspend fun FlowCollector<Bitmap>.emitPptxPages(file: File, width: Int) {
    val slideXml = sortedMapOf<Int, String>()
    val relationshipXml = mutableMapOf<Int, String>()
    val media = mutableMapOf<String, ByteArray>()
    val budget = ViewerArchiveBudget()
    ZipInputStream(file.inputStream().buffered()).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            val name = entry.name
            budget.beginEntry(name)
            readPresentationEntry(name, budget, zip, slideXml, relationshipXml, media)
            zip.closeEntry()
            entry = zip.nextEntry
        }
    }

    val height = (width * 0.5625f).toInt().coerceAtLeast(1)
    slideXml.entries.take(ViewerResourceLimits.MAX_RENDERED_PAGES).forEach { (number, xml) ->
        val relationships = relationshipXml[number]?.let(::parseViewerRelationships).orEmpty()
        emit(renderViewerSlide(xml, relationships, media, width, height))
    }
}

private fun readPresentationEntry(
    name: String,
    budget: ViewerArchiveBudget,
    zip: ZipInputStream,
    slideXml: MutableMap<Int, String>,
    relationshipXml: MutableMap<Int, String>,
    media: MutableMap<String, ByteArray>,
) {
    val slideNumber = viewerSlideNumber(name)
    val relationshipNumber = viewerSlideRelationshipNumber(name)
    when {
        slideNumber != null -> slideXml[slideNumber] = budget.readXml(zip)
        relationshipNumber != null -> relationshipXml[relationshipNumber] = budget.readXml(zip)
        isAcceptedPresentationMedia(name, media.size) -> {
            media[name.substringAfterLast('/')] = budget.readEntry(zip, ViewerResourceLimits.MAX_MEDIA_BYTES)
        }
        else -> budget.skipEntry(zip)
    }
}

private fun isAcceptedPresentationMedia(name: String, mediaCount: Int): Boolean =
    name.startsWith("ppt/media/") && mediaCount < ViewerResourceLimits.MAX_MEDIA_ITEMS

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
    val elements = parseViewerSlideElements(xml, width, height)
    elements.images.forEach { image -> drawSlideImage(canvas, image, relationships, media, width, height) }
    elements.texts.forEach { text -> drawSlideText(canvas, text, width) }
    return bitmap
}

private fun drawSlideImage(
    canvas: Canvas,
    image: ViewerSlideImage,
    relationships: Map<String, String>,
    media: Map<String, ByteArray>,
    width: Int,
    height: Int,
) {
    val mediaName = relationships[image.relationshipId] ?: return
    val bytes = media[mediaName] ?: return
    val destination =
        image.bounds.takeIf { it.width() > 2 && it.height() > 2 }
            ?: RectF(0f, 0f, width.toFloat(), height.toFloat())
    val source =
        ThumbnailInput.decodeImage(
            bytes,
            maxOf(destination.width(), destination.height()).toInt().coerceIn(1, 2_048),
        ) ?: return
    try {
        canvas.drawBitmap(source, null, destination, null)
    } finally {
        source.recycle()
    }
}

private fun drawSlideText(canvas: Canvas, text: ViewerSlideText, width: Int) {
    if (text.bounds.width() < 4 || text.bounds.height() < 4) return
    val fontSize = (text.bounds.height() * 0.18f).coerceIn(width * 0.018f, width * 0.055f)
    val paint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = fontSize
        }
    val layout = buildViewerStaticLayout(text.text, paint, text.bounds.width().toInt())
    canvas.save()
    try {
        canvas.translate(text.bounds.left, text.bounds.top + (text.bounds.height() - layout.height) / 2f)
        layout.draw(canvas)
    } finally {
        canvas.restore()
    }
}

internal fun parseViewerSlideElements(xml: String, width: Int, height: Int): ViewerSlideElements =
    try {
        parseViewerSlideElementsOrThrow(xml, width, height)
    } catch (error: XmlPullParserException) {
        failedSlideParse(error)
    } catch (error: IOException) {
        failedSlideParse(error)
    } catch (error: IllegalArgumentException) {
        failedSlideParse(error)
    }

private fun parseViewerSlideElementsOrThrow(xml: String, width: Int, height: Int): ViewerSlideElements {
    val parser =
        XmlPullParserFactory
            .newInstance()
            .apply { isNamespaceAware = true }
            .newPullParser()
            .also { it.setInput(xml.reader()) }
    val state = ViewerSlideState(width, height)
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT && state.canAcceptElements) {
        state.consume(parser, event)
        event = parser.next()
    }
    return state.elements()
}

private fun failedSlideParse(error: Exception): ViewerSlideElements {
    Timber.tag("PdfViewer").w(error, "event=presentation_slide_parse_failed")
    return ViewerSlideElements(emptyList(), emptyList())
}

private class ViewerSlideState(
    private val width: Int,
    private val height: Int,
) {
    private val texts = mutableListOf<ViewerSlideText>()
    private val images = mutableListOf<ViewerSlideImage>()
    private var inProperties = false
    private var inText = false
    private var offsetX = 0f
    private var offsetY = 0f
    private var extentWidth = 0f
    private var extentHeight = 0f
    private var relationshipId = ""
    private val text = StringBuilder()

    val canAcceptElements: Boolean
        get() = texts.size + images.size < ViewerResourceLimits.MAX_SLIDE_ELEMENTS

    fun elements(): ViewerSlideElements = ViewerSlideElements(texts, images)

    fun consume(parser: XmlPullParser, event: Int) {
        when (event) {
            XmlPullParser.START_TAG -> handleStartTag(parser, parser.name.orEmpty())
            XmlPullParser.TEXT -> appendText(parser.text)
            XmlPullParser.END_TAG -> handleEndTag(parser.name.orEmpty())
        }
    }

    private fun handleStartTag(parser: XmlPullParser, name: String) {
        when (name) {
            "sp", "pic" -> resetElement()
            "spPr" -> inProperties = true
            "off" -> readOffset(parser)
            "ext" -> readExtent(parser)
            "blip" -> relationshipId = parser.relationshipId().orEmpty()
            "t" -> inText = true
        }
    }

    private fun readOffset(parser: XmlPullParser) {
        if (inProperties) {
            offsetX = viewerCoordinate(parser.attributeByLocalName("x"))
            offsetY = viewerCoordinate(parser.attributeByLocalName("y"))
        }
    }

    private fun readExtent(parser: XmlPullParser) {
        if (inProperties) {
            extentWidth = viewerCoordinate(parser.attributeByLocalName("cx"))
            extentHeight = viewerCoordinate(parser.attributeByLocalName("cy"))
        }
    }

    private fun appendText(value: String) {
        if (inText && text.length < ViewerResourceLimits.MAX_CELL_CHARACTERS) {
            text.append(boundedViewerTextFragment(text.length, value))
        }
    }

    private fun handleEndTag(name: String) {
        when (name) {
            "spPr" -> inProperties = false
            "t" -> inText = false
            "p" -> appendParagraphBreak()
            "sp" -> finishShape()
            "pic" -> finishPicture()
        }
    }

    private fun appendParagraphBreak() {
        if (text.isNotEmpty() && !text.endsWith("\n")) text.append('\n')
    }

    private fun finishShape() {
        val value = text.toString().trim()
        if (value.isNotEmpty() && canAcceptElements) texts += ViewerSlideText(bounds(), value)
        finishPicture()
    }

    private fun finishPicture() {
        if (relationshipId.isNotEmpty() && canAcceptElements) {
            images += ViewerSlideImage(bounds(), relationshipId)
        }
    }

    private fun bounds(): RectF =
        RectF(
            offsetX * width / PRESENTATION_WIDTH_EMU,
            offsetY * height / PRESENTATION_HEIGHT_EMU,
            (offsetX + extentWidth) * width / PRESENTATION_WIDTH_EMU,
            (offsetY + extentHeight) * height / PRESENTATION_HEIGHT_EMU,
        )

    private fun resetElement() {
        inProperties = false
        offsetX = 0f
        offsetY = 0f
        extentWidth = 0f
        extentHeight = 0f
        relationshipId = ""
        text.clear()
    }
}
