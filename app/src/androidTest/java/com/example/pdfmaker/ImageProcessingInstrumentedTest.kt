package com.example.pdfmaker

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageProcessingInstrumentedTest {
    @Test
    fun noOpRenderRetainsCallerOwnedSource() {
        val source = bitmap(width = 4, height = 3)
        try {
            val result = runBlocking { ImageProcessing.render(request(source)) }

            assertSame(source, result.display)
            assertSame(source, result.final)
            assertTrue(result.generatedBitmaps(source).isEmpty())
            assertFalse(source.isRecycled)
        } finally {
            source.recycle()
        }
    }

    @Test
    fun cropCreatesASeparatelyOwnedFinalBitmap() {
        val source = bitmap(width = 10, height = 10)
        var final: Bitmap? = null
        try {
            val result =
                runBlocking {
                    ImageProcessing.render(
                        request(
                            source = source,
                            cropRect = RectF(0.1f, 0.2f, 0.9f, 0.8f),
                            cropApplied = true,
                        ),
                    )
                }
            final = result.final

            assertSame(source, result.display)
            assertNotSame(source, result.final)
            assertEquals(8, result.final.width)
            assertEquals(6, result.final.height)
            assertEquals(listOf(result.final), result.generatedBitmaps(source))
        } finally {
            final?.takeUnless(Bitmap::isRecycled)?.recycle()
            source.takeUnless(Bitmap::isRecycled)?.recycle()
        }
    }

    @Test
    fun invalidAdjustmentFailsWithoutRecyclingTheSource() {
        val source = bitmap(width = 3, height = 3)
        try {
            val failed =
                runCatching {
                    runBlocking {
                        ImageProcessing.render(request(source = source, brightness = Float.NaN))
                    }
                }.isFailure

            assertTrue(failed)
            assertFalse(source.isRecycled)
        } finally {
            source.recycle()
        }
    }

    @Test
    fun thumbnailOwnsItsBitmapEvenWhenTheRequestedSizeMatches() {
        val source = bitmap(width = 80, height = 80)
        var thumbnail: Bitmap? = null
        try {
            thumbnail =
                runBlocking {
                    ImageProcessing.filterThumbnail(source, ImageFilter.ORIGINAL, size = 80)
                }

            assertNotSame(source, thumbnail)
            assertEquals(80, thumbnail.width)
            assertEquals(80, thumbnail.height)
            assertFalse(source.isRecycled)
        } finally {
            thumbnail?.takeUnless(Bitmap::isRecycled)?.recycle()
            source.takeUnless(Bitmap::isRecycled)?.recycle()
        }
    }

    @Test
    fun quarterTurnSwapsBitmapDimensions() {
        val source = bitmap(width = 5, height = 3)
        var rotated: Bitmap? = null
        try {
            rotated = ImageProcessing.rotateBitmap(source, 90f)

            assertNotSame(source, rotated)
            assertEquals(3, rotated.width)
            assertEquals(5, rotated.height)
            assertFalse(source.isRecycled)
        } finally {
            rotated?.takeUnless(Bitmap::isRecycled)?.recycle()
            source.takeUnless(Bitmap::isRecycled)?.recycle()
        }
    }

    @Test
    fun blankImageFallsBackToConservativeDocumentBounds() {
        val source = bitmap(width = 100, height = 120)
        try {
            val detected = autoDetectQuad(source)

            assertPoint(detected.tl.x, detected.tl.y, expectedX = 0.05f, expectedY = 0.05f)
            assertPoint(detected.tr.x, detected.tr.y, expectedX = 0.95f, expectedY = 0.05f)
            assertPoint(detected.br.x, detected.br.y, expectedX = 0.95f, expectedY = 0.95f)
            assertPoint(detected.bl.x, detected.bl.y, expectedX = 0.05f, expectedY = 0.95f)
            assertTrue(detected.points().all { point -> point.x in 0f..1f && point.y in 0f..1f })
        } finally {
            source.recycle()
        }
    }

    @Test
    fun perspectiveWarpCreatesABoundedCallerOwnedBitmap() {
        val source = bitmap(width = 100, height = 120)
        var warped: Bitmap? = null
        try {
            warped = perspectiveWarp(source, Quad())

            assertNotSame(source, warped)
            assertTrue(warped.width in 79..80)
            assertTrue(warped.height in 95..96)
            assertTrue(warped.width <= ImageInputPolicy.MAX_DECODE_EDGE_PX)
            assertTrue(warped.height <= ImageInputPolicy.MAX_DECODE_EDGE_PX)
            assertTrue(warped.width.toLong() * warped.height <= ImageInputPolicy.MAX_DECODE_PIXELS)
            assertFalse(source.isRecycled)
        } finally {
            warped?.takeUnless(Bitmap::isRecycled)?.recycle()
            source.takeUnless(Bitmap::isRecycled)?.recycle()
        }
    }

    private fun bitmap(
        width: Int,
        height: Int,
    ): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(Color.rgb(64, 96, 128))
        }

    private fun assertPoint(
        actualX: Float,
        actualY: Float,
        expectedX: Float,
        expectedY: Float,
    ) {
        assertEquals(expectedX, actualX, 0.001f)
        assertEquals(expectedY, actualY, 0.001f)
    }

    private fun request(
        source: Bitmap,
        brightness: Float = 0f,
        cropRect: RectF = RectF(0f, 0f, 1f, 1f),
        cropApplied: Boolean = false,
    ): ImageRenderRequest =
        ImageRenderRequest(
            source = source,
            filter = ImageFilter.ORIGINAL,
            brightness = brightness,
            contrast = 0f,
            details = 0f,
            rotationDegrees = 0f,
            cropRect = cropRect,
            cropApplied = cropApplied,
        )
}
