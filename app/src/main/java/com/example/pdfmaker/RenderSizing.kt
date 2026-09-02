package com.example.pdfmaker

import kotlin.math.roundToInt

internal data class PixelSize(val width: Int, val height: Int) {
    val pixelCount: Long = width.toLong() * height.toLong()
}

internal object RenderSizing {
    fun fitWithin(
        sourceWidth: Int,
        sourceHeight: Int,
        maxDimension: Int,
        allowUpscale: Boolean = false,
    ): PixelSize? {
        if (sourceWidth <= 0 || sourceHeight <= 0 || maxDimension <= 0) return null
        val longestSide = maxOf(sourceWidth, sourceHeight).toDouble()
        val requestedScale = maxDimension.toDouble() / longestSide
        val scale = if (allowUpscale) requestedScale else minOf(1.0, requestedScale)
        return PixelSize(
            width = (sourceWidth.toDouble() * scale).roundToInt().coerceIn(1, maxDimension),
            height = (sourceHeight.toDouble() * scale).roundToInt().coerceIn(1, maxDimension),
        )
    }
}
