package com.example.pdfmaker

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/** Bounded, namespace-aware OOXML text extraction used by thumbnails. */
internal object ThumbnailXmlParser {
    fun paragraphs(
        xml: String,
        maximumParagraphs: Int,
        checkCancellation: () -> Unit,
    ): List<String> {
        require(maximumParagraphs > 0) { "Paragraph preview limit must be positive" }
        val handler = ParagraphHandler(maximumParagraphs)
        parse(xml, handler, checkCancellation)
        return handler.output
    }

    fun sharedStrings(
        xml: String,
        maximumStrings: Int = MAX_SHARED_STRINGS,
        checkCancellation: () -> Unit,
    ): List<String> {
        require(maximumStrings > 0) { "Shared-string preview limit must be positive" }
        val handler = SharedStringsHandler(maximumStrings)
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
        val handler = SheetHandler(sharedStrings, maximumRows, maximumColumns)
        parse(xml, handler, checkCancellation)
        return handler.output
    }

    private fun parse(
        xml: String,
        handler: DefaultHandler,
        checkCancellation: () -> Unit,
    ) {
        SecureSaxParser.parse(xml, handler, checkCancellation = checkCancellation)
    }

    private class ParagraphHandler(
        private val maximumParagraphs: Int,
    ) : DefaultHandler() {
        val output = mutableListOf<String>()
        private val text = StringBuilder()
        private var depth = 0
        private var paragraphDepth: Int? = null
        private var textElementDepth: Int? = null
        private var textNamespaces = emptySet<String>()

        override fun startElement(
            uri: String?,
            localName: String?,
            qualifiedName: String?,
            attributes: Attributes?,
        ) {
            depth += 1
            val namespace = uri.orEmpty()
            val name = elementName(localName, qualifiedName)
            if (depth == 1) validateRoot(namespace, name)
            when {
                paragraphDepth == null && name == "p" && namespace in textNamespaces -> {
                    paragraphDepth = depth
                    text.clear()
                }

                paragraphDepth != null &&
                    textElementDepth == null &&
                    name == "t" &&
                    namespace in textNamespaces -> {
                    textElementDepth = depth
                }
            }
        }

        private fun validateRoot(
            namespace: String,
            name: String,
        ) {
            textNamespaces =
                when {
                    name == "document" && namespace in OoxmlNamespaces.wordProcessing -> {
                        OoxmlNamespaces.wordProcessing
                    }

                    name == "sld" && namespace in OoxmlNamespaces.presentation -> {
                        OoxmlNamespaces.drawing
                    }

                    else -> {
                        throw IllegalArgumentException("OOXML preview uses an unsupported namespace")
                    }
                }
        }

        override fun characters(
            characters: CharArray,
            start: Int,
            length: Int,
        ) {
            if (depth == textElementDepth) appendBounded(text, characters, start, length)
        }

        override fun endElement(
            uri: String?,
            localName: String?,
            qualifiedName: String?,
        ) {
            val namespace = uri.orEmpty()
            val name = elementName(localName, qualifiedName)
            if (depth == textElementDepth && name == "t" && namespace in textNamespaces) textElementDepth = null
            if (depth == paragraphDepth && name == "p" && namespace in textNamespaces) finishParagraph()
            depth -= 1
        }

        private fun finishParagraph() {
            if (output.size < maximumParagraphs) {
                text
                    .toString()
                    .trim()
                    .takeIf(String::isNotEmpty)
                    ?.let(output::add)
            }
            paragraphDepth = null
            textElementDepth = null
        }
    }

    private class SharedStringsHandler(
        private val maximumStrings: Int,
    ) : DefaultHandler() {
        val output = mutableListOf<String>()
        private val text = StringBuilder()
        private var depth = 0
        private var itemDepth: Int? = null
        private var textElementDepth: Int? = null

        override fun startElement(
            uri: String?,
            localName: String?,
            qualifiedName: String?,
            attributes: Attributes?,
        ) {
            depth += 1
            val namespace = uri.orEmpty()
            val name = elementName(localName, qualifiedName)
            if (depth == 1) {
                require(name == "sst" && namespace in OoxmlNamespaces.spreadsheet) {
                    "Shared strings use an unsupported namespace"
                }
            }
            when {
                itemDepth == null && name == "si" && namespace in OoxmlNamespaces.spreadsheet -> {
                    itemDepth = depth
                    text.clear()
                }

                itemDepth != null &&
                    textElementDepth == null &&
                    name == "t" &&
                    namespace in OoxmlNamespaces.spreadsheet -> {
                    textElementDepth = depth
                }
            }
        }

        override fun characters(
            characters: CharArray,
            start: Int,
            length: Int,
        ) {
            if (depth == textElementDepth) appendBounded(text, characters, start, length)
        }

        override fun endElement(
            uri: String?,
            localName: String?,
            qualifiedName: String?,
        ) {
            val namespace = uri.orEmpty()
            val name = elementName(localName, qualifiedName)
            if (depth == textElementDepth && name == "t" && namespace in OoxmlNamespaces.spreadsheet) {
                textElementDepth = null
            }
            if (depth == itemDepth && name == "si" && namespace in OoxmlNamespaces.spreadsheet) finishItem()
            depth -= 1
        }

        private fun finishItem() {
            if (output.size < maximumStrings) output += text.toString()
            itemDepth = null
            textElementDepth = null
        }
    }

