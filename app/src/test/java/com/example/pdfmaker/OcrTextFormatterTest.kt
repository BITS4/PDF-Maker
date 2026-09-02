package com.example.pdfmaker

import org.junit.Assert.assertEquals
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
}
