package com.example.pdfmaker

import java.io.IOException

/** Pure resource policy shared by Smart Scan capture, gallery handoff, and previews. */
internal object SmartScanPolicy {
    const val MAX_DOCUMENT_PAGES = ImageInputPolicy.MAX_SELECTED_IMAGES
    const val MAX_CAPTURE_EDGE_PX = 2_048
    const val MAX_CAPTURE_PIXELS = 4_194_304L
    const val MAX_SOURCE_BYTES = ImageInputPolicy.MAX_ENCODED_BYTES
    const val MAX_NORMALIZED_BYTES = ImageInputPolicy.MAX_ENCODED_BYTES
    const val THUMBNAIL_EDGE_PX = 96

    fun canStartDocumentCapture(
        capturedPages: Int,
        pendingCaptures: Int,
    ): Boolean {
        require(capturedPages in 0..MAX_DOCUMENT_PAGES) { "Captured page count is invalid" }
        require(pendingCaptures in 0..1) { "Pending capture count is invalid" }
        return capturedPages + pendingCaptures < MAX_DOCUMENT_PAGES
    }

    fun remainingDocumentSlots(capturedPages: Int): Int {
        require(capturedPages in 0..MAX_DOCUMENT_PAGES) { "Captured page count is invalid" }
        return MAX_DOCUMENT_PAGES - capturedPages
    }

    fun <T> selectForHandoff(
        captured: Iterable<T>,
        selected: Iterable<T>,
    ): BoundedSelection<T> =
        ImageInputPolicy.mergeDistinct(
            existing = captured,
            candidates = selected.distinct(),
            maximumItems = MAX_DOCUMENT_PAGES,
        )

    fun captureDecodePlan(
        width: Int,
        height: Int,
    ): ImageDecodePlan? =
        ImageInputPolicy.decodePlan(
            width = width,
            height = height,
            maximumEdge = MAX_CAPTURE_EDGE_PX,
            maximumPixels = MAX_CAPTURE_PIXELS,
        )

    fun fittedCaptureSize(
        width: Int,
        height: Int,
    ): ImageDimensions? =
        ImageInputPolicy.fitWithinLimits(
            width = width,
            height = height,
            maximumEdge = MAX_CAPTURE_EDGE_PX,
            maximumPixels = MAX_CAPTURE_PIXELS,
        )

    fun thumbnailDecodePlan(
        width: Int,
        height: Int,
        targetEdge: Int = THUMBNAIL_EDGE_PX,
    ): ImageDecodePlan? {
        require(targetEdge in 1..MAX_CAPTURE_EDGE_PX) { "Thumbnail edge is invalid" }
        val decodeEdge = (targetEdge * 2).coerceAtMost(MAX_CAPTURE_EDGE_PX)
        return ImageInputPolicy.decodePlan(
            width = width,
            height = height,
            maximumEdge = decodeEdge,
            maximumPixels = decodeEdge.toLong() * decodeEdge,
        )
    }

    fun fittedThumbnailSize(
        width: Int,
        height: Int,
        targetEdge: Int = THUMBNAIL_EDGE_PX,
    ): ImageDimensions? {
        require(targetEdge in 1..MAX_CAPTURE_EDGE_PX) { "Thumbnail edge is invalid" }
        return ImageInputPolicy.fitWithinLimits(
            width = width,
            height = height,
            maximumEdge = targetEdge,
            maximumPixels = targetEdge.toLong() * targetEdge,
        )
    }

    fun requireSourceLength(byteCount: Long) {
        require(byteCount in 1..MAX_SOURCE_BYTES) {
            "Captured photo is empty or exceeds the ${MAX_SOURCE_BYTES / (1024 * 1024)} MB limit"
        }
    }

    fun requireNormalizedLength(byteCount: Long) {
        require(byteCount in 1..MAX_NORMALIZED_BYTES) {
            "Processed photo is empty or exceeds the ${MAX_NORMALIZED_BYTES / (1024 * 1024)} MB limit"
        }
    }

    fun processingFailureMessage(error: Throwable): String =
        when {
            error is IllegalArgumentException && error.message.orEmpty().contains("limit") -> {
                "This photo is too large to scan safely. Lower the camera resolution and try again."
            }

            error is IOException -> {
                "The photo could not be saved. Free some storage space and try again."
            }

            else -> {
                "The photo could not be processed. Retake it in good light and hold the camera steady."
            }
        }
}
