package com.example.pdfmaker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.abs

/** Owns Android bitmap allocation while delegating bounded pixel math to pure algorithms. */
object ImageProcessing {
    suspend fun render(request: ImageRenderRequest): ImageRenderResult {
        val context = currentCoroutineContext()
        val checkpoint = { context.ensureActive() }
        requireUsable(request.source)

        var current = request.source

        fun replaceIntermediate(next: Bitmap) {
            val previous = current
            current = next
            if (previous !== request.source && previous !== next && !previous.isRecycled) {
                previous.recycle()
            }
        }

        var completed = false
        try {
            replaceIntermediate(applyFilter(request.source, request.filter, checkpoint))
            replaceIntermediate(
                applyAdjustments(
                    source = current,
                    brightness = request.brightness,
                    contrast = request.contrast,
                    details = request.details,
                    checkpoint = checkpoint,
                ),
            )
            replaceIntermediate(rotateBitmap(current, request.rotationDegrees))
            checkpoint()

            val display = current
            val final =
                if (request.cropApplied) {
                    cropBitmap(display, request.cropRect)
                } else {
                    display
                }
            completed = true
            return ImageRenderResult(display, final)
        } finally {
            if (!completed && current !== request.source && !current.isRecycled) {
                current.recycle()
            }
        }
    }

