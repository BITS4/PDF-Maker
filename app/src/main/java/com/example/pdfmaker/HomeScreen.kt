package com.example.pdfmaker

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pdfmaker.R

// ── Screen ────────────────────────────────────────────────────────────────────

// Home is the route-level state owner for its mutually exclusive dialogs, search,
// filtering, and speed-dial state; splitting those owners would create conflicting UI state.
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
fun HomeScreen(
    onToolClick: (String) -> Unit = {},
    onFileClick: (PdfFile) -> Unit = {},
    onShareFile: (PdfFile) -> Unit = {},
    onNavigateToFiles: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
) {
    val context = LocalContext.current

    val allFiles = FileCache.files
    val isLoading = FileCache.isLoading
    var sortOrder by remember { mutableStateOf(SortOrder.DATE_DESC) }
    var showSort by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var favorites by remember { mutableStateOf<Set<String>>(emptySet()) }
    var menuFile by remember { mutableStateOf<PdfFile?>(null) }
    var pendingDeleteFile by remember { mutableStateOf<PdfFile?>(null) }
    var showRenameFor by remember { mutableStateOf<PdfFile?>(null) }
    var renameText by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(FileTypeFilter.ALL) }
    var fabExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(FileCache.version) { FileCache.load(context) }

    val files =
        remember(allFiles, sortOrder, selectedFilter) {
            allFiles.filteredBy(selectedFilter).sorted(sortOrder)
        }

    // ── Search overlay ────────────────────────────────────────────────────────
    if (showSearch) {
        SearchScreen(
            allFiles = allFiles,
            onFileClick = { f ->
                showSearch = false
                onFileClick(f)
            },
            onBack = { showSearch = false },
        )
        return
    }

    // ── Sort dialog ───────────────────────────────────────────────────────────
    if (showSort) {
        AlertDialog(
            onDismissRequest = { showSort = false },
            containerColor = currentCard,
            title = { Text("Sort by", color = currentText, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    SortOrder.entries.forEach { order ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        sortOrder = order
                                        showSort = false
                                    }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = sortOrder == order,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = AccentBlue),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(order.label, color = currentText, fontSize = 15.sp)
                        }
                    }
                }
            },
            confirmButton = {},
        )
    }

    // ── Delete confirmation ───────────────────────────────────────────────────
    val fileToConfirmDelete = pendingDeleteFile
    if (fileToConfirmDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDeleteFile = null },
            containerColor = currentCard,
            title = { Text("Delete File", color = currentText) },
            text = {
                Text(
                    "Delete \"${fileToConfirmDelete.name}\"? This cannot be undone.",
                    color = currentTextSecond,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val deleted = FileRepository.deleteFile(context, fileToConfirmDelete.filePath)
                    if (deleted) {
                        FileCache.removeFile(fileToConfirmDelete.filePath)
                        PdfThumbnailCache.invalidate(fileToConfirmDelete.filePath)
                        Toast.makeText(context, "Deleted", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Could not delete", Toast.LENGTH_SHORT).show()
                    }
                    pendingDeleteFile = null
                }) {
                    Text("Delete", color = BadgeRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteFile = null }) {
                    Text("Cancel", color = currentTextSecond)
                }
            },
        )
    }

    // ── More-options dialog ───────────────────────────────────────────────────
    val fileForMenu = menuFile
    if (fileForMenu != null) {
        val isFav = favorites.contains(fileForMenu.filePath)
        AlertDialog(
            onDismissRequest = { menuFile = null },
            containerColor = currentCard,
            title = {
                Text(fileForMenu.name, color = currentText, fontSize = 13.sp, maxLines = 2)
            },
            text = {
                Column {
                    MoreMenuItem(icon = Icons.Default.Visibility, label = "Open") {
                        menuFile = null
                        onFileClick(fileForMenu)
                    }
                    MoreMenuItem(icon = Icons.Default.DriveFileRenameOutline, label = "Rename") {
                        renameText = fileForMenu.name
                        menuFile = null
                        showRenameFor = fileForMenu
                    }
                    MoreMenuItem(icon = Icons.Default.Share, label = "Share") {
                        menuFile = null
                        onShareFile(fileForMenu)
                    }
                    val favIcon = if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder
                    val favLabel = if (isFav) "Remove from Favorites" else "Add to Favorites"
                    MoreMenuItem(icon = favIcon, label = favLabel) {
                        favorites =
                            if (isFav) {
                                favorites - fileForMenu.filePath
                            } else {
                                favorites + fileForMenu.filePath
                            }
                        menuFile = null
                    }
                    MoreMenuItem(icon = Icons.Default.Delete, label = "Delete", tint = BadgeRed) {
                        pendingDeleteFile = fileForMenu
                        menuFile = null
                    }
                }
            },
            confirmButton = {},
        )
    }

    // ── Rename dialog ────────────────────────────────────────────────────────
    showRenameFor?.let { fileToRename ->
        AlertDialog(
            onDismissRequest = { showRenameFor = null },
            containerColor = currentCard,
            title = { Text("Rename", color = currentText, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("File name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentBlue,
                            unfocusedBorderColor = currentTextSecond,
                            focusedTextColor = currentText,
                            unfocusedTextColor = currentText,
                            focusedLabelColor = AccentBlue,
                            unfocusedLabelColor = currentTextSecond,
                        ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val newName = renameText.trim()
                    if (newName.isNotEmpty()) {
                        val old = java.io.File(fileToRename.filePath)
                        OutputStore.renameWithinParent(old, newName).fold(
                            onSuccess = { renamed ->
                                FileCache.renameFile(
                                    fileToRename.filePath,
                                    renamed.absolutePath,
                                    renamed.nameWithoutExtension,
                                )
                                PdfThumbnailCache.invalidate(fileToRename.filePath)
                                Toast.makeText(context, "Renamed", Toast.LENGTH_SHORT).show()
                            },
                            onFailure = {
                                Toast.makeText(context, "Rename failed", Toast.LENGTH_SHORT).show()
                            },
                        )
                    }
                    showRenameFor = null
                }) { Text("Rename", color = AccentBlue, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showRenameFor = null }) {
                    Text("Cancel", color = currentTextSecond)
                }
            },
        )
    }

    // ── Main scaffold ─────────────────────────────────────────────────────────
    Scaffold(
        containerColor = currentBg,
        bottomBar = {
            BottomNavBar(
                selected = 0,
                onHomeClick = {},
                onFilesClick = onNavigateToFiles,
                onFabClick = { fabExpanded = !fabExpanded },
                showFab = true,
                fabExpanded = fabExpanded,
                onAction = { key ->
                    fabExpanded = false
                    onToolClick(key)
                },
            )
        },
    ) { padding ->
        // Scrim — dismiss speed dial on tap outside
        if (fabExpanded) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xAA000000))
                    .clickable(
                        indication = null,
                        interactionSource =
                            remember {
                                androidx.compose.foundation.interaction
                                    .MutableInteractionSource()
                            },
                    ) { fabExpanded = false },
            )
        }
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Header
            item {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.home_title),
                        color = currentText,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButtonRound(
                            Icons.Default.Search,
                            "Search",
                            onClick = { showSearch = true },
                        )
                        IconButtonRound(
                            Icons.Default.Settings,
                            "Settings",
                            onClick = onNavigateToSettings,
                        )
                    }
                }
            }

            // Tools grid
            item { ToolsGrid(onToolClick = onToolClick) }

            // File-type filter chips
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FileTypeFilter.entries.forEach { filter ->
                        FilterTypeChip(
                            filter = filter,
                            selected = selectedFilter == filter,
                            onClick = { selectedFilter = filter },
                        )
                    }
                }
            }

            // Section header — only when loaded
            if (!isLoading) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        color = currentCard,
                        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                    ) {
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.home_all),
                                color = currentText,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                            )
                            Text(
                                " (${files.size}${ if (selectedFilter != FileTypeFilter.ALL) " · ${selectedFilter.label}" else "" })",
                                color = currentTextSecond,
                                fontSize = 16.sp,
                            )
                            Spacer(Modifier.weight(1f))
                            Row(
                                modifier =
                                    Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { showSort = true }
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Sort,
                                    contentDescription = null,
                                    tint =
                                        if (sortOrder != SortOrder.DATE_DESC) {
                                            AccentBlue
                                        } else {
                                            currentTextSecond
                                        },
                                    modifier = Modifier.size(22.dp),
                                )
                                if (sortOrder != SortOrder.DATE_DESC) {
                                    Spacer(Modifier.width(4.dp))
                                    Text(sortOrder.label, color = AccentBlue, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }

            // Loading skeletons
            if (isLoading) {
                item { SectionHeaderSkeleton() }
                items(6) { FileItemSkeleton() }
            } else if (files.isEmpty()) {
                item {
                    EmptyState(
                        kind = EmptyKind.ALL_FILES,
                        onAction = { onToolClick("image_to_pdf") },
                    )
                }
            } else {
                items(files, key = { it.filePath }) { file ->
                    FileItemWithThumb(
                        file = file,
                        onItemClick = { onFileClick(file) },
                        onShareClick = { onShareFile(file) },
                        onMoreClick = { menuFile = file },
                    )
                }
            }
        }
    }
}
