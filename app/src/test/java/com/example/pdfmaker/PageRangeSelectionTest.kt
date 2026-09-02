package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageRangeSelectionTest {
    @Test
    fun `all pages resolves the complete one-based range`() {
        assertEquals(listOf(1, 2, 3, 4), PageSelectionPolicy.resolve(4, PageSelection.All))
    }

    @Test
    fun `requested range is clamped to document bounds`() {
        assertEquals(
            listOf(1, 2, 3, 4, 5),
            PageSelectionPolicy.resolve(5, PageSelection.Range(-10, 20)),
        )
    }

    @Test
    fun `reversed and empty document ranges are rejected`() {
        assertEquals(emptyList<Int>(), PageSelectionPolicy.resolve(5, PageSelection.Range(4, 2)))
        assertEquals(emptyList<Int>(), PageSelectionPolicy.resolve(0, PageSelection.All))
        assertNull(PageSelectionPolicy.contiguousRange(0, PageSelection.All))
    }

    @Test
    fun `contiguous range preserves validated endpoints`() {
        assertEquals(2..7, PageSelectionPolicy.contiguousRange(10, PageSelection.Range(2, 7)))
    }
}
