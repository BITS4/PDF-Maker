package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerDocumentXmlTest {
    @Test
    fun `takes only text that remains inside the viewer budget`() {
        assertEquals("hello", boundedViewerTextFragment(0, "hello", maximumLength = 5))
        assertEquals("he", boundedViewerTextFragment(3, "hello", maximumLength = 5))
        assertEquals("", boundedViewerTextFragment(5, "hello", maximumLength = 5))
    }

    @Test
    fun `rejects invalid viewer text limits`() {
        assertTrue(runCatching { boundedViewerTextFragment(-1, "text") }.isFailure)
        assertTrue(runCatching { boundedViewerTextFragment(0, "text", maximumLength = 0) }.isFailure)
    }

    @Test
    fun `maps supported heading styles without partial matches`() {
        assertEquals(1, viewerHeadingLevel("Title"))
        assertEquals(1, viewerHeadingLevel("heading1"))
        assertEquals(2, viewerHeadingLevel("Heading2"))
        assertEquals(3, viewerHeadingLevel("Heading3"))
        assertEquals(0, viewerHeadingLevel("Subtitle"))
        assertEquals(0, viewerHeadingLevel("Heading10"))
        assertEquals(0, viewerHeadingLevel("NotHeading1"))
    }
}
