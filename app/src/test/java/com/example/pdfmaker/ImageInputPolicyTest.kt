package com.example.pdfmaker

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageInputPolicyTest {
    @Test
    fun selectionPreservesOrderAndIgnoresDuplicates() {
        val result =
            ImageInputPolicy.mergeDistinct(
                existing = listOf("first", "second"),
                candidates = listOf("second", "third", "first"),
                maximumItems = 4,
            )

        assertEquals(listOf("first", "second", "third"), result.items)
        assertEquals(0, result.rejectedCount)
    }

    @Test
    fun selectionRejectsOnlyDistinctItemsBeyondLimit() {
        val result =
            ImageInputPolicy.mergeDistinct(
                existing = listOf(1, 2),
                candidates = listOf(2, 3, 4, 4, 5),
                maximumItems = 4,
            )

        assertEquals(listOf(1, 2, 3, 4), result.items)
        assertEquals(1, result.rejectedCount)
    }

    @Test
    fun invalidSelectionLimitFailsClosed() {
        assertTrue(
            runCatching {
                ImageInputPolicy.mergeDistinct(emptyList<Int>(), listOf(1), maximumItems = 0)
            }.isFailure,
        )
    }

    @Test
    fun ordinaryImageNeedsNoSampling() {
        val plan = ImageInputPolicy.decodePlan(1_200, 800)

        assertEquals(1, plan?.sampleSize)
        assertEquals(ImageDimensions(1_200, 800), plan?.estimatedDimensions)
    }

    @Test
    fun decodePlanUsesPowerOfTwoSamplingForLargeImages() {
        val plan = ImageInputPolicy.decodePlan(12_000, 3_000)

        assertEquals(8, plan?.sampleSize)
        assertEquals(ImageDimensions(1_500, 375), plan?.estimatedDimensions)
    }

    @Test
    fun pixelBudgetCanDriveSamplingIndependentlyOfEdgeLimit() {
        val plan =
            ImageInputPolicy.decodePlan(
                width = 4_000,
                height = 4_000,
                maximumEdge = 5_000,
                maximumPixels = 1_000_000,
            )

        assertEquals(4, plan?.sampleSize)
        assertEquals(1_000_000L, plan?.estimatedDimensions?.pixels)
    }

    @Test
    fun extremeDimensionsRemainBoundedWithoutOverflow() {
        val plan = ImageInputPolicy.decodePlan(Int.MAX_VALUE, Int.MAX_VALUE)

        assertTrue(requireNotNull(plan).estimatedDimensions.pixels <= ImageInputPolicy.MAX_DECODE_PIXELS)
        assertTrue(plan.estimatedDimensions.width <= ImageInputPolicy.MAX_DECODE_EDGE_PX)
        assertTrue(plan.sampleSize > 1)
    }

    @Test
    fun exactFitPreservesAspectRatioAndPixelBudget() {
        val fitted = requireNotNull(ImageInputPolicy.fitWithinLimits(4_000, 3_000))

        assertTrue(fitted.width <= ImageInputPolicy.MAX_DECODE_EDGE_PX)
        assertTrue(fitted.height <= ImageInputPolicy.MAX_DECODE_EDGE_PX)
        assertTrue(fitted.pixels <= ImageInputPolicy.MAX_DECODE_PIXELS)
        assertEquals(4.0 / 3.0, fitted.width.toDouble() / fitted.height, 0.01)
    }

    @Test
    fun invalidDimensionsAreRejected() {
        assertNull(ImageInputPolicy.decodePlan(0, 100))
        assertNull(ImageInputPolicy.fitWithinLimits(100, -1))
    }

    @Test
    fun mimeAllowlistRejectsGenericOrMissingTypes() {
        assertTrue(ImageInputPolicy.isSupportedMimeType("image/jpeg"))
        assertTrue(ImageInputPolicy.isSupportedMimeType("IMAGE/PNG"))
        assertFalse(ImageInputPolicy.isSupportedMimeType("application/octet-stream"))
        assertFalse(ImageInputPolicy.isSupportedMimeType(null))
    }

    @Test
    fun mimeMatchingIsIndependentOfTheDeviceLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertTrue(ImageInputPolicy.isSupportedMimeType("IMAGE/GIF"))
        } finally {
            Locale.setDefault(original)
        }
    }
}
