package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Test

class FileSortingTest {
    private val files =
        listOf(
            PdfFile("zulu", "/z.pdf", "900 KB", "old", lastModified = 10L),
            PdfFile("Alpha", "/a.pdf", "1.2 MB", "new", lastModified = 30L),
            PdfFile("middle", "/m.pdf", "2 GB", "mid", lastModified = 20L),
        )

    @Test
    fun `sorts names case insensitively in both directions`() {
        assertEquals(listOf("Alpha", "middle", "zulu"), files.sorted(SortOrder.NAME_ASC).map { it.name })
        assertEquals(listOf("zulu", "middle", "Alpha"), files.sorted(SortOrder.NAME_DESC).map { it.name })
    }

    @Test
    fun `sorts timestamps in both directions`() {
        assertEquals(listOf("zulu", "middle", "Alpha"), files.sorted(SortOrder.DATE_ASC).map { it.name })
        assertEquals(listOf("Alpha", "middle", "zulu"), files.sorted(SortOrder.DATE_DESC).map { it.name })
    }

    @Test
    fun `sorts sizes numerically across units`() {
        assertEquals(listOf("zulu", "Alpha", "middle"), files.sorted(SortOrder.SIZE_ASC).map { it.name })
        assertEquals(listOf("middle", "Alpha", "zulu"), files.sorted(SortOrder.SIZE_DESC).map { it.name })
    }
}