    private class SheetHandler(
        private val sharedStrings: List<String>,
        private val maximumRows: Int,
        private val maximumColumns: Int,
    ) : DefaultHandler() {
        val output = mutableListOf<List<String>>()
        private var row = mutableListOf<String>()
        private val value = StringBuilder()
        private var depth = 0
        private var rowDepth: Int? = null
        private var cellDepth: Int? = null
        private var valueDepth: Int? = null
        private var cellType = ""

        override fun startElement(
            uri: String?,
            localName: String?,
            qualifiedName: String?,
            attributes: Attributes?,
        ) {
            depth += 1
            val namespace = uri.orEmpty()
            val name = elementName(localName, qualifiedName)
            if (depth == 1) {
                require(name == "worksheet" && namespace in OoxmlNamespaces.spreadsheet) {
                    "Worksheet uses an unsupported namespace"
                }
            }
            if (namespace !in OoxmlNamespaces.spreadsheet) return
            when {
                rowDepth == null && name == "row" -> startRow()
                rowDepth != null && cellDepth == null && name == "c" -> startCell(attributes)
                cellDepth != null && valueDepth == null && name in CELL_VALUE_ELEMENTS -> valueDepth = depth
            }
        }

        private fun startRow() {
            rowDepth = depth
            row = mutableListOf()
        }

        private fun startCell(attributes: Attributes?) {
            cellDepth = depth
            valueDepth = null
            cellType = attributes?.unqualifiedValue("t").orEmpty()
            value.clear()
        }

        override fun characters(
            characters: CharArray,
            start: Int,
            length: Int,
        ) {
            if (depth == valueDepth) appendBounded(value, characters, start, length)
        }

        override fun endElement(
            uri: String?,
            localName: String?,
            qualifiedName: String?,
        ) {
            val namespace = uri.orEmpty()
            val name = elementName(localName, qualifiedName)
            if (namespace in OoxmlNamespaces.spreadsheet) {
                when {
                    depth == valueDepth && name in CELL_VALUE_ELEMENTS -> valueDepth = null
                    depth == cellDepth && name == "c" -> finishCell()
                    depth == rowDepth && name == "row" -> finishRow()
                }
            }
            depth -= 1
        }

        private fun finishCell() {
            if (row.size < maximumColumns) {
                val raw = value.toString()
                row += if (cellType == "s") sharedStrings.getOrElse(raw.toIntOrNull() ?: -1) { raw } else raw
            }
            cellDepth = null
            valueDepth = null
        }

        private fun finishRow() {
            if (row.isNotEmpty() && output.size < maximumRows) output += row.toList()
            rowDepth = null
            cellDepth = null
            valueDepth = null
        }
    }

    private fun elementName(
        localName: String?,
        qualifiedName: String?,
    ): String = localName?.takeIf(String::isNotEmpty) ?: qualifiedName.orEmpty().substringAfter(':')

    private fun Attributes.unqualifiedValue(name: String): String {
        repeat(length) { index ->
            val localName = getLocalName(index).orEmpty().takeIf(String::isNotEmpty) ?: getQName(index).orEmpty()
            if (localName == name && getURI(index).orEmpty().isEmpty()) return getValue(index).orEmpty()
        }
        return ""
    }

    private fun appendBounded(
        destination: StringBuilder,
        characters: CharArray,
        start: Int,
        length: Int,
    ) {
        val remaining = ThumbnailGenerationPolicy.MAX_PARAGRAPH_CHARACTERS - destination.length
        if (remaining > 0) destination.append(characters, start, minOf(length, remaining))
    }

    private val CELL_VALUE_ELEMENTS = setOf("v", "t")
    private const val MAX_SHARED_STRINGS = 512
}
