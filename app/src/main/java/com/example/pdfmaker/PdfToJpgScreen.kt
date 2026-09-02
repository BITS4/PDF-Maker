package com.example.pdfmaker

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class JpgConvertState { PICK, LOADING, PREVIEW, CONVERTING, DONE, ERROR }

@Composable
// This state-machine UI delegates validation/rendering; boundary failures are surfaced after cancellation is rethrown.
@Suppress("CyclomaticComplexMethod", "LongMethod", "TooGenericExceptionCaught")
fun PdfToJpgScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    val bgDark  = Color(0xFF0D0D16)
    val barBg   = Color(0xFF1A1A2A)
    val cardBg  = Color(0xFF14141F)
    val textPri = Color.White
    val textSec = Color(0xFF9999BB)
    val accent  = AccentBlue

    var state        by remember { mutableStateOf(JpgConvertState.PICK) }
    var pickedName   by remember { mutableStateOf("") }
    var pickedSizeKb by remember { mutableStateOf(0L) }
    var pageCount    by remember { mutableIntStateOf(0) }
    var quality      by remember { mutableStateOf(JpgQuality.HIGH) }
    var progress     by remember { mutableIntStateOf(0) }
    var progressText by remember { mutableStateOf("") }
    var resultFiles  by remember { mutableStateOf<List<File>>(emptyList()) }
    var previews     by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var errorMsg     by remember { mutableStateOf("") }
    var savedToGallery by remember { mutableStateOf(false) }
    var savingToGallery by remember { mutableStateOf(false) }
    var galleryMessage by remember { mutableStateOf<String?>(null) }
    var sharing by remember { mutableStateOf(false) }
    var shareMessage by remember { mutableStateOf<String?>(null) }
    var stagedSource by remember { mutableStateOf<StagedPdfSource?>(null) }
    var activeJob by remember { mutableStateOf<Job?>(null) }

    // Page selection (null = all pages)
    var allPages     by remember { mutableStateOf(true) }
    var pageFrom     by remember { mutableIntStateOf(1) }
    var pageTo       by remember { mutableIntStateOf(1) }

    val latestPreviews by rememberUpdatedState(previews)
    val latestSource by rememberUpdatedState(stagedSource)
    val latestJob by rememberUpdatedState(activeJob)

    DisposableEffect(Unit) {
        onDispose {
            latestJob?.cancel()
            latestSource?.close()
            BitmapOwnership.retire(latestPreviews)
        }
    }

    fun releaseSelection() {
        activeJob?.cancel()
        activeJob = null
        stagedSource?.close()
        stagedSource = null
        BitmapOwnership.retire(previews)
        previews = emptyList()
    }

    fun loadSelection(uri: Uri) {
        releaseSelection()
        state = JpgConvertState.LOADING
        errorMsg = ""
        savedToGallery = false
        galleryMessage = null
        shareMessage = null
        pickedName = PdfToJpgPolicy.displayBaseName(uri.lastPathSegment)

        activeJob = scope.launch {
            var pendingSource: StagedPdfSource? = null
            var pendingPreviews: List<Bitmap> = emptyList()
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val source = SafePdfInput.fromUri(context, uri)
                    pendingSource = source
                    loadPdfToJpgPreview(source).also { pendingPreviews = it.bitmaps }
                }
                ensureActive()
                val source = checkNotNull(pendingSource)
                stagedSource = source
                pendingSource = null
                pickedSizeKb = source.file.length() / 1024
                pageCount = loaded.pageCount
                pageFrom = 1
                pageTo = loaded.pageCount
                previews = loaded.bitmaps
                pendingPreviews = emptyList()
                state = JpgConvertState.PREVIEW
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                errorMsg = PdfToJpgPolicy.failureMessage(PdfToJpgFailureStage.LOAD, error)
                state = JpgConvertState.ERROR
            } finally {
                pendingSource?.close()
                BitmapOwnership.retire(pendingPreviews)
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) loadSelection(uri)
    }

    fun startConvert() {
        val source = stagedSource ?: run {
            errorMsg = "Choose a PDF before converting."
            state = JpgConvertState.ERROR
            return
        }
        val selection = if (allPages) {
            PageSelection.All
        } else {
            PageSelection.Range(pageFrom, pageTo)
        }
        val selectedRange = PageSelectionPolicy.contiguousRange(pageCount, selection)
        if (selectedRange == null) {
            errorMsg = "Select a valid page range."
            state = JpgConvertState.ERROR
            return
        }
        state    = JpgConvertState.CONVERTING
        progress = 0
        val from = selectedRange.first - 1
        val to = selectedRange.last - 1

        activeJob?.cancel()
        activeJob = scope.launch {
            var pendingFiles: List<File> = emptyList()
            try {
                val files = withContext(Dispatchers.IO) {
                    convertPdfToJpg(
                        context   = context,
                        source    = source,
                        baseName  = pickedName,
                        quality   = quality,
                        fromPage  = from,
                        toPage    = to
                    ) { p, txt ->
                        scope.launch(Dispatchers.Main) { progress = p; progressText = txt }
                    }.also { pendingFiles = it }
                }
                ensureActive()
                resultFiles = files
                pendingFiles = emptyList()
                stagedSource = null
                source.close()
                BitmapOwnership.retire(previews)
                previews = emptyList()
                state = if (files.isNotEmpty()) JpgConvertState.DONE else JpgConvertState.ERROR.also {
                    errorMsg = "No pages could be converted."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                errorMsg = PdfToJpgPolicy.failureMessage(PdfToJpgFailureStage.CONVERT, error)
                state = JpgConvertState.ERROR
            } finally {
                pendingFiles.forEach(File::delete)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(bgDark).statusBarsPadding()) {
        Column(Modifier.fillMaxSize()) {

            JpgTopBar(
                background = barBg,
                primaryText = textPri,
                secondaryText = textSec,
                showChange = state == JpgConvertState.PREVIEW,
                onBack = onBack,
                onChange = {
                    releaseSelection()
                    state = JpgConvertState.PICK
                },
            )

            // ── Body ──────────────────────────────────────────────────────────
            when (state) {

                // ── 1. Pick ───────────────────────────────────────────────────
                JpgConvertState.PICK -> {
                    JpgPickerPanel(
                        primaryText = textPri,
                        secondaryText = textSec,
                        onPick = { filePicker.launch(arrayOf("application/pdf")) },
                    )
                }

                JpgConvertState.LOADING -> {
                    JpgConvertingPanel(
                        progress = 0,
                        progressText = "Opening PDF safely…",
                        primaryText = textPri,
                        secondaryText = textSec,
                    )
                }

                // ── 2. Preview + settings ─────────────────────────────────────
                JpgConvertState.PREVIEW -> {
                    Column(
                        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        PdfToJpgPreviewHeader(
                            name = pickedName,
                            sizeKb = pickedSizeKb,
                            pageCount = pageCount,
                            previews = previews,
                            primaryText = textPri,
                            secondaryText = textSec,
                            cardBackground = cardBg,
                        )

                        // Page range selection
                        Text("Pages", color = textPri,
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .background(cardBg).padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(0.dp)
                        ) {
                            PageRangeTab("All pages", selected = allPages,
                                accent = accent, modifier = Modifier.weight(1f)) { allPages = true }
                            PageRangeTab("Custom range", selected = !allPages,
                                accent = accent, modifier = Modifier.weight(1f)) { allPages = false }
                        }
                        if (!allPages) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment     = Alignment.CenterVertically
                            ) {
                                PageNumberField("From", pageFrom,
                                    range = 1..pageTo, cardBg = cardBg, textPri = textPri,
                                    textSec = textSec, accent = accent) { pageFrom = it }
                                Text("–", color = textSec, fontSize = 18.sp)
                                PageNumberField("To", pageTo,
                                    range = pageFrom..pageCount, cardBg = cardBg,
                                    textPri = textPri, textSec = textSec, accent = accent) { pageTo = it }
                                Text("of $pageCount", color = textSec, fontSize = 13.sp)
                            }
                        }

                        // Quality selection
                        Text("Quality", color = textPri,
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            JpgQuality.entries.forEach { q ->
                                val sel = q == quality
                                Column(
                                    Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (sel) Color(0xFF1B2340) else cardBg)
                                        .border(
                                            if (sel) 1.5.dp else 0.dp,
                                            if (sel) accent else Color.Transparent,
                                            RoundedCornerShape(12.dp)
                                        )
                                        .clickable { quality = q }
                                        .padding(12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Box(
                                        Modifier.size(36.dp).clip(CircleShape)
                                            .background(q.color.copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Default.Image, null, tint = q.color,
                                            modifier = Modifier.size(18.dp))
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Text(q.label, color = if (sel) textPri else textSec,
                                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    Text(q.sub, color = textSec, fontSize = 10.sp,
                                        textAlign = TextAlign.Center, lineHeight = 12.sp)
                                }
                            }
                        }

                        Spacer(Modifier.weight(1f))

                        val totalPages = if (allPages) pageCount else (pageTo - pageFrom + 1).coerceAtLeast(1)
                        Button(
                            onClick  = { startConvert() },
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            shape    = RoundedCornerShape(14.dp),
                            colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF7043))
                        ) {
                            Icon(Icons.Default.Image, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Convert $totalPages ${if (totalPages == 1) "Page" else "Pages"} to JPG",
                                fontWeight = FontWeight.Bold, fontSize = 15.sp
                            )
                        }
                    }
                }

                // ── 3. Converting ─────────────────────────────────────────────
                JpgConvertState.CONVERTING -> {
                    JpgConvertingPanel(
                        progress = progress,
                        progressText = progressText,
                        primaryText = textPri,
                        secondaryText = textSec,
                    )
                }

                // ── 4. Done ───────────────────────────────────────────────────
                JpgConvertState.DONE -> {
                    JpgResultPanel(
                        files = resultFiles,
                        primaryText = textPri,
                        secondaryText = textSec,
                        status = PdfToJpgResultStatus(
                            savedToGallery,
                            savingToGallery,
                            galleryMessage,
                            sharing,
                            shareMessage,
                        ),
                        onSaveToGallery = {
                            scope.launch {
                                savingToGallery = true
                                galleryMessage = null
                                val report = withContext(Dispatchers.IO) {
                                    saveJpgsToGallery(context, resultFiles)
                                }
                                savedToGallery = report.isComplete
                                galleryMessage = report.userMessage.takeUnless { report.isComplete }
                                savingToGallery = false
                            }
                        },
                        onShareAll = {
                            if (!sharing) {
                                sharing = true
                                shareMessage = null
                                scope.launch {
                                    val shareFiles =
                                        withContext(Dispatchers.IO) {
                                            prepareJpgShareFiles(context, resultFiles)
                                        }
                                    shareMessage =
                                        when {
                                            shareFiles == null -> {
                                                PdfToJpgPolicy.shareFailureMessage(
                                                    PdfToJpgFailureStage.SHARE_PREPARE,
                                                )
                                            }

                                            !DocumentShareAdapter.share(
                                                context = context,
                                                files = shareFiles,
                                                chooserTitle =
                                                    if (shareFiles.size == 1) "Share JPG" else "Share JPG images",
                                                requestedMimeType = "image/jpeg",
                                            ) -> {
                                                PdfToJpgPolicy.shareFailureMessage(
                                                    PdfToJpgFailureStage.SHARE_LAUNCH,
                                                )
                                            }

                                            else -> {
                                                null
                                            }
                                        }
                                    sharing = false
                                }
                            }
                        },
                        onNewConversion = {
                            releaseSelection()
                            resultFiles = emptyList()
                            savedToGallery = false
                            savingToGallery = false
                            galleryMessage = null
                            sharing = false
                            shareMessage = null
                            state = JpgConvertState.PICK
                        },
                    )
                }

                // ── 5. Error ──────────────────────────────────────────────────
                JpgConvertState.ERROR -> {
                    JpgErrorPanel(
                        message = errorMsg,
                        primaryText = textPri,
                        secondaryText = textSec,
                        onRetry = {
                            state = if (stagedSource == null) JpgConvertState.PICK else JpgConvertState.PREVIEW
                        },
                    )
                }
            }
        }
    }
}
