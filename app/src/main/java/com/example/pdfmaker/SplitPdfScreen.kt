package com.example.pdfmaker

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class SplitMode  { RANGE, CUSTOM }
private enum class SplitState { PICK, CONFIGURE, SPLITTING, DONE, ERROR }

@Composable
// Compose state-machine callbacks stay colocated while staging, rendering, and output live in tested modules.
@Suppress("CyclomaticComplexMethod", "LongMethod", "TooGenericExceptionCaught")
fun SplitPdfScreen(onBack: () -> Unit, onOpenFile: (PdfFile) -> Unit = {}) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    var state         by remember { mutableStateOf(SplitState.PICK) }
    var pickedName    by remember { mutableStateOf("") }
    var pickedSize    by remember { mutableStateOf("") }
    var totalPages    by remember { mutableIntStateOf(0) }
    var thumbs        by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var splitMode     by remember { mutableStateOf(SplitMode.RANGE) }
    var rangeFrom     by remember { mutableIntStateOf(1) }
    var rangeTo       by remember { mutableIntStateOf(1) }
    var selPages      by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var outPath       by remember { mutableStateOf("") }
    var outName       by remember { mutableStateOf("") }
    var errMsg        by remember { mutableStateOf("") }
    var loadingThumbs by remember { mutableStateOf(false) }
    var stagedSource  by remember { mutableStateOf<StagedPdfSource?>(null) }
    var activeJob     by remember { mutableStateOf<Job?>(null) }

    val latestThumbs by rememberUpdatedState(thumbs)
    val latestSource by rememberUpdatedState(stagedSource)
    val latestJob by rememberUpdatedState(activeJob)

    DisposableEffect(Unit) {
        onDispose {
            val resources = latestThumbs
            val source = latestSource
            val release = {
                BitmapOwnership.retire(resources)
                source?.close()
                Unit
            }
            latestJob?.let { job ->
                job.cancel()
                job.invokeOnCompletion { release() }
            } ?: release()
        }
    }

    fun loadPdf(uri: Uri) {
        activeJob?.cancel()
        stagedSource?.close()
        stagedSource = null
        BitmapOwnership.retire(thumbs)
        thumbs = emptyList()

        lateinit var loadJob: Job
        loadJob = scope.launch(start = CoroutineStart.LAZY) {
            loadingThumbs = true
            pickedName = uri.lastPathSegment
                ?.substringAfterLast("/")?.substringAfterLast("%2F")
                ?.removeSuffix(".pdf")?.take(40) ?: "document"
            var pendingSource: StagedPdfSource? = null
            var pendingThumbs: List<Bitmap> = emptyList()
            try {
                val preview = withContext(Dispatchers.IO) {
                    SafePdfInput.fromUri(context, uri).also { pendingSource = it }.let { source ->
                        loadSplitPdfPreview(source).also { pendingThumbs = it.bitmaps }
                    }
                }
                ensureActive()
                val source = checkNotNull(pendingSource)
                stagedSource = source
                pendingSource = null
                pickedSize = FileRepository.formatSize(source.file.length())
                thumbs = preview.bitmaps
                pendingThumbs = emptyList()
                totalPages = preview.pageCount
                rangeFrom = 1
                rangeTo = preview.pageCount
                selPages = emptySet()
                state = SplitState.CONFIGURE
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                errMsg =
                    UserVisibleFailureReporter.message(
                        UserFailureStage.SPLIT_PREVIEW_LOAD,
                        error,
                    )
                state = SplitState.ERROR
            } finally {
                pendingSource?.close()
                BitmapOwnership.retire(pendingThumbs)
                if (activeJob === loadJob) {
                    activeJob = null
                    loadingThumbs = false
                }
            }
        }
        activeJob = loadJob
        loadJob.start()
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
                Text("Split PDF", color = currentText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }

            when (state) {
                SplitState.PICK -> {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center) {
                        Box(Modifier.size(100.dp).clip(CircleShape).background(Color(0xFF2A1010)),
                            contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.ContentCut, null,
                                tint = Color(0xFFEF5350), modifier = Modifier.size(52.dp))
                        }
                        Spacer(Modifier.height(24.dp))
                        Text("Split a PDF", color = currentText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text("Extract a range or custom pages into a new file",
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

                SplitState.CONFIGURE -> {
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        Surface(color = currentCard, shape = RoundedCornerShape(12.dp)) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.PictureAsPdf, null, tint = Color(0xFFEF5350), modifier = Modifier.size(36.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(pickedName, color = currentText, fontWeight = FontWeight.SemiBold,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("$totalPages pages · $pickedSize", color = currentTextSecond, fontSize = 12.sp)
                                }
                                TextButton(onClick = { picker.launch(arrayOf("application/pdf")) }) {
                                    Text("Change", color = AccentBlue, fontSize = 12.sp)
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Text("Split mode", color = currentTextSecond, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SpModeChip("Page range", splitMode == SplitMode.RANGE) {
                                splitMode = SplitMode.RANGE; selPages = emptySet()
                            }
                            SpModeChip("Custom pages", splitMode == SplitMode.CUSTOM) { splitMode = SplitMode.CUSTOM }
                        }
                        Spacer(Modifier.height(16.dp))
                        if (splitMode == SplitMode.RANGE) {
                            Text("Select range", color = currentTextSecond, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                SpSpinner("From", rangeFrom, 1, (rangeTo - 1).coerceAtLeast(1)) { rangeFrom = it }
                                Text("→", color = currentTextSecond, fontSize = 18.sp)
                                SpSpinner("To", rangeTo, (rangeFrom + 1).coerceAtMost(totalPages), totalPages) { rangeTo = it }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text("${(rangeTo - rangeFrom + 1).coerceAtLeast(1)} page(s) will be extracted",
                                color = AccentBlue, fontSize = 13.sp)
                        } else {
                            Text("Tap pages to include (${selPages.size} selected)",
                                color = currentTextSecond, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                        Spacer(Modifier.height(8.dp))
                        if (loadingThumbs) {
                            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = AccentBlue)
                            }
                        } else {
                            LazyVerticalGrid(columns = GridCells.Fixed(4), modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement   = Arrangement.spacedBy(6.dp)) {
                                itemsIndexed(thumbs) { idx, bmp ->
                                    val pn  = idx + 1
                                    val hi  = (splitMode == SplitMode.RANGE && pn in rangeFrom..rangeTo) ||
                                              (splitMode == SplitMode.CUSTOM && pn in selPages)
                                    val bc by animateColorAsState(if (hi) AccentBlue else androidx.compose.ui.graphics.Color.Transparent, label = "b")
                                    Box(Modifier.aspectRatio(0.77f).clip(RoundedCornerShape(6.dp))
                                            .border(2.dp, bc, RoundedCornerShape(6.dp))
                                            .clickable {
                                                if (splitMode == SplitMode.CUSTOM)
                                                    selPages = if (pn in selPages) selPages - pn else selPages + pn
                                            }) {
                                        Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                                .background(if (hi) AccentBlue.copy(alpha = 0.85f) else androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f))
                                                .padding(vertical = 2.dp), contentAlignment = Alignment.Center) {
                                            Text("$pn", color = androidx.compose.ui.graphics.Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        }
                                        if (hi) Icon(Icons.Default.CheckCircle, null, tint = AccentBlue,
                                            modifier = Modifier.size(18.dp).align(Alignment.TopEnd).padding(3.dp))
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        val selection = if (splitMode == SplitMode.RANGE) {
                            PageSelection.Range(rangeFrom, rangeTo)
                        } else {
                            PageSelection.Custom(selPages)
                        }
                        val selectedPages = PageSelectionPolicy.resolve(totalPages, selection)
                        val canSplit = selectedPages.isNotEmpty() && !loadingThumbs && activeJob?.isActive != true
                        Button(onClick = {
                            val source = stagedSource ?: return@Button
                            lateinit var splitJob: Job
                            splitJob = scope.launch(start = CoroutineStart.LAZY) {
                                state = SplitState.SPLITTING
                                try {
                                    val pages = selectedPages
                                    val result = withContext(Dispatchers.IO) {
                                        doSplitPdf(context, source, pages, pickedName)
                                    }
                                    ensureActive()
                                    val (resultPath, resultName) = result
                                    outPath = resultPath
                                    outName = resultName
                                    val f = File(outPath)
                                    val sz = FileRepository.formatSize(f.length())
                                    FileCache.prependFile(PdfFile(f.nameWithoutExtension, outPath, sz, "", pages.size, f.lastModified()))
                                    state = SplitState.DONE
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    errMsg =
                                        UserVisibleFailureReporter.message(
                                            UserFailureStage.PDF_SPLIT,
                                            error,
                                        )
                                    state = SplitState.ERROR
                                } finally {
                                    if (activeJob === splitJob) activeJob = null
                                }
                            }
                            activeJob = splitJob
                            splitJob.start()
                        }, enabled = canSplit,
                            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                            shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.ContentCut, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (splitMode == SplitMode.RANGE) "Split pages $rangeFrom–$rangeTo"
                                 else "Split ${selPages.size} page(s)",
                                fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                    }
                }

                SplitState.SPLITTING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = AccentBlue, modifier = Modifier.size(52.dp))
                        Spacer(Modifier.height(16.dp))
                        Text("Splitting PDF…", color = currentText, fontSize = 15.sp)
                    }
                }

                SplitState.DONE -> Column(Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Box(Modifier.size(90.dp).clip(CircleShape).background(Color(0xFF0F2420)),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF26C6A0), modifier = Modifier.size(50.dp))
                    }
                    Spacer(Modifier.height(20.dp))
                    Text("Split complete!", color = currentText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
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
                        Button(onClick = { state = SplitState.CONFIGURE; selPages = emptySet() },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                            shape = RoundedCornerShape(12.dp)) { Text("Split Another", fontWeight = FontWeight.Bold) }
                    }
                }

                SplitState.ERROR -> Column(Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Default.ErrorOutline, null, tint = BadgeRed, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(16.dp))
                    Text(errMsg, color = currentText, fontSize = 15.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { state = SplitState.PICK },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)) { Text("Try Again") }
                }
            }
        }
    }
}
@Composable private fun SpModeChip(label: String, sel: Boolean, onClick: () -> Unit) {
    val bg  by animateColorAsState(if (sel) AccentBlue else currentCard, label = "bg")
    val txt by animateColorAsState(if (sel) androidx.compose.ui.graphics.Color.White else currentTextSecond, label = "tx")
    Box(Modifier.clip(RoundedCornerShape(20.dp)).background(bg).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(label, color = txt, fontSize = 13.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable private fun SpSpinner(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = currentTextSecond, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(32.dp).clip(CircleShape)
                    .background(if (value <= min) currentCard.copy(alpha=0.4f) else currentCard)
                    .clickable(enabled = value > min) { onChange(value - 1) },
                contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Remove, null, tint = if (value<=min) currentTextSecond else currentText, modifier = Modifier.size(16.dp))
            }
            Box(Modifier.clip(RoundedCornerShape(8.dp)).background(currentCard).padding(horizontal=14.dp,vertical=8.dp),
                contentAlignment = Alignment.Center) {
                Text("$value", color = currentText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Box(Modifier.size(32.dp).clip(CircleShape)
                    .background(if (value >= max) currentCard.copy(alpha=0.4f) else currentCard)
                    .clickable(enabled = value < max) { onChange(value + 1) },
                contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Add, null, tint = if (value>=max) currentTextSecond else currentText, modifier = Modifier.size(16.dp))
            }
        }
    }
}
