package com.example.pdfmaker

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import timber.log.Timber
import java.io.IOException

internal fun boundedViewerTextFragment(
    currentLength: Int,
    value: String,
    maximumLength: Int = ViewerResourceLimits.MAX_CELL_CHARACTERS,
): String {
    require(currentLength >= 0 && maximumLength > 0) { "Viewer text limits are invalid" }
    return value.take((maximumLength - currentLength).coerceAtLeast(0))
}

internal fun parseViewerRelationships(xml: String): Map<String, String> =
    try {
        parseViewerRelationshipsOrThrow(xml)
    } catch (error: XmlPullParserException) {
        failedRelationshipParse(error)
    } catch (error: IOException) {
        failedRelationshipParse(error)
    } catch (error: IllegalArgumentException) {
        failedRelationshipParse(error)
    }

private fun parseViewerRelationshipsOrThrow(xml: String): Map<String, String> {
    val relationships = mutableMapOf<String, String>()
    val parser = XmlPullParserFactory.newInstance().newPullParser().also { it.setInput(xml.reader()) }
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT && relationships.size < ViewerResourceLimits.MAX_RELATIONSHIPS) {
        readViewerRelationship(parser, event)?.let { (id, mediaName) -> relationships[id] = mediaName }
        event = parser.next()
    }
    return relationships
}

private fun readViewerRelationship(
    parser: XmlPullParser,
    event: Int,
): Pair<String, String>? {
    if (event != XmlPullParser.START_TAG || parser.name != "Relationship") return null
    val id = parser.getAttributeValue(null, "Id").orEmpty()
    val target = parser.getAttributeValue(null, "Target").orEmpty()
    val referencesMedia = target.contains("media/") || target.contains("image")
    return if (id.isNotEmpty() && referencesMedia) {
        viewerMediaName(target)?.let { mediaName -> id to mediaName }
    } else {
        null
    }
}

private fun failedRelationshipParse(error: Exception): Map<String, String> {
    Timber.tag("PdfViewer").w(error, "event=document_relationship_parse_failed")
    return emptyMap()
}

internal fun parseViewerDocument(
    xml: String,
    relationships: Map<String, String>,
): List<DocBlock> =
    try {
        parseViewerDocumentOrThrow(xml, relationships)
    } catch (error: XmlPullParserException) {
        failedDocumentParse(error)
    } catch (error: IOException) {
        failedDocumentParse(error)
    } catch (error: IllegalArgumentException) {
        failedDocumentParse(error)
    }

private fun parseViewerDocumentOrThrow(
    xml: String,
    relationships: Map<String, String>,
): List<DocBlock> {
    val parser =
        XmlPullParserFactory
            .newInstance()
            .apply { isNamespaceAware = true }
            .newPullParser()
            .also { it.setInput(xml.reader()) }
    val state = ViewerDocumentState(relationships)
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT && state.canAcceptBlocks) {
        state.consume(parser, event)
        event = parser.next()
    }
    return state.blocks
}

private fun failedDocumentParse(error: Exception): List<DocBlock> {
    Timber.tag("PdfViewer").w(error, "event=word_document_parse_failed")
    return emptyList()
}

