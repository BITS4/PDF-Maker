package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MergeScreenPolicyTest {
    @Test
    fun `merge eligibility enforces lower and upper file boundaries`() {
        listOf(Int.MIN_VALUE, -1, 0, 1).forEach { count ->
            assertFalse(MergeScreenPolicy.canMerge(count))
        }
        assertTrue(MergeScreenPolicy.canMerge(2))
        assertTrue(MergeScreenPolicy.canMerge(MergePdfPolicy.MAX_SOURCE_FILES))
        assertFalse(MergeScreenPolicy.canMerge(MergePdfPolicy.MAX_SOURCE_FILES + 1))
        assertFalse(MergeScreenPolicy.canMerge(Int.MAX_VALUE))
    }

    @Test
    fun `summary aggregates file page and size totals`() {
        assertEquals(
            MergeSummary(fileCount = 0, pageCount = 0, sizeKb = 0L),
            MergeScreenPolicy.summary(emptyList(), emptyList()),
        )
        assertEquals(
            MergeSummary(fileCount = 3, pageCount = 12, sizeKb = 3_584L),
            MergeScreenPolicy.summary(
                pageCounts = listOf(2, 4, 6),
                sizesKb = listOf(512L, 1_024L, 2_048L),
            ),
        )
    }

    @Test
    fun `summary rejects mismatched and negative source data`() {
        assertThrows(IllegalArgumentException::class.java) {
            MergeScreenPolicy.summary(listOf(1), emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            MergeScreenPolicy.summary(listOf(-1), listOf(1L))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MergeScreenPolicy.summary(listOf(1), listOf(-1L))
        }
    }

    @Test
    fun `summary rejects integer and long overflow`() {
        assertThrows(ArithmeticException::class.java) {
            MergeScreenPolicy.summary(
                pageCounts = listOf(Int.MAX_VALUE, 1),
                sizesKb = listOf(0L, 0L),
            )
        }
        assertThrows(ArithmeticException::class.java) {
            MergeScreenPolicy.summary(
                pageCounts = listOf(0, 0),
                sizesKb = listOf(Long.MAX_VALUE, 1L),
            )
        }
    }

    @Test
    fun `target index accepts valid adjacent moves`() {
        assertEquals(0, MergeScreenPolicy.targetIndex(3, 1, MergeItemMove.UP))
        assertEquals(2, MergeScreenPolicy.targetIndex(3, 1, MergeItemMove.DOWN))
    }

    @Test
    fun `target index rejects empty invalid and edge moves`() {
        assertEquals(null, MergeScreenPolicy.targetIndex(0, 0, MergeItemMove.UP))
        assertEquals(null, MergeScreenPolicy.targetIndex(3, -1, MergeItemMove.DOWN))
        assertEquals(null, MergeScreenPolicy.targetIndex(3, 3, MergeItemMove.UP))
        assertEquals(null, MergeScreenPolicy.targetIndex(3, 0, MergeItemMove.UP))
        assertEquals(null, MergeScreenPolicy.targetIndex(3, 2, MergeItemMove.DOWN))
    }

    @Test
    fun `moved swaps adjacent values without mutating input`() {
        val source = listOf("first", "second", "third")

        val movedUp = MergeScreenPolicy.moved(source, 2, MergeItemMove.UP)
        val movedDown = MergeScreenPolicy.moved(source, 0, MergeItemMove.DOWN)

        assertEquals(listOf("first", "third", "second"), movedUp)
        assertEquals(listOf("second", "first", "third"), movedDown)
        assertEquals(listOf("first", "second", "third"), source)
    }

    @Test
    fun `moved returns original list when move is outside boundaries`() {
        val source = listOf("first", "second")

        assertSame(source, MergeScreenPolicy.moved(source, 0, MergeItemMove.UP))
        assertSame(source, MergeScreenPolicy.moved(source, 1, MergeItemMove.DOWN))
        assertSame(source, MergeScreenPolicy.moved(source, -1, MergeItemMove.DOWN))
    }

    @Test
    fun `progress clamps external callbacks to percentage range`() {
        assertEquals(0, MergeScreenPolicy.progress(Int.MIN_VALUE))
        assertEquals(0, MergeScreenPolicy.progress(0))
        assertEquals(37, MergeScreenPolicy.progress(37))
        assertEquals(100, MergeScreenPolicy.progress(100))
        assertEquals(100, MergeScreenPolicy.progress(Int.MAX_VALUE))
    }

    @Test
    fun `default output names advance safely`() {
        assertEquals("merged_document_1", MergeScreenPolicy.defaultOutputName(0))
        assertEquals("merged_document_2", MergeScreenPolicy.defaultOutputName(1))
        assertThrows(IllegalArgumentException::class.java) {
            MergeScreenPolicy.defaultOutputName(-1)
        }
        assertThrows(ArithmeticException::class.java) {
            MergeScreenPolicy.defaultOutputName(Int.MAX_VALUE)
        }
    }
}
