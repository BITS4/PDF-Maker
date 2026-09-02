package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CompressionPolicyTest {
    @Test
    fun `accepts the configured page boundary`() {
        assertEquals(1, CompressionPolicy.requirePageCount(1))
        assertEquals(
            CompressionPolicy.MAX_PAGES,
            CompressionPolicy.requirePageCount(CompressionPolicy.MAX_PAGES),
        )
    }

    @Test
    fun `rejects empty and oversized documents`() {
        listOf(0, CompressionPolicy.MAX_PAGES + 1, Int.MAX_VALUE).forEach { count ->
            assertThrows(IllegalArgumentException::class.java) {
                CompressionPolicy.requirePageCount(count)
            }
        }
    }

    @Test
    fun `bounds wide and tall render dimensions without upscaling`() {
        val wide = CompressionPolicy.renderSize(8_000, 2_000, 1_600)
        val tall = CompressionPolicy.renderSize(2_000, 8_000, 1_600)
        val small = CompressionPolicy.renderSize(400, 300, 1_600)

        assertEquals(PixelSize(1_600, 400), wide)
        assertEquals(PixelSize(400, 1_600), tall)
        assertEquals(PixelSize(400, 300), small)
        assertTrue(wide.width.toLong() * wide.height <= 1_600L * 1_600L)
    }

    @Test
    fun `rejects invalid dimensions`() {
        assertThrows(IllegalArgumentException::class.java) {
            CompressionPolicy.renderSize(0, 1_000, 1_600)
        }
    }
}