private class ViewerDocumentState(
    private val relationships: Map<String, String>,
) {
    private val mutableBlocks = mutableListOf<DocBlock>()
    private var inBody = false
    private var inParagraph = false
    private var inRun = false
    private var inRunProperties = false
    private var bold = false
    private var italic = false
    private var fontSize = 11f
    private var paragraphStyle = ""
    private var paragraphRuns = mutableListOf<DocRun>()
    private val runText = StringBuilder()

    val blocks: List<DocBlock> get() = mutableBlocks
    val canAcceptBlocks: Boolean get() = mutableBlocks.size < ViewerResourceLimits.MAX_DOCUMENT_BLOCKS

    fun consume(
        parser: XmlPullParser,
        event: Int,
    ) {
        when (event) {
            XmlPullParser.START_TAG -> handleStartTag(parser, parser.name.orEmpty())
            XmlPullParser.TEXT -> handleText(parser.text)
            XmlPullParser.END_TAG -> handleEndTag(parser.name.orEmpty())
        }
    }

    private fun handleStartTag(
        parser: XmlPullParser,
        name: String,
    ) {
        when (name) {
            "body" -> inBody = true
            "p" -> if (inBody) inParagraph = true
            "r" -> beginRun()
            "rPr" -> inRunProperties = true
            "pStyle" -> if (inParagraph) paragraphStyle = parser.attributeByLocalName("val")
            else -> handleRunContentTag(parser, name)
        }
    }

    private fun handleRunContentTag(
        parser: XmlPullParser,
        name: String,
    ) {
        when (name) {
            "b" -> if (inRunProperties) bold = true
            "i" -> if (inRunProperties) italic = true
            "sz" -> updateFontSize(parser)
            "br" -> handleBreak(parser)
            "blip" -> handleImage(parser)
        }
    }

    private fun beginRun() {
        if (inParagraph) {
            inRun = true
            bold = false
            italic = false
        }
    }

    private fun updateFontSize(parser: XmlPullParser) {
        if (!inRunProperties) return
        parser.attributeByLocalName("val").toFloatOrNull()?.let { halfPoints ->
            fontSize = (halfPoints / 2f).coerceIn(7f, 72f)
        }
    }

    private fun handleBreak(parser: XmlPullParser) {
        if (parser.attributeByLocalName("type") == "page") {
            flushRun()
            flushParagraph()
            addBlock(DocBlock.PageBreak)
        } else {
            appendRunText("\n")
        }
    }

    private fun handleImage(parser: XmlPullParser) {
        val imageName = parser.relationshipId()?.let(relationships::get) ?: return
        flushRun()
        if (paragraphRuns.isNotEmpty()) addBlock(DocBlock.Paragraph(paragraphRuns.toList()))
        paragraphRuns = mutableListOf()
        addBlock(DocBlock.ImageBlock(imageName))
    }

    private fun handleText(value: String) {
        if (inRun && inParagraph && !inRunProperties) appendRunText(value)
    }

    private fun handleEndTag(name: String) {
        when (name) {
            "rPr" -> inRunProperties = false
            "r" -> endRun()
            "p" -> endParagraph()
            "body" -> inBody = false
        }
    }

    private fun endRun() {
        flushRun()
        inRun = false
    }

    private fun endParagraph() {
        flushRun()
        if (inParagraph) flushParagraph()
        inParagraph = false
    }

    private fun appendRunText(value: String) {
        runText.append(boundedViewerTextFragment(runText.length, value))
    }

    private fun flushRun() {
        val value = runText.toString()
        if (value.isNotEmpty() && paragraphRuns.size < ViewerResourceLimits.MAX_RUNS_PER_PARAGRAPH) {
            paragraphRuns += DocRun(value, bold, italic, fontSize)
        }
        runText.clear()
    }

    private fun flushParagraph() {
        addBlock(DocBlock.Paragraph(paragraphRuns.toList(), viewerHeadingLevel(paragraphStyle)))
        paragraphRuns = mutableListOf()
        paragraphStyle = ""
    }

    private fun addBlock(block: DocBlock) {
        if (canAcceptBlocks) mutableBlocks += block
    }
}

internal fun viewerHeadingLevel(style: String): Int =
    when {
        style.equals("Heading1", ignoreCase = true) -> 1
        style.equals("Heading2", ignoreCase = true) -> 2
        style.equals("Heading3", ignoreCase = true) -> 3
        style.equals("Title", ignoreCase = true) -> 1
        else -> 0
    }

internal fun XmlPullParser.attributeByLocalName(localName: String): String {
    repeat(attributeCount) { index ->
        if (getAttributeName(index) == localName) return getAttributeValue(index).orEmpty()
    }
    return ""
}

internal fun XmlPullParser.relationshipId(): String? {
    val namespace = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    return getAttributeValue(namespace, "embed")
        ?: getAttributeValue(null, "r:embed")
        ?: attributeByLocalName("embed").ifEmpty { null }
}
