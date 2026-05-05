package com.example.pdfmaker

import com.example.pdfmaker.R
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material3.Surface
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
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

// ── Tool helpers ──────────────────────────────────────────────────────────────

@Composable
fun toolName(name: String): String = when (name) {
    "image_to_pdf" -> stringResource(R.string.tool_image_to_pdf)
    "smart_scan"   -> stringResource(R.string.tool_smart_scan)
    "import_pdf"   -> stringResource(R.string.tool_import_pdf)
    "compress"     -> stringResource(R.string.tool_compress)
    "pdf_to_jpg"   -> stringResource(R.string.tool_pdf_to_jpg)
    "merge_pdf"    -> stringResource(R.string.tool_merge_pdf)
    "docx_to_pdf"  -> stringResource(R.string.tool_docx_to_pdf)
    "more"         -> stringResource(R.string.tool_more)
    else           -> name
}

fun toolIcon(key: String): ImageVector = when (key) {
    "image_to_pdf" -> Icons.Default.Image
    "smart_scan"   -> Icons.Default.DocumentScanner
    "import_pdf"   -> Icons.Default.Folder
    "compress"     -> Icons.Default.Compress
    "pdf_to_jpg"   -> Icons.Default.Photo
    "merge_pdf"    -> Icons.Default.CallMerge
    "docx_to_pdf"  -> Icons.Default.Description
    "more"         -> Icons.Default.Apps
    else           -> Icons.Default.PictureAsPdf
}


fun toolIconTint(key: String): Color = when (key) {
    "image_to_pdf" -> Color(0xFFEF5350)
    "smart_scan"   -> Color(0xFF4F8EF7)
    "import_pdf"   -> Color(0xFFFFA726)
    "compress"     -> Color(0xFFEF5350)
    "pdf_to_jpg"   -> Color(0xFFFFA726)
    "merge_pdf"    -> Color(0xFFFFA726)
    "docx_to_pdf"  -> Color(0xFF4F8EF7)
    "more"         -> Color(0xFF26C6A0)
    else           -> Color(0xFF4F8EF7)
}

fun toolIconBg(key: String): Color = when (key) {
    "image_to_pdf" -> Color(0xFF2A1010)
    "smart_scan"   -> Color(0xFF1A2340)
    "import_pdf"   -> Color(0xFF2A1E0A)
    "compress"     -> Color(0xFF2A1010)
    "pdf_to_jpg"   -> Color(0xFF2A1E0A)
    "merge_pdf"    -> Color(0xFF2A1E0A)
    "docx_to_pdf"  -> Color(0xFF1A2340)
    "more"         -> Color(0xFF0F2420)
    else           -> Color(0xFF1A2340)
}

val toolKeys = listOf(
    "image_to_pdf", "smart_scan", "import_pdf", "compress",
    "pdf_to_jpg",   "merge_pdf",  "docx_to_pdf", "more"
)

// ── Screen ────────────────────────────────────────────────────────────────────

