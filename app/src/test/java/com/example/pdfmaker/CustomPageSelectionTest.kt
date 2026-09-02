package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CustomPageSelectionTest {
    @Test
    fun `custom pages are bounded deduplicated and sorted`() {
        val result =
            PageSelectionPolicy.resolve(
                totalPages = 6,
                selection = PageSelection.Custom(listOf(6, 2, 2, 0, 9, 4)),
            )
        assertEquals(listOf(2, 4, 6), result)
    }

    @Test
    fun `sparse custom pages cannot masquerade as a contiguous range`() {
        assertNull(
            PageSelectionPolicy.contiguousRange(
                totalPages = 10,
                selection = PageSelection.Custom(listOf(2, 4, 5)),
            ),
        )
    }

    @Test
    fun `contiguous custom pages can be represented as a range`() {
        assertEquals(
            2..4,
            PageSelectionPolicy.contiguousRange(10, PageSelection.Custom(listOf(4, 2, 3))),
        )
    }
}
