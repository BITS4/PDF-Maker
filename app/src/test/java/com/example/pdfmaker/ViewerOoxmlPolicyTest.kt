package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
