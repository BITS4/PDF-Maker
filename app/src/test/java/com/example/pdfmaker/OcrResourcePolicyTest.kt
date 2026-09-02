package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrResourcePolicyTest {
    @Test
    fun `accepts first and maximum safe PDF page counts`() {
        assertEquals(1, OcrResourcePolicy.requirePdfPageCount(1))
        assertEquals(
            OcrResourcePolicy.MAX_PDF_PAGES,
            OcrResourcePolicy.requirePdfPageCount(OcrResourcePolicy.MAX_PDF_PAGES),
        )
    }

    @Test
    fun `rejects empty negative and oversized PDF page counts`() {
        listOf(-1, 0, OcrResourcePolicy.MAX_PDF_PAGES + 1, Int.MAX_VALUE).forEach { pageCount ->
            assertThrows(IllegalArgumentException::class.java) {
                OcrResourcePolicy.requirePdfPageCount(pageCount)
            }
        }
    }

    @Test
    fun `bounds PDF renders while preserving aspect ratio`() {
        assertEquals(PixelSize(1_600, 800), OcrResourcePolicy.pdfRenderSize(4_000, 2_000))
        assertEquals(PixelSize(800, 1_600), OcrResourcePolicy.pdfRenderSize(2_000, 4_000))
        assertEquals(PixelSize(1_600, 800), OcrResourcePolicy.pdfRenderSize(400, 200))
    }

    @Test
    fun `rejects invalid PDF and image dimensions`() {
        listOf(0 to 100, 100 to 0, -1 to 100).forEach { (width, height) ->
            assertThrows(IllegalArgumentException::class.java) {
                OcrResourcePolicy.pdfRenderSize(width, height)
            }
            assertThrows(IllegalArgumentException::class.java) {
                OcrResourcePolicy.imageDecodeSize(width, height)
            }
        }
    }

    @Test
    fun `chooses a power of two sample that fits the image target`() {
        val smallTarget = OcrResourcePolicy.imageDecodeSize(1_600, 900)
        val largeTarget = OcrResourcePolicy.imageDecodeSize(4_000, 2_000)

        assertEquals(PixelSize(1_600, 900), smallTarget)
        assertEquals(PixelSize(2_000, 1_000), largeTarget)
        assertEquals(1, OcrResourcePolicy.imageSampleSize(1_600, 900, smallTarget))
        assertEquals(2, OcrResourcePolicy.imageSampleSize(4_000, 2_000, largeTarget))
        assertEquals(8, OcrResourcePolicy.imageSampleSize(16_000, 1, PixelSize(2_000, 1)))
        assertEquals(8, OcrResourcePolicy.imageSampleSize(1, 16_000, PixelSize(1, 2_000)))
    }

    @Test
    fun `rejects malformed sample targets and oversized decoded images`() {
        assertThrows(IllegalArgumentException::class.java) {
            OcrResourcePolicy.imageSampleSize(0, 100, PixelSize(100, 100))
        }
        assertThrows(IllegalArgumentException::class.java) {
            OcrResourcePolicy.imageSampleSize(100, 0, PixelSize(100, 100))
        }
        assertThrows(IllegalArgumentException::class.java) {
            OcrResourcePolicy.imageSampleSize(100, 100, PixelSize(0, 100))
        }
        assertThrows(IllegalArgumentException::class.java) {
            OcrResourcePolicy.imageSampleSize(100, 100, PixelSize(100, 0))
        }
        listOf(
            0 to 1,
            1 to 0,
            OcrResourcePolicy.MAX_IMAGE_DIMENSION + 1 to 1,
            OcrResourcePolicy.MAX_IMAGE_DIMENSION to OcrResourcePolicy.MAX_IMAGE_DIMENSION + 1,
        ).forEach { (width, height) ->
            assertThrows(IllegalArgumentException::class.java) {
                OcrResourcePolicy.requireDecodedImage(width, height)
            }
        }
        assertEquals(
            PixelSize(OcrResourcePolicy.MAX_IMAGE_DIMENSION, OcrResourcePolicy.MAX_IMAGE_DIMENSION),
            OcrResourcePolicy.requireDecodedImage(
                OcrResourcePolicy.MAX_IMAGE_DIMENSION,
                OcrResourcePolicy.MAX_IMAGE_DIMENSION,
            ),
        )
    }

    @Test
    fun `allows the exact cumulative render boundary`() {
        assertEquals(
            OcrResourcePolicy.MAX_TOTAL_RENDERED_PIXELS,
            OcrResourcePolicy.updatedRenderedPixels(
                OcrResourcePolicy.MAX_TOTAL_RENDERED_PIXELS - 1,
                1,
            ),
        )
    }

    @Test
    fun `rejects invalid overflowing and exhausted render budgets`() {
        listOf(-1L, OcrResourcePolicy.MAX_TOTAL_RENDERED_PIXELS + 1, Long.MAX_VALUE).forEach { current ->
            assertThrows(IllegalArgumentException::class.java) {
                OcrResourcePolicy.updatedRenderedPixels(current, 1)
            }
        }
        listOf(0L, -1L, Long.MAX_VALUE).forEach { additional ->
            assertThrows(IllegalArgumentException::class.java) {
                OcrResourcePolicy.updatedRenderedPixels(0, additional)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            OcrResourcePolicy.updatedRenderedPixels(OcrResourcePolicy.MAX_TOTAL_RENDERED_PIXELS, 1)
        }
    }

    @Test
    fun `normalizes accepted text and tracks retained characters`() {
        val accepted = OcrResourcePolicy.acceptRecognizedText(
            pageNumber = 2,
            recognizedText = "  recognized text\n",
            currentCharacters = 10,
        )

        assertEquals("recognized text", accepted.text)
        assertEquals(25, accepted.totalCharacters)
    }

    @Test
    fun `accepts the exact aggregate text boundary`() {
        val accepted = OcrResourcePolicy.acceptRecognizedText(
            pageNumber = OcrResourcePolicy.MAX_PDF_PAGES,
            recognizedText = "x",
            currentCharacters = OcrResourcePolicy.MAX_TOTAL_TEXT_CHARACTERS - 1,
        )

        assertEquals(OcrResourcePolicy.MAX_TOTAL_TEXT_CHARACTERS, accepted.totalCharacters)
    }

    @Test
    fun `rejects invalid page per page and aggregate text limits`() {
        listOf(0, OcrResourcePolicy.MAX_PDF_PAGES + 1).forEach { page ->
            assertThrows(IllegalArgumentException::class.java) {
                OcrResourcePolicy.acceptRecognizedText(page, "text", 0)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            OcrResourcePolicy.acceptRecognizedText(
                1,
                "x".repeat(OcrResourcePolicy.MAX_TEXT_CHARACTERS_PER_PAGE + 1),
                0,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            OcrResourcePolicy.acceptRecognizedText(
                1,
                "xx",
                OcrResourcePolicy.MAX_TOTAL_TEXT_CHARACTERS - 1,
            )
        }
        listOf(-1, OcrResourcePolicy.MAX_TOTAL_TEXT_CHARACTERS + 1, Int.MAX_VALUE).forEach { current ->
            assertThrows(IllegalArgumentException::class.java) {
                OcrResourcePolicy.acceptRecognizedText(1, "x", current)
            }
        }
    }

    @Test
    fun `formatted length checks exact boundary without integer overflow`() {
        assertEquals(
            OcrResourcePolicy.MAX_FORMATTED_TEXT_CHARACTERS,
            OcrResourcePolicy.requireFormattedLength(
                OcrResourcePolicy.MAX_FORMATTED_TEXT_CHARACTERS - 1,
                1,
            ),
        )
        listOf(-1, Int.MAX_VALUE).forEach { current ->
            assertThrows(IllegalArgumentException::class.java) {
                OcrResourcePolicy.requireFormattedLength(current, 1)
            }
        }
        listOf(-1, Int.MAX_VALUE).forEach { additional ->
            assertThrows(IllegalArgumentException::class.java) {
                OcrResourcePolicy.requireFormattedLength(0, additional)
            }
        }
        assertTrue(OcrResourcePolicy.MAX_FORMATTED_TEXT_CHARACTERS > OcrResourcePolicy.MAX_TOTAL_TEXT_CHARACTERS)
    }
}
