package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfEditorOfficeXmlTest {
    @Test
    fun `builds Word image relationships and document package parts`() {
        val relationship = docxImageRelationshipXml("rId200", "image1.jpg")
        val picture = docxPictureParagraphXml(0, "image1.jpg", "rId200", 800, 600)
        val parts = buildDocxPackageXml(picture, listOf(relationship))

        assertTrue(parts.document.contains("r:embed=\"rId200\""))
        assertTrue(parts.documentRelationships.contains("Target=\"media/image1.jpg\""))
        assertTrue(parts.contentTypes.contains("wordprocessingml.document.main+xml"))
        assertTrue(parts.rootRelationships.contains("Target=\"word/document.xml\""))
    }

    @Test
    fun `creates one PowerPoint relationship and override per slide`() {
        val parts = buildPptxPackageXml(slideCount = 3)

        assertEquals(3, parts.presentation.windowed("<p:sldId ".length).count { it == "<p:sldId " })
        assertEquals(
            3,
            parts.presentationRelationships
                .windowed("relationships/slide\"".length)
                .count { it == "relationships/slide\"" },
        )
        assertEquals(
            3,
            parts.contentTypes
                .windowed("<Override PartName=\"/ppt/slides/".length)
                .count { it == "<Override PartName=\"/ppt/slides/" },
        )
    }

    @Test
    fun `guards image dimension math against a zero source width`() {
        val wordXml = docxPictureParagraphXml(0, "image.jpg", "rId200", 0, 600)
        val slideXml = pptxPictureSlideXml(0, "image.jpg", 0, 600)

        assertTrue(wordXml.contains("wp:extent"))
        assertTrue(slideXml.contains("a:ext"))
    }
}
