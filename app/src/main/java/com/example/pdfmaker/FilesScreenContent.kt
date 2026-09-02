package com.example.pdfmaker

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val FilesTabs =
    listOf(
        FilesTab.ALL to "All",
        FilesTab.RECENT to "Recent",
        FilesTab.FAVORITES to "Favorites",
    )

@Composable
internal fun FilesScreenContent(
    state: FilesScreenState,
    displayedFiles: List<PdfFile>,
    isLoading: Boolean,
    callbacks: FilesScreenCallbacks,
) {
    Scaffold(
        containerColor = currentBg,
        bottomBar = {
            BottomNavBar(
                selected = 1,
                onHomeClick = callbacks.onNavigateToHome,
                onFilesClick = {},
                onFabClick = callbacks.onFabClick,
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
        ) {
            FilesHeader(state, callbacks.onNavigateToSettings)
            FilesTabRow(state)
            Spacer(Modifier.height(8.dp))
            FilesFilterRow(state)
            Spacer(Modifier.height(6.dp))
            FilesStats(state, isLoading, displayedFiles.size)
            FilesListContent(
                mode = FilesScreenPolicy.contentMode(isLoading, displayedFiles.size),
                files = displayedFiles,
                selectedFilter = state.selectedFilter,
                callbacks = callbacks,
            )
        }
    }
}

@Composable
private fun FilesHeader(
    state: FilesScreenState,
    onNavigateToSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.files_title),
            color = currentText,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            IconButtonRound(Icons.Default.Search, "Search") { state.showSearch = true }
            IconButtonRound(Icons.AutoMirrored.Filled.Sort, "Sort") {
                state.showSortDialog = true
            }
            IconButtonRound(
                Icons.Default.Settings,
                "Settings",
                onClick = onNavigateToSettings,
            )
        }
    }
}

@Composable
private fun FilesTabRow(state: FilesScreenState) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilesTabs.forEach { (tab, label) ->
            TabChip(label, state.selectedTab == tab) {
                state.selectedTab = tab
            }
        }
    }
}

@Composable
private fun FilesFilterRow(state: FilesScreenState) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FileTypeFilter.entries.forEach { filter ->
            FilterTypeChip(
                filter = filter,
                selected = state.selectedFilter == filter,
                onClick = { state.selectedFilter = filter },
            )
        }
    }
}

@Composable
private fun FilesStats(
    state: FilesScreenState,
    isLoading: Boolean,
    displayedFileCount: Int,
) {
    if (FilesScreenPolicy.contentMode(isLoading, displayedFileCount) != FilesContentMode.FILES) {
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = FilesScreenPolicy.countLabel(displayedFileCount),
            color = currentTextSecond,
            fontSize = 12.sp,
        )
        if (state.selectedFilter != FileTypeFilter.ALL) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = "· ${state.selectedFilter.label}",
                color = AccentBlue,
                fontSize = 12.sp,
            )
        }
        if (state.sortOrder != SortOrder.DATE_DESC) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = "· ${state.sortOrder.label}",
                color = AccentBlue,
                fontSize = 12.sp,
                modifier = Modifier.clickable { state.showSortDialog = true },
            )
        }
    }
}

@Composable
private fun FilesListContent(
    mode: FilesContentMode,
    files: List<PdfFile>,
    selectedFilter: FileTypeFilter,
    callbacks: FilesScreenCallbacks,
) {
    when (mode) {
        FilesContentMode.LOADING -> {
            LazyColumn(Modifier.fillMaxSize()) {
                items(7) { FileItemSkeleton() }
            }
        }

        FilesContentMode.EMPTY -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(kind = FilesScreenPolicy.emptyKind(selectedFilter))
            }
        }

        FilesContentMode.FILES -> {
            LazyColumn(Modifier.fillMaxSize()) {
                items(files, key = PdfFile::filePath) { file ->
                    FileItemWithThumb(
                        file = file,
                        onItemClick = { callbacks.onFileClick(file) },
                        onShareClick = { callbacks.onShareFile(file) },
                        onMoreClick = { callbacks.onMoreFile(file) },
                    )
                }
            }
        }
    }
}
