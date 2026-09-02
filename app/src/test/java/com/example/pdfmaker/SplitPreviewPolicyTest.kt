package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitPreviewPolicyTest {
    @Test
    fun `bounds preview count and thumbnail allocation`() {
        val plan = SplitPreviewPolicy.plan(
            pageCount = SplitPreviewPolicy.MAX_PREVIEW_PAGES,
            pageWidth = 4_000,
            pageHeight = 6_000,
        )

        assertEquals(SplitPreviewPolicy.MAX_PREVIEW_PAGES, plan.pageCount)
        assertTrue(plan.thumbnailSize.width <= SplitPreviewPolicy.THUMBNAIL_WIDTH_PX)
        assertTrue(plan.thumbnailSize.height <= SplitPreviewPolicy.THUMBNAIL_WIDTH_PX)
    }

    @Test
    fun `rejects empty oversized and malformed documents`() {
        listOf(0, SplitPreviewPolicy.MAX_PREVIEW_PAGES + 1).forEach { pageCount ->
            assertTrue(runCatching { SplitPreviewPolicy.plan(pageCount, 100, 100) }.isFailure)
        }
        assertTrue(runCatching { SplitPreviewPolicy.plan(1, 0, 100) }.isFailure)
    }
}
