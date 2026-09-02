package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

class DocxConversionXmlTest {
    @Test
    fun `parses transitional relationships and formatted blocks`() {
        val relationships = parseConversionRelationships(relationshipsXml(TRANSITIONAL_OFFICE_RELATIONSHIPS))
        val document =
            """
            <w:document xmlns:w="$TRANSITIONAL_WORD" xmlns:r="$TRANSITIONAL_OFFICE_RELATIONSHIPS"
                xmlns:a="$TRANSITIONAL_DRAWING">
              <w:body>
                <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:rPr><w:b/></w:rPr><w:t>Title</w:t></w:r></w:p>
                <w:p><w:r><w:t>Body</w:t></w:r><w:r><w:br w:type="page"/></w:r></w:p>
                <w:p><w:r><w:drawing><a:blip r:embed="rId1"/></w:drawing></w:r></w:p>
              </w:body>
            </w:document>
            """.trimIndent()

        val blocks = parseConversionDocument(document, relationships)

        assertEquals("image1.png", relationships["rId1"])
        val title = blocks.filterIsInstance<DocBlock.Paragraph>().first()
        assertEquals(1, title.headingLevel)
        assertTrue(title.runs.single().bold)
        assertTrue(blocks.any { it is DocBlock.PageBreak })
        assertTrue(blocks.any { it is DocBlock.ImageBlock && it.name == "image1.png" })
    }

    @Test
    fun `accepts strict document and relationship attribute namespaces`() {
        val relationships =
            parseConversionRelationships(
                relationshipsXml(
                    officeRelationships = STRICT_OFFICE_RELATIONSHIPS,
                    packageRelationships = STRICT_PACKAGE_RELATIONSHIPS,
                ),
            )
        val document =
            """
            <w:document xmlns:w="$STRICT_WORD" xmlns:r="$STRICT_OFFICE_RELATIONSHIPS"
                xmlns:a="$STRICT_DRAWING" xmlns:v="$VML">
              <w:body>
                <w:p><w:r><w:rPr><w:i/><w:sz w:val="28"/></w:rPr><w:t>Strict</w:t></w:r></w:p>
                <w:p><w:r><w:pict><v:imagedata r:id="rId1"/></w:pict></w:r></w:p>
              </w:body>
            </w:document>
            """.trimIndent()

        val blocks = parseConversionDocument(document, relationships)

        val textRun =
            blocks
                .filterIsInstance<DocBlock.Paragraph>()
                .first()
                .runs
                .single()
        assertEquals("Strict", textRun.text)
        assertTrue(textRun.italic)
        assertEquals(14f, textRun.fontSize)
        assertTrue(blocks.any { it is DocBlock.ImageBlock && it.name == "image1.png" })
    }

    @Test
    fun `rejects foreign roots and ignores foreign lookalikes`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseConversionRelationships(
                "<evil:Relationships xmlns:evil=\"urn:foreign\"><evil:Relationship Id=\"rId1\" Target=\"media/x.png\"/></evil:Relationships>",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseConversionDocument(
                "<evil:document xmlns:evil=\"urn:foreign\"><evil:body/></evil:document>",
                emptyMap(),
            )
        }

        val relationships =
            parseConversionRelationships(
                """
                <Relationships xmlns="$PACKAGE_RELATIONSHIPS" xmlns:evil="urn:foreign">
                  <evil:Relationship Id="spoof" Target="media/spoof.png"/>
                  <Relationship Id="external" Target="media/external.png" TargetMode="External"/>
                  <Relationship Id="valid" Target="media/valid.png"
                    Type="$TRANSITIONAL_OFFICE_RELATIONSHIPS/image"/>
                </Relationships>
                """.trimIndent(),
            )
        val document =
            """
            <w:document xmlns:w="$TRANSITIONAL_WORD" xmlns:evil="urn:foreign">
              <w:body>
                <evil:p><evil:r><evil:t>Injected</evil:t></evil:r></evil:p>
                <w:p><w:r><evil:t>Nested spoof</evil:t><w:t>Visible<evil:x>Injected</evil:x> text</w:t></w:r></w:p>
              </w:body>
            </w:document>
            """.trimIndent()

        val paragraph =
            parseConversionDocument(document, relationships)
                .filterIsInstance<DocBlock.Paragraph>()
                .single()

        assertEquals(mapOf("valid" to "valid.png"), relationships)
        assertEquals("Visible text", paragraph.runs.single().text)
    }

    @Test
    fun `foreign relationship attributes cannot select images`() {
        val relationships = mapOf("rId1" to "image.png")
        val document =
            """
            <w:document xmlns:w="$TRANSITIONAL_WORD" xmlns:a="$TRANSITIONAL_DRAWING" xmlns:evil="urn:foreign">
              <w:body><w:p><w:r><w:drawing><a:blip evil:embed="rId1"/></w:drawing><w:t>Text</w:t></w:r></w:p></w:body>
            </w:document>
            """.trimIndent()

        val blocks = parseConversionDocument(document, relationships)

        assertFalse(blocks.any { it is DocBlock.ImageBlock })
    }

    @Test
    fun `malformed XML and declarations fail instead of returning partial data`() {
        assertThrows(Exception::class.java) {
            parseConversionRelationships(
                "<Relationships xmlns=\"$PACKAGE_RELATIONSHIPS\"><Relationship",
            )
        }
        assertThrows(Exception::class.java) {
            parseConversionDocument(
                "<w:document xmlns:w=\"$TRANSITIONAL_WORD\"><w:body><w:p>",
                emptyMap(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseConversionDocument(
                "<!DOCTYPE document [<!ENTITY secret 'expanded'>]><document>&secret;</document>",
                emptyMap(),
            )
        }
    }

    @Test
    fun `DOCX callbacks propagate cancellation during parsing`() {
        val paragraphs =
            buildString {
                append("<w:document xmlns:w=\"$TRANSITIONAL_WORD\"><w:body>")
                repeat(50) { index -> append("<w:p><w:r><w:t>$index</w:t></w:r></w:p>") }
                append("</w:body></w:document>")
            }
        var checks = 0

        assertThrows(CancellationException::class.java) {
            parseConversionDocument(paragraphs, emptyMap()) {
                checks += 1
                if (checks == 20) throw CancellationException("cancelled")
            }
        }
        assertEquals(20, checks)
    }

    private fun relationshipsXml(
        officeRelationships: String,
        packageRelationships: String = PACKAGE_RELATIONSHIPS,
    ): String =
        """
        <Relationships xmlns="$packageRelationships">
          <Relationship Id="rId1" Target="media/image1.png" Type="$officeRelationships/image"/>
        </Relationships>
        """.trimIndent()

    private companion object {
        const val TRANSITIONAL_WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        const val STRICT_WORD = "http://purl.oclc.org/ooxml/wordprocessingml/main"
        const val TRANSITIONAL_DRAWING = "http://schemas.openxmlformats.org/drawingml/2006/main"
        const val STRICT_DRAWING = "http://purl.oclc.org/ooxml/drawingml/main"
        const val PACKAGE_RELATIONSHIPS = "http://schemas.openxmlformats.org/package/2006/relationships"
        const val STRICT_PACKAGE_RELATIONSHIPS = "http://purl.oclc.org/ooxml/package/relationships"
        const val TRANSITIONAL_OFFICE_RELATIONSHIPS =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        const val STRICT_OFFICE_RELATIONSHIPS = "http://purl.oclc.org/ooxml/officeDocument/relationships"
        const val VML = "urn:schemas-microsoft-com:vml"
    }
}
