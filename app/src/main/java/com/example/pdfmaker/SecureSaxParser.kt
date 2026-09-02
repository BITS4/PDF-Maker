package com.example.pdfmaker

import org.xml.sax.Attributes
import org.xml.sax.ContentHandler
import org.xml.sax.InputSource
import org.xml.sax.Locator
import org.xml.sax.SAXException
import org.xml.sax.SAXNotRecognizedException
import org.xml.sax.SAXNotSupportedException
import org.xml.sax.XMLReader
import org.xml.sax.ext.EntityResolver2
import org.xml.sax.helpers.DefaultHandler
import java.io.Reader
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.ParserConfigurationException
import javax.xml.parsers.SAXParserFactory

internal data class SecureSaxLimits(
    val maximumCharacters: Int = 10 * 1024 * 1024,
    val maximumEvents: Int = 500_000,
    val maximumDepth: Int = 256,
) {
    init {
        require(maximumCharacters > 0) { "XML character limit must be positive" }
        require(maximumEvents > 0) { "XML event limit must be positive" }
        require(maximumDepth > 0) { "XML depth limit must be positive" }
    }
}

/** A bounded, cancellation-aware SAX boundary for OOXML supplied by untrusted documents. */
internal object SecureSaxParser {
    fun parse(
        xml: String,
        handler: DefaultHandler,
        limits: SecureSaxLimits = SecureSaxLimits(),
        checkCancellation: () -> Unit = {},
    ) {
        require(xml.length <= limits.maximumCharacters) { "XML input exceeds the character limit" }
        rejectUnsafeDeclarations(xml)
        checkCancellation()

        val parser = newFactory().newSAXParser()
        parser.setPropertyCompatibly(ACCESS_EXTERNAL_DTD, "")
        parser.setPropertyCompatibly(ACCESS_EXTERNAL_SCHEMA, "")
        val reader =
            parser.xmlReader.apply {
                securityFeatures.forEach { (name, enabled) -> setFeatureCompatibly(name, enabled) }
                setPropertyCompatibly(ACCESS_EXTERNAL_DTD, "")
                setPropertyCompatibly(ACCESS_EXTERNAL_SCHEMA, "")
                entityResolver = DenyAllEntityResolver
                contentHandler = GuardedContentHandler(handler, checkCancellation, limits)
                dtdHandler = handler
                errorHandler = handler
            }

        val guardedInput = CancellationCheckingReader(xml, checkCancellation)
        reader.parse(InputSource(guardedInput))
        checkCancellation()
    }

    private fun newFactory(): SAXParserFactory =
        SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            isValidating = false
            disableXIncludeCompatibly()
            securityFeatures.forEach { (name, enabled) -> setFeatureCompatibly(name, enabled) }
        }

    /**
     * This scanner is the mandatory fallback on Android providers that lack optional SAX controls.
     * It skips harmless comments and CDATA, while rejecting DTD/entity declarations before parsing.
     */
    private fun rejectUnsafeDeclarations(xml: String) {
        var cursor = 0
        while (cursor < xml.length) {
            val declaration = xml.indexOf("<!", cursor)
            if (declaration < 0) return
            when {
                xml.startsWith("<!--", declaration) -> {
                    cursor = xml.indexAfterOrEnd("-->", declaration + 4)
                }

                xml.startsWith("<![CDATA[", declaration) -> {
                    cursor = xml.indexAfterOrEnd("]]>", declaration + 9)
                }

                else -> {
                    val keywordStart = xml.firstNonWhitespace(declaration + 2)
                    require(!xml.regionMatches(keywordStart, "DOCTYPE", 0, 7, ignoreCase = true)) {
                        "DOCTYPE is not allowed"
                    }
                    require(!xml.regionMatches(keywordStart, "ENTITY", 0, 6, ignoreCase = true)) {
                        "XML entities are not allowed"
                    }
                    cursor = keywordStart + 1
                }
            }
        }
    }

    private fun String.indexAfterOrEnd(
        token: String,
        startIndex: Int,
    ): Int {
        val end = indexOf(token, startIndex)
        return if (end < 0) length else end + token.length
    }

    private fun String.firstNonWhitespace(startIndex: Int): Int {
        var index = startIndex
        while (index < length && this[index].isWhitespace()) index += 1
        return index
    }

    private fun SAXParserFactory.disableXIncludeCompatibly() {
        try {
            isXIncludeAware = false
        } catch (_: UnsupportedOperationException) {
            // Providers without XInclude support cannot expand XInclude nodes.
        }
    }

    private fun SAXParserFactory.setFeatureCompatibly(
        name: String,
        enabled: Boolean,
    ) {
        try {
            setFeature(name, enabled)
        } catch (_: ParserConfigurationException) {
            // Lexical declaration rejection and the deny-all resolver remain mandatory controls.
        } catch (_: SAXNotRecognizedException) {
            // Lexical declaration rejection and the deny-all resolver remain mandatory controls.
        } catch (_: SAXNotSupportedException) {
            // Lexical declaration rejection and the deny-all resolver remain mandatory controls.
        }
    }

    private fun javax.xml.parsers.SAXParser.setPropertyCompatibly(
        name: String,
        value: String,
    ) {
        try {
            setProperty(name, value)
        } catch (_: SAXNotRecognizedException) {
            // Android providers vary; the resolver and lexical gate still deny external access.
        } catch (_: SAXNotSupportedException) {
            // Android providers vary; the resolver and lexical gate still deny external access.
        }
    }

    private fun XMLReader.setFeatureCompatibly(
        name: String,
        enabled: Boolean,
    ) {
        try {
            setFeature(name, enabled)
        } catch (_: SAXNotRecognizedException) {
            // Android XML readers expose different optional feature sets across API levels.
        } catch (_: SAXNotSupportedException) {
            // Android XML readers expose different optional feature sets across API levels.
        }
    }

    private fun XMLReader.setPropertyCompatibly(
        name: String,
        value: String,
    ) {
        try {
            setProperty(name, value)
        } catch (_: SAXNotRecognizedException) {
            // Android providers vary; the resolver and lexical gate still deny external access.
        } catch (_: SAXNotSupportedException) {
            // Android providers vary; the resolver and lexical gate still deny external access.
        }
    }

    private val securityFeatures =
        mapOf(
            XMLConstants.FEATURE_SECURE_PROCESSING to true,
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
            "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
            "http://apache.org/xml/features/xinclude" to false,
            "http://xml.org/sax/features/use-entity-resolver2" to true,
        )

    private const val ACCESS_EXTERNAL_DTD = "http://javax.xml.XMLConstants/property/accessExternalDTD"
    private const val ACCESS_EXTERNAL_SCHEMA = "http://javax.xml.XMLConstants/property/accessExternalSchema"
}

