package com.example.pdfmaker

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagePixelAlgorithmsTest {
    @Test
    fun bilateralFilterKeepsConstantLumaStable() {
        val source = FloatArray(9) { 42f }

        val output = ImagePixelAlgorithms.bilateralFilterLuma(source, 3, 3, 2.5f, 18f)

        output.forEach { assertEquals(42f, it, 0.0001f) }
    }

    @Test
    fun bilateralFilterPreservesAHighContrastCenter() {
        val source =
            floatArrayOf(
                0f,
                0f,
                0f,
                0f,
                255f,
                0f,
                0f,
                0f,
                0f,
            )

        val output = ImagePixelAlgorithms.bilateralFilterLuma(source, 3, 3, 1f, 10f)

        assertTrue(output[4] > 250f)
        assertTrue(output[0] < 1f)
    }

    @Test
    fun bilateralFilterChecksCancellationBetweenRows() {
        var checks = 0

        val error =
            runCatching {
                ImagePixelAlgorithms.bilateralFilterLuma(FloatArray(16), 4, 4, 1f, 10f) {
                    checks += 1
                    if (checks == 2) throw ProcessingCancelled()
                }
            }.exceptionOrNull()

        assertTrue(error is ProcessingCancelled)
        assertEquals(2, checks)
    }

    @Test
    fun claheReturnsBoundedValuesForMultipleTiles() {
        val source = FloatArray(16) { index -> (index * 17).toFloat() }

        val output = ImagePixelAlgorithms.claheLuma(source, 4, 4, tileSize = 2, clipLimit = 2f)

        assertEquals(source.size, output.size)
        assertTrue(output.all { it in 0f..255f })
        assertTrue(output.asList().zipWithNext().any { (first, second) -> first != second })
    }

    @Test
    fun claheRedistributesEveryClippedSample() {
        val source = FloatArray(257) { 100f }

        val output = ImagePixelAlgorithms.claheLuma(source, 257, 1, tileSize = 257, clipLimit = 1f)

        assertEquals(257, output.size)
        assertTrue(output.all { it.isFinite() && it in 0f..255f })
    }

    @Test
    fun boxBlurPreservesPerPixelAlpha() {
        val source =
            intArrayOf(
                argb(10, 0, 0, 0),
                argb(20, 255, 0, 0),
                argb(30, 0, 0, 0),
            )

        val output = ImagePixelAlgorithms.boxBlurArgb(source, width = 3, height = 1, radius = 1)

        assertEquals(10, alpha(output[0]))
        assertEquals(20, alpha(output[1]))
        assertEquals(30, alpha(output[2]))
        assertTrue(red(output[0]) > 0)
        assertTrue(red(output[1]) > 0)
    }

    @Test
    fun constantImageIsUnchangedBySharpening() {
        val color = argb(127, 80, 90, 100)
        val source = IntArray(9) { color }

        val output = ImagePixelAlgorithms.unsharpArgb(source, 3, 3, 0.75f, 1, 3)

        assertArrayEquals(source, output)
    }

    @Test
    fun sharpeningChangesEdgesButKeepsAlpha() {
        val source = IntArray(9) { argb(80 + it, 20, 20, 20) }
        source[4] = argb(84, 180, 160, 140)

        val output = ImagePixelAlgorithms.unsharpArgb(source, 3, 3, 1f, 1, 0)

        assertNotEquals(source.toList(), output.toList())
        output.indices.forEach { index -> assertEquals(alpha(source[index]), alpha(output[index])) }
    }

    @Test
    fun enhancementPreservesDimensionsAndAlpha() {
        val source =
            intArrayOf(
                argb(20, 10, 20, 30),
                argb(40, 40, 50, 60),
                argb(60, 70, 80, 90),
                argb(80, 100, 110, 120),
            )

        val output = ImagePixelAlgorithms.enhanceArgb(source, 2, 2)

        assertEquals(source.size, output.size)
        output.indices.forEach { index -> assertEquals(alpha(source[index]), alpha(output[index])) }
    }

    @Test
    fun enhancementCanBeCancelledBeforeExpensiveFiltering() {
        val error =
            runCatching {
                ImagePixelAlgorithms.enhanceArgb(IntArray(4), 2, 2) {
                    throw ProcessingCancelled()
                }
            }.exceptionOrNull()

        assertTrue(error is ProcessingCancelled)
    }

    @Test
    fun invalidPixelBuffersAndParametersFailClosed() {
        val cases =
            listOf<() -> Unit>(
                { ImagePixelAlgorithms.boxBlurArgb(IntArray(3), 2, 2, 1) },
                { ImagePixelAlgorithms.boxBlurArgb(IntArray(1), 1, 1, 0) },
                { ImagePixelAlgorithms.boxBlurArgb(IntArray(1), 1, 1, 17) },
                { ImagePixelAlgorithms.unsharpArgb(IntArray(1), 1, 1, Float.NaN, 1, 1) },
                { ImagePixelAlgorithms.unsharpArgb(IntArray(1), 1, 1, 1f, 1, -1) },
                { ImagePixelAlgorithms.bilateralFilterLuma(FloatArray(1), 1, 1, 0f, 1f) },
                { ImagePixelAlgorithms.bilateralFilterLuma(FloatArray(1), 1, 1, 1f, Float.NaN) },
                { ImagePixelAlgorithms.bilateralFilterLuma(floatArrayOf(Float.NaN), 1, 1, 1f, 1f) },
                { ImagePixelAlgorithms.claheLuma(FloatArray(1), 1, 1, 0, 1f) },
                { ImagePixelAlgorithms.claheLuma(FloatArray(1), 1, 1, 1, -1f) },
            )

        cases.forEach { action -> assertTrue(runCatching(action).isFailure) }
    }

    private class ProcessingCancelled : RuntimeException()

    private companion object {
        fun argb(
            alpha: Int,
            red: Int,
            green: Int,
            blue: Int,
        ): Int = (alpha shl 24) or (red shl 16) or (green shl 8) or blue

        fun alpha(color: Int): Int = color ushr 24 and 0xff

        fun red(color: Int): Int = color ushr 16 and 0xff
    }
}
