package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PageRetentionPolicyTest {
    @Test
    fun `retained indexes preserve original page order`() {
        assertEquals(listOf(0, 2, 4), PageEditPolicy.retainedIndexes(listOf(false, true, false, true, false)))
    }

    @Test
    fun `saving requires at least one retained page`() {
        assertFalse(PageEditPolicy.canSave(emptyList()))
        assertFalse(PageEditPolicy.canSave(listOf(true, true)))
        assertTrue(PageEditPolicy.canSave(listOf(true, false)))
    }

    @Test
    fun `page manager bounds the number of retained thumbnails`() {
        assertEquals(1, PageEditPolicy.requireSupportedPageCount(1))
        assertEquals(200, PageEditPolicy.requireSupportedPageCount(200))
        listOf(0, 201).forEach { invalidCount ->
            try {
                PageEditPolicy.requireSupportedPageCount(invalidCount)
                fail("Unsupported page count must be rejected")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }
}
