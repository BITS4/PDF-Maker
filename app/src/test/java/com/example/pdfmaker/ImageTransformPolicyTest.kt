package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageTransformPolicyTest {
    @Test
    fun fullCropNeedsNoBitmapAllocation() {
        assertNull(ImageTransformPolicy.cropBoundsOrNull(1_200, 800, 0f, 0f, 1f, 1f))
        assertNull(ImageTransformPolicy.cropBoundsOrNull(1_200, 800, 0.0005f, 0f, 0.9995f, 1f))
    }

    @Test
    fun normalizedCropConvertsToSafePixelBounds() {
        assertEquals(
            PixelCropBounds(x = 100, y = 50, width = 700, height = 400),
            ImageTransformPolicy.cropBoundsOrNull(1_000, 500, 0.1f, 0.1f, 0.8f, 0.9f),
        )
    }

    @Test
    fun tinyCropStillProducesOnePixel() {
        assertEquals(
            PixelCropBounds(x = 9, y = 9, width = 1, height = 1),
            ImageTransformPolicy.cropBoundsOrNull(10, 10, 0.94f, 0.94f, 0.95f, 0.95f),
        )
    }

    @Test
    fun invalidCropValuesFailClosed() {
        val invalidCrops =
            listOf(
                floatArrayOf(Float.NaN, 0f, 1f, 1f),
                floatArrayOf(-0.1f, 0f, 1f, 1f),
                floatArrayOf(0f, 0f, 1.1f, 1f),
                floatArrayOf(0.8f, 0f, 0.2f, 1f),
                floatArrayOf(0f, 0.5f, 1f, 0.5f),
            )

        invalidCrops.forEach { crop ->
            assertTrue(
                runCatching {
                    ImageTransformPolicy.cropBoundsOrNull(
                        100,
                        100,
                        crop[0],
                        crop[1],
                        crop[2],
                        crop[3],
                    )
                }.isFailure,
            )
        }
    }

    @Test
    fun processingDimensionsReuseDecodeLimits() {
        ImageTransformPolicy.requireProcessableDimensions(1_200, 1_000)

        assertTrue(runCatching { ImageTransformPolicy.requireProcessableDimensions(0, 10) }.isFailure)
        assertTrue(runCatching { ImageTransformPolicy.requireProcessableDimensions(1_601, 1) }.isFailure)
        assertTrue(runCatching { ImageTransformPolicy.requireProcessableDimensions(1_200, 1_001) }.isFailure)
    }

    @Test
    fun rotationsAreFiniteAndCanonical() {
        assertEquals(0f, ImageTransformPolicy.normalizedRotation(720f))
        assertEquals(-90f, ImageTransformPolicy.normalizedRotation(-450f))
        assertEquals(90f, ImageTransformPolicy.normalizedRotation(450f))
        assertTrue(runCatching { ImageTransformPolicy.normalizedRotation(Float.POSITIVE_INFINITY) }.isFailure)
    }

    @Test
    fun adjustmentAndThumbnailRangesAreEnforced() {
        assertEquals(25f, ImageTransformPolicy.requireAdjustment(25f, -100f..100f, "Brightness"))
        assertEquals(80, ImageTransformPolicy.requireThumbnailEdge(80))
        assertTrue(
            runCatching {
                ImageTransformPolicy.requireAdjustment(Float.NaN, -100f..100f, "Contrast")
            }.isFailure,
        )
        assertTrue(
            runCatching {
                ImageTransformPolicy.requireAdjustment(101f, -100f..100f, "Contrast")
            }.isFailure,
        )
        assertTrue(runCatching { ImageTransformPolicy.requireThumbnailEdge(0) }.isFailure)
        assertTrue(
            runCatching {
                ImageTransformPolicy.requireThumbnailEdge(ImageTransformPolicy.MAX_THUMBNAIL_EDGE + 1)
            }.isFailure,
        )
    }
}
