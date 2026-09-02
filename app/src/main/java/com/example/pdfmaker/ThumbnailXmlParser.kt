package com.example.pdfmaker

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory

/** Bounded OOXML text extraction used by thumbnails. Parse failures propagate to the request boundary. */
internal object ThumbnailXmlParser {
    fun paragraphs(
        xml: String,
        maximumParagraphs: Int,
        checkCancellation: () -> Unit,
    ): List<String> {
        require(maximumParagraphs > 0) { "Paragraph preview limit must be positive" }
        val handler = ParagraphHandler(maximumParagraphs, checkCancellation)
        parse(xml, handler, checkCancellation)
        return handler.output
    }

    fun sharedStrings(
        xml: String,
        maximumStrings: Int = MAX_SHARED_STRINGS,
        checkCancellation: () -> Unit,
    ): List<String> {
        require(maximumStrings > 0) { "Shared-string preview limit must be positive" }
        val handler = SharedStringsHandler(maximumStrings, checkCancellation)
        parse(xml, handler, checkCancellation)
        return handler.output
    }

    fun sheetRows(
        xml: String,
        sharedStrings: List<String>,
        maximumRows: Int = ThumbnailGenerationPolicy.MAX_PREVIEW_ROWS,
        maximumColumns: Int = ThumbnailGenerationPolicy.MAX_PREVIEW_COLUMNS,
        checkCancellation: () -> Unit,
    ): List<List<String>> {
        require(maximumRows > 0 && maximumColumns > 0) { "Worksheet preview limits must be positive" }
        val handler = SheetHandler(sharedStrings, maximumRows, maximumColumns, checkCancellation)
        parse(xml, handler, checkCancellation)
        return handler.output
    }

    private fun parse(
        xml: String,
        handler: DefaultHandler,
        checkCancellation: () -> Unit,
    ) {
        requireSafeXml(xml)
        checkCancellation()
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
        factory.newSAXParser().parse(InputSource(StringReader(xml)), handler)
        checkCancellation()
    }

    private fun requireSafeXml(xml: String) {
        require(!xml.contains("<!DOCTYPE", ignoreCase = true)) { "DOCTYPE is not allowed" }
        require(!xml.contains("<!ENTITY", ignoreCase = true)) { "XML entities are not allowed" }
    }

    private class ParagraphHandler(
        private val maximumParagraphs: Int,
        private val checkCancellation: () -> Unit,
    ) : DefaultHandler() {
        val output = mutableListOf<String>()
        private val text = StringBuilder()
        private var paragraphDepth = 0

        override fun startElement(
            uri: String?,
            localName: String?,
            qualifiedName: String?,
            attributes: Attributes?,
        ) {
            checkCancellation()
            if (elementName(localName, qualifiedName) == "p") {
                if (paragraphDepth == 0) text.clear()
                paragraphDepth += 1
            }
        }

        override fun characters(
            characters: CharArray,
            start: Int,
            length: Int,
        ) {
            checkCancellation()
            if (paragraphDepth > 0) appendBounded(text, characters, start, length)
        }

        override fun endElement(
            uri: String?,
            localName: String?,
            qualifiedName: String?,
        ) {
            checkCancellation()
            if (elementName(localName, qualifiedName) != "p" || paragraphDepth == 0) return
            paragraphDepth -= 1
            if (paragraphDepth == 0 && output.size < maximumParagraphs) {
                text
                    .toString()
                    .trim()
                    .takeIf(String::isNotEmpty)
                    ?.let(output::add)
            }
        }
    }

    private class SharedStringsHandler(
        private val maximumStrings: Int,
        private val checkCancellation: () -> Unit,
    ) : DefaultHandler() {
        val output = mutableListOf<String>()
        private val text = StringBuilder()
        private var insideItem = false
        private var insideText = false

        override fun startElement(
            uri: String?,
            localName: String?,
            qualifiedName: String?,
            attributes: Attributes?,
        ) {
            checkCancellation()
            when (elementName(localName, qualifiedName)) {
                "si" -> {
                    insideItem = true
                    text.clear()
                }

                "t" -> {
                    if (insideItem) insideText = true
                }
            }
        }

        override fun characters(
            characters: CharArray,
            start: Int,
            length: Int,
        ) {
            checkCancellation()
            if (insideText) appendBounded(text, characters, start, length)
        }

        override fun endElement(
            uri: String?,
            localName: String?,
            qualifiedName: String?,
        ) {
            checkCancellation()
            when (elementName(localName, qualifiedName)) {
                "t" -> {
                    insideText = false
                }

                "si" -> {
                    if (output.size < maximumStrings) output += text.toString()
                    insideItem = false
                    insideText = false
                }
            }
        }
    }

    private class SheetHandler(
        private val sharedStrings: List<String>,
        private val maximumRows: Int,
        private val maximumColumns: Int,
        private val checkCancellation: () -> Unit,
    ) : DefaultHandler() {
        val output = mutableListOf<List<String>>()
        private var row = mutableListOf<String>()
        private val value = StringBuilder()
        private var cellType = ""
        private var insideCell = false
        private var insideValue = false

        override fun startElement(
            uri: String?,
            localName: String?,
            qualifiedName: String?,
            attributes: Attributes?,
        ) {
            checkCancellation()
            when (elementName(localName, qualifiedName)) {
                "row" -> {
                    row = mutableListOf()
                }

                "c" -> {
                    insideCell = true
                    insideValue = false
                    cellType = attributes?.getValue("t").orEmpty()
                    value.clear()
                }

                "v", "t" -> {
                    if (insideCell) insideValue = true
                }
            }
        }

        override fun characters(
            characters: CharArray,
            start: Int,
            length: Int,
        ) {
            checkCancellation()
            if (insideValue) appendBounded(value, characters, start, length)
        }

        override fun endElement(
            uri: String?,
            localName: String?,
            qualifiedName: String?,
        ) {
            checkCancellation()
            when (elementName(localName, qualifiedName)) {
                "v", "t" -> {
                    insideValue = false
                }

                "c" -> {
                    finishCell()
                }

                "row" -> {
                    if (row.isNotEmpty() && output.size < maximumRows) output += row.toList()
                }
            }
        }

        private fun finishCell() {
            if (insideCell && row.size < maximumColumns) {
                val raw = value.toString()
                row += if (cellType == "s") sharedStrings.getOrElse(raw.toIntOrNull() ?: -1) { raw } else raw
            }
            insideCell = false
            insideValue = false
        }
    }

    private fun elementName(
        localName: String?,
        qualifiedName: String?,
    ): String = localName?.takeIf(String::isNotEmpty) ?: qualifiedName.orEmpty().substringAfter(':')

    private fun appendBounded(
        destination: StringBuilder,
        characters: CharArray,
        start: Int,
        length: Int,
    ) {
        val remaining = ThumbnailGenerationPolicy.MAX_PARAGRAPH_CHARACTERS - destination.length
        if (remaining > 0) destination.append(characters, start, minOf(length, remaining))
    }

    private const val MAX_SHARED_STRINGS = 512
}
