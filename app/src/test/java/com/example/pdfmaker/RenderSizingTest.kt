package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderSizingTest {
    @Test
    fun `downscales landscape pages while preserving aspect ratio`() {
        assertEquals(PixelSize(1_600, 800), RenderSizing.fitWithin(4_000, 2_000, 1_600))
    }

    @Test
    fun `downscales portrait pages while preserving aspect ratio`() {
        assertEquals(PixelSize(600, 1_200), RenderSizing.fitWithin(1_000, 2_000, 1_200))
    }

    @Test
    fun `does not upscale small pages by default`() {
        assertEquals(PixelSize(320, 240), RenderSizing.fitWithin(320, 240, 1_600))
    }

    @Test
    fun `supports explicit bounded upscaling`() {
        assertEquals(PixelSize(1_000, 500), RenderSizing.fitWithin(200, 100, 1_000, allowUpscale = true))
    }

    @Test
    fun `rejects invalid dimensions`() {
        assertNull(RenderSizing.fitWithin(0, 100, 1_000))
        assertNull(RenderSizing.fitWithin(100, -1, 1_000))
        assertNull(RenderSizing.fitWithin(100, 100, 0))
    }

    @Test
    fun `handles extreme source sizes without integer overflow`() {
        val result = RenderSizing.fitWithin(Int.MAX_VALUE, Int.MAX_VALUE - 1, 2_048)!!
        assertEquals(2_048, result.width)
        assertTrue(result.height in 1..2_048)
        assertTrue(result.pixelCount <= 2_048L * 2_048L)
    }
}