    fun rotateBitmap(
        source: Bitmap,
        degrees: Float,
    ): Bitmap {
        requireUsable(source)
        val rotation = ImageTransformPolicy.normalizedRotation(degrees)
        if (rotation == 0f) return source
        val matrix = Matrix().also { it.postRotate(rotation) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    suspend fun filterThumbnail(
        source: Bitmap,
        filter: ImageFilter,
        size: Int,
    ): Bitmap {
        val context = currentCoroutineContext()
        val checkpoint = { context.ensureActive() }
        requireUsable(source)
        val edge = ImageTransformPolicy.requireThumbnailEdge(size)
        val scaled = scaledCopy(source, edge)
        var filtered: Bitmap? = null
        var completed = false
        try {
            checkpoint()
            filtered = applyFilter(scaled, filter, checkpoint)
            checkpoint()
            if (filtered !== scaled && !scaled.isRecycled) scaled.recycle()
            completed = true
            return requireNotNull(filtered)
        } finally {
            if (!completed) {
                filtered?.takeIf { it !== scaled }?.takeUnless(Bitmap::isRecycled)?.recycle()
                scaled.takeUnless(Bitmap::isRecycled)?.recycle()
            }
        }
    }

    private fun applyFilter(
        source: Bitmap,
        filter: ImageFilter,
        checkpoint: () -> Unit,
    ): Bitmap =
        when (filter) {
            ImageFilter.ORIGINAL -> {
                source
            }

            ImageFilter.AI_ENHANCE -> {
                applyAiEnhance(source, checkpoint)
            }

            ImageFilter.DOCS -> {
                applySharpenedMatrix(source, ImageColorMatrices.docs(), 0.75f, 2, 4, checkpoint)
            }

            ImageFilter.BW2 -> {
                applySharpenedMatrix(
                    source,
                    ImageColorMatrices.highContrastBlackAndWhite(),
                    0.6f,
                    1,
                    3,
                    checkpoint,
                )
            }

            ImageFilter.SUPER -> {
                applySharpenedMatrix(source, ImageColorMatrices.vividDocument(), 0.55f, 1, 5, checkpoint)
            }

            else -> {
                applyColorMatrix(source, ImageColorMatrices.forFilter(filter), checkpoint)
            }
        }

    private fun applyAiEnhance(
        source: Bitmap,
        checkpoint: () -> Unit,
    ): Bitmap {
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        val enhanced =
            ImagePixelAlgorithms.enhanceArgb(
                pixels = pixels,
                width = source.width,
                height = source.height,
                checkpoint = checkpoint,
            )
        checkpoint()
        return bitmapFromPixels(enhanced, source.width, source.height)
    }

    private fun applyAdjustments(
        source: Bitmap,
        brightness: Float,
        contrast: Float,
        details: Float,
        checkpoint: () -> Unit,
    ): Bitmap {
        val safeBrightness =
            ImageTransformPolicy.requireAdjustment(brightness, -100f..100f, "Brightness")
        val safeContrast =
            ImageTransformPolicy.requireAdjustment(contrast, -100f..100f, "Contrast")
        val safeDetails = ImageTransformPolicy.requireAdjustment(details, 0f..100f, "Details")
        if (abs(safeBrightness) < 0.001f && abs(safeContrast) < 0.001f && safeDetails <= 2f) {
            return source
        }

        val contrastFactor = 1f + safeContrast / 100f
        val brightnessOffset = safeBrightness * 2.55f
        val translation = brightnessOffset + 128f * (1f - contrastFactor)
        val matrix = ImageColorMatrices.adjustment(contrastFactor, translation)
        val adjusted = applyColorMatrix(source, matrix, checkpoint)
        if (safeDetails <= 2f) return adjusted

        return replaceWithSharpened(
            source = adjusted,
            strength = safeDetails / 120f,
            radius = 1,
            threshold = 3,
            checkpoint = checkpoint,
        )
    }

    private fun applySharpenedMatrix(
        source: Bitmap,
        matrix: android.graphics.ColorMatrix,
        strength: Float,
        radius: Int,
        threshold: Int,
        checkpoint: () -> Unit,
    ): Bitmap {
        val adjusted = applyColorMatrix(source, matrix, checkpoint)
        return replaceWithSharpened(adjusted, strength, radius, threshold, checkpoint)
    }

    private fun replaceWithSharpened(
        source: Bitmap,
        strength: Float,
        radius: Int,
        threshold: Int,
        checkpoint: () -> Unit,
    ): Bitmap {
        var result: Bitmap? = null
        var completed = false
        try {
            result = unsharpMask(source, strength, radius, threshold, checkpoint)
            if (result !== source && !source.isRecycled) source.recycle()
            completed = true
            return requireNotNull(result)
        } finally {
            if (!completed) {
                result?.takeIf { it !== source }?.takeUnless(Bitmap::isRecycled)?.recycle()
                source.takeUnless(Bitmap::isRecycled)?.recycle()
            }
        }
    }

    private fun unsharpMask(
        source: Bitmap,
        strength: Float,
        radius: Int,
        threshold: Int,
        checkpoint: () -> Unit,
    ): Bitmap {
        if (source.width < 3 || source.height < 3) return source
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        val sharpened =
            ImagePixelAlgorithms.unsharpArgb(
                pixels = pixels,
                width = source.width,
                height = source.height,
                strength = strength,
                radius = radius,
                threshold = threshold,
                checkpoint = checkpoint,
            )
        checkpoint()
        return bitmapFromPixels(sharpened, source.width, source.height)
    }

    private fun applyColorMatrix(
        source: Bitmap,
        matrix: android.graphics.ColorMatrix,
        checkpoint: () -> Unit,
    ): Bitmap {
        checkpoint()
        val result = createBitmap(source.width, source.height)
        var completed = false
        try {
            Canvas(result).drawBitmap(
                source,
                0f,
                0f,
                Paint().also { paint -> paint.colorFilter = ColorMatrixColorFilter(matrix) },
            )
            checkpoint()
            completed = true
            return result
        } finally {
            if (!completed) result.recycle()
        }
    }

    private fun cropBitmap(
        source: Bitmap,
        rect: RectF,
    ): Bitmap {
        val bounds =
            ImageTransformPolicy.cropBoundsOrNull(
                imageWidth = source.width,
                imageHeight = source.height,
                left = rect.left,
                top = rect.top,
                right = rect.right,
                bottom = rect.bottom,
            ) ?: return source
        return Bitmap.createBitmap(source, bounds.x, bounds.y, bounds.width, bounds.height)
    }

    private fun bitmapFromPixels(
        pixels: IntArray,
        width: Int,
        height: Int,
    ): Bitmap =
        createBitmap(width, height).also { bitmap ->
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        }

    private fun scaledCopy(
        source: Bitmap,
        edge: Int,
    ): Bitmap =
        createBitmap(edge, edge).also { bitmap ->
            Canvas(bitmap).drawBitmap(
                source,
                null,
                Rect(0, 0, edge, edge),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
            )
        }

    private fun requireUsable(source: Bitmap) {
        require(!source.isRecycled) { "The image is no longer available" }
        ImageTransformPolicy.requireProcessableDimensions(source.width, source.height)
    }
}
