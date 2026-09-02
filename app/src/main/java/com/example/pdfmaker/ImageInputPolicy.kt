package com.example.pdfmaker

import java.util.Locale
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

data class BoundedSelection<T>(
    val items: List<T>,
    val rejectedCount: Int,
)

data class ImageDimensions(
    val width: Int,
    val height: Int,
) {
    val pixels: Long = width.toLong() * height.toLong()
}

data class ImageDecodePlan(
    val sampleSize: Int,
    val estimatedDimensions: ImageDimensions,
)

/** Resource limits shared by gallery selection, decoding, and perspective transforms. */
object ImageInputPolicy {
    const val MAX_SELECTED_IMAGES = 12
    const val MAX_GALLERY_ITEMS = 500
    const val MAX_DECODE_EDGE_PX = 1_600
    const val MAX_DECODE_PIXELS = 1_200_000L
    const val MAX_ENCODED_BYTES = 40L * 1024L * 1024L
    private val supportedMimeTypes = setOf(
        "image/bmp",
        "image/gif",
        "image/heic",
        "image/heif",
        "image/jpeg",
        "image/png",
        "image/webp",
    )

    fun <T> mergeDistinct(
        existing: Iterable<T>,
        candidates: Iterable<T>,
        maximumItems: Int = MAX_SELECTED_IMAGES,
    ): BoundedSelection<T> {
        require(maximumItems > 0) { "Maximum selection size must be positive" }
        val accepted = LinkedHashSet<T>()
        var rejected = 0

        sequenceOf(existing, candidates).forEach { source ->
            source.forEach { item ->
                if (item in accepted) return@forEach
                if (accepted.size < maximumItems) {
                    accepted += item
                } else {
                    rejected += 1
                }
            }
        }
        return BoundedSelection(accepted.toList(), rejected)
    }

    fun decodePlan(
        width: Int,
        height: Int,
        maximumEdge: Int = MAX_DECODE_EDGE_PX,
        maximumPixels: Long = MAX_DECODE_PIXELS,
    ): ImageDecodePlan? {
        require(maximumEdge > 0) { "Maximum image edge must be positive" }
        require(maximumPixels > 0) { "Maximum pixel count must be positive" }
        if (width <= 0 || height <= 0) return null

        var sampleSize = 1
        var dimensions = sampledDimensions(width, height, sampleSize)
        while (
            maxOf(dimensions.width, dimensions.height) > maximumEdge ||
                dimensions.pixels > maximumPixels
        ) {
            check(sampleSize <= Int.MAX_VALUE / 2) { "Image dimensions cannot be sampled safely" }
            sampleSize *= 2
            dimensions = sampledDimensions(width, height, sampleSize)
        }
        return ImageDecodePlan(sampleSize, dimensions)
    }

    fun fitWithinLimits(
        width: Int,
        height: Int,
        maximumEdge: Int = MAX_DECODE_EDGE_PX,
        maximumPixels: Long = MAX_DECODE_PIXELS,
    ): ImageDimensions? {
        require(maximumEdge > 0) { "Maximum image edge must be positive" }
        require(maximumPixels > 0) { "Maximum pixel count must be positive" }
        if (width <= 0 || height <= 0) return null

        val widthDouble = width.toDouble()
        val heightDouble = height.toDouble()
        val edgeScale = maximumEdge.toDouble() / maxOf(widthDouble, heightDouble)
        val pixelScale = sqrt(maximumPixels.toDouble() / (widthDouble * heightDouble))
        val scale = min(1.0, min(edgeScale, pixelScale))
        return ImageDimensions(
            width = floor(widthDouble * scale).toInt().coerceAtLeast(1),
            height = floor(heightDouble * scale).toInt().coerceAtLeast(1),
        )
    }

    fun isSupportedMimeType(mimeType: String?): Boolean =
        mimeType?.lowercase(Locale.ROOT) in supportedMimeTypes

    private fun sampledDimensions(width: Int, height: Int, sampleSize: Int): ImageDimensions =
        ImageDimensions(
            width = ceilDivide(width, sampleSize),
            height = ceilDivide(height, sampleSize),
        )

    private fun ceilDivide(value: Int, divisor: Int): Int =
        ((value.toLong() + divisor - 1L) / divisor).toInt().coerceAtLeast(1)
}
