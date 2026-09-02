package com.example.pdfmaker

import com.example.pdfmaker.R
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.core.content.FileProvider
import java.io.File

// ── Tabs ──────────────────────────────────────────────────────────────────────

enum class FilesTab { ALL, RECENT, FAVORITES }

// ── Screen ────────────────────────────────────────────────────────────────────

@Composable
fun FilesScreen(
    activity            : MainActivity,
    onFileClick         : (PdfFile) -> Unit = {},
    onNavigateToHome    : () -> Unit,
    onNavigateToSettings: () -> Unit = {},
    onFabClick          : () -> Unit
) {
    val context = LocalContext.current

    val allFiles         = FileCache.files
    val isLoading        = FileCache.isLoading
    var favorites        by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedTab      by remember { mutableStateOf(FilesTab.ALL) }
    var selectedFilter   by remember { mutableStateOf(FileTypeFilter.ALL) }
    var sortOrder        by remember { mutableStateOf(SortOrder.DATE_DESC) }
    var showSort         by remember { mutableStateOf(false) }
    var showSearch       by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf<PdfFile?>(null) }
    var showMoreMenuFor  by remember { mutableStateOf<PdfFile?>(null) }
    var showRenameFor   by remember { mutableStateOf<PdfFile?>(null) }
    var renameText      by remember { mutableStateOf("") }

    LaunchedEffect(FileCache.version) { FileCache.load(context) }

    val recentFiles = remember(allFiles) {
        val cutoff = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
        allFiles.filter { it.lastModified >= cutoff }
    }

    val baseList: List<PdfFile> = when (selectedTab) {
        FilesTab.ALL       -> allFiles
        FilesTab.RECENT    -> recentFiles
        FilesTab.FAVORITES -> allFiles.filter { favorites.contains(it.filePath) }
    }
    val displayedFiles = remember(baseList, sortOrder, selectedFilter) {
        baseList.filteredBy(selectedFilter).sorted(sortOrder)
    }

    // ── Search overlay ────────────────────────────────────────────────────────
    if (showSearch) {
        SearchScreen(
            allFiles    = allFiles,
            onFileClick = { file -> showSearch = false; onFileClick(file) },
            onBack      = { showSearch = false }
        )
        return
    }

    // ── Sort dialog ───────────────────────────────────────────────────────────
    if (showSort) {
        AlertDialog(
            onDismissRequest = { showSort = false },
            containerColor   = currentCard,
            title = { Text("Sort by", color = currentText, fontWeight = FontWeight.Bold) },
            text  = {
                Column {
                    SortOrder.entries.forEach { order ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { sortOrder = order; showSort = false }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = sortOrder == order, onClick = null,
                                colors   = RadioButtonDefaults.colors(selectedColor = AccentBlue)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(order.label, color = currentText, fontSize = 15.sp)
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }

    // ── Delete confirmation ───────────────────────────────────────────────────
    showDeleteDialog?.let { fileToDelete ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            containerColor   = currentCard,
            title = { Text("Delete File", color = currentText) },
            text  = {
                Text("Delete \"${fileToDelete.name}\"? This cannot be undone.",
                    color = currentTextSecond)
            },
            confirmButton = {
                TextButton(onClick = {
                    if (FileRepository.deleteFile(context, fileToDelete.filePath)) {
                        FileCache.removeFile(fileToDelete.filePath)
                        PdfThumbnailCache.invalidate(fileToDelete.filePath)
                        Toast.makeText(context, "Deleted", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Could not delete file", Toast.LENGTH_SHORT).show()
                    }
                    showDeleteDialog = null
                }) { Text("Delete", color = BadgeRed) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("Cancel", color = currentTextSecond)
                }
            }
        )
    }

    // ── More options ──────────────────────────────────────────────────────────
    showMoreMenuFor?.let { fileForMenu ->
        val isFav = favorites.contains(fileForMenu.filePath)
        AlertDialog(
            onDismissRequest = { showMoreMenuFor = null },
            containerColor   = currentCard,
            title = { Text(fileForMenu.name, color = currentText, fontSize = 13.sp, maxLines = 2) },
            text = {
                Column {
                    MoreMenuItem(Icons.Default.Visibility, "Open") {
                        showMoreMenuFor = null; onFileClick(fileForMenu)
                    }
                    MoreMenuItem(Icons.Default.DriveFileRenameOutline, "Rename") {
                        renameText = fileForMenu.name
                        showMoreMenuFor = null; showRenameFor = fileForMenu
                    }
                    MoreMenuItem(Icons.Default.Share, "Share") {
                        sharePdf(context, fileForMenu); showMoreMenuFor = null
                    }
                    MoreMenuItem(
                        icon  = if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        label = if (isFav) "Remove from Favorites" else "Add to Favorites"
                    ) {
                        favorites = if (isFav) favorites - fileForMenu.filePath
                                    else       favorites + fileForMenu.filePath
                        showMoreMenuFor = null
                    }
                    MoreMenuItem(Icons.Default.Delete, "Delete", tint = BadgeRed) {
                        showMoreMenuFor = null; showDeleteDialog = fileForMenu
                    }
                }
            },
            confirmButton = {}
        )
    }

    // ── Rename dialog ────────────────────────────────────────────────────────
    showRenameFor?.let { fileToRename ->
        AlertDialog(
            onDismissRequest = { showRenameFor = null },
            containerColor   = currentCard,
            title = { Text("Rename", color = currentText, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value         = renameText,
                    onValueChange = { renameText = it },
                    label         = { Text("File name") },
                    singleLine    = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                    colors   = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = AccentBlue,
                        unfocusedBorderColor = currentTextSecond,
                        focusedTextColor     = currentText,
                        unfocusedTextColor   = currentText,
                        focusedLabelColor    = AccentBlue,
                        unfocusedLabelColor  = currentTextSecond
                    )
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
            }
        )
    }

    // ── Scaffold ──────────────────────────────────────────────────────────────
    Scaffold(
        containerColor = currentBg,
        bottomBar = {
            BottomNavBar(
                selected     = 1,
                onHomeClick  = onNavigateToHome,
                onFilesClick = {},
                onFabClick   = onFabClick
            )
        }
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {

            // ── Header ────────────────────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.files_title),
                    color = currentText, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconButtonRound(Icons.Default.Search, "Search") { showSearch = true }
                    IconButtonRound(Icons.AutoMirrored.Filled.Sort, "Sort") { showSort = true }
                    IconButtonRound(Icons.Default.Settings, "Settings", onClick = onNavigateToSettings)
                }
            }

            // ── Tabs: All / Recent / Favorites ────────────────────────────────
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(FilesTab.ALL to "All", FilesTab.RECENT to "Recent", FilesTab.FAVORITES to "Favorites")
                    .forEach { (tab, label) ->
                        TabChip(label, selectedTab == tab) { selectedTab = tab }
                    }
            }

            Spacer(Modifier.height(8.dp))

            // ── File-type filter chips ────────────────────────────────────────
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FileTypeFilter.entries.forEach { filter ->
                    FilterTypeChip(
                        filter   = filter,
                        selected = selectedFilter == filter,
                        onClick  = { selectedFilter = filter }
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            // ── Stats bar ─────────────────────────────────────────────────────
            if (!isLoading && displayedFiles.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${displayedFiles.size} file${if (displayedFiles.size != 1) "s" else ""}",
                        color = currentTextSecond, fontSize = 12.sp
                    )
                    if (selectedFilter != FileTypeFilter.ALL) {
                        Spacer(Modifier.width(6.dp))
                        Text("· ${selectedFilter.label}", color = AccentBlue, fontSize = 12.sp)
                    }
                    if (sortOrder != SortOrder.DATE_DESC) {
                        Spacer(Modifier.width(6.dp))
                        Text("· ${sortOrder.label}", color = AccentBlue, fontSize = 12.sp,
                            modifier = Modifier.clickable { showSort = true })
                    }
                }
            }

            // ── Content ───────────────────────────────────────────────────────
            when {
                isLoading -> LazyColumn(Modifier.fillMaxSize()) {
                    items(7) { FileItemSkeleton() }
                }
                displayedFiles.isEmpty() -> {
                    val kind = when {
                        selectedFilter == FileTypeFilter.PDF    -> EmptyKind.PDF
                        selectedFilter == FileTypeFilter.DOCS   -> EmptyKind.DOCS
                        selectedFilter == FileTypeFilter.IMAGES -> EmptyKind.IMAGES
                        else                                    -> EmptyKind.ALL_FILES
                    }
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyState(kind = kind)
                    }
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(displayedFiles, key = { it.filePath }) { file ->
                        FileItemWithThumb(
                            file         = file,
                            onItemClick  = { onFileClick(file) },
                            onShareClick = { sharePdf(context, file) },
                            onMoreClick  = { showMoreMenuFor = file }
                        )
                    }
                }
            }
        }
    }
}

