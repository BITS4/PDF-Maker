package com.example.pdfmaker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
fun FilesScreen(
    activity: MainActivity,
    onFileClick: (PdfFile) -> Unit = {},
    onNavigateToHome: () -> Unit,
    onNavigateToSettings: () -> Unit = {},
    onFabClick: () -> Unit,
) {
    val state = rememberFilesScreenState()
    FilesScreenCacheEffect(activity)

    val allFiles = FileCache.files
    val nowMillis = remember(allFiles) { System.currentTimeMillis() }
    val displayedFiles =
        remember(
            allFiles,
            state.selectedTab,
            state.favoritePaths,
            state.selectedFilter,
            state.sortOrder,
            nowMillis,
        ) {
            FilesScreenPolicy.displayedFiles(
                allFiles = allFiles,
                selectedTab = state.selectedTab,
                favoritePaths = state.favoritePaths,
                selectedFilter = state.selectedFilter,
                sortOrder = state.sortOrder,
                nowMillis = nowMillis,
            )
        }
    val callbacks =
        FilesScreenCallbacks(
            onFileClick = onFileClick,
            onNavigateToHome = onNavigateToHome,
            onNavigateToSettings = onNavigateToSettings,
            onFabClick = onFabClick,
            onShareFile = { file -> FilesScreenOperations.share(activity, file) },
            onMoreFile = state::openMenu,
        )

    if (state.showSearch) {
        SearchScreen(
            allFiles = allFiles,
            onFileClick = { file ->
                state.showSearch = false
                onFileClick(file)
            },
            onBack = { state.showSearch = false },
        )
        return
    }

    FilesScreenDialogs(activity, state, callbacks)
    FilesScreenContent(
        state = state,
        displayedFiles = displayedFiles,
        isLoading = FileCache.isLoading,
        callbacks = callbacks,
    )
}
