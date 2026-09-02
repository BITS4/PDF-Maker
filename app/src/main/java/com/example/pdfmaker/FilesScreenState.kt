package com.example.pdfmaker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

@Stable
internal class FilesScreenState {
    var favoritePaths by mutableStateOf<Set<String>>(emptySet())
        private set
    var selectedTab by mutableStateOf(FilesTab.ALL)
    var selectedFilter by mutableStateOf(FileTypeFilter.ALL)
    var sortOrder by mutableStateOf(SortOrder.DATE_DESC)
    var showSortDialog by mutableStateOf(false)
    var showSearch by mutableStateOf(false)
    var deleteCandidate by mutableStateOf<PdfFile?>(null)
    var menuCandidate by mutableStateOf<PdfFile?>(null)
    var renameCandidate by mutableStateOf<PdfFile?>(null)
    var renameDraft by mutableStateOf("")

    fun toggleFavorite(file: PdfFile) {
        favoritePaths =
            if (file.filePath in favoritePaths) {
                favoritePaths - file.filePath
            } else {
                favoritePaths + file.filePath
            }
    }

    fun openMenu(file: PdfFile) {
        menuCandidate = file
    }

    fun requestRename(file: PdfFile) {
        menuCandidate = null
        renameCandidate = file
        renameDraft = file.name
    }

    fun requestDelete(file: PdfFile) {
        menuCandidate = null
        deleteCandidate = file
    }

    fun fileDeleted(file: PdfFile) {
        favoritePaths = favoritePaths - file.filePath
        deleteCandidate = null
    }
}

@Composable
internal fun rememberFilesScreenState(): FilesScreenState = remember { FilesScreenState() }
