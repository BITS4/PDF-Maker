package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ThumbnailGenerationPolicyTest {
    @Test
    fun `classifies supported extensions without trusting case`() {
        val expectations =
            mapOf(
                "document.PDF" to ThumbnailSourceKind.PDF,
                "photo.JpEg" to ThumbnailSourceKind.IMAGE,
                "document.docx" to ThumbnailSourceKind.WORD_PROCESSING,
                "slides.pptx" to ThumbnailSourceKind.PRESENTATION,
                "table.xlsx" to ThumbnailSourceKind.SPREADSHEET,
                "table.tsv" to ThumbnailSourceKind.DELIMITED_TEXT,
                "notes.md" to ThumbnailSourceKind.PLAIN_TEXT,
                "legacy.doc" to ThumbnailSourceKind.UNSUPPORTED,
                "missing-extension" to ThumbnailSourceKind.UNSUPPORTED,
            )

        expectations.forEach { (path, expected) ->
            assertEquals(expected, ThumbnailGenerationPolicy.classify(path))
        }
    }

    @Test
    fun `accepts only bounded positive thumbnail sizes`() {
        ThumbnailGenerationPolicy.requireValidSize(1)
        ThumbnailGenerationPolicy.requireValidSize(ThumbnailGenerationPolicy.MAX_SIZE_PX)

        assertThrows(IllegalArgumentException::class.java) {
            ThumbnailGenerationPolicy.requireValidSize(0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ThumbnailGenerationPolicy.requireValidSize(ThumbnailGenerationPolicy.MAX_SIZE_PX + 1)
        }
    }

    @Test
    fun `selects delimiters and ellipsizes only overflowing text`() {
        assertEquals('\t', ThumbnailGenerationPolicy.delimiter("DATA.TSV"))
        assertEquals(',', ThumbnailGenerationPolicy.delimiter("data.csv"))
        assertEquals("short", ThumbnailGenerationPolicy.displayText("short", 5))
        assertEquals("long…", ThumbnailGenerationPolicy.displayText("longer", 5))
        assertThrows(IllegalArgumentException::class.java) {
            ThumbnailGenerationPolicy.displayText("text", 1)
        }
    }

    @Test
    fun `bounds delimited rows columns and individual cells`() {
        val content = "abcdef,second,ignored\nnext,row,ignored\nthird,row,ignored"

        assertEquals(
            listOf(
                listOf("abcd", "seco"),
                listOf("next", "row"),
            ),
            ThumbnailGenerationPolicy.delimitedRows(
                content = content,
                delimiter = ',',
                maximumRows = 2,
                maximumColumns = 2,
                maximumCellCharacters = 4,
            ),
        )
    }

    @Test
    fun `rejects invalid delimited preview limits`() {
        assertThrows(IllegalArgumentException::class.java) {
            ThumbnailGenerationPolicy.delimitedRows("data", ',', maximumRows = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ThumbnailGenerationPolicy.delimitedRows("data", ',', maximumColumns = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ThumbnailGenerationPolicy.delimitedRows("data", ',', maximumCellCharacters = 0)
        }
    }

    @Test
    fun `text previews discard blanks and cap lines and characters`() {
        assertEquals(
            listOf("firs", "seco"),
            ThumbnailGenerationPolicy.visibleTextLines(
                content = "  first  \n\nsecond\nthird",
                maximumLines = 2,
                maximumCharacters = 4,
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            ThumbnailGenerationPolicy.visibleTextLines("text", maximumLines = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ThumbnailGenerationPolicy.visibleTextLines("text", maximumCharacters = 0)
        }
    }
}
