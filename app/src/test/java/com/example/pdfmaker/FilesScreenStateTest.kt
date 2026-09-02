package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FilesScreenStateTest {
    private val file = PdfFile("Report", "/owned/report.pdf", "1 KB", "today")

    @Test
    fun `initial state uses all files and newest sorting`() {
        val state = FilesScreenState()

        assertEquals(FilesTab.ALL, state.selectedTab)
        assertEquals(FileTypeFilter.ALL, state.selectedFilter)
        assertEquals(SortOrder.DATE_DESC, state.sortOrder)
        assertTrue(state.favoritePaths.isEmpty())
        assertFalse(state.showSearch)
    }

    @Test
    fun `favorite toggle is reversible within the screen session`() {
        val state = FilesScreenState()

        state.toggleFavorite(file)
        assertEquals(setOf(file.filePath), state.favoritePaths)

        state.toggleFavorite(file)
        assertTrue(state.favoritePaths.isEmpty())
    }

    @Test
    fun `rename request transfers menu candidate into dialog state`() {
        val state = FilesScreenState()
        state.openMenu(file)

        state.requestRename(file)

        assertNull(state.menuCandidate)
        assertSame(file, state.renameCandidate)
        assertEquals(file.name, state.renameDraft)
    }

    @Test
    fun `delete request closes menu and records candidate`() {
        val state = FilesScreenState()
        state.openMenu(file)

        state.requestDelete(file)

        assertNull(state.menuCandidate)
        assertSame(file, state.deleteCandidate)
    }

    @Test
    fun `confirmed deletion also removes session favorite`() {
        val state = FilesScreenState()
        state.toggleFavorite(file)
        state.requestDelete(file)

        state.fileDeleted(file)

        assertTrue(state.favoritePaths.isEmpty())
        assertNull(state.deleteCandidate)
    }
}
