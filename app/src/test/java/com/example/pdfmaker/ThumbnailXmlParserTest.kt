package com.example.pdfmaker

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ThumbnailXmlParserTest {
    @Test
    fun `extracts only canonical Word paragraph text`() {
        val xml =
            """
            <w:document xmlns:w="$WORD" xmlns:evil="urn:foreign">
              <w:metadata><w:t>ignored</w:t></w:metadata>
              <w:p><w:r><w:t>First</w:t><evil:t> injected</evil:t><w:t> paragraph</w:t></w:r></w:p>
              <evil:p><w:t>spoofed paragraph</w:t></evil:p>
              <w:p><w:r><w:t>Second</w:t></w:r></w:p>
            </w:document>
            """.trimIndent()

        assertEquals(
            listOf("First paragraph", "Second"),
            ThumbnailXmlParser.paragraphs(xml, maximumParagraphs = 5) {},
        )
    }

    @Test
    fun `extracts strict DrawingML text from a presentation slide`() {
        val xml =
            """
            <p:sld xmlns:p="$STRICT_PRESENTATION" xmlns:a="$STRICT_DRAWING">
              <p:cSld><a:p><a:r><a:t>Strict slide</a:t></a:r></a:p></p:cSld>
            </p:sld>
            """.trimIndent()

        assertEquals(
            listOf("Strict slide"),
            ThumbnailXmlParser.paragraphs(xml, maximumParagraphs = 2) {},
        )
    }

    @Test
    fun `paragraph extraction enforces count and character limits`() {
        val oversized = "x".repeat(ThumbnailGenerationPolicy.MAX_PARAGRAPH_CHARACTERS + 20)
        val xml =
            "<w:document xmlns:w=\"$STRICT_WORD\"><w:body>" +
                "<w:p><w:t>$oversized</w:t></w:p><w:p><w:t>ignored</w:t></w:p>" +
                "</w:body></w:document>"

        val output = ThumbnailXmlParser.paragraphs(xml, maximumParagraphs = 1) {}

        assertEquals(1, output.size)
        assertEquals(ThumbnailGenerationPolicy.MAX_PARAGRAPH_CHARACTERS, output.single().length)
        assertThrows(IllegalArgumentException::class.java) {
            ThumbnailXmlParser.paragraphs(xml, maximumParagraphs = 0) {}
        }
    }

    @Test
    fun `combines canonical rich shared strings and honors item cap`() {
        val xml =
            """
            <sst xmlns="$SHEET" xmlns:evil="urn:foreign">
              <si><r><t>rich </t></r><r><t>text</t></r><evil:t>injected</evil:t></si>
              <evil:si><t>spoofed</t></evil:si>
              <si><t>second</t></si>
            </sst>
            """.trimIndent()

        assertEquals(
            listOf("rich text"),
            ThumbnailXmlParser.sharedStrings(xml, maximumStrings = 1) {},
        )
        assertThrows(IllegalArgumentException::class.java) {
            ThumbnailXmlParser.sharedStrings(xml, maximumStrings = 0) {}
        }
    }

    @Test
    fun `accepts strict SpreadsheetML shared strings`() {
        val xml = "<sst xmlns=\"$STRICT_SHEET\"><si><t>strict</t></si></sst>"

        assertEquals(listOf("strict"), ThumbnailXmlParser.sharedStrings(xml) {})
    }

    @Test
    fun `resolves shared numeric and inline worksheet cells`() {
        val xml =
            """
            <worksheet xmlns="$SHEET"><sheetData>
              <row>
                <c t="s"><v>1</v></c>
                <c><v>42</v></c>
                <c t="inlineStr"><is><t>inline</t></is></c>
              </row>
              <row><c t="s"><v>99</v></c></row>
            </sheetData></worksheet>
            """.trimIndent()

        assertEquals(
            listOf(listOf("second", "42"), listOf("99")),
            ThumbnailXmlParser.sheetRows(
                xml = xml,
                sharedStrings = listOf("first", "second"),
                maximumRows = 2,
                maximumColumns = 2,
            ) {},
        )
    }

    @Test
    fun `strict sheets ignore foreign cells and namespaced type spoofing`() {
        val xml =
            """
            <worksheet xmlns="$STRICT_SHEET" xmlns:evil="urn:foreign"><sheetData><row>
              <evil:c t="s"><evil:v>0</evil:v></evil:c>
              <c evil:t="s"><v>7</v></c>
              <c t="s"><evil:v>0</evil:v><v>1</v></c>
            </row></sheetData></worksheet>
            """.trimIndent()

        assertEquals(
            listOf(listOf("7", "second")),
            ThumbnailXmlParser.sheetRows(xml, listOf("first", "second")) {},
        )
    }

    @Test
    fun `foreign roots are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ThumbnailXmlParser.paragraphs(
                "<document xmlns=\"urn:lookalike\"><p><t>unsafe</t></p></document>",
                2,
            ) {}
        }
        assertThrows(IllegalArgumentException::class.java) {
            ThumbnailXmlParser.sharedStrings("<sst xmlns=\"urn:lookalike\"><si><t>x</t></si></sst>") {}
        }
        assertThrows(IllegalArgumentException::class.java) {
            ThumbnailXmlParser.sheetRows("<worksheet xmlns=\"urn:lookalike\"/>", emptyList()) {}
        }
    }

    @Test
    fun `worksheet extraction rejects invalid limits`() {
        val xml = "<worksheet xmlns=\"$SHEET\"/>"

        assertThrows(IllegalArgumentException::class.java) {
            ThumbnailXmlParser.sheetRows(xml, emptyList(), maximumRows = 0) {}
        }
        assertThrows(IllegalArgumentException::class.java) {
            ThumbnailXmlParser.sheetRows(xml, emptyList(), maximumColumns = 0) {}
        }
    }

    @Test
    fun `parser rejects document types and entity declarations`() {
        assertThrows(IllegalArgumentException::class.java) {
            ThumbnailXmlParser.paragraphs("<!DOCTYPE doc><document/>", 2) {}
        }
        assertThrows(IllegalArgumentException::class.java) {
            ThumbnailXmlParser.paragraphs("<!ENTITY secret 'value'><document/>", 2) {}
        }
    }

    @Test
    fun `parser callbacks propagate cancellation`() {
        var checks = 0
        val cancellation = {
            checks += 1
            if (checks == 10) throw CancellationException("cancelled")
        }
        val xml =
            "<w:document xmlns:w=\"$WORD\"><w:body>" +
                "<w:p><w:r><w:t>text</w:t></w:r></w:p>".repeat(20) +
                "</w:body></w:document>"

        assertThrows(CancellationException::class.java) {
            ThumbnailXmlParser.paragraphs(xml, 20, cancellation)
        }
        assertEquals(10, checks)
    }

    private companion object {
        const val WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        const val STRICT_WORD = "http://purl.oclc.org/ooxml/wordprocessingml/main"
        const val STRICT_PRESENTATION = "http://purl.oclc.org/ooxml/presentationml/main"
        const val STRICT_DRAWING = "http://purl.oclc.org/ooxml/drawingml/main"
        const val SHEET = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        const val STRICT_SHEET = "http://purl.oclc.org/ooxml/spreadsheetml/main"
    }
}
