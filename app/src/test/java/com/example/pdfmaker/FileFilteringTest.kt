package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class FileFilteringTest {
    @Test
    fun `normalizes case query strings and fragments in extensions`() {
        assertEquals("pdf", FileCatalog.extension("content://docs/REPORT.PDF?download=true#page=2"))
        assertEquals("", FileCatalog.extension("content://docs/no_extension"))
    }

    @Test
    fun `filters every supported document family`() {
        val files = listOf(
            pdf("report", "/docs/report.PDF"),
            pdf("letter", "/docs/letter.docx"),
            pdf("budget", "/docs/budget.XLSX"),
            pdf("deck", "/docs/deck.pptx"),
            pdf("notes", "/docs/notes.md"),
            pdf("photo", "/docs/photo.webp"),
        )

        assertEquals(listOf("report"), files.filteredBy(FileTypeFilter.PDF).map { it.name })
        assertEquals(listOf("letter"), files.filteredBy(FileTypeFilter.DOCS).map { it.name })
        assertEquals(listOf("budget"), files.filteredBy(FileTypeFilter.SHEETS).map { it.name })
        assertEquals(listOf("deck"), files.filteredBy(FileTypeFilter.SLIDES).map { it.name })
        assertEquals(listOf("notes"), files.filteredBy(FileTypeFilter.TEXT).map { it.name })
        assertEquals(listOf("photo"), files.filteredBy(FileTypeFilter.IMAGES).map { it.name })
    }

    @Test
    fun `all filter preserves the original list instance`() {
        val files = listOf(pdf("report", "/docs/report.pdf"))
        assertSame(files, files.filteredBy(FileTypeFilter.ALL))
    }

    private fun pdf(name: String, path: String) = PdfFile(name, path, "1 KB", "today")
}
