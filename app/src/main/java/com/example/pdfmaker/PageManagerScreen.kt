package com.example.pdfmaker

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal data class PageState(
    val bitmap  : Bitmap,
    val rotation: Int = 0,    // 0, 90, 180, 270
    val deleted : Boolean = false
)

private enum class PmState { PICK, EDIT, SAVING, DONE, ERROR }

@Composable
fun PageManagerScreen(onBack: () -> Unit, onOpenFile: (PdfFile) -> Unit = {}) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    var pmState    by remember { mutableStateOf(PmState.PICK) }
    var pickedUri  by remember { mutableStateOf<Uri?>(null) }
    var pickedName by remember { mutableStateOf("") }
    var pages      by remember { mutableStateOf<List<PageState>>(emptyList()) }
    var loading    by remember { mutableStateOf(false) }
    var outPath    by remember { mutableStateOf("") }
    var outName    by remember { mutableStateOf("") }
    var errMsg     by remember { mutableStateOf("") }
    val latestPages by rememberUpdatedState(pages)

    DisposableEffect(Unit) {
        onDispose {
            latestPages.forEach { page ->
                if (!page.bitmap.isRecycled) page.bitmap.recycle()
            }
        }
    }

    fun loadPdf(uri: Uri) {
        scope.launch {
            loading   = true
            pickedUri = uri
            pickedName = uri.lastPathSegment
                ?.substringAfterLast("/")?.substringAfterLast("%2F")
                ?.removeSuffix(".pdf")?.take(40) ?: "document"
            val result = withContext(Dispatchers.IO) {
                runCatching { loadPageStates(context, uri) }
            }
            result.fold(
                onSuccess = { loadedPages ->
                    pages.forEach { page ->
                        if (!page.bitmap.isRecycled) page.bitmap.recycle()
                    }
                    pages = loadedPages
                    pmState = PmState.EDIT
                },
                onFailure = { error ->
                    pages.forEach { page ->
                        if (!page.bitmap.isRecycled) page.bitmap.recycle()
                    }
                    pages = emptyList()
                    errMsg = error.message ?: "Could not read this PDF"
                    pmState = PmState.ERROR
                },
            )
            loading = false
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadPdf(uri)
    }

    Box(Modifier.fillMaxSize().background(currentBg)) {
        Column(Modifier.fillMaxSize()) {
            // Top bar
            Row(Modifier.fillMaxWidth().background(currentCard).statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = currentText)
                }
                Text("Manage Pages", color = currentText, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                if (pmState == PmState.EDIT) {
                    val active = pages.filter { !it.deleted }
                    val changed = pages.any { it.rotation != 0 } || pages.any { it.deleted }
                    val deletedCount = pages.count { it.deleted }
                    if (deletedCount > 0) {
                        Text("${active.size} pages", color = currentTextSecond, fontSize = 12.sp)
                        Spacer(Modifier.width(8.dp))
                    }
                    if (changed) {
                        TextButton(
                            enabled = PageEditPolicy.canSave(pages.map { it.deleted }),
                            onClick = {
                            val uri = pickedUri ?: return@TextButton
                            scope.launch {
                                pmState = PmState.SAVING
                                val res = withContext(Dispatchers.IO) {
                                    savePages(context, uri, pages, pickedName)
                                }
                                if (res != null) {
                                    outPath = res.first; outName = res.second
                                    val f = File(outPath)
                                    val sz = f.length().let { b -> if (b < 1024*1024) "${b/1024} KB" else "${"%.1f".format(b/(1024.0*1024))} MB" }
                                    FileCache.prependFile(PdfFile(f.nameWithoutExtension, outPath, sz, "", active.size, f.lastModified()))
                                    pmState = PmState.DONE
                                } else { errMsg = "Could not save PDF"; pmState = PmState.ERROR }
                            }
                            },
                        ) {
                            Text("Save", color = AccentBlue, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            when (pmState) {
                PmState.PICK -> {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center) {
                        Box(Modifier.size(100.dp).clip(CircleShape).background(Color(0xFF1A2340)),
                            contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Pages, null, tint = Color(0xFF4F8EF7), modifier = Modifier.size(52.dp))
                        }
                        Spacer(Modifier.height(24.dp))
                        Text("Manage Pages", color = currentText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text("Rotate or delete pages from any PDF",
                            color = currentTextSecond, fontSize = 14.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp))
                        Spacer(Modifier.height(32.dp))
                        Button(onClick = { picker.launch(arrayOf("application/pdf")) },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.padding(horizontal = 32.dp).fillMaxWidth()) {
                            Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Choose PDF", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                    }
                }

                PmState.EDIT -> {
                    if (loading) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = AccentBlue)
                        }
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            // File chip
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.PictureAsPdf, null, tint = Color(0xFFEF5350),
                                    modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(pickedName, color = currentTextSecond, fontSize = 12.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                TextButton(onClick = { picker.launch(arrayOf("application/pdf")) }) {
                                    Text("Change", color = AccentBlue, fontSize = 12.sp)
                                }
                            }
                            // Hint
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                HintPill(Icons.AutoMirrored.Filled.RotateRight, "Rotate", Color(0xFF4F8EF7))
                                HintPill(Icons.Default.Delete, "Delete", BadgeRed)
                            }
                            // Page grid
                            LazyVerticalGrid(columns = GridCells.Fixed(3),
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement   = Arrangement.spacedBy(8.dp)) {
                                itemsIndexed(pages) { idx, pg ->
                                    PageCard(
                                        page      = pg,
                                        pageNum   = idx + 1,
                                        onRotateCW = {
                                            pages = pages.toMutableList().also {
                                                it[idx] = it[idx].copy(
                                                    rotation = PageEditPolicy.rotateClockwise(it[idx].rotation),
                                                )
                                            }
                                        },
                                        onRotateCCW = {
                                            pages = pages.toMutableList().also {
                                                it[idx] = it[idx].copy(
                                                    rotation = PageEditPolicy.rotateCounterClockwise(it[idx].rotation),
                                                )
                                            }
                                        },
                                        onToggleDelete = {
                                            pages = pages.toMutableList().also {
                                                it[idx] = it[idx].copy(deleted = !it[idx].deleted)
                                            }
                                        }
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }

                PmState.SAVING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = AccentBlue, modifier = Modifier.size(52.dp))
                        Spacer(Modifier.height(16.dp))
                        Text("Saving PDF…", color = currentText, fontSize = 15.sp)
                    }
                }

                PmState.DONE -> Column(Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Box(Modifier.size(90.dp).clip(CircleShape).background(Color(0xFF0F2420)),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF26C6A0), modifier = Modifier.size(50.dp))
                    }
                    Spacer(Modifier.height(20.dp))
                    Text("Saved!", color = currentText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(outName, color = currentTextSecond, fontSize = 13.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(32.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = {
                            val f = File(outPath)
                            onOpenFile(PdfFile(f.nameWithoutExtension, outPath, "", "", lastModified = f.lastModified()))
                        }, shape = RoundedCornerShape(12.dp)) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp)); Text("Open")
                        }
                        Button(onClick = { pmState = PmState.EDIT; pages = pages.map { it.copy(deleted = false, rotation = 0) } },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                            shape = RoundedCornerShape(12.dp)) {
                            Text("Edit Again", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                PmState.ERROR -> Column(Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Default.ErrorOutline, null, tint = BadgeRed, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(16.dp))
                    Text(errMsg, color = currentText, fontSize = 15.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { pmState = PmState.PICK },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)) { Text("Try Again") }
                }
            }
        }
    }
}
@Composable
private fun PageCard(
    page           : PageState,
    pageNum        : Int,
    onRotateCW     : () -> Unit,
    onRotateCCW    : () -> Unit,
    onToggleDelete : () -> Unit
) {
    val overlayAlpha by animateColorAsState(
        if (page.deleted) BadgeRed.copy(alpha = 0.55f) else androidx.compose.ui.graphics.Color.Transparent,
        label = "del")

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.aspectRatio(0.77f).clip(RoundedCornerShape(8.dp)).background(currentCard)) {
            Image(page.bitmap.asImageBitmap(), null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().rotate(page.rotation.toFloat()))
            // Delete overlay
            if (page.deleted) {
                Box(Modifier.fillMaxSize().background(overlayAlpha), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Delete, null, tint = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.size(32.dp))
                }
            }
            // Rotation badge
            if (page.rotation != 0) {
                Box(Modifier.align(Alignment.TopStart).padding(4.dp)
                        .clip(CircleShape).background(AccentBlue.copy(alpha = 0.9f))
                        .padding(4.dp)) {
                    Text("${page.rotation}°", color = androidx.compose.ui.graphics.Color.White,
                        fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        // Page number
        Text("$pageNum", color = currentTextSecond, fontSize = 11.sp)
        // Action row
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            MiniActionBtn(Icons.AutoMirrored.Filled.RotateLeft,  "CCW",    Color(0xFF4F8EF7), onRotateCCW)
            MiniActionBtn(Icons.AutoMirrored.Filled.RotateRight, "CW",     Color(0xFF4F8EF7), onRotateCW)
            MiniActionBtn(
                icon    = if (page.deleted) Icons.Default.Refresh else Icons.Default.Delete,
                desc    = if (page.deleted) "Restore" else "Delete",
                tint    = if (page.deleted) Color(0xFF26C6A0) else BadgeRed,
                onClick = onToggleDelete
            )
        }
    }
}

@Composable
private fun MiniActionBtn(
    icon   : androidx.compose.ui.graphics.vector.ImageVector,
    desc   : String,
    tint   : androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    Box(Modifier.size(28.dp).clip(CircleShape).background(currentCard.copy(alpha = 0.7f))
            .clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = tint, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun HintPill(
    icon  : androidx.compose.ui.graphics.vector.ImageVector,
    label : String,
    tint  : androidx.compose.ui.graphics.Color
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
        Text(label, color = currentTextSecond, fontSize = 11.sp)
    }
}

// ── Save pages logic ──────────────────────────────────────────────────────────
