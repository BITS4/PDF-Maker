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

    @Test
    fun `computes the transform that fits a source into its target bitmap`() {
        assertEquals(
            RenderScale(scaleX = 0.25f, scaleY = 0.25f),
            RenderSizing.scaleTo(800, 1_200, PixelSize(200, 300)),
        )
        assertEquals(
            RenderScale(scaleX = 2f, scaleY = 2f),
            RenderSizing.scaleTo(400, 200, PixelSize(800, 400)),
        )
    }

    @Test
    fun `rejects render transforms with invalid source or target dimensions`() {
        assertNull(RenderSizing.scaleTo(0, 100, PixelSize(50, 50)))
        assertNull(RenderSizing.scaleTo(100, 0, PixelSize(50, 50)))
        assertNull(RenderSizing.scaleTo(100, 100, PixelSize(0, 50)))
        assertNull(RenderSizing.scaleTo(100, 100, PixelSize(50, -1)))
    }

    @Test
    fun `render transform maps both source edges onto a rounded fitted bitmap`() {
        val target = RenderSizing.fitWithin(612, 792, 200, allowUpscale = true)!!
        val scale = RenderSizing.scaleTo(612, 792, target)!!

        assertEquals(target.width.toFloat(), 612f * scale.scaleX, 0.0001f)
        assertEquals(target.height.toFloat(), 792f * scale.scaleY, 0.0001f)
    }
}
