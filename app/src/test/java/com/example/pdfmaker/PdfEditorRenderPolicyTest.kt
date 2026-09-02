package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfEditorRenderPolicyTest {
    @Test
    fun `bounds extreme pages and requested display widths`() {
        val portrait = PdfEditorRenderPolicy.targetSize(1_000, 100_000, 20_000)!!
        assertTrue(portrait.width <= PdfEditorRenderPolicy.MAX_RENDER_EDGE)
        assertTrue(portrait.height <= PdfEditorRenderPolicy.MAX_RENDER_EDGE)

        val minimum = PdfEditorRenderPolicy.targetSize(100, 50, 1)!!
        assertEquals(PixelSize(320, 160), minimum)
    }

    @Test
    fun `rejects malformed pages and excessive editor page counts`() {
        assertNull(PdfEditorRenderPolicy.targetSize(0, 100, 1_000))
        assertTrue(
            runCatching {
                PdfEditorRenderPolicy.requirePageCount(PageEditPolicy.MAX_EDITABLE_PAGES + 1)
            }.isFailure,
        )
    }
}
