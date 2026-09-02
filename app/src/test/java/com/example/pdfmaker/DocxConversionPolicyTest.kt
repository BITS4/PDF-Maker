package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DocxConversionPolicyTest {
    @Test
    fun `allows exact collection and text boundaries`() {
        DocxConversionPolicy.requireCanAdd(0, 1, "blocks")
        DocxConversionPolicy.requireCanAdd(9, 10, "blocks")
        DocxConversionPolicy.requireCanAppend(7, 3, 10, "text")
        assertEquals(2, DocxConversionPolicy.nextPage(1))
        assertEquals(
            DocxConversionPolicy.MAX_PAGES,
            DocxConversionPolicy.nextPage(DocxConversionPolicy.MAX_PAGES - 1),
        )
    }

    @Test
    fun `rejects collection overflow`() {
        listOf(1, Int.MAX_VALUE).forEach { current ->
            assertThrows(IllegalArgumentException::class.java) {
                DocxConversionPolicy.requireCanAdd(current, 1, "blocks")
            }
        }
    }

    @Test
    fun `rejects text overflow without arithmetic wraparound`() {
        assertThrows(IllegalArgumentException::class.java) {
            DocxConversionPolicy.requireCanAppend(10, 1, 10, "text")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DocxConversionPolicy.requireCanAppend(Int.MAX_VALUE, 1, Int.MAX_VALUE, "text")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DocxConversionPolicy.requireCanAppend(0, -1, 10, "text")
        }
    }

    @Test
    fun `rejects rendered page overflow`() {
        listOf(0, DocxConversionPolicy.MAX_PAGES, Int.MAX_VALUE).forEach { page ->
            assertThrows(IllegalArgumentException::class.java) {
                DocxConversionPolicy.nextPage(page)
            }
        }
    }
}
