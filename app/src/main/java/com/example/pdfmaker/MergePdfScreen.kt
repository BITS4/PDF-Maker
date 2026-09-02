package com.example.pdfmaker

import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import android.os.Environment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── Screen ────────────────────────────────────────────────────────────────────

@Composable
fun MergePdfScreen(onBack: () -> Unit, onOpenFile: (PdfFile) -> Unit) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    val bgDark  = Color(0xFF0D0D16)
    val barBg   = Color(0xFF1A1A2A)
    val cardBg  = Color(0xFF14141F)
    val textPri = Color.White
    val textSec = Color(0xFF9999BB)
    val accent  = AccentBlue
    val orange  = Color(0xFFFF7043)

    var items        by remember { mutableStateOf<List<MergeItem>>(emptyList()) }
    var state        by remember { mutableStateOf(MergeState.EMPTY) }
    var progress     by remember { mutableIntStateOf(0) }
    var progressText by remember { mutableStateOf("") }
    var resultFile   by remember { mutableStateOf<File?>(null) }
    var errorMsg     by remember { mutableStateOf("") }
    var mergeCount   by remember { mutableIntStateOf(1) }
    var outputName   by remember { mutableStateOf("merged_document_1") }
    var showRenameDialog  by remember { mutableStateOf(false) }
    var showPreMergeDialog by remember { mutableStateOf(false) }

    // Drag-to-reorder state
    var dragFromIdx  by remember { mutableIntStateOf(-1) }
    var dragToIdx    by remember { mutableIntStateOf(-1) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                val newItems = uris.mapNotNull { uri ->
                    try {
                        val name = uri.lastPathSegment
                            ?.substringAfterLast("/")?.substringAfterLast("%2F")
                            ?.removeSuffix(".pdf")?.take(40) ?: "document"
                        val sizeKb = context.contentResolver
                            .openFileDescriptor(uri, "r")?.use { it.statSize / 1024 } ?: 0L
                        val cnt   = pdfPageCount(context, uri)
                        val thumb = renderPage(context, uri, 0, 300)
                        MergeItem(uri = uri, name = name, sizeKb = sizeKb,
                                  pageCount = cnt, thumb = thumb)
                    } catch (_: Exception) { null }
                }
                withContext(Dispatchers.Main) {
                    items = items + newItems
                    state = if (items.isNotEmpty()) MergeState.READY else MergeState.EMPTY
                }
            }
        }
    }

    fun startMerge(requestedName: String = outputName) {
        if (items.size < 2) return
        state    = MergeState.MERGING
        progress = 0
        scope.launch(Dispatchers.IO) {
            try {
                val file = mergePdfs(context, items.map { it.uri }, requestedName) { p, txt ->
                    scope.launch(Dispatchers.Main) { progress = p; progressText = txt }
                }
                withContext(Dispatchers.Main) {
                    if (file != null) {
                        resultFile = file
                        val totalPages = items.sumOf { it.pageCount }
                        FileCache.prependFile(
                            PdfFile(
                                name         = file.name,
                                filePath     = file.absolutePath,
                                size         = mergeFormatSize(file.length() / 1024),
                                date         = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date()),
                                pageCount    = totalPages,
                                lastModified = file.lastModified()
                            )
                        )
                        mergeCount += 1
                        outputName = "merged_document_$mergeCount"
                        state = MergeState.DONE
                    } else {
                        errorMsg = "Merge failed. One or more files may be encrypted or corrupted."
                        state    = MergeState.ERROR
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    errorMsg = e.message ?: "Unknown error"
                    state    = MergeState.ERROR
                }
            }
        }
    }

    if (showRenameDialog) {
        MergeRenameDialog(
            initialName = outputName,
            onConfirm = { name ->
                outputName = name
                showRenameDialog = false
            },
            onDismiss = { showRenameDialog = false },
        )
    }

    if (showPreMergeDialog) {
        MergePreflightDialog(
            initialName = outputName,
            onConfirm = { name ->
                outputName = name
                showPreMergeDialog = false
                startMerge(name)
            },
            onDismiss = { showPreMergeDialog = false },
        )
    }

    Box(Modifier.fillMaxSize().background(bgDark).statusBarsPadding()) {
        Column(Modifier.fillMaxSize()) {

            // ── Top bar ───────────────────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth().background(barBg)
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = textPri)
                }
                Text("Merge PDF", color = textPri,
                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).padding(start = 4.dp))
                if (state == MergeState.READY) {
                    IconButton(onClick = { filePicker.launch(arrayOf("application/pdf")) }) {
                        Icon(Icons.Default.Add, null, tint = accent)
                    }
                }
            }

            when (state) {

                // 1. Empty
                MergeState.EMPTY -> MergeEmptyPanel(
                    onSelectFiles = { filePicker.launch(arrayOf("application/pdf")) },
                )

                // ── 2. Ready — draggable list ─────────────────────────────────
                MergeState.READY -> {
                    // Output name row
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.DriveFileRenameOutline, null,
                            tint = textSec, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("$outputName.pdf", color = textPri,
                            fontSize = 13.sp, modifier = Modifier.weight(1f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { showRenameDialog = true }) {
                            Text("Rename", color = accent, fontSize = 13.sp)
                        }
                    }

                    // Summary chip
                    val totalPages = items.sumOf { it.pageCount }
                    val totalKb    = items.sumOf { it.sizeKb }
                    Row(
                        Modifier.padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        InfoChip("${items.size} files", cardBg, textSec)
                        InfoChip("$totalPages pages", cardBg, textSec)
                        InfoChip(mergeFormatSize(totalKb), cardBg, textSec)
                    }
                    Spacer(Modifier.height(8.dp))

                    // Draggable list
                    LazyColumn(
                        Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(items, key = { _, it -> it.id }) { idx, item ->
                            val isDragging = idx == dragFromIdx
                            MergeItemCard(
                                item      = item,
                                index     = idx,
                                total     = items.size,
                                isDragging = isDragging,
                                cardBg    = cardBg,
                                textPri   = textPri,
                                textSec   = textSec,
                                accent    = accent,
                                onDelete  = {
                                    items = items.toMutableList().also { l -> l.removeAt(idx) }
                                    if (items.isEmpty()) state = MergeState.EMPTY
                                },
                                onMoveUp  = {
                                    if (idx > 0) {
                                        val l = items.toMutableList()
                                        val tmp = l[idx]; l[idx] = l[idx-1]; l[idx-1] = tmp
                                        items = l
                                    }
                                },
                                onMoveDown = {
                                    if (idx < items.size - 1) {
                                        val l = items.toMutableList()
                                        val tmp = l[idx]; l[idx] = l[idx+1]; l[idx+1] = tmp
                                        items = l
                                    }
                                }
                            )
                        }
                        // Add more button at bottom of list
                        item {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(cardBg)
                                    .border(1.dp, Color(0xFF333344), RoundedCornerShape(12.dp))
                                    .clickable { filePicker.launch(arrayOf("application/pdf")) }
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Add, null, tint = accent,
                                        modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Add more PDFs", color = accent,
                                        fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }

                    // Merge button
                    Column(
                        Modifier.fillMaxWidth().background(barBg)
                            .navigationBarsPadding().padding(16.dp)
                    ) {
                        Button(
                            onClick  = { if (items.size >= 2) showPreMergeDialog = true },
                            enabled  = items.size >= 2,
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            shape    = RoundedCornerShape(14.dp),
                            colors   = ButtonDefaults.buttonColors(containerColor = orange)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.MergeType, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (items.size < 2) "Add at least 2 PDFs" else "Merge ${items.size} PDFs",
                                fontWeight = FontWeight.Bold, fontSize = 16.sp
                            )
                        }
                    }
                }

                // ── 3. Merging ────────────────────────────────────────────────
                MergeState.MERGING -> {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        MergeSpinner(progress = progress, color = orange)
                        Spacer(Modifier.height(28.dp))
                        Text("Merging…", color = textPri,
                            fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text(progressText, color = textSec, fontSize = 13.sp)
                        Spacer(Modifier.height(6.dp))
                        Text("$progress%", color = orange,
                            fontSize = 32.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(20.dp))
                        LinearProgressIndicator(
                            progress   = { progress / 100f },
                            modifier   = Modifier.fillMaxWidth().height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color      = orange,
                            trackColor = Color(0xFF2A2A40)
                        )
                    }
                }

                // ── 4. Done ───────────────────────────────────────────────────
                MergeState.DONE -> {
                    val file = resultFile
                    val totalPages = items.sumOf { it.pageCount }
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            Modifier.size(100.dp).clip(CircleShape)
                                .background(Color(0xFF1A3020)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.CheckCircle, null,
                                tint = Color(0xFF4CAF50), modifier = Modifier.size(52.dp))
                        }
                        Spacer(Modifier.height(20.dp))
                        Text("Merged Successfully!", color = textPri,
                            fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text("$outputName.pdf", color = textSec, fontSize = 14.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(24.dp))

                        // Stats row
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                                .background(cardBg).padding(20.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            StatColumn("Files merged", "${items.size}", textSec, textPri)
                            VerticalDivider(Modifier.height(40.dp), color = Color(0xFF2A2A3A))
                            StatColumn("Total pages", "$totalPages", textSec, textPri)
                            VerticalDivider(Modifier.height(40.dp), color = Color(0xFF2A2A3A))
                            StatColumn("File size",
                                mergeFormatSize((file?.length() ?: 0) / 1024), textSec, textPri)
                        }
                        Spacer(Modifier.height(32.dp))

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Open
                            Button(
                                onClick = {
                                    if (file != null) {
                                        val totalPg = items.sumOf { it.pageCount }
                                        onOpenFile(PdfFile(
                                            name         = file.name,
                                            filePath     = file.absolutePath,
                                            size         = mergeFormatSize(file.length() / 1024),
                                            date         = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date()),
                                            pageCount    = totalPg,
                                            lastModified = file.lastModified()
                                        ))
                                    }
                                },
                                modifier = Modifier.weight(1f).height(54.dp),
                                shape    = RoundedCornerShape(14.dp),
                                colors   = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Open", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            }
                            // Share
                            Button(
                                onClick = {
                                    if (file != null) shareMergedFile(context, file)
                                },
                                modifier = Modifier.weight(1f).height(54.dp),
                                shape    = RoundedCornerShape(14.dp),
                                colors   = ButtonDefaults.buttonColors(containerColor = orange)
                            ) {
                                Icon(Icons.Default.Share, null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Share", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = {
                                items = emptyList(); resultFile = null; state = MergeState.EMPTY
                            },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape    = RoundedCornerShape(14.dp),
                            border   = androidx.compose.foundation.BorderStroke(
                                1.dp, textSec.copy(alpha = 0.4f)
                            )
                        ) {
                            Icon(Icons.Default.Add, null, tint = textSec,
                                modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Merge Another", color = textSec,
                                fontWeight = FontWeight.Medium, fontSize = 15.sp)
                        }
                    }
                }

                // ── 5. Error ──────────────────────────────────────────────────
                MergeState.ERROR -> {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            Modifier.size(90.dp).clip(CircleShape)
                                .background(Color(0xFF2A1010)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.ErrorOutline, null,
                                tint = Color(0xFFF44336), modifier = Modifier.size(46.dp))
                        }
                        Spacer(Modifier.height(18.dp))
                        Text("Merge Failed", color = textPri,
                            fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(errorMsg, color = textSec, fontSize = 13.sp,
                            textAlign = TextAlign.Center)
                        Spacer(Modifier.height(28.dp))
                        Button(
                            onClick = { state = MergeState.READY },
                            modifier = Modifier.fillMaxWidth(0.6f).height(50.dp),
                            shape    = RoundedCornerShape(14.dp),
                            colors   = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                        ) { Text("Try Again", fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }
}
