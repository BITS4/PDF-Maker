package com.example.pdfmaker

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailXmlParserTest {
    @Test
    fun `extracts only namespaced paragraph content`() {
        val xml =
            """
            <w:document xmlns:w="urn:word">
              <w:metadata>ignored</w:metadata>
              <w:p><w:r><w:t>First</w:t></w:r><w:r><w:t> paragraph</w:t></w:r></w:p>
              <w:p><w:r><w:t>Second</w:t></w:r></w:p>
            </w:document>
            """.trimIndent()

        assertEquals(
            listOf("First paragraph", "Second"),
            ThumbnailXmlParser.paragraphs(xml, maximumParagraphs = 5) {},
        )
    }

    @Test
    fun `paragraph extraction enforces count and character limits`() {
        val oversized = "x".repeat(ThumbnailGenerationPolicy.MAX_PARAGRAPH_CHARACTERS + 20)
        val xml = "<document><p>$oversized</p><p>ignored</p></document>"

        val output = ThumbnailXmlParser.paragraphs(xml, maximumParagraphs = 1) {}

        assertEquals(1, output.size)
        assertEquals(ThumbnailGenerationPolicy.MAX_PARAGRAPH_CHARACTERS, output.single().length)
        assertThrows(IllegalArgumentException::class.java) {
            ThumbnailXmlParser.paragraphs(xml, maximumParagraphs = 0) {}
        }
    }

    @Test
    fun `combines rich shared string runs and honors its item cap`() {
        val xml =
            """
            <sst xmlns="urn:sheet">
              <si><r><t>rich </t></r><r><t>text</t></r></si>
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
    fun `resolves shared numeric and inline worksheet cells`() {
        val xml =
            """
            <worksheet xmlns="urn:sheet"><sheetData>
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
    fun `worksheet extraction rejects invalid limits`() {
        assertThrows(IllegalArgumentException::class.java) {
            ThumbnailXmlParser.sheetRows("<worksheet/>", emptyList(), maximumRows = 0) {}
        }
        assertThrows(IllegalArgumentException::class.java) {
            ThumbnailXmlParser.sheetRows("<worksheet/>", emptyList(), maximumColumns = 0) {}
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
    fun `parser loops propagate cancellation`() {
        var checks = 0
        val cancellation = {
            checks += 1
            if (checks == 3) throw CancellationException("cancelled")
        }

        assertThrows(CancellationException::class.java) {
            ThumbnailXmlParser.paragraphs("<document><p>text</p></document>", 2, cancellation)
        }
        assertTrue(checks >= 3)
    }
}
