package com.example.pdfmaker

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

internal fun parseConversionRelationships(
    xml: String,
    checkCancellation: () -> Unit = {},
): Map<String, String> {
    val handler = ConversionRelationshipHandler()
    SecureSaxParser.parse(xml, handler, checkCancellation = checkCancellation)
    return handler.result()
}

internal fun parseConversionDocument(
    xml: String,
    relationships: Map<String, String>,
    checkCancellation: () -> Unit = {},
): List<DocBlock> {
    val handler = ConversionDocumentHandler(relationships)
    SecureSaxParser.parse(xml, handler, checkCancellation = checkCancellation)
    return handler.result()
}

private class ConversionRelationshipHandler : DefaultHandler() {
    private val relationships = mutableMapOf<String, String>()
    private var rootSeen = false

    override fun startElement(
        uri: String?,
        localName: String?,
        qName: String?,
        attributes: Attributes,
    ) {
        val namespace = uri.orEmpty()
        val name = xmlName(localName, qName)
        if (!rootSeen) {
            rootSeen = true
            require(name == "Relationships" && namespace in OoxmlNamespaces.packageRelationships) {
                "DOCX relationships use an unsupported namespace"
            }
            return
        }
        if (name == "Relationship" && namespace in OoxmlNamespaces.packageRelationships) {
            captureRelationship(attributes)
        }
    }

    private fun captureRelationship(attributes: Attributes) {
        val id = attributes.valueIn("Id", UNQUALIFIED_NAMESPACE)
        val target = attributes.valueIn("Target", UNQUALIFIED_NAMESPACE).replace('\\', '/')
        val targetMode = attributes.valueIn("TargetMode", UNQUALIFIED_NAMESPACE)
        val relationshipType = attributes.valueIn("Type", UNQUALIFIED_NAMESPACE)
        if (id.isEmpty()) return
        if (targetMode.equals("External", ignoreCase = true)) return
        if (relationshipType !in OoxmlNamespaces.imageRelationships) return
        if (target.substringBeforeLast('/', "") != "media") return
        if (target.substringAfterLast('/').isEmpty()) return
        require(id !in relationships) { "DOCX contains duplicate relationship identifiers" }
        DocxConversionPolicy.requireCanAdd(
            relationships.size,
            DocxConversionPolicy.MAX_RELATIONSHIPS,
            "relationships",
        )
        relationships[id] = target.substringAfterLast('/')
    }

    fun result(): Map<String, String> {
        require(rootSeen) { "DOCX relationships are empty" }
        return relationships.toMap()
    }
}

