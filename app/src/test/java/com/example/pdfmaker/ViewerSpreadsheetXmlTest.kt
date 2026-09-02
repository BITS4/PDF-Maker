package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerSpreadsheetXmlTest {
    @Test
    fun `takes only spreadsheet text inside the cell budget`() {
        assertEquals("abcd", boundedSpreadsheetText(0, "abcdef", maximumLength = 4))
        assertEquals("d", boundedSpreadsheetText(3, "def", maximumLength = 4))
        assertEquals("", boundedSpreadsheetText(4, "def", maximumLength = 4))
    }

    @Test
    fun `rejects invalid spreadsheet text limits`() {
        assertTrue(runCatching { boundedSpreadsheetText(-1, "text", 4) }.isFailure)
        assertTrue(runCatching { boundedSpreadsheetText(0, "text", 0) }.isFailure)
    }
}
