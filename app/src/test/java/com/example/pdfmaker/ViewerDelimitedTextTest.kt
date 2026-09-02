package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
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

    @Test
    fun `bounds rows columns and cell lengths`() {
        assertEquals(
            listOf(listOf("abc", "123"), listOf("row", "456")),
            parseDelimitedRows(
                "abcdef,123,discard\nrow,456,discard\nignored,789",
                ',',
                maximumRows = 2,
                maximumColumns = 2,
                maximumCellCharacters = 3,
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            parseDelimitedRows("a".repeat(MAX_VIEWER_TEXT_BYTES + 1), ',')
        }
    }
}
