package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageBitmapCachePolicyTest {
    @Test
    fun `keeps only current and adjacent pages`() {
        assertEquals(setOf(4, 5, 6), PageBitmapCachePolicy.retainedIndexes(5, 20))
        assertEquals(setOf(0, 1), PageBitmapCachePolicy.retainedIndexes(0, 20))
        assertEquals(setOf(18, 19), PageBitmapCachePolicy.retainedIndexes(19, 20))
    }

    @Test
    fun `handles empty clamped and invalid requests`() {
        assertTrue(PageBitmapCachePolicy.retainedIndexes(0, 0).isEmpty())
        assertEquals(setOf(0, 1), PageBitmapCachePolicy.retainedIndexes(-5, 3))
        assertEquals(setOf(1, 2), PageBitmapCachePolicy.retainedIndexes(99, 3))
        assertTrue(runCatching { PageBitmapCachePolicy.retainedIndexes(0, 1, -1) }.isFailure)
    }
}
