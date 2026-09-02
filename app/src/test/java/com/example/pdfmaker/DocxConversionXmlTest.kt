package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DocxConversionXmlTest {
    @Test
    fun `parses relationships and formatted document blocks`() {
        val relationships = parseConversionRelationships(
            """<Relationships><Relationship Id="rId1" Target="media/image1.png"/></Relationships>""",
        )
        val document = """
            <w:document xmlns:w="urn:w" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
                xmlns:a="urn:a">
              <w:body>
                <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:rPr><w:b/></w:rPr><w:t>Title</w:t></w:r></w:p>
                <w:p><w:r><w:t>Body</w:t></w:r><w:r><w:br w:type="page"/></w:r></w:p>
                <w:p><w:r><w:drawing><a:blip r:embed="rId1"/></w:drawing></w:r></w:p>
              </w:body>
            </w:document>
        """.trimIndent()

        val blocks = parseConversionDocument(document, relationships)

        assertEquals("image1.png", relationships["rId1"])
        assertTrue(blocks.any { it is DocBlock.Paragraph && it.headingLevel == 1 })
        assertTrue(blocks.any { it is DocBlock.PageBreak })
        assertTrue(blocks.any { it is DocBlock.ImageBlock && it.name == "image1.png" })
    }

    @Test
    fun `rejects malformed relationship XML instead of returning partial data`() {
        assertThrows(Exception::class.java) {
            parseConversionRelationships("<Relationships><Relationship")
        }
    }

    @Test
    fun `rejects malformed document XML instead of returning partial blocks`() {
        assertThrows(Exception::class.java) {
            parseConversionDocument("<document><body><p>", emptyMap())
        }
    }

    @Test
    fun `ignores text outside document runs and run property nodes`() {
        val document = """
            <w:document xmlns:w="urn:w">
              outside
              <w:body>
                body text
                <w:p><w:r><w:rPr><w:b>property text</w:b></w:rPr><w:t>Visible</w:t></w:r></w:p>
              </w:body>
            </w:document>
        """.trimIndent()

        val paragraph = parseConversionDocument(document, emptyMap())
            .filterIsInstance<DocBlock.Paragraph>()
            .single()

        assertEquals("Visible", paragraph.runs.single().text)
    }

    @Test
    fun `rejects entity declarations before parser expansion`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseConversionDocument(
                "<!DOCTYPE document [<!ENTITY secret 'expanded'>]><document>&secret;</document>",
                emptyMap(),
            )
        }
    }
}
