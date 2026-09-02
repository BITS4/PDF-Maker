package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerSpreadsheetXmlTest {
    @Test
    fun `parses shared and inline spreadsheet values`() {
        val strings = parseViewerSharedStrings(
            "<sst><si><t>Name</t></si><si><t>Value</t></si></sst>",
        )
        val rows = parseViewerSheet(
            """
            <worksheet><sheetData><row>
              <c t="s"><v>0</v></c><c t="s"><v>1</v></c><c><v>42</v></c>
            </row></sheetData></worksheet>
            """.trimIndent(),
            strings,
        )

        assertEquals(listOf("Name", "Value"), strings)
        assertEquals(listOf(listOf("Name", "Value", "42")), rows)
    }

    @Test
    fun `bounds spreadsheet rows columns and cell text`() {
        val strings = parseViewerSharedStrings(
            "<sst><si><t>abcdef</t></si><si><t>second</t></si><si><t>ignored</t></si></sst>",
            maximumStrings = 2,
            maximumCellCharacters = 4,
        )
        val cells = buildString {
            repeat(4) { append("<c><v>abcdef</v></c>") }
        }
        val rowsXml = buildString {
            repeat(3) { append("<row>$cells</row>") }
        }
        val rows = parseViewerSheet(
            "<worksheet><sheetData>$rowsXml</sheetData></worksheet>",
            emptyList(),
            maximumRows = 2,
            maximumColumns = 3,
            maximumCellCharacters = 4,
        )

        assertEquals(listOf("abcd", "seco"), strings)
        assertEquals(2, rows.size)
        assertEquals(3, rows.first().size)
        assertEquals("abcd", rows.first().first())
    }
}