@Composable
fun HomeScreen(
    activity            : MainActivity,
    onToolClick         : (String) -> Unit = {},
    onFileClick         : (PdfFile) -> Unit = {},
    onShareFile         : (PdfFile) -> Unit = {},
    onFabClick          : () -> Unit = {},
    onNavigateToFiles   : () -> Unit = {},
    onNavigateToSettings: () -> Unit = {}
) {
    val context = LocalContext.current

    val allFiles  = FileCache.files
    val isLoading = FileCache.isLoading
    var sortOrder  by remember { mutableStateOf(SortOrder.DATE_DESC) }
    var showSort   by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var favorites  by remember { mutableStateOf<Set<String>>(emptySet()) }
    var menuFile   by remember { mutableStateOf<PdfFile?>(null) }
    var pendingDeleteFile by remember { mutableStateOf<PdfFile?>(null) }
    var showRenameFor    by remember { mutableStateOf<PdfFile?>(null) }
    var renameText       by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(FileTypeFilter.ALL) }
    var fabExpanded    by remember { mutableStateOf(false) }

    LaunchedEffect(FileCache.version) { FileCache.load(context) }

    val files = remember(allFiles, sortOrder, selectedFilter) {
        allFiles.filteredBy(selectedFilter).sorted(sortOrder)
    }

    // ── Search overlay ────────────────────────────────────────────────────────
    if (showSearch) {
        SearchScreen(
            allFiles    = allFiles,
            onFileClick = { f -> showSearch = false; onFileClick(f) },
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
            text = {
                Column {
                    SortOrder.entries.forEach { order ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { sortOrder = order; showSort = false }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = sortOrder == order,
                                onClick  = null,
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
    val fileToConfirmDelete = pendingDeleteFile
    if (fileToConfirmDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDeleteFile = null },
            containerColor   = currentCard,
            title = { Text("Delete File", color = currentText) },
            text  = {
                Text(
                    "Delete \"${fileToConfirmDelete.name}\"? This cannot be undone.",
                    color = currentTextSecond
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val deleted = FileRepository.deleteFile(fileToConfirmDelete.filePath)
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
            }
        )
    }

    // ── More-options dialog ───────────────────────────────────────────────────
    val fileForMenu = menuFile
    if (fileForMenu != null) {
        val isFav = favorites.contains(fileForMenu.filePath)
        AlertDialog(
            onDismissRequest = { menuFile = null },
            containerColor   = currentCard,
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
                        menuFile = null; showRenameFor = fileForMenu
                    }
                    MoreMenuItem(icon = Icons.Default.Share, label = "Share") {
                        menuFile = null
                        onShareFile(fileForMenu)
                    }
                    val favIcon  = if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder
                    val favLabel = if (isFav) "Remove from Favorites" else "Add to Favorites"
                    MoreMenuItem(icon = favIcon, label = favLabel) {
                        favorites = if (isFav) {
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
                        val ext = old.extension
                        val newFile = java.io.File(old.parent ?: "", "$newName.$ext")
                        if (old.renameTo(newFile)) {
                            FileCache.renameFile(fileToRename.filePath, newFile.absolutePath, newName)
                            PdfThumbnailCache.invalidate(fileToRename.filePath)
                            Toast.makeText(context, "Renamed", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Rename failed", Toast.LENGTH_SHORT).show()
                        }
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

    // ── Main scaffold ─────────────────────────────────────────────────────────
    Scaffold(
        containerColor = currentBg,
        bottomBar = {
            BottomNavBar(
                selected     = 0,
                onHomeClick  = {},
                onFilesClick = onNavigateToFiles,
                onFabClick   = { fabExpanded = !fabExpanded },
                showFab      = true,
                fabExpanded  = fabExpanded,
                onAction     = { key -> fabExpanded = false; onToolClick(key) }
            )
        }
    ) { padding ->
        // Scrim — dismiss speed dial on tap outside
        if (fabExpanded) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xAA000000))
                    .clickable(
                        indication = null,
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    ) { fabExpanded = false }
            )
        }
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {

            // Header
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.home_title),
                        color = currentText, fontSize = 28.sp, fontWeight = FontWeight.Bold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButtonRound(Icons.Default.Search, "Search",
                            onClick = { showSearch = true })
                        IconButtonRound(Icons.Default.Settings, "Settings",
                            onClick = onNavigateToSettings)
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
            }

            // Section header — only when loaded
            if (!isLoading) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        color    = currentCard,
                        shape    = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(R.string.home_all),
                                color = currentText, fontWeight = FontWeight.Bold, fontSize = 18.sp
                            )
                            Text(
                                " (${files.size}${ if (selectedFilter != FileTypeFilter.ALL) " · ${selectedFilter.label}" else "" })",
                                color = currentTextSecond, fontSize = 16.sp
                            )
                            Spacer(Modifier.weight(1f))
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { showSort = true }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Sort,
                                    contentDescription = null,
                                    tint = if (sortOrder != SortOrder.DATE_DESC)
                                        AccentBlue else currentTextSecond,
                                    modifier = Modifier.size(22.dp)
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
                        kind     = EmptyKind.ALL_FILES,
                        onAction = { onToolClick("image_to_pdf") }
                    )
                }
            } else {
                items(files, key = { it.filePath }) { file ->
                    FileItemWithThumb(
                        file         = file,
                        onItemClick  = { onFileClick(file) },
                        onShareClick = { onShareFile(file) },
                        onMoreClick  = { menuFile = file }
                    )
                }
            }
        }
    }
}

// ── File item with real PDF thumbnail ─────────────────────────────────────────