private class CancellationCheckingReader(
    xml: String,
    private val checkCancellation: () -> Unit,
) : Reader() {
    private val delegate = StringReader(xml)

    override fun read(
        buffer: CharArray,
        offset: Int,
        length: Int,
    ): Int {
        checkCancellation()
        return delegate.read(buffer, offset, length)
    }

    override fun close() = delegate.close()
}

private class GuardedContentHandler(
    private val delegate: ContentHandler,
    private val checkCancellation: () -> Unit,
    private val limits: SecureSaxLimits,
) : DefaultHandler() {
    private var eventCount = 0
    private var depth = 0

    override fun setDocumentLocator(locator: Locator?) = delegate.setDocumentLocator(locator)

    override fun startDocument() {
        beforeEvent()
        delegate.startDocument()
    }

    override fun endDocument() {
        beforeEvent()
        delegate.endDocument()
    }

    override fun startPrefixMapping(
        prefix: String?,
        uri: String?,
    ) {
        beforeEvent()
        delegate.startPrefixMapping(prefix, uri)
    }

    override fun endPrefixMapping(prefix: String?) {
        beforeEvent()
        delegate.endPrefixMapping(prefix)
    }

    override fun startElement(
        uri: String?,
        localName: String?,
        qName: String?,
        attributes: Attributes?,
    ) {
        beforeEvent()
        depth += 1
        require(depth <= limits.maximumDepth) { "XML nesting exceeds the depth limit" }
        delegate.startElement(uri, localName, qName, attributes)
    }

    override fun endElement(
        uri: String?,
        localName: String?,
        qName: String?,
    ) {
        beforeEvent()
        try {
            delegate.endElement(uri, localName, qName)
        } finally {
            depth -= 1
        }
    }

    override fun characters(
        characters: CharArray?,
        start: Int,
        length: Int,
    ) {
        beforeEvent()
        delegate.characters(characters, start, length)
    }

    override fun ignorableWhitespace(
        characters: CharArray?,
        start: Int,
        length: Int,
    ) {
        beforeEvent()
        delegate.ignorableWhitespace(characters, start, length)
    }

    override fun processingInstruction(
        target: String?,
        data: String?,
    ) {
        beforeEvent()
        delegate.processingInstruction(target, data)
    }

    override fun skippedEntity(name: String?) {
        beforeEvent()
        delegate.skippedEntity(name)
    }

    private fun beforeEvent() {
        checkCancellation()
        eventCount += 1
        require(eventCount <= limits.maximumEvents) { "XML event limit exceeded" }
    }
}

private object DenyAllEntityResolver : EntityResolver2 {
    override fun getExternalSubset(
        name: String?,
        baseUri: String?,
    ): InputSource = rejectExternalInput()

    override fun resolveEntity(
        publicId: String?,
        systemId: String?,
    ): InputSource = rejectExternalInput()

    override fun resolveEntity(
        name: String?,
        publicId: String?,
        baseUri: String?,
        systemId: String?,
    ): InputSource = rejectExternalInput()

    private fun rejectExternalInput(): Nothing = throw SAXException("External XML input is not allowed")
}

internal object OoxmlNamespaces {
    val wordProcessing =
        setOf(
            "http://schemas.openxmlformats.org/wordprocessingml/2006/main",
            "http://purl.oclc.org/ooxml/wordprocessingml/main",
        )
    val drawing =
        setOf(
            "http://schemas.openxmlformats.org/drawingml/2006/main",
            "http://purl.oclc.org/ooxml/drawingml/main",
        )
    val presentation =
        setOf(
            "http://schemas.openxmlformats.org/presentationml/2006/main",
            "http://purl.oclc.org/ooxml/presentationml/main",
        )
    val spreadsheet =
        setOf(
            "http://schemas.openxmlformats.org/spreadsheetml/2006/main",
            "http://purl.oclc.org/ooxml/spreadsheetml/main",
        )
    val packageRelationships =
        setOf(
            "http://schemas.openxmlformats.org/package/2006/relationships",
            "http://purl.oclc.org/ooxml/package/relationships",
        )
    val officeRelationships =
        setOf(
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
            "http://purl.oclc.org/ooxml/officeDocument/relationships",
        )
    val imageRelationships: Set<String> = officeRelationships.map { namespace -> "$namespace/image" }.toSet()
    const val VML = "urn:schemas-microsoft-com:vml"
}
