package com.example.pdfmaker

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ViewerParsingInstrumentedTest {
    @Test
    fun documentParserPreservesStylesBreaksAndImageRelationships() {
        val relationships =
            parseViewerRelationships(
                """
                <Relationships>
                  <Relationship Id="rId1" Target="media/image1.png"/>
                </Relationships>
                """.trimIndent(),
            )
        val blocks =
            parseViewerDocument(
                """
                <w:document xmlns:w="urn:word" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <w:body>
                    <w:p>
                      <w:pPr><w:pStyle w:val="Heading2"/></w:pPr>
                      <w:r><w:rPr><w:b/><w:i/><w:sz w:val="28"/></w:rPr><w:t>Hello</w:t></w:r>
                      <w:r><w:br w:type="page"/></w:r>
                      <w:r><w:blip r:embed="rId1"/></w:r>
                    </w:p>
                  </w:body>
                </w:document>
                """.trimIndent(),
                relationships,
            )

        val paragraph = blocks.filterIsInstance<DocBlock.Paragraph>().first()
        assertEquals(2, paragraph.headingLevel)
        assertEquals("Hello", paragraph.runs.single().text)
        assertTrue(paragraph.runs.single().bold)
        assertTrue(paragraph.runs.single().italic)
        assertEquals(14f, paragraph.runs.single().fontSize)
        assertTrue(blocks.any { block -> block is DocBlock.PageBreak })
        assertTrue(blocks.any { block -> block == DocBlock.ImageBlock("image1.png") })
    }

    @Test
    fun slideParserPreservesTextImageAndScaledBounds() {
        val elements = parseViewerSlideElements(smallSlideXml(), width = 1_000, height = 562)

        assertEquals(1, elements.texts.size)
        assertEquals("Quarterly results", elements.texts.single().text)
        assertBounds(
            elements.texts.single().bounds,
            left = 100f,
            top = 56.2f,
            right = 300f,
            bottom = 168.6f,
        )
        assertEquals(1, elements.images.size)
        assertEquals("rId5", elements.images.single().relationshipId)
        assertBounds(
            elements.images.single().bounds,
            left = 500f,
            top = 281f,
            right = 600f,
            bottom = 337.2f,
        )
    }

    @Test
    fun slideParserNeverEmitsMoreThanTheElementBudget() {
        val xml =
            buildString {
                append(SLIDE_START)
                repeat(ViewerResourceLimits.MAX_SLIDE_ELEMENTS - 1) { index ->
                    append(shapeXml("Shape $index"))
                }
                append(shapeXml("Last shape", relationshipId = "rId-overflow"))
                append(SLIDE_END)
            }

        val elements = parseViewerSlideElements(xml, width = 1_000, height = 562)
        val emittedCount = elements.texts.size + elements.images.size

        assertEquals(ViewerResourceLimits.MAX_SLIDE_ELEMENTS, emittedCount)
        assertEquals(ViewerResourceLimits.MAX_SLIDE_ELEMENTS, elements.texts.size)
        assertTrue(elements.images.isEmpty())
        assertEquals("Last shape", elements.texts.last().text)
    }

    private fun assertBounds(
        bounds: android.graphics.RectF,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) {
        assertEquals(left, bounds.left, 0.01f)
        assertEquals(top, bounds.top, 0.01f)
        assertEquals(right, bounds.right, 0.01f)
        assertEquals(bottom, bounds.bottom, 0.01f)
    }

    private fun smallSlideXml(): String =
        buildString {
            append(SLIDE_START)
            append(
                """
                <p:sp>
                  <p:spPr><a:xfrm><a:off x="914400" y="514350"/><a:ext cx="1828800" cy="1028700"/></a:xfrm></p:spPr>
                  <p:txBody><a:p><a:r><a:t>Quarterly results</a:t></a:r></a:p></p:txBody>
                </p:sp>
                <p:pic>
                  <p:blipFill><a:blip r:embed="rId5"/></p:blipFill>
                  <p:spPr><a:xfrm><a:off x="4572000" y="2571750"/><a:ext cx="914400" cy="514350"/></a:xfrm></p:spPr>
                </p:pic>
                """.trimIndent(),
            )
            append(SLIDE_END)
        }

    private fun shapeXml(
        text: String,
        relationshipId: String? = null,
    ): String =
        buildString {
            append("<p:sp><p:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"914400\" cy=\"514350\"/></a:xfrm></p:spPr>")
            relationshipId?.let { append("<a:blip r:embed=\"").append(it).append("\"/>") }
            append("<p:txBody><a:p><a:r><a:t>").append(text).append("</a:t></a:r></a:p></p:txBody></p:sp>")
        }

    private companion object {
        const val SLIDE_START =
            "<p:sld xmlns:p=\"urn:presentation\" xmlns:a=\"urn:drawing\" " +
                "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><p:cSld><p:spTree>"
        const val SLIDE_END = "</p:spTree></p:cSld></p:sld>"
    }
}