@Composable
fun FileItemWithThumb(
    file        : PdfFile,
    onItemClick : () -> Unit,
    onShareClick: () -> Unit,
    onMoreClick : () -> Unit
) {
    val context = LocalContext.current
    val thumb   = rememberPdfThumbnail(context, file.filePath, 200)
    val ext     = file.filePath.substringAfterLast('.').uppercase()

    Surface(modifier = Modifier.fillMaxWidth(), color = currentCard) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onItemClick() }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isLocked = remember(file.filePath) { isLockedFile(file.filePath) }
                Box(
                    modifier = Modifier
                        .size(width = 72.dp, height = 80.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(currentThumbnail)
                ) {
                    if (isLocked) {
                        // Show lock icon for encrypted files
                        Box(
                            Modifier.fillMaxSize().background(Color(0xFF1A2340)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Lock, null,
                                tint = AccentBlue,
                                modifier = Modifier.size(32.dp))
                        }
                    } else if (thumb != null) {
                        Image(
                            thumb.asImageBitmap(), null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            fileTypeIcon(file.filePath.substringAfterLast('.', "").lowercase()),
                            null,
                            tint = fileTypeTint(file.filePath.substringAfterLast('.', "").lowercase()),
                            modifier = Modifier.size(36.dp).align(Alignment.Center)
                        )
                    }
                    Text(
                        ext, color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .background(
                                fileTypeTint(file.filePath.substringAfterLast('.', "").lowercase()),
                                RoundedCornerShape(bottomStart = 4.dp)
                            )
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        file.name, color = currentText,
                        fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(AccentBlue),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                file.pageCount.toString(), color = Color.White,
                                fontSize = 10.sp, fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(file.date, color = currentTextSecond, fontSize = 12.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(file.size, color = currentTextSecond, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Icon(
                            Icons.Default.Share, null, tint = currentTextSecond,
                            modifier = Modifier.size(22.dp).clickable { onShareClick() }
                        )
                        Spacer(Modifier.width(20.dp))
                        Icon(
                            Icons.Default.MoreVert, null, tint = currentTextSecond,
                            modifier = Modifier.size(22.dp).clickable { onMoreClick() }
                        )
                    }
                }
            }
            HorizontalDivider(
                modifier  = Modifier.padding(horizontal = 12.dp),
                thickness = 0.5.dp, color = currentDivider
            )
        }
    }
}

// ── Shared UI pieces ──────────────────────────────────────────────────────────

@Composable
fun IconButtonRound(
    icon       : ImageVector,
    contentDesc: String,
    onClick    : () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(currentToolIcon)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDesc, tint = currentText, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun ToolsGrid(onToolClick: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        toolKeys.chunked(4).forEach { rowKeys ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                rowKeys.forEach { key ->
                    Column(
                        modifier = Modifier
                            .width(80.dp)
                            .clickable { onToolClick(key) }
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(toolIconBg(key)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(toolIcon(key), null, tint = toolIconTint(key),
                                modifier = Modifier.size(28.dp))
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            toolName(key), color = currentText, fontSize = 11.sp,
                            textAlign = TextAlign.Center, maxLines = 2, lineHeight = 14.sp
                        )
                    }
                }
            }
        }
    }
}


// ── Speed dial FAB ────────────────────────────────────────────────────────────

private data class FabAction(val key: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val tint: Color, val bg: Color)

private val fabActions = listOf(
    FabAction("image_to_pdf", "Image to PDF",  Icons.Default.Image,           Color(0xFFEF5350), Color(0xFF2A1010)),
    FabAction("smart_scan",   "Smart Scan",    Icons.Default.DocumentScanner, Color(0xFF4F8EF7), Color(0xFF1A2340)),
    FabAction("import_pdf",   "Import PDF",    Icons.Default.FolderOpen,      Color(0xFFFFA726), Color(0xFF2A1E0A)),
    FabAction("merge_pdf",    "Merge PDF",     Icons.Default.MergeType,       Color(0xFFFFA726), Color(0xFF2A1E0A)),
    FabAction("docx_to_pdf",  "Docx to PDF",   Icons.Default.Description,     Color(0xFF4F8EF7), Color(0xFF1A2340)),
)

@Composable
fun SpeedDialFab(
    expanded  : Boolean,
    onToggle  : () -> Unit,
    onAction  : (String) -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier            = Modifier.padding(bottom = 6.dp)
    ) {
        // Mini action buttons (shown when expanded)
        fabActions.forEach { action ->
            AnimatedVisibility(
                visible = expanded,
                enter   = fadeIn() + scaleIn(),
                exit    = fadeOut() + scaleOut()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Label pill
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF1E1E2E))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(action.label, color = Color.White, fontSize = 12.sp,
                            fontWeight = FontWeight.Medium)
                    }
                    // Mini FAB
                    Box(
                        Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(action.bg)
                            .clickable { onAction(action.key); onToggle() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(action.icon, null, tint = action.tint,
                            modifier = Modifier.size(22.dp))
                    }
                }
            }
        }

        // Main FAB
        val rotation by animateFloatAsState(
            targetValue = if (expanded) 45f else 0f, label = "fabRot"
        )
        FloatingActionButton(
            onClick        = onToggle,
            containerColor = AccentBlue,
            contentColor   = Color.White,
            shape          = CircleShape,
            modifier       = Modifier.size(56.dp)
        ) {
            Icon(Icons.Default.Add, null,
                modifier = Modifier.size(28.dp).rotate(rotation))
        }
    }
}

// ── File type icon / tint helpers ─────────────────────────────────────────────

