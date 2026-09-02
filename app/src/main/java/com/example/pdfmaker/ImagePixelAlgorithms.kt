package com.example.pdfmaker

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt

/** Pure, bounded pixel algorithms used by [ImageProcessing] and covered by JVM tests. */
internal object ImagePixelAlgorithms {
    private const val CHANNEL_MAX = 255
    private const val ALPHA_SHIFT = 24
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val MAX_BLUR_RADIUS = 16

    fun enhanceArgb(
        pixels: IntArray,
        width: Int,
        height: Int,
        checkpoint: () -> Unit = {},
    ): IntArray {
        validatePixels(pixels, width, height)
        val luma = FloatArray(pixels.size)
        val blueDifference = FloatArray(pixels.size)
        val redDifference = FloatArray(pixels.size)

        pixels.indices.forEach { index ->
            if (index % width == 0) checkpoint()
            val red = channel(pixels[index], RED_SHIFT).toFloat()
            val green = channel(pixels[index], GREEN_SHIFT).toFloat()
            val blue = channel(pixels[index], 0).toFloat()
            luma[index] = 0.299f * red + 0.587f * green + 0.114f * blue
            blueDifference[index] = -0.168736f * red - 0.331264f * green + 0.5f * blue
            redDifference[index] = 0.5f * red - 0.418688f * green - 0.081312f * blue
        }
        checkpoint()

        val denoised = bilateralFilterLuma(luma, width, height, 2.5f, 18f, checkpoint)
        val contrasted = claheLuma(denoised, width, height, tileSize = 48, clipLimit = 2.8f, checkpoint)
        val sharpened = multiScaleUnsharp(contrasted, width, height, checkpoint)

        return IntArray(pixels.size) { index ->
            if (index % width == 0) checkpoint()
            val y = sharpened[index]
            val cb = blueDifference[index]
            val cr = redDifference[index]
            val red = (y + 1.402f * cr).roundToChannel()
            val green = (y - 0.344136f * cb - 0.714136f * cr).roundToChannel()
            val blue = (y + 1.772f * cb).roundToChannel()
            pack(channel(pixels[index], ALPHA_SHIFT), red, green, blue)
        }
    }

    fun bilateralFilterLuma(
        luma: FloatArray,
        width: Int,
        height: Int,
        spatialSigma: Float,
        rangeSigma: Float,
        checkpoint: () -> Unit = {},
    ): FloatArray {
        validateLuma(luma, width, height)
        require(spatialSigma.isFinite() && spatialSigma > 0f) { "Spatial sigma must be positive" }
        require(rangeSigma.isFinite() && rangeSigma > 0f) { "Range sigma must be positive" }

        val output = FloatArray(luma.size)
        val radius = (2.5f * spatialSigma).roundToInt().coerceIn(1, MAX_BLUR_RADIUS)
        val spatialDenominator = 2f * spatialSigma * spatialSigma
        val rangeDenominator = 2f * rangeSigma * rangeSigma
        val kernelSize = 2 * radius + 1
        val spatialWeights = FloatArray(kernelSize * kernelSize)
        for (deltaY in -radius..radius) {
            for (deltaX in -radius..radius) {
                val distance = (deltaX * deltaX + deltaY * deltaY).toFloat()
                spatialWeights[(deltaY + radius) * kernelSize + deltaX + radius] =
                    exp(-distance / spatialDenominator)
            }
        }

        for (targetY in 0 until height) {
            checkpoint()
            for (targetX in 0 until width) {
                val center = luma[targetY * width + targetX]
                var weightSum = 0f
                var weightedLuma = 0f
                var kernelIndex = 0
                for (deltaY in -radius..radius) {
                    val sourceY = (targetY + deltaY).coerceIn(0, height - 1)
                    for (deltaX in -radius..radius) {
                        val sourceX = (targetX + deltaX).coerceIn(0, width - 1)
                        val sourceLuma = luma[sourceY * width + sourceX]
                        val difference = sourceLuma - center
                        val weight =
                            spatialWeights[kernelIndex++] * exp(-(difference * difference) / rangeDenominator)
                        weightedLuma += sourceLuma * weight
                        weightSum += weight
                    }
                }
                output[targetY * width + targetX] =
                    if (weightSum > 0f) weightedLuma / weightSum else center
            }
        }
        return output
    }

