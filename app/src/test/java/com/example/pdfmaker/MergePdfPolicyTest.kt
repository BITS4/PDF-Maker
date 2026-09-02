package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MergePdfPolicyTest {
    @Test
    fun `accepts configured source and aggregate limits`() {
        assertEquals(1, MergePdfPolicy.requireSourceCount(1))
        assertEquals(
            MergePdfPolicy.MAX_SOURCE_FILES,
            MergePdfPolicy.requireSourceCount(MergePdfPolicy.MAX_SOURCE_FILES),
        )
        assertEquals(
            MergePdfPolicy.MAX_TOTAL_PAGES,
            MergePdfPolicy.updatedTotalPages(
                MergePdfPolicy.MAX_TOTAL_PAGES - MergePdfPolicy.MAX_PAGES_PER_SOURCE,
                MergePdfPolicy.MAX_PAGES_PER_SOURCE,
            ),
        )
    }

    @Test
    fun `rejects unsafe source counts`() {
        listOf(0, MergePdfPolicy.MAX_SOURCE_FILES + 1).forEach { sourceCount ->
            assertThrows(IllegalArgumentException::class.java) {
                MergePdfPolicy.requireSourceCount(sourceCount)
            }
        }
    }

    @Test
    fun `rejects unsafe page counts and integer boundary bypasses`() {
        listOf(0, MergePdfPolicy.MAX_PAGES_PER_SOURCE + 1).forEach { sourcePages ->
            assertThrows(IllegalArgumentException::class.java) {
                MergePdfPolicy.updatedTotalPages(0, sourcePages)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            MergePdfPolicy.updatedTotalPages(MergePdfPolicy.MAX_TOTAL_PAGES, 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MergePdfPolicy.updatedTotalPages(Int.MAX_VALUE, 1)
        }
    }
}
