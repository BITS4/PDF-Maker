package com.example.pdfmaker

import kotlin.math.roundToInt

internal data class PixelSize(val width: Int, val height: Int) {
    val pixelCount: Long = width.toLong() * height.toLong()
}

internal data class RenderScale(val scaleX: Float, val scaleY: Float)

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

    fun scaleTo(sourceWidth: Int, sourceHeight: Int, target: PixelSize): RenderScale? {
        if (sourceWidth <= 0 || sourceHeight <= 0) return null
        if (target.width <= 0 || target.height <= 0) return null
        return RenderScale(
            scaleX = target.width.toFloat() / sourceWidth.toFloat(),
            scaleY = target.height.toFloat() / sourceHeight.toFloat(),
        )
    }
}
