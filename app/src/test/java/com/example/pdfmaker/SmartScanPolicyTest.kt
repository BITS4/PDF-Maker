package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SmartScanPolicyTest {
    @Test
    fun documentLimitMatchesTheDownstreamEditorLimit() {
        assertEquals(ImageInputPolicy.MAX_SELECTED_IMAGES, SmartScanPolicy.MAX_DOCUMENT_PAGES)
    }

    @Test
    fun captureAvailabilityIncludesThePendingCapture() {
        assertTrue(SmartScanPolicy.canStartDocumentCapture(0, 0))
        assertTrue(SmartScanPolicy.canStartDocumentCapture(10, 1))
        assertFalse(SmartScanPolicy.canStartDocumentCapture(11, 1))
        assertFalse(SmartScanPolicy.canStartDocumentCapture(12, 0))
    }

    @Test
    fun invalidCaptureCountersFailClosed() {
        assertTrue(runCatching { SmartScanPolicy.canStartDocumentCapture(-1, 0) }.isFailure)
        assertTrue(runCatching { SmartScanPolicy.canStartDocumentCapture(13, 0) }.isFailure)
        assertTrue(runCatching { SmartScanPolicy.canStartDocumentCapture(0, -1) }.isFailure)
        assertTrue(runCatching { SmartScanPolicy.canStartDocumentCapture(0, 2) }.isFailure)
    }

    @Test
    fun remainingSlotsHandlesBothBoundaries() {
        assertEquals(SmartScanPolicy.MAX_DOCUMENT_PAGES, SmartScanPolicy.remainingDocumentSlots(0))
        assertEquals(0, SmartScanPolicy.remainingDocumentSlots(SmartScanPolicy.MAX_DOCUMENT_PAGES))
        assertTrue(runCatching { SmartScanPolicy.remainingDocumentSlots(Int.MAX_VALUE) }.isFailure)
    }

    @Test
    fun handoffSelectionPreservesOrderAndCountsOnlyDistinctOverflow() {
        val result =
            SmartScanPolicy.selectForHandoff(
                captured = listOf(1, 2),
                selected = (2..14).toList() + 14,
            )

        assertEquals((1..SmartScanPolicy.MAX_DOCUMENT_PAGES).toList(), result.items)
        assertEquals(2, result.rejectedCount)
    }

    @Test
    fun ordinaryCaptureNeedsNoSampling() {
        val plan = SmartScanPolicy.captureDecodePlan(1_600, 1_200)

        assertEquals(1, plan?.sampleSize)
        assertEquals(ImageDimensions(1_600, 1_200), plan?.estimatedDimensions)
    }

    @Test
    fun oversizedCaptureUsesPowerOfTwoSamplingWithinBothBudgets() {
        val plan = requireNotNull(SmartScanPolicy.captureDecodePlan(12_000, 9_000))

        assertEquals(8, plan.sampleSize)
        assertTrue(plan.estimatedDimensions.width <= SmartScanPolicy.MAX_CAPTURE_EDGE_PX)
        assertTrue(plan.estimatedDimensions.height <= SmartScanPolicy.MAX_CAPTURE_EDGE_PX)
        assertTrue(plan.estimatedDimensions.pixels <= SmartScanPolicy.MAX_CAPTURE_PIXELS)
    }

    @Test
    fun captureFitRetainsAspectRatioAndRejectsInvalidDimensions() {
        val fitted = requireNotNull(SmartScanPolicy.fittedCaptureSize(8_000, 4_000))

        assertEquals(SmartScanPolicy.MAX_CAPTURE_EDGE_PX, fitted.width)
        assertEquals(SmartScanPolicy.MAX_CAPTURE_EDGE_PX / 2, fitted.height)
        assertNull(SmartScanPolicy.captureDecodePlan(0, 100))
        assertNull(SmartScanPolicy.fittedCaptureSize(100, -1))
    }

    @Test
    fun thumbnailPlanningBoundsLandscapePortraitAndSquareImages() {
        val landscape = requireNotNull(SmartScanPolicy.fittedThumbnailSize(4_000, 2_000))
        val portrait = requireNotNull(SmartScanPolicy.fittedThumbnailSize(2_000, 4_000))
        val squarePlan = requireNotNull(SmartScanPolicy.thumbnailDecodePlan(4_000, 4_000))

        assertEquals(ImageDimensions(96, 48), landscape)
        assertEquals(ImageDimensions(48, 96), portrait)
        assertTrue(squarePlan.estimatedDimensions.pixels <= 192L * 192L)
    }

    @Test
    fun invalidThumbnailTargetsFailClosed() {
        assertTrue(runCatching { SmartScanPolicy.thumbnailDecodePlan(100, 100, 0) }.isFailure)
        assertTrue(runCatching { SmartScanPolicy.fittedThumbnailSize(100, 100, 2_049) }.isFailure)
    }

    @Test
    fun encodedByteLimitsAcceptOnlyNonEmptyBoundedArtifacts() {
        SmartScanPolicy.requireSourceLength(1)
        SmartScanPolicy.requireSourceLength(SmartScanPolicy.MAX_SOURCE_BYTES)
        SmartScanPolicy.requireNormalizedLength(1)
        SmartScanPolicy.requireNormalizedLength(SmartScanPolicy.MAX_NORMALIZED_BYTES)

        assertTrue(runCatching { SmartScanPolicy.requireSourceLength(0) }.isFailure)
        assertTrue(
            runCatching {
                SmartScanPolicy.requireSourceLength(SmartScanPolicy.MAX_SOURCE_BYTES + 1)
            }.isFailure,
        )
        assertTrue(runCatching { SmartScanPolicy.requireNormalizedLength(-1) }.isFailure)
        assertTrue(
            runCatching {
                SmartScanPolicy.requireNormalizedLength(SmartScanPolicy.MAX_NORMALIZED_BYTES + 1)
            }.isFailure,
        )
    }

    @Test
    fun processingErrorsProduceActionableStableMessages() {
        assertTrue(
            SmartScanPolicy
                .processingFailureMessage(
                    IllegalArgumentException("input exceeds limit"),
                ).contains("too large"),
        )
        assertTrue(
            SmartScanPolicy
                .processingFailureMessage(IOException("private path"))
                .contains("storage space"),
        )
        assertFalse(
            SmartScanPolicy
                .processingFailureMessage(RuntimeException("secret-file-name"))
                .contains("secret-file-name"),
        )
    }
}