    fun claheLuma(
        luma: FloatArray,
        width: Int,
        height: Int,
        tileSize: Int,
        clipLimit: Float,
        checkpoint: () -> Unit = {},
    ): FloatArray {
        validateLuma(luma, width, height)
        require(tileSize > 0) { "CLAHE tile size must be positive" }
        require(clipLimit.isFinite() && clipLimit > 0f) { "CLAHE clip limit must be positive" }

        val columns = ceil(width.toDouble() / tileSize).toInt()
        val rows = ceil(height.toDouble() / tileSize).toInt()
        val lookupTables = Array(rows) { Array(columns) { FloatArray(CHANNEL_MAX + 1) } }

        for (tileRow in 0 until rows) {
            checkpoint()
            for (tileColumn in 0 until columns) {
                val startX = tileColumn * tileSize
                val endX = minOf(startX + tileSize, width)
                val startY = tileRow * tileSize
                val endY = minOf(startY + tileSize, height)
                val sampleCount = (endX - startX) * (endY - startY)
                val histogram = IntArray(CHANNEL_MAX + 1)
                for (pixelY in startY until endY) {
                    for (pixelX in startX until endX) {
                        histogram[luma[pixelY * width + pixelX].roundToChannel()] += 1
                    }
                }
                clipAndRedistribute(histogram, sampleCount, clipLimit)
                populateLookupTable(histogram, sampleCount, lookupTables[tileRow][tileColumn])
            }
        }

        val output = FloatArray(luma.size)
        for (pixelY in 0 until height) {
            checkpoint()
            for (pixelX in 0 until width) {
                val bin = luma[pixelY * width + pixelX].roundToChannel()
                output[pixelY * width + pixelX] =
                    interpolateLookup(
                        lookupTables = lookupTables,
                        tileSize = tileSize,
                        pixelX = pixelX,
                        pixelY = pixelY,
                        bin = bin,
                    )
            }
        }
        return output
    }

    fun unsharpArgb(
        pixels: IntArray,
        width: Int,
        height: Int,
        strength: Float,
        radius: Int,
        threshold: Int,
        checkpoint: () -> Unit = {},
    ): IntArray {
        validatePixels(pixels, width, height)
        require(strength.isFinite() && strength in 0f..1f) { "Sharpening strength is invalid" }
        require(threshold in 0..CHANNEL_MAX) { "Sharpening threshold is invalid" }
        val blurred = boxBlurArgb(pixels, width, height, radius, checkpoint)
        return IntArray(pixels.size) { index ->
            if (index % width == 0) checkpoint()
            val original = pixels[index]
            val blur = blurred[index]
            pack(
                alpha = channel(original, ALPHA_SHIFT),
                red = sharpenChannel(original, blur, RED_SHIFT, strength, threshold),
                green = sharpenChannel(original, blur, GREEN_SHIFT, strength, threshold),
                blue = sharpenChannel(original, blur, 0, strength, threshold),
            )
        }
    }

    fun boxBlurArgb(
        pixels: IntArray,
        width: Int,
        height: Int,
        radius: Int,
        checkpoint: () -> Unit = {},
    ): IntArray {
        validatePixels(pixels, width, height)
        require(radius in 1..MAX_BLUR_RADIUS) { "Blur radius is outside the supported range" }
        val temporary = IntArray(pixels.size)
        val output = IntArray(pixels.size)
        val sampleCount = 2 * radius + 1

        for (y in 0 until height) {
            checkpoint()
            var redSum = 0
            var greenSum = 0
            var blueSum = 0
            for (deltaX in -radius..radius) {
                val pixel = pixels[y * width + deltaX.coerceIn(0, width - 1)]
                redSum += channel(pixel, RED_SHIFT)
                greenSum += channel(pixel, GREEN_SHIFT)
                blueSum += channel(pixel, 0)
            }
            for (x in 0 until width) {
                temporary[y * width + x] =
                    pack(
                        alpha = channel(pixels[y * width + x], ALPHA_SHIFT),
                        red = redSum / sampleCount,
                        green = greenSum / sampleCount,
                        blue = blueSum / sampleCount,
                    )
                val removed = pixels[y * width + (x - radius).coerceIn(0, width - 1)]
                val added = pixels[y * width + (x + radius + 1).coerceIn(0, width - 1)]
                redSum += channel(added, RED_SHIFT) - channel(removed, RED_SHIFT)
                greenSum += channel(added, GREEN_SHIFT) - channel(removed, GREEN_SHIFT)
                blueSum += channel(added, 0) - channel(removed, 0)
            }
        }

        for (x in 0 until width) {
            checkpoint()
            var redSum = 0
            var greenSum = 0
            var blueSum = 0
            for (deltaY in -radius..radius) {
                val pixel = temporary[deltaY.coerceIn(0, height - 1) * width + x]
                redSum += channel(pixel, RED_SHIFT)
                greenSum += channel(pixel, GREEN_SHIFT)
                blueSum += channel(pixel, 0)
            }
            for (y in 0 until height) {
                output[y * width + x] =
                    pack(
                        alpha = channel(pixels[y * width + x], ALPHA_SHIFT),
                        red = redSum / sampleCount,
                        green = greenSum / sampleCount,
                        blue = blueSum / sampleCount,
                    )
                val removed = temporary[(y - radius).coerceIn(0, height - 1) * width + x]
                val added = temporary[(y + radius + 1).coerceIn(0, height - 1) * width + x]
                redSum += channel(added, RED_SHIFT) - channel(removed, RED_SHIFT)
                greenSum += channel(added, GREEN_SHIFT) - channel(removed, GREEN_SHIFT)
                blueSum += channel(added, 0) - channel(removed, 0)
            }
        }
        return output
    }

