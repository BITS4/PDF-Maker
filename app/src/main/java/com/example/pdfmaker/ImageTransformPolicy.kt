package com.example.pdfmaker

import kotlin.math.roundToInt

data class PixelCropBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

/** Validates user-controlled transform values before they reach Android bitmap APIs. */
object ImageTransformPolicy {
    const val MAX_THUMBNAIL_EDGE = 512
    private const val FULL_CROP_EPSILON = 0.001f

    fun requireProcessableDimensions(
        width: Int,
        height: Int,
    ) {
        val fitted = ImageInputPolicy.fitWithinLimits(width, height)
        require(fitted == ImageDimensions(width, height)) {
            "The image exceeds the safe processing limits"
        }
    }

    fun normalizedRotation(degrees: Float): Float {
        require(degrees.isFinite()) { "Rotation must be finite" }
        val normalized = degrees % 360f
        return if (normalized == -0f || kotlin.math.abs(normalized) < FULL_CROP_EPSILON) {
            0f
        } else {
            normalized
        }
    }

    fun requireAdjustment(
        value: Float,
        range: ClosedFloatingPointRange<Float>,
        name: String,
    ): Float {
        require(value.isFinite() && value in range) { "$name is outside the supported range" }
        return value
    }

    fun requireThumbnailEdge(size: Int): Int {
        require(size in 1..MAX_THUMBNAIL_EDGE) { "Thumbnail size is outside the supported range" }
        return size
    }

    fun cropBoundsOrNull(
        imageWidth: Int,
        imageHeight: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): PixelCropBounds? {
        requireProcessableDimensions(imageWidth, imageHeight)
        require(listOf(left, top, right, bottom).all(Float::isFinite)) {
            "Crop coordinates must be finite"
        }
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f) {
            "Crop coordinates must be normalized"
        }
        require(right > left && bottom > top) { "Crop area must have positive dimensions" }

        if (isFullCrop(left, top, right, bottom)) return null

        val x = (left * imageWidth).roundToInt().coerceIn(0, imageWidth - 1)
        val y = (top * imageHeight).roundToInt().coerceIn(0, imageHeight - 1)
        val endX = (right * imageWidth).roundToInt().coerceIn(x + 1, imageWidth)
        val endY = (bottom * imageHeight).roundToInt().coerceIn(y + 1, imageHeight)
        return PixelCropBounds(x, y, endX - x, endY - y)
    }

    private fun isFullCrop(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): Boolean {
        val startsAtOrigin = left <= FULL_CROP_EPSILON && top <= FULL_CROP_EPSILON
        val reachesFarEdge = right >= 1f - FULL_CROP_EPSILON && bottom >= 1f - FULL_CROP_EPSILON
        return startsAtOrigin && reachesFarEdge
    }
}
