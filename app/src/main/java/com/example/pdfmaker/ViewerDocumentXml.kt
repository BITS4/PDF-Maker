package com.example.pdfmaker

import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

internal fun parseViewerRelationships(xml: String): Map<String, String> {
    val relationships = mutableMapOf<String, String>()
    return try {
        val parser = XmlPullParserFactory.newInstance().newPullParser().also { it.setInput(xml.reader()) }
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "Relationship") {
                val id = parser.getAttributeValue(null, "Id").orEmpty()
                val target = parser.getAttributeValue(null, "Target").orEmpty()
                if (id.isNotEmpty() && (target.contains("media/") || target.contains("image"))) {
                    viewerMediaName(target)?.let { relationships[id] = it }
                }
            }
            event = parser.next()
        }
        relationships
    } catch (ignoredError: Exception) {
        Log.w("PdfViewer", "Unable to parse document relationships", ignoredError)
        emptyMap()
    }
}

internal fun parseViewerDocument(
    xml: String,
    relationships: Map<String, String>,
): List<DocBlock> {
    val blocks = mutableListOf<DocBlock>()
    return try {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(xml.reader())
        var inBody = false
        var inParagraph = false
        var inRun = false
        var inRunProperties = false
        var bold = false
        var italic = false
        var fontSize = 11f
        var paragraphStyle = ""
        var paragraphRuns = mutableListOf<DocRun>()
        val runText = StringBuilder()

        fun flushRun() {
            val text = runText.toString()
            if (text.isNotEmpty()) paragraphRuns += DocRun(text, bold, italic, fontSize)
            runText.clear()
        }

        fun flushParagraph() {
            val heading =
                when {
                    paragraphStyle.contains("Heading1", ignoreCase = true) -> 1
                    paragraphStyle.contains("Heading2", ignoreCase = true) -> 2
                    paragraphStyle.contains("Heading3", ignoreCase = true) -> 3
                    paragraphStyle.equals("Title", ignoreCase = true) -> 1
                    else -> 0
                }
            blocks += DocBlock.Paragraph(paragraphRuns.toList(), heading)
            paragraphRuns = mutableListOf()
            paragraphStyle = ""
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            val name = parser.name.orEmpty()
            when (event) {
                XmlPullParser.START_TAG ->
                    when (name) {
                        "body" -> inBody = true
                        "p" -> if (inBody) inParagraph = true
                        "r" ->
                            if (inParagraph) {
                                inRun = true
                                bold = false
                                italic = false
                            }
                        "rPr" -> inRunProperties = true
                        "pStyle" -> if (inParagraph) paragraphStyle = parser.attributeByLocalName("val")
                        "b" -> if (inRunProperties) bold = true
                        "i" -> if (inRunProperties) italic = true
                        "sz" ->
                            if (inRunProperties) {
                                parser.attributeByLocalName("val").toFloatOrNull()?.let {
                                    fontSize = (it / 2f).coerceIn(7f, 72f)
                                }
                            }
                        "br" -> {
                            if (parser.attributeByLocalName("type") == "page") {
                                flushRun()
                                flushParagraph()
                                blocks += DocBlock.PageBreak
                            } else {
                                runText.append('\n')
                            }
                        }
                        "blip" ->
                            parser.relationshipId()?.let(relationships::get)?.let { imageName ->
                                flushRun()
                                if (paragraphRuns.isNotEmpty()) blocks += DocBlock.Paragraph(paragraphRuns.toList())
                                paragraphRuns = mutableListOf()
                                blocks += DocBlock.ImageBlock(imageName)
                            }
                    }
                XmlPullParser.TEXT -> if (inRun && inParagraph && !inRunProperties) runText.append(parser.text)
                XmlPullParser.END_TAG ->
                    when (name) {
                        "rPr" -> inRunProperties = false
                        "r" -> {
                            flushRun()
                            inRun = false
                        }
                        "p" -> {
                            flushRun()
                            if (inParagraph) flushParagraph()
                            inParagraph = false
                        }
                        "body" -> inBody = false
                    }
            }
            event = parser.next()
        }
        blocks
    } catch (ignoredError: Exception) {
        Log.w("PdfViewer", "Unable to parse Word document", ignoredError)
        emptyList()
    }
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
