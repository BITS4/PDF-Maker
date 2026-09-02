package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerDelimitedTextTest {
    @Test
    fun `parses comma separated rows`() {
        assertEquals(
            listOf(listOf("name", "age"), listOf("Ada", "36")),
            parseDelimitedRows("name,age\nAda,36", ','),
        )
    }

    @Test
    fun `preserves delimiters line breaks and escaped quotes inside quoted cells`() {
        assertEquals(
            listOf(listOf("Ada, Lovelace", "first\nprogrammer", "said \"hello\"")),
            parseDelimitedRows("\"Ada, Lovelace\",\"first\nprogrammer\",\"said \"\"hello\"\"\"", ','),
        )
    }

    @Test
    fun `handles windows lines trailing newline and empty cells`() {
        assertEquals(
            listOf(listOf("a", "", "c"), listOf("1", "2", "3")),
            parseDelimitedRows("a,,c\r\n1,2,3\r\n", ','),
        )
    }

    @Test
    fun `selects tsv delimiters case insensitively`() {
        assertEquals('\t', delimiterForFileName("DATA.TSV"))
        assertEquals(',', delimiterForFileName("data.csv"))
        assertTrue(parseDelimitedRows("", ',').isEmpty())
    }
}