    private fun multiScaleUnsharp(
        luma: FloatArray,
        width: Int,
        height: Int,
        checkpoint: () -> Unit,
    ): FloatArray {
        val grayscale =
            IntArray(luma.size) { index ->
                val value = luma[index].roundToChannel()
                pack(CHANNEL_MAX, value, value, value)
            }
        val fine = boxBlurArgb(grayscale, width, height, radius = 1, checkpoint)
        val coarse = boxBlurArgb(grayscale, width, height, radius = 3, checkpoint)
        return FloatArray(luma.size) { index ->
            if (index % width == 0) checkpoint()
            val original = channel(grayscale[index], 0)
            val fineDifference = original - channel(fine[index], 0)
            val coarseDifference = original - channel(coarse[index], 0)
            var value = original.toFloat()
            if (abs(fineDifference) > 3) value += fineDifference * 0.55f
            if (abs(coarseDifference) > 3) value += coarseDifference * 0.30f
            value.coerceIn(0f, CHANNEL_MAX.toFloat())
        }
    }

    private fun clipAndRedistribute(histogram: IntArray, sampleCount: Int, clipLimit: Float) {
        val maximumBin = (clipLimit * sampleCount / histogram.size).roundToInt().coerceAtLeast(1)
        var excess = 0
        histogram.indices.forEach { bin ->
            if (histogram[bin] > maximumBin) {
                excess += histogram[bin] - maximumBin
                histogram[bin] = maximumBin
            }
        }
        val perBin = excess / histogram.size
        val remainder = excess % histogram.size
        histogram.indices.forEach { bin ->
            histogram[bin] += perBin + if (bin < remainder) 1 else 0
        }
        check(histogram.sum() == sampleCount) { "CLAHE histogram redistribution lost samples" }
    }

    private fun populateLookupTable(histogram: IntArray, sampleCount: Int, output: FloatArray) {
        var cumulative = 0
        histogram.indices.forEach { bin ->
            cumulative += histogram[bin]
            output[bin] = (cumulative * CHANNEL_MAX.toFloat() / sampleCount).coerceIn(0f, CHANNEL_MAX.toFloat())
        }
    }

    private fun interpolateLookup(
        lookupTables: Array<Array<FloatArray>>,
        tileSize: Int,
        pixelX: Int,
        pixelY: Int,
        bin: Int,
    ): Float {
        val columns = lookupTables.first().size
        val rows = lookupTables.size
        val tileColumn = pixelX.toFloat() / tileSize - 0.5f
        val tileRow = pixelY.toFloat() / tileSize - 0.5f
        val left = floor(tileColumn).toInt().coerceIn(0, columns - 1)
        val top = floor(tileRow).toInt().coerceIn(0, rows - 1)
        val right = (left + 1).coerceIn(0, columns - 1)
        val bottom = (top + 1).coerceIn(0, rows - 1)
        val horizontalWeight = (tileColumn - left).coerceIn(0f, 1f)
        val verticalWeight = (tileRow - top).coerceIn(0f, 1f)
        val topValue =
            (1f - horizontalWeight) * lookupTables[top][left][bin] +
                horizontalWeight * lookupTables[top][right][bin]
        val bottomValue =
            (1f - horizontalWeight) * lookupTables[bottom][left][bin] +
                horizontalWeight * lookupTables[bottom][right][bin]
        return (1f - verticalWeight) * topValue + verticalWeight * bottomValue
    }

    private fun sharpenChannel(
        original: Int,
        blurred: Int,
        shift: Int,
        strength: Float,
        threshold: Int,
    ): Int {
        val originalChannel = channel(original, shift)
        val difference = originalChannel - channel(blurred, shift)
        return if (abs(difference) > threshold) {
            (originalChannel + difference * strength).roundToInt().coerceIn(0, CHANNEL_MAX)
        } else {
            originalChannel
        }
    }

    private fun validatePixels(pixels: IntArray, width: Int, height: Int) {
        validateSamples(pixels.size, width, height)
        require(width.toLong() * height <= ImageInputPolicy.MAX_DECODE_PIXELS) {
            "Pixel buffer exceeds the processing limit"
        }
    }

    private fun validateLuma(luma: FloatArray, width: Int, height: Int) {
        validateSamples(luma.size, width, height)
        require(luma.all(Float::isFinite)) { "Luma samples must be finite" }
    }

    private fun validateSamples(sampleCount: Int, width: Int, height: Int) {
        require(width > 0 && height > 0) { "Image dimensions must be positive" }
        require(width.toLong() * height == sampleCount.toLong()) { "Pixel buffer dimensions do not match" }
    }

    private fun Float.roundToChannel(): Int = roundToInt().coerceIn(0, CHANNEL_MAX)

    private fun channel(color: Int, shift: Int): Int = color ushr shift and CHANNEL_MAX

    private fun pack(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha shl ALPHA_SHIFT) or (red shl RED_SHIFT) or (green shl GREEN_SHIFT) or blue
}
