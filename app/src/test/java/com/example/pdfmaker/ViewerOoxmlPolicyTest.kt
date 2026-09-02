package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerOoxmlPolicyTest {
    @Test
    fun `extracts slide numbers only from canonical slide entries`() {
        assertEquals(1, viewerSlideNumber("ppt/slides/slide1.xml"))
        assertEquals(42, viewerSlideNumber("ppt/slides/slide42.xml"))
        assertNull(viewerSlideNumber("ppt/slides/_rels/slide1.xml.rels"))
        assertNull(viewerSlideNumber("../ppt/slides/slide1.xml"))
    }

    @Test
    fun `extracts relationship slide numbers`() {
        assertEquals(7, viewerSlideRelationshipNumber("ppt/slides/_rels/slide7.xml.rels"))
        assertNull(viewerSlideRelationshipNumber("ppt/slides/slide7.xml"))
    }

    @Test
    fun `normalizes relationship targets to safe media names`() {
        assertEquals("image1.png", viewerMediaName("../media/image1.png"))
        assertEquals("image2.jpeg", viewerMediaName("..\\media\\image2.jpeg"))
        assertNull(viewerMediaName(".."))
        assertNull(viewerMediaName(""))
    }

    @Test
    fun `rejects non-finite and negative presentation coordinates`() {
        assertEquals(42f, viewerCoordinate("42"), 0f)
        assertEquals(0f, viewerCoordinate("NaN"), 0f)
        assertEquals(0f, viewerCoordinate("Infinity"), 0f)
        assertEquals(0f, viewerCoordinate("-1"), 0f)
        assertEquals(100_000_000f, viewerCoordinate("3.4E38"), 0f)
        assertTrue(viewerCoordinate("3.5").isFinite())
    }
}
