package com.example.pdfmaker

import java.io.StringReader
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

internal fun parseConversionRelationships(xml: String): Map<String, String> {
    val handler = ConversionRelationshipHandler()
    parseConversionXml(xml, handler)
    return handler.relationships.toMap()
}

internal fun parseConversionDocument(
    xml: String,
    relationships: Map<String, String>,
): List<DocBlock> {
    val handler = ConversionDocumentHandler(relationships)
    parseConversionXml(xml, handler)
    return handler.result()
}

private fun parseConversionXml(xml: String, handler: DefaultHandler) {
    require(!xml.contains("<!DOCTYPE", ignoreCase = true)) { "DOCTYPE is not allowed" }
    require(!xml.contains("<!ENTITY", ignoreCase = true)) { "XML entities are not allowed" }
    val parser = SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser()
    parser.xmlReader.entityResolver = org.xml.sax.EntityResolver { _, _ ->
        throw SAXException("External XML entities are not allowed")
    }
    parser.parse(InputSource(StringReader(xml)), handler)
}

private class ConversionRelationshipHandler : DefaultHandler() {
    val relationships = mutableMapOf<String, String>()

    override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
        if (xmlName(localName, qName) != "Relationship") return
        val id = attributes.localValue("Id")
        val target = attributes.localValue("Target").replace('\\', '/')
        val parent = target.substringBeforeLast('/', "")
        if (id.isEmpty() || !parent.endsWith("media")) return
        require(id !in relationships) { "DOCX contains duplicate relationship identifiers" }
        DocxConversionPolicy.requireCanAdd(
            relationships.size,
            DocxConversionPolicy.MAX_RELATIONSHIPS,
            "relationships",
        )
        relationships[id] = target.substringAfterLast('/')
    }
}

private class ConversionDocumentHandler(
    private val relationships: Map<String, String>,
) : DefaultHandler() {
    private val blocks = mutableListOf<DocBlock>()
    private var inBody = false
    private var inParagraph = false
    private var inRun = false
    private var inRunProperties = false
    private var inText = false
    private var paragraphHasStandaloneBlock = false
    private var bold = false
    private var italic = false
    private var fontSize = 11f
    private var paragraphStyle = ""
    private var paragraphRuns = mutableListOf<DocRun>()
    private val runText = StringBuilder()
    private var totalTextCharacters = 0

    @Suppress("CyclomaticComplexMethod")
    override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
        when (xmlName(localName, qName)) {
            "body" -> inBody = true
            "p" -> if (inBody) startParagraph()
            "r" -> if (inParagraph) startRun()
            "rPr" -> inRunProperties = true
            "t" -> if (inRun) inText = true
            "tab" -> if (inRun) appendText("\t")
            "pStyle" -> if (inParagraph) paragraphStyle = attributes.localValue("val")
            "b" -> if (inRunProperties && inRun) bold = attributes.isEnabledProperty()
            "i" -> if (inRunProperties && inRun) italic = attributes.isEnabledProperty()
            "sz" -> if (inRunProperties && inRun) {
                attributes.localValue("val").toFloatOrNull()?.let { value ->
                    fontSize = (value / 2f).coerceIn(7f, 72f)
                }
            }
            "br" -> if (attributes.localValue("type") == "page") addPageBreak() else appendText("\n")
            "blip" -> addImage(attributes.localValue("embed"))
            "imagedata" -> addImage(attributes.localValue("id"))
        }
    }

    override fun characters(characters: CharArray, start: Int, length: Int) {
        if (inText && inRun && inParagraph && !inRunProperties && length > 0) {
            appendText(String(characters, start, length))
        }
    }

    override fun endElement(uri: String?, localName: String?, qName: String?) {
        when (xmlName(localName, qName)) {
            "t" -> inText = false
            "rPr" -> inRunProperties = false
            "r" -> {
                flushRun()
                inRun = false
                inText = false
            }
            "p" -> finishParagraph()
            "body" -> inBody = false
        }
    }

    fun result(): List<DocBlock> {
        require(blocks.isNotEmpty()) { "DOCX document body is empty" }
        return blocks.toList()
    }

    private fun startParagraph() {
        inParagraph = true
        paragraphHasStandaloneBlock = false
        paragraphStyle = ""
        paragraphRuns = mutableListOf()
        runText.clear()
    }

    private fun startRun() {
        inRun = true
        bold = false
        italic = false
        fontSize = 11f
    }

    private fun appendText(value: String) {
        DocxConversionPolicy.requireCanAppend(
            runText.length,
            value.length,
            DocxConversionPolicy.MAX_RUN_CHARACTERS,
            "run text",
        )
        DocxConversionPolicy.requireCanAppend(
            totalTextCharacters,
            value.length,
            DocxConversionPolicy.MAX_TEXT_CHARACTERS,
            "document text",
        )
        runText.append(value)
        totalTextCharacters += value.length
    }

    private fun flushRun() {
        if (runText.isEmpty()) return
        DocxConversionPolicy.requireCanAdd(
            paragraphRuns.size,
            DocxConversionPolicy.MAX_RUNS_PER_PARAGRAPH,
            "runs in a paragraph",
        )
        paragraphRuns += DocRun(runText.toString(), bold, italic, fontSize)
        runText.clear()
    }

    private fun finishParagraph() {
        flushRun()
        if (paragraphRuns.isNotEmpty() || !paragraphHasStandaloneBlock) addParagraph()
        paragraphRuns = mutableListOf()
        paragraphStyle = ""
        inParagraph = false
    }

    private fun addParagraph() {
        val heading = when {
            paragraphStyle.contains("Heading1", ignoreCase = true) -> 1
            paragraphStyle.contains("Heading2", ignoreCase = true) -> 2
            paragraphStyle.contains("Heading3", ignoreCase = true) -> 3
            paragraphStyle.equals("Title", ignoreCase = true) -> 1
            else -> 0
        }
        addBlock(DocBlock.Paragraph(paragraphRuns.toList(), heading))
        paragraphRuns = mutableListOf()
    }

    private fun addPageBreak() {
        flushRun()
        if (paragraphRuns.isNotEmpty()) addParagraph()
        addBlock(DocBlock.PageBreak)
        paragraphHasStandaloneBlock = true
    }

    private fun addImage(relationshipId: String) {
        if (relationshipId.isEmpty()) return
        val imageName = relationships[relationshipId] ?: return
        flushRun()
        if (paragraphRuns.isNotEmpty()) addParagraph()
        addBlock(DocBlock.ImageBlock(imageName))
        paragraphHasStandaloneBlock = true
    }

    private fun addBlock(block: DocBlock) {
        DocxConversionPolicy.requireCanAdd(
            blocks.size,
            DocxConversionPolicy.MAX_BLOCKS,
            "document blocks",
        )
        blocks += block
    }
}

private fun xmlName(localName: String?, qualifiedName: String?): String =
    localName?.takeIf(String::isNotEmpty) ?: qualifiedName.orEmpty().substringAfter(':')

private fun Attributes.localValue(name: String): String {
    repeat(length) { index ->
        if (getLocalName(index) == name || getQName(index).substringAfter(':') == name) {
            return getValue(index).orEmpty()
        }
    }
    return ""
}

private fun Attributes.isEnabledProperty(): Boolean =
    localValue("val").lowercase() !in setOf("0", "false", "off")
