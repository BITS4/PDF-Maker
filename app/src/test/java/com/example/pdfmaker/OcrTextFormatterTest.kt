package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OcrTextFormatterTest {
    @Test
    fun formatsMultiplePagesWithStableBoundaries() {
        assertEquals(
            "=== Page 1 ===\nfirst\n\n=== Page 3 ===\nthird",
            OcrTextFormatter.format(listOf(1 to "first", 3 to "third")),
        )
    }

    @Test
    fun preservesRecognizedTextWithoutInventingContent() {
        assertEquals("=== Page 2 ===\n  original\ntext  ", OcrTextFormatter.format(listOf(2 to "  original\ntext  ")))
    }

    @Test
    fun emptyRecognitionProducesAnEmptyExport() {
        assertEquals("", OcrTextFormatter.format(emptyList()))
    }

    @Test
    fun rejectsTooManyPagesAndInvalidPageNumbers() {
        val tooMany = List(OcrResourcePolicy.MAX_PDF_PAGES + 1) { index -> index + 1 to "text" }
        assertThrows(IllegalArgumentException::class.java) {
            OcrTextFormatter.format(tooMany)
        }
        assertThrows(IllegalArgumentException::class.java) {
            OcrTextFormatter.format(listOf(0 to "text"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            OcrTextFormatter.format(listOf(OcrResourcePolicy.MAX_PDF_PAGES + 1 to "text"))
        }
    }

    @Test
    fun rejectsOversizedPageText() {
        assertThrows(IllegalArgumentException::class.java) {
            OcrTextFormatter.format(
                listOf(1 to "x".repeat(OcrResourcePolicy.MAX_TEXT_CHARACTERS_PER_PAGE + 1)),
            )
        }
    }

    @Test
    fun rejectsAnOversizedFormattedAggregate() {
        val pagesNeededToExceedLimit =
            (
                OcrResourcePolicy.MAX_FORMATTED_TEXT_CHARACTERS /
                    OcrResourcePolicy.MAX_TEXT_CHARACTERS_PER_PAGE
            ) + 1
        val oversized =
            List(pagesNeededToExceedLimit) { index ->
                index + 1 to "x".repeat(OcrResourcePolicy.MAX_TEXT_CHARACTERS_PER_PAGE)
            }

        assertThrows(IllegalArgumentException::class.java) {
            OcrTextFormatter.format(oversized)
        }
    }
}
