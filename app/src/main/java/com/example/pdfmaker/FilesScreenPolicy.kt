package com.example.pdfmaker

enum class FilesTab {
    ALL,
    RECENT,
    FAVORITES,
}

internal enum class FilesContentMode {
    LOADING,
    EMPTY,
    FILES,
}

internal object FilesScreenPolicy {
    const val RECENT_WINDOW_MILLIS = 7L * 24L * 60L * 60L * 1_000L

    fun filesForTab(
        allFiles: List<PdfFile>,
        selectedTab: FilesTab,
        favoritePaths: Set<String>,
        nowMillis: Long,
    ): List<PdfFile> =
        when (selectedTab) {
            FilesTab.ALL -> {
                allFiles
            }

            FilesTab.RECENT -> {
                val cutoff = safeRecentCutoff(nowMillis)
                allFiles.filter { it.lastModified >= cutoff }
            }

            FilesTab.FAVORITES -> {
                allFiles.filter { it.filePath in favoritePaths }
            }
        }

    fun displayedFiles(
        allFiles: List<PdfFile>,
        selectedTab: FilesTab,
        favoritePaths: Set<String>,
        selectedFilter: FileTypeFilter,
        sortOrder: SortOrder,
        nowMillis: Long,
    ): List<PdfFile> =
        filesForTab(allFiles, selectedTab, favoritePaths, nowMillis)
            .filteredBy(selectedFilter)
            .sorted(sortOrder)

    fun contentMode(
        isLoading: Boolean,
        displayedFileCount: Int,
    ): FilesContentMode {
        require(displayedFileCount >= 0) { "Displayed file count cannot be negative" }
        return when {
            isLoading -> FilesContentMode.LOADING
            displayedFileCount == 0 -> FilesContentMode.EMPTY
            else -> FilesContentMode.FILES
        }
    }

    fun countLabel(displayedFileCount: Int): String {
        require(displayedFileCount >= 0) { "Displayed file count cannot be negative" }
        val suffix = if (displayedFileCount == 1) "file" else "files"
        return "$displayedFileCount $suffix"
    }

    fun emptyKind(filter: FileTypeFilter): EmptyKind =
        when (filter) {
            FileTypeFilter.PDF -> EmptyKind.PDF

            FileTypeFilter.DOCS -> EmptyKind.DOCS

            FileTypeFilter.IMAGES -> EmptyKind.IMAGES

            FileTypeFilter.ALL,
            FileTypeFilter.SHEETS,
            FileTypeFilter.SLIDES,
            FileTypeFilter.TEXT,
            -> EmptyKind.ALL_FILES
        }

    private fun safeRecentCutoff(nowMillis: Long): Long =
        if (nowMillis < Long.MIN_VALUE + RECENT_WINDOW_MILLIS) {
            Long.MIN_VALUE
        } else {
            nowMillis - RECENT_WINDOW_MILLIS
        }
}
