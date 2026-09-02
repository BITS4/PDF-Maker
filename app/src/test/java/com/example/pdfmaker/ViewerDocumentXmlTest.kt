package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerDocumentXmlTest {
    @Test
    fun `parses safe relationships and formatted document runs`() {
        val relationships = parseViewerRelationships(
            """
            <Relationships>
              <Relationship Id="rId1" Target="media/image1.png" />
              <Relationship Id="external" Target="https://example.com/image.png" />
            </Relationships>
            """.trimIndent(),
        )
        val blocks = parseViewerDocument(
            """
            <w:document xmlns:w="urn:word" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
              <w:body>
                <w:p><w:r><w:rPr><w:b /></w:rPr><w:t>Hello</w:t></w:r></w:p>
                <w:p><w:r><w:br w:type="page" /></w:r></w:p>
                <w:p><w:r><w:drawing><w:blip r:embed="rId1" /></w:drawing></w:r></w:p>
              </w:body>
            </w:document>
            """.trimIndent(),
            relationships,
        )

        val paragraph = blocks.filterIsInstance<DocBlock.Paragraph>().first()
        assertEquals("Hello", paragraph.runs.single().text)
        assertTrue(paragraph.runs.single().bold)
        assertTrue(blocks.any { it is DocBlock.PageBreak })
        assertTrue(blocks.any { it is DocBlock.ImageBlock && it.name == "image1.png" })
    }

    @Test
    fun `bounds document blocks and run text`() {
        val oversizedText = "x".repeat(MAX_VIEWER_CELL_CHARACTERS + 50)
        val paragraphs = buildString {
            repeat(MAX_VIEWER_DOCUMENT_BLOCKS + 1) { append("<w:p><w:r><w:t>x</w:t></w:r></w:p>") }
        }
        val blocks = parseViewerDocument(
            "<w:document xmlns:w=\"urn:word\"><w:body>$paragraphs</w:body></w:document>",
            emptyMap(),
        )
        val longRun = parseViewerDocument(
            "<w:document xmlns:w=\"urn:word\"><w:body><w:p><w:r><w:t>$oversizedText</w:t></w:r></w:p></w:body></w:document>",
            emptyMap(),
        ).filterIsInstance<DocBlock.Paragraph>().single().runs.single()

        assertEquals(MAX_VIEWER_DOCUMENT_BLOCKS, blocks.size)
        assertEquals(MAX_VIEWER_CELL_CHARACTERS, longRun.text.length)
    }
}