private class ConversionDocumentHandler(
    private val relationships: Map<String, String>,
) : DefaultHandler() {
    private val blocks = mutableListOf<DocBlock>()
    private var rootSeen = false
    private var inBody = false
    private var inParagraph = false
    private var inRun = false
    private var inRunProperties = false
    private var depth = 0
    private var textElementDepth: Int? = null
    private var paragraphHasStandaloneBlock = false
    private var bold = false
    private var italic = false
    private var fontSize = 11f
    private var paragraphStyle = ""
    private var paragraphRuns = mutableListOf<DocRun>()
    private val runText = StringBuilder()
    private var totalTextCharacters = 0

    override fun startElement(
        uri: String?,
        localName: String?,
        qName: String?,
        attributes: Attributes,
    ) {
        depth += 1
        val namespace = uri.orEmpty()
        val name = xmlName(localName, qName)
        validateDocumentRoot(namespace, name)
        when {
            namespace in OoxmlNamespaces.wordProcessing -> {
                startWordElement(name, attributes)
            }

            namespace in OoxmlNamespaces.drawing && name == "blip" && inParagraph -> {
                addImage(attributes.valueIn("embed", OoxmlNamespaces.officeRelationships))
            }

            namespace == OoxmlNamespaces.VML && name == "imagedata" && inParagraph -> {
                addImage(attributes.valueIn("id", OoxmlNamespaces.officeRelationships))
            }
        }
    }

    private fun validateDocumentRoot(
        namespace: String,
        name: String,
    ) {
        if (rootSeen) return
        rootSeen = true
        require(name == "document" && namespace in OoxmlNamespaces.wordProcessing) {
            "DOCX document uses an unsupported namespace"
        }
    }

    private fun startWordElement(
        name: String,
        attributes: Attributes,
    ) {
        when (name) {
            "body", "p", "r", "rPr", "t" -> startWordStructure(name)
            "tab", "pStyle", "br" -> applyWordTextControl(name, attributes)
            "b", "i", "sz" -> applyRunProperty(name, attributes)
        }
    }

    private fun startWordStructure(name: String) {
        when (name) {
            "body" -> inBody = true
            "p" -> if (inBody && !inParagraph) startParagraph()
            "r" -> if (inParagraph && !inRun) startRun()
            "rPr" -> if (inRun) inRunProperties = true
            "t" -> if (inRun && !inRunProperties && textElementDepth == null) textElementDepth = depth
        }
    }

    private fun applyWordTextControl(
        name: String,
        attributes: Attributes,
    ) {
        when (name) {
            "tab" -> {
                if (inRun) appendText("\t")
            }

            "pStyle" -> {
                if (inParagraph) {
                    paragraphStyle = attributes.valueIn("val", OoxmlNamespaces.wordProcessing)
                }
            }

            "br" -> {
                if (inRun) {
                    if (attributes.valueIn("type", OoxmlNamespaces.wordProcessing) == "page") {
                        addPageBreak()
                    } else {
                        appendText("\n")
                    }
                }
            }
        }
    }

    private fun applyRunProperty(
        name: String,
        attributes: Attributes,
    ) {
        if (!inRunProperties || !inRun) return
        when (name) {
            "b" -> {
                bold = attributes.isEnabledWordProperty()
            }

            "i" -> {
                italic = attributes.isEnabledWordProperty()
            }

            "sz" -> {
                attributes.valueIn("val", OoxmlNamespaces.wordProcessing).toFloatOrNull()?.let { value ->
                    fontSize = (value / 2f).coerceIn(7f, 72f)
                }
            }
        }
    }

    override fun characters(
        characters: CharArray,
        start: Int,
        length: Int,
    ) {
        if (length > 0 && isCollectingRunText()) appendText(String(characters, start, length))
    }

    private fun isCollectingRunText(): Boolean = depth == textElementDepth && inRun && inParagraph && !inRunProperties

    override fun endElement(
        uri: String?,
        localName: String?,
        qName: String?,
    ) {
        if (uri.orEmpty() in OoxmlNamespaces.wordProcessing) {
            when (xmlName(localName, qName)) {
                "t" -> {
                    if (depth == textElementDepth) textElementDepth = null
                }

                "rPr" -> {
                    inRunProperties = false
                }

                "r" -> {
                    if (inRun) {
                        flushRun()
                        inRun = false
                        textElementDepth = null
                    }
                }

                "p" -> {
                    if (inParagraph) finishParagraph()
                }

                "body" -> {
                    inBody = false
                }
            }
        }
        depth -= 1
    }

    fun result(): List<DocBlock> {
        require(rootSeen) { "DOCX document is empty" }
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
        inRunProperties = false
        textElementDepth = null
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
        val heading =
            when {
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

private fun xmlName(
    localName: String?,
    qualifiedName: String?,
): String = localName?.takeIf(String::isNotEmpty) ?: qualifiedName.orEmpty().substringAfter(':')

private fun Attributes.valueIn(
    name: String,
    namespaces: Set<String>,
): String {
    repeat(length) { index ->
        val attributeName =
            getLocalName(index).orEmpty().takeIf(String::isNotEmpty) ?: getQName(index).orEmpty().substringAfter(':')
        if (attributeName == name && getURI(index).orEmpty() in namespaces) return getValue(index).orEmpty()
    }
    return ""
}

private fun Attributes.isEnabledWordProperty(): Boolean = valueIn("val", OoxmlNamespaces.wordProcessing).lowercase() !in setOf("0", "false", "off")

private val UNQUALIFIED_NAMESPACE = setOf("")