fun fileTypeIcon(ext: String): androidx.compose.ui.graphics.vector.ImageVector = when (ext) {
    "pdf"             -> Icons.Default.PictureAsPdf
    "doc", "docx"     -> Icons.Default.Description
    "xls", "xlsx"     -> Icons.Default.TableChart
    "ppt", "pptx"     -> Icons.Default.Slideshow
    "csv", "tsv"      -> Icons.Default.TableRows
    "txt", "md", "log"-> Icons.Default.TextSnippet
    "jpg", "jpeg",
    "png", "webp",
    "bmp", "gif"      -> Icons.Default.Image
    else              -> Icons.Default.InsertDriveFile
}

fun fileTypeTint(ext: String): Color = when (ext) {
    "pdf"             -> Color(0xFFEF5350)
    "doc", "docx"     -> Color(0xFF4F8EF7)
    "xls", "xlsx"     -> Color(0xFF26C6A0)
    "ppt", "pptx"     -> Color(0xFFFFA726)
    "csv", "tsv"      -> Color(0xFF9C6DFF)
    "txt", "md", "log"-> Color(0xFF8888AA)
    "jpg", "jpeg",
    "png", "webp",
    "bmp", "gif"      -> Color(0xFF26C6A0)
    else              -> Color(0xFF8888AA)
}

// Legacy alias keeps FilesScreen and SearchScreen compiling
@Composable
fun FileItem(
    file        : PdfFile,
    onItemClick : () -> Unit,
    onShareClick: () -> Unit,
    onMoreClick : () -> Unit
) = FileItemWithThumb(file, onItemClick, onShareClick, onMoreClick)

@Composable
fun BottomNavBar(
    selected    : Int,
    onHomeClick : () -> Unit,
    onFilesClick: () -> Unit,
    onFabClick  : () -> Unit,
    showFab     : Boolean = true,
    fabExpanded : Boolean = false,
    onAction    : (String) -> Unit = {}
) {
    // Wrap in a Box so speed-dial items can float above the bar
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {

        // ── Speed dial items (above bar, only on Home) ────────────────────────
        if (showFab) {
            AnimatedVisibility(
                visible = fabExpanded,
                enter   = fadeIn() + slideInVertically { it / 2 },
                exit    = fadeOut() + slideOutVertically { it / 2 },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 72.dp)   // sits just above the bar
            ) {
                Surface(
                    shape         = RoundedCornerShape(16.dp),
                    color         = Color(0xFF1A1A2E),
                    tonalElevation = 8.dp,
                    shadowElevation = 8.dp,
                    modifier      = Modifier.padding(horizontal = 24.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 12.dp, horizontal = 16.dp),
                        verticalArrangement   = Arrangement.spacedBy(4.dp),
                        horizontalAlignment   = Alignment.End
                    ) {
                        fabActions.forEach { action ->
                            Row(
                                verticalAlignment     = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onAction(action.key); onFabClick() }
                                    .padding(vertical = 8.dp, horizontal = 4.dp)
                            ) {
                                Box(
                                    Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
                                        .background(action.bg),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(action.icon, null, tint = action.tint,
                                        modifier = Modifier.size(20.dp))
                                }
                                Text(action.label, color = Color.White, fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }

        // ── Navigation bar ────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(currentCard)
                .navigationBarsPadding()
                .height(65.dp)
        ) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight().clickable { onHomeClick() },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.Home, null,
                        tint = if (selected == 0) AccentBlue else currentTextSecond,
                        modifier = Modifier.size(24.dp))
                    Text(stringResource(R.string.nav_home),
                        color = if (selected == 0) AccentBlue else currentTextSecond,
                        fontSize = 11.sp)
                }
                Spacer(Modifier.weight(1f))
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight().clickable { onFilesClick() },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.Description, null,
                        tint = if (selected == 1) AccentBlue else currentTextSecond,
                        modifier = Modifier.size(24.dp))
                    Text(stringResource(R.string.nav_files),
                        color = if (selected == 1) AccentBlue else currentTextSecond,
                        fontSize = 11.sp)
                }
            }

            // FAB — centered, lifted above the bar
            if (showFab) {
                val rotation by animateFloatAsState(
                    targetValue = if (fabExpanded) 45f else 0f, label = "fabRot"
                )
                FloatingActionButton(
                    onClick        = onFabClick,
                    modifier       = Modifier
                        .align(Alignment.Center)
                        .offset(y = (-10).dp)
                        .size(56.dp),
                    containerColor = AccentBlue,
                    contentColor   = Color.White,
                    shape          = CircleShape,
                    elevation      = FloatingActionButtonDefaults.elevation(
                        defaultElevation  = 4.dp,
                        pressedElevation  = 8.dp
                    )
                ) {
                    Icon(Icons.Default.Add, null,
                        modifier = Modifier.size(28.dp).rotate(rotation))
                }
            }
        }
    }
}
