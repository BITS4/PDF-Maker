package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class FilesScreenPolicyTest {
    @Test
    fun `all tab preserves the complete file list`() {
        val files = listOf(file("first", "/first.pdf", modified = 10L))

        val selected =
            FilesScreenPolicy.filesForTab(
                allFiles = files,
                selectedTab = FilesTab.ALL,
                favoritePaths = emptySet(),
                nowMillis = 100L,
            )

        assertSame(files, selected)
    }

    @Test
    fun `recent tab includes exact cutoff and newer files`() {
        val now = 1_000_000_000L
        val cutoff = now - FilesScreenPolicy.RECENT_WINDOW_MILLIS
        val files =
            listOf(
                file("older", "/older.pdf", cutoff - 1L),
                file("boundary", "/boundary.pdf", cutoff),
                file("current", "/current.pdf", now),
                file("future", "/future.pdf", now + 1L),
            )

        val recent =
            FilesScreenPolicy.filesForTab(
                allFiles = files,
                selectedTab = FilesTab.RECENT,
                favoritePaths = emptySet(),
                nowMillis = now,
            )

        assertEquals(listOf("boundary", "current", "future"), recent.map(PdfFile::name))
    }

    @Test
    fun `recent cutoff is safe at long minimum boundary`() {
        val oldest = file("oldest", "/oldest.pdf", Long.MIN_VALUE)

        assertEquals(
            listOf(oldest),
            FilesScreenPolicy.filesForTab(
                allFiles = listOf(oldest),
                selectedTab = FilesTab.RECENT,
                favoritePaths = emptySet(),
                nowMillis = Long.MIN_VALUE,
            ),
        )
    }

    @Test
    fun `favorites tab selects known paths without changing order`() {
        val first = file("first", "/first.pdf", 1L)
        val second = file("second", "/second.pdf", 2L)
        val third = file("third", "/third.pdf", 3L)

        val favorites =
            FilesScreenPolicy.filesForTab(
                allFiles = listOf(first, second, third),
                selectedTab = FilesTab.FAVORITES,
                favoritePaths = setOf(third.filePath, first.filePath, "/missing.pdf"),
                nowMillis = 10L,
            )

        assertEquals(listOf(first, third), favorites)
    }

    @Test
    fun `displayed files compose tab filter and sort policies`() {
        val files =
            listOf(
                file("zeta", "/zeta.pdf", 20L),
                file("alpha", "/alpha.docx", 30L),
                file("beta", "/beta.pdf", 40L),
            )

        val displayed =
            FilesScreenPolicy.displayedFiles(
                allFiles = files,
                selectedTab = FilesTab.FAVORITES,
                favoritePaths = files.map(PdfFile::filePath).toSet(),
                selectedFilter = FileTypeFilter.PDF,
                sortOrder = SortOrder.NAME_ASC,
                nowMillis = 100L,
            )

        assertEquals(listOf("beta", "zeta"), displayed.map(PdfFile::name))
    }

    @Test
    fun `content mode gives loading precedence then empty and files`() {
        assertEquals(FilesContentMode.LOADING, FilesScreenPolicy.contentMode(true, 0))
        assertEquals(FilesContentMode.LOADING, FilesScreenPolicy.contentMode(true, 3))
        assertEquals(FilesContentMode.EMPTY, FilesScreenPolicy.contentMode(false, 0))
        assertEquals(FilesContentMode.FILES, FilesScreenPolicy.contentMode(false, 1))
        assertEquals(FilesContentMode.FILES, FilesScreenPolicy.contentMode(false, Int.MAX_VALUE))
    }

    @Test
    fun `content mode rejects impossible negative counts`() {
        assertThrows(IllegalArgumentException::class.java) {
            FilesScreenPolicy.contentMode(isLoading = false, displayedFileCount = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FilesScreenPolicy.contentMode(isLoading = true, displayedFileCount = Int.MIN_VALUE)
        }
    }

    @Test
    fun `count label handles singular plural and boundaries`() {
        assertEquals("0 files", FilesScreenPolicy.countLabel(0))
        assertEquals("1 file", FilesScreenPolicy.countLabel(1))
        assertEquals("2 files", FilesScreenPolicy.countLabel(2))
        assertEquals("${Int.MAX_VALUE} files", FilesScreenPolicy.countLabel(Int.MAX_VALUE))
        assertThrows(IllegalArgumentException::class.java) {
            FilesScreenPolicy.countLabel(-1)
        }
    }

    @Test
    fun `empty state mapping covers every file type filter`() {
        val expected =
            mapOf(
                FileTypeFilter.ALL to EmptyKind.ALL_FILES,
                FileTypeFilter.PDF to EmptyKind.PDF,
                FileTypeFilter.DOCS to EmptyKind.DOCS,
                FileTypeFilter.SHEETS to EmptyKind.ALL_FILES,
                FileTypeFilter.SLIDES to EmptyKind.ALL_FILES,
                FileTypeFilter.TEXT to EmptyKind.ALL_FILES,
                FileTypeFilter.IMAGES to EmptyKind.IMAGES,
            )

        assertEquals(FileTypeFilter.entries.toSet(), expected.keys)
        expected.forEach { (filter, kind) ->
            assertEquals(kind, FilesScreenPolicy.emptyKind(filter))
        }
    }

    private fun file(
        name: String,
        path: String,
        modified: Long,
    ): PdfFile =
        PdfFile(
            name = name,
            filePath = path,
            size = "1 KB",
            date = "today",
            lastModified = modified,
        )
}
