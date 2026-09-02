package com.example.pdfmaker

import android.graphics.Bitmap
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.sqrt

private const val EDGE_PROCESSING_LIMIT = 512

internal data class DocumentEdgeMap(
    val width: Int,
    val height: Int,
    val edges: BooleanArray,
)

private data class SampledImage(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
)

private data class Gradients(
    val horizontal: FloatArray,
    val vertical: FloatArray,
    val magnitude: FloatArray,
)

private val gaussianKernel =
    floatArrayOf(
        2f, 4f, 5f, 4f, 2f,
        4f, 9f, 12f, 9f, 4f,
        5f, 12f, 15f, 12f, 5f,
        4f, 9f, 12f, 9f, 4f,
        2f, 4f, 5f, 4f, 2f,
    )

internal fun createDocumentEdgeMap(bitmap: Bitmap): DocumentEdgeMap {
    val sampled = sampleForEdgeDetection(bitmap)
    val gray = toGrayscale(sampled.pixels)
    val blurred = gaussianBlur(gray, sampled.width, sampled.height)
    val gradients = sobelGradients(blurred, sampled.width, sampled.height)
    val thinned = suppressNonMaximum(gradients, sampled.width, sampled.height)
    return DocumentEdgeMap(sampled.width, sampled.height, connectWeakEdges(thinned, sampled.width, sampled.height))
}

private fun sampleForEdgeDetection(bitmap: Bitmap): SampledImage {
    val scale = min(1f, EDGE_PROCESSING_LIMIT.toFloat() / maxOf(bitmap.width, bitmap.height))
    val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
    val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
    val sampled = Bitmap.createScaledBitmap(bitmap, width, height, true)
    val pixels = IntArray(width * height)
    try {
        sampled.getPixels(pixels, 0, width, 0, 0, width, height)
    } finally {
        if (sampled !== bitmap) sampled.recycle()
    }
    return SampledImage(width, height, pixels)
}

private fun toGrayscale(pixels: IntArray): FloatArray =
    FloatArray(pixels.size) { index ->
        val color = pixels[index]
        0.299f * (color shr 16 and 0xFF) +
            0.587f * (color shr 8 and 0xFF) +
            0.114f * (color and 0xFF)
    }

private fun gaussianBlur(gray: FloatArray, width: Int, height: Int): FloatArray {
    val blurred = FloatArray(gray.size)
    for (y in 0 until height) {
        for (x in 0 until width) blurred[y * width + x] = blurredPixel(gray, width, height, x, y)
    }
    return blurred
}

private fun blurredPixel(gray: FloatArray, width: Int, height: Int, x: Int, y: Int): Float {
    var weightedSum = 0f
    for (kernelY in -2..2) {
        for (kernelX in -2..2) {
            val sourceX = (x + kernelX).coerceIn(0, width - 1)
            val sourceY = (y + kernelY).coerceIn(0, height - 1)
            weightedSum += gray[sourceY * width + sourceX] * gaussianKernel[(kernelY + 2) * 5 + kernelX + 2]
        }
    }
    return weightedSum / 159f
}

private fun sobelGradients(blurred: FloatArray, width: Int, height: Int): Gradients {
    val horizontal = FloatArray(blurred.size)
    val vertical = FloatArray(blurred.size)
    val magnitude = FloatArray(blurred.size)
    for (y in 1 until height - 1) {
        for (x in 1 until width - 1) {
            val index = y * width + x
            val xGradient = sobelHorizontal(blurred, width, x, y)
            val yGradient = sobelVertical(blurred, width, x, y)
            horizontal[index] = xGradient
            vertical[index] = yGradient
            magnitude[index] = sqrt(xGradient * xGradient + yGradient * yGradient)
        }
    }
    return Gradients(horizontal, vertical, magnitude)
}

private fun sobelHorizontal(values: FloatArray, width: Int, x: Int, y: Int): Float =
    -values[(y - 1) * width + x - 1] + values[(y - 1) * width + x + 1] -
        2 * values[y * width + x - 1] + 2 * values[y * width + x + 1] -
        values[(y + 1) * width + x - 1] + values[(y + 1) * width + x + 1]

private fun sobelVertical(values: FloatArray, width: Int, x: Int, y: Int): Float =
    -values[(y - 1) * width + x - 1] - 2 * values[(y - 1) * width + x] -
        values[(y - 1) * width + x + 1] + values[(y + 1) * width + x - 1] +
        2 * values[(y + 1) * width + x] + values[(y + 1) * width + x + 1]

private fun suppressNonMaximum(gradients: Gradients, width: Int, height: Int): FloatArray {
    val thinned = FloatArray(gradients.magnitude.size)
    for (y in 1 until height - 1) {
        for (x in 1 until width - 1) retainLocalMaximum(gradients, thinned, width, x, y)
    }
    return thinned
}

private fun retainLocalMaximum(
    gradients: Gradients,
    destination: FloatArray,
    width: Int,
    x: Int,
    y: Int,
) {
    val index = y * width + x
    val value = gradients.magnitude[index]
    if (value < 1f) return
    val degrees =
        (atan2(gradients.vertical[index].toDouble(), gradients.horizontal[index].toDouble()) * 180.0 / PI +
            180.0) % 180.0
    val (firstNeighbor, secondNeighbor) = gradientNeighbors(gradients.magnitude, width, x, y, degrees)
    if (value >= firstNeighbor && value >= secondNeighbor) destination[index] = value
}

private fun gradientNeighbors(
    magnitude: FloatArray,
    width: Int,
    x: Int,
    y: Int,
    degrees: Double,
): Pair<Float, Float> =
    when {
        degrees < 22.5 || degrees >= 157.5 -> magnitude[y * width + x - 1] to magnitude[y * width + x + 1]
        degrees < 67.5 -> magnitude[(y - 1) * width + x + 1] to magnitude[(y + 1) * width + x - 1]
        degrees < 112.5 -> magnitude[(y - 1) * width + x] to magnitude[(y + 1) * width + x]
        else -> magnitude[(y - 1) * width + x - 1] to magnitude[(y + 1) * width + x + 1]
    }

private fun connectWeakEdges(thinned: FloatArray, width: Int, height: Int): BooleanArray {
    val maximum = thinned.max().coerceAtLeast(1f)
    val highThreshold = maximum * 0.20f
    val lowThreshold = maximum * 0.05f
    val edges = BooleanArray(thinned.size) { index -> thinned[index] >= highThreshold }
    for (y in 1 until height - 1) {
        for (x in 1 until width - 1) {
            val index = y * width + x
            if (!edges[index] && thinned[index] >= lowThreshold && hasAdjacentEdge(edges, width, x, y)) {
                edges[index] = true
            }
        }
    }
    return edges
}

private fun hasAdjacentEdge(edges: BooleanArray, width: Int, x: Int, y: Int): Boolean =
    (-1..1).any { deltaY ->
        (-1..1).any { deltaX -> edges[(y + deltaY) * width + x + deltaX] }
    }