// ── Tab chip ──────────────────────────────────────────────────────────────────

@Composable
fun TabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg  by animateColorAsState(if (selected) AccentBlue else currentToolIcon, label = "tabBg")
    val txt by animateColorAsState(if (selected) Color.White else currentTextSecond, label = "tabTxt")
    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = txt, fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

// ── File-type filter chip ─────────────────────────────────────────────────────

@Composable
fun FilterTypeChip(filter: FileTypeFilter, selected: Boolean, onClick: () -> Unit) {
    val (icon, tint, bgSel) = remember(filter) {
        when (filter) {
            FileTypeFilter.ALL    -> Triple(Icons.Default.Apps,          Color(0xFF4F8EF7), Color(0xFF1A2340))
            FileTypeFilter.PDF    -> Triple(Icons.Default.PictureAsPdf,  Color(0xFFEF5350), Color(0xFF2A1010))
            FileTypeFilter.DOCS   -> Triple(Icons.Default.Description,   Color(0xFF4F8EF7), Color(0xFF1A2340))
            FileTypeFilter.SHEETS -> Triple(Icons.Default.TableChart,    Color(0xFF26C6A0), Color(0xFF0F2420))
            FileTypeFilter.SLIDES -> Triple(Icons.Default.Slideshow,     Color(0xFFFFA726), Color(0xFF2A1E0A))
            FileTypeFilter.TEXT   -> Triple(Icons.AutoMirrored.Filled.TextSnippet, Color(0xFF9C6DFF), Color(0xFF1E1530))
            FileTypeFilter.IMAGES -> Triple(Icons.Default.Image,         Color(0xFF26C6A0), Color(0xFF0F2420))
        }
    }
    val bg  by animateColorAsState(if (selected) bgSel else currentToolIcon, label = "filterBg")
    val ic  by animateColorAsState(if (selected) tint else currentTextSecond, label = "filterIc")
    val txt by animateColorAsState(if (selected) tint else currentTextSecond, label = "filterTxt")

    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(icon, null, tint = ic, modifier = Modifier.size(15.dp))
        Text(filter.label, color = txt, fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

// ── More menu item ────────────────────────────────────────────────────────────

@Composable
fun MoreMenuItem(
    icon   : ImageVector,
    label  : String,
    tint   : Color = currentText,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, color = tint, fontSize = 15.sp)
    }
}

// ── Share helper ──────────────────────────────────────────────────────────────

private fun sharePdf(context: android.content.Context, file: PdfFile) {
    try {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.provider", File(file.filePath)
        )
        context.startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, context.getString(R.string.share_pdf_via)
        ))
    } catch (_: Exception) {
        Toast.makeText(context, context.getString(R.string.could_not_share), Toast.LENGTH_SHORT).show()
    }
}
