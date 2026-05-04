package com.tajapps.pdfmaker

import android.content.Context
import android.content.Intent
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.webkit.MimeTypeMap
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.util.zip.ZipInputStream

// ── File type detection ────────────────────────────────────────────────────────

private enum class FileKind {
    PDF, DOCX, XLSX, CSV, TXT, IMAGE, PPTX, UNSUPPORTED
}

private fun detectKind(file: PdfFile): FileKind {
    // filePath always has extension; name may be stripped by FileRepository
    val ext = file.filePath.substringAfterLast('.', "").lowercase()
        .ifEmpty { file.name.substringAfterLast('.', "").lowercase() }
    return when (ext) {
        "pdf"                      -> FileKind.PDF
        "docx", "doc"              -> FileKind.DOCX
        "xlsx", "xls"              -> FileKind.XLSX
        "pptx", "ppt"              -> FileKind.PPTX
        "csv", "tsv"               -> FileKind.CSV
        "txt", "md", "log", "text" -> FileKind.TXT
        "jpg", "jpeg", "png",
        "gif", "webp", "bmp"       -> FileKind.IMAGE
        else                       -> FileKind.UNSUPPORTED
    }
}

// ── Universal viewer ──────────────────────────────────────────────────────────

@Composable
fun PdfViewerScreen(
    file   : PdfFile,
    onBack : () -> Unit,
    onShare: () -> Unit = {}
) {
    val context       = LocalContext.current
    val scope         = rememberCoroutineScope()
    val screenWidth   = LocalConfiguration.current.screenWidthDp
    val kind          = remember(file.filePath) { detectKind(file) }

    var pages         by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var isLoading     by remember { mutableStateOf(true) }
    var loadedCount   by remember { mutableIntStateOf(0) }
    var totalPages    by remember { mutableIntStateOf(0) }
    var errorMsg      by remember { mutableStateOf<String?>(null) }
    var showBars      by remember { mutableStateOf(true) }
    var scale         by remember { mutableFloatStateOf(1f) }
    var offset        by remember { mutableStateOf(Offset.Zero) }
    val listState     = rememberLazyListState()

    val currentPage = remember(listState.firstVisibleItemIndex) {
        listState.firstVisibleItemIndex + 1
    }

    // ── Locked PDF gate ───────────────────────────────────────────────────────
    var isLocked       by remember(file.filePath) { mutableStateOf(isLockedPdf(file.filePath)) }
    var unlockPassword by remember { mutableStateOf("") }
    var unlockError    by remember { mutableStateOf(false) }
    var showUnlockPass by remember { mutableStateOf(false) }
    // Temp decrypted file path (deleted when screen leaves)
    var decryptedPath  by remember { mutableStateOf<String?>(null) }
    val viewFile       = remember(decryptedPath, file) {
        decryptedPath?.let { file.copy(filePath = it) } ?: file
    }

    DisposableEffect(file.filePath) {
        onDispose {
            decryptedPath?.let { java.io.File(it).delete() }
        }
    }

    // ── Locked PDF overlay ────────────────────────────────────────────────────
    if (isLocked) {
        Box(Modifier.fillMaxSize().background(currentBg), contentAlignment = Alignment.Center) {
            Column(
                Modifier.fillMaxWidth().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(Modifier.size(90.dp).clip(androidx.compose.foundation.shape.CircleShape)
                        .background(androidx.compose.ui.graphics.Color(0xFF1A2340)),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Lock, null, tint = AccentBlue,
                        modifier = Modifier.size(48.dp))
                }
                Spacer(Modifier.height(20.dp))
                Text("Password Protected", color = currentText, fontSize = 18.sp,
                    fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("Enter the password to open this file",
                    color = currentTextSecond, fontSize = 13.sp)
                Spacer(Modifier.height(24.dp))
                OutlinedTextField(
                    value = unlockPassword,
                    onValueChange = { unlockPassword = it; unlockError = false },
                    label = { Text("Password") },
                    singleLine = true,
                    isError = unlockError,
                    visualTransformation = if (showUnlockPass) VisualTransformation.None
                                          else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        Icon(if (showUnlockPass) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            null, tint = currentTextSecond,
                            modifier = Modifier.clickable { showUnlockPass = !showUnlockPass })
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = AccentBlue,
                        unfocusedBorderColor = currentTextSecond,
                        focusedTextColor     = currentText,
                        unfocusedTextColor   = currentText,
                        focusedLabelColor    = AccentBlue,
                        unfocusedLabelColor  = currentTextSecond,
                        errorBorderColor     = BadgeRed
                    )
                )
                if (unlockError) {
                    Spacer(Modifier.height(4.dp))
                    Text("Wrong password", color = BadgeRed, fontSize = 12.sp)
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onBack,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) {
                        Text("Back")
                    }
                    Button(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val encrypted = java.io.File(file.filePath).readBytes()
                                    val decrypted = decryptPdf(encrypted, unlockPassword)
                                    if (decrypted != null) {
                                        val tmp = java.io.File(context.cacheDir, "unlock_${System.currentTimeMillis()}.pdf")
                                        tmp.writeBytes(decrypted)
                                        withContext(Dispatchers.Main) {
                                            decryptedPath = tmp.absolutePath
                                            isLocked      = false
                                        }
                                    } else {
                                        withContext(Dispatchers.Main) { unlockError = true }
                                    }
                                } catch (_: Exception) {
                                    withContext(Dispatchers.Main) { unlockError = true }
                                }
                            }
                        },
                        enabled = unlockPassword.isNotEmpty(),
                        colors  = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                        shape   = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.LockOpen, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Open", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        return
    }

    // ── Progressive page stream ───────────────────────────────────────────────
    LaunchedEffect(file.filePath) {
        isLoading   = true
        errorMsg    = null
        pages       = emptyList()
        loadedCount = 0
        totalPages  = 0

        if (kind == FileKind.UNSUPPORTED) {
            isLoading = false
            return@LaunchedEffect
        }

        val targetW = (screenWidth * context.resources.displayMetrics.density * 2f).toInt()
            .coerceAtLeast(1080)

        val pageFlow: Flow<Bitmap> = pageStreamForFile(context, viewFile, kind, targetW)

        pageFlow.collect { bmp ->
            loadedCount++
            totalPages = loadedCount   // grows as pages arrive
            pages = pages + bmp
        }
        isLoading = false
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF1A1A1A))) {

        when {
            // ── Unsupported ───────────────────────────────────────────────────
            kind == FileKind.UNSUPPORTED -> {
                UnsupportedView(file,
                    modifier = Modifier.align(Alignment.Center))
            }

            // ── Error ─────────────────────────────────────────────────────────
            errorMsg != null -> {
                Column(
                    Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.ErrorOutline, null,
                        tint = BadgeRed, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(16.dp))
                    Text(errorMsg ?: "", color = Color.White, fontSize = 14.sp,
                        textAlign = TextAlign.Center)
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = onBack,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                    ) { Text("Go Back") }
                }
            }

            // ── Pages (show as they arrive) ───────────────────────────────────
            else -> {
                LazyColumn(
                    state          = listState,
                    modifier       = Modifier
                        .fillMaxSize()
                        .padding(top = 64.dp, bottom = 64.dp)
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(0.5f, 4f)
                                offset = if (scale > 1f) offset + pan else Offset.Zero
                            }
                        },
                    contentPadding  = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(pages) { idx, bmp ->
                        Image(
                            bitmap       = bmp.asImageBitmap(),
                            contentDescription = "Page ${idx + 1}",
                            contentScale = ContentScale.FillWidth,
                            modifier     = Modifier
                                .fillMaxWidth()
                                .graphicsLayer(
                                    scaleX       = scale,
                                    scaleY       = scale,
                                    translationX = if (scale > 1f) offset.x else 0f,
                                    translationY = if (scale > 1f) offset.y else 0f
                                )
                                .clickable { showBars = !showBars }
                        )
                    }

                    // ── Loading skeleton shown while more pages arrive ─────────
                    if (isLoading) {
                        item {
                            PageLoadingPlaceholder()
                        }
                    }
                }
            }
        }

        // ── Top bar ───────────────────────────────────────────────────────────
        AnimatedVisibility(
            visible = showBars || isLoading || errorMsg != null,
            enter   = fadeIn() + slideInVertically(),
            exit    = fadeOut() + slideOutVertically()
        ) {
            Box(
                Modifier.fillMaxWidth()
                    .background(Color(0xCC121218))
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(38.dp).clip(CircleShape)
                            .background(BgToolIcon).clickable { onBack() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null,
                            tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    // File type badge
                    FileKindBadge(kind)
                    Spacer(Modifier.width(8.dp))
                    Text(file.name, color = Color.White, fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    // Progress indicator while loading
                    if (isLoading && pages.isNotEmpty()) {
                        Text("$loadedCount pages…", color = TextSecond, fontSize = 11.sp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Box(
                        Modifier.size(38.dp).clip(CircleShape)
                            .background(BgToolIcon).clickable { onShare() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Share, null,
                            tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        // ── Bottom thumbnail strip + page counter ─────────────────────────
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
        AnimatedVisibility(
            visible  = showBars && pages.isNotEmpty(),
            enter    = fadeIn() + slideInVertically { it },
            exit     = fadeOut() + slideOutVertically { it }
        ) {
            val thumbListState = rememberLazyListState()
            LaunchedEffect(currentPage) {
                if (pages.size > 1)
                    thumbListState.animateScrollToItem((currentPage - 1).coerceIn(0, pages.size - 1))
            }
            Column(Modifier.fillMaxWidth().background(Color(0xEE0D0D14)).navigationBarsPadding()) {
                // Thumbnail strip — only when multiple pages
                if (pages.size > 1) {
                    LazyRow(
                        state                 = thumbListState,
                        modifier              = Modifier.fillMaxWidth().height(72.dp).padding(vertical = 6.dp),
                        contentPadding        = PaddingValues(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        itemsIndexed(pages) { idx, bmp ->
                            val isActive = idx + 1 == currentPage
                            Box(
                                Modifier
                                    .width(46.dp).fillMaxHeight()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isActive) AccentBlue.copy(alpha = 0.25f) else Color(0xFF1E1E2E))
                                    .then(if (isActive) Modifier.border(2.dp, AccentBlue, RoundedCornerShape(6.dp)) else Modifier)
                                    .clickable { scope.launch { listState.animateScrollToItem(idx) } },
                                contentAlignment = Alignment.Center
                            ) {
                                androidx.compose.foundation.Image(
                                    bmp.asImageBitmap(), null,
                                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize().padding(2.dp)
                                )
                                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                        .background(Color(0xAA000000)).padding(vertical = 1.dp),
                                    contentAlignment = Alignment.Center) {
                                    Text("${idx+1}",
                                        color = if (isActive) AccentBlue else Color.White,
                                        fontSize = 8.sp,
                                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal)
                                }
                            }
                        }
                    }
                    Divider(color = Color(0xFF2A2A3A), thickness = 0.5.dp)
                }
                // Page counter row
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton(onClick = {
                        if (currentPage > 1) scope.launch { listState.animateScrollToItem(currentPage - 2) }
                    }, enabled = currentPage > 1) {
                        Icon(Icons.Default.KeyboardArrowUp, null,
                            tint = if (currentPage > 1) Color.White else TextSecond)
                    }
                    Box(Modifier.clip(RoundedCornerShape(20.dp)).background(BgToolIcon)
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center) {
                        val countLabel = if (isLoading) "$currentPage / $loadedCount+" else "$currentPage / $totalPages"
                        Text(countLabel, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                    IconButton(onClick = {
                        if (currentPage < pages.size) scope.launch { listState.animateScrollToItem(currentPage) }
                    }, enabled = currentPage < pages.size) {
                        Icon(Icons.Default.KeyboardArrowDown, null,
                            tint = if (currentPage < pages.size) Color.White else TextSecond)
                    }
                }
            }
        }
        } // end wrapper Box

        // ── Zoom reset ────────────────────────────────────────────────────────
        AnimatedVisibility(
            visible  = scale > 1.1f,
            enter    = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 80.dp)
        ) {
            FloatingActionButton(onClick = { scale = 1f; offset = Offset.Zero },
                containerColor = BgToolIcon, contentColor = Color.White,
                modifier = Modifier.size(42.dp), shape = CircleShape
            ) { Icon(Icons.Default.ZoomOut, null, modifier = Modifier.size(20.dp)) }
        }
    }
}

// ── Animated loading placeholder ──────────────────────────────────────────────

@Composable
private fun PageLoadingPlaceholder() {
    val inf = rememberInfiniteTransition(label = "shimmer")
    val alpha by inf.animateFloat(
        initialValue  = 0.15f,
        targetValue   = 0.35f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label         = "alpha"
    )
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(0.77f)     // A4 ratio
            .padding(horizontal = 2.dp)
            .background(Color.White.copy(alpha = alpha), RoundedCornerShape(4.dp))
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(6) { i ->
                Box(
                    Modifier
                        .fillMaxWidth(if (i % 3 == 2) 0.6f else 1f)
                        .height(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(alpha = alpha * 1.5f))
                )
            }
        }
    }
}

// ── File kind badge ───────────────────────────────────────────────────────────

@Composable
private fun FileKindBadge(kind: FileKind) {
    val (label, color) = when (kind) {
        FileKind.PDF         -> "PDF"   to Color(0xFFE53935)
        FileKind.DOCX        -> "DOCX"  to Color(0xFF1565C0)
        FileKind.XLSX        -> "XLSX"  to Color(0xFF2E7D32)
        FileKind.PPTX        -> "PPTX"  to Color(0xFFE65100)
        FileKind.CSV         -> "CSV"   to Color(0xFF6A1B9A)
        FileKind.TXT         -> "TXT"   to Color(0xFF37474F)
        FileKind.IMAGE       -> "IMG"   to Color(0xFF00838F)
        FileKind.UNSUPPORTED -> "?"     to Color(0xFF555566)
    }
    Box(
        Modifier.clip(RoundedCornerShape(5.dp)).background(color)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(label, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

// ── Unsupported file view ─────────────────────────────────────────────────────

@Composable
private fun UnsupportedView(file: PdfFile, modifier: Modifier) {
    val context = LocalContext.current
    val ext = file.name.substringAfterLast('.', "?").uppercase()
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(90.dp).clip(CircleShape).background(Color(0xFF252535)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null,
                tint = TextSecond, modifier = Modifier.size(46.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(".$ext files can't be previewed", color = Color.White,
            fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("Open with another app to view this file.",
            color = TextSecond, fontSize = 13.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { openWithExternalApp(context, file) },
            colors  = ButtonDefaults.buttonColors(containerColor = AccentBlue),
            shape   = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Open with…", fontWeight = FontWeight.Bold)
        }
    }
}

private fun openWithExternalApp(context: Context, file: PdfFile) {
    try {
        val f   = File(file.filePath)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", f)
        val ext = file.name.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        context.startActivity(Intent.createChooser(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Open with"
        ))
    } catch (_: Exception) {}
}

// ── Page stream router ────────────────────────────────────────────────────────

private fun pageStreamForFile(
    context : Context,
    file    : PdfFile,
    kind    : FileKind,
    targetW : Int
): Flow<Bitmap> = flow {
    val f = File(file.filePath)
    if (!f.exists()) return@flow

    when (kind) {
        FileKind.PDF   -> emitPdfPages(f, targetW)
        FileKind.IMAGE -> emitImagePage(f, targetW)
        FileKind.TXT   -> emitTextPages(f.readText(), targetW)
        FileKind.CSV   -> emitCsvPages(f, targetW)
        FileKind.DOCX  -> emitDocxPages(f, targetW)
        FileKind.XLSX  -> emitXlsxPages(f, targetW)
        FileKind.PPTX  -> emitPptxPages(f, targetW)
        else           -> { /* UNSUPPORTED — handled above */ }
    }
}.flowOn(Dispatchers.IO)

// Suspend emit wrapper
private suspend fun kotlinx.coroutines.flow.FlowCollector<Bitmap>.emitPdfPages(f: File, w: Int) {
    val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    val rdr = PdfRenderer(pfd)
    for (i in 0 until rdr.pageCount) {
        val page = rdr.openPage(i)
        val scale = w.toFloat() / page.width.toFloat()
        val h     = (page.height * scale).toInt().coerceAtLeast(1)
        val bmp   = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(AColor.WHITE)
        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()
        emit(bmp)
    }
    rdr.close(); pfd.close()
}

private suspend fun kotlinx.coroutines.flow.FlowCollector<Bitmap>.emitImagePage(f: File, w: Int) {
    val src = BitmapFactory.decodeFile(f.absolutePath) ?: return
    val h   = (w.toFloat() / src.width * src.height).toInt().coerceAtLeast(1)
    val bmp = Bitmap.createScaledBitmap(src, w, h, true)
    if (bmp !== src) src.recycle()
    emit(bmp)
}

// ── TXT renderer ─────────────────────────────────────────────────────────────

private suspend fun kotlinx.coroutines.flow.FlowCollector<Bitmap>.emitTextPages(
    text: String, w: Int
) {
    val pageH   = (w * 1.414f).toInt()   // A4 ratio
    val margin  = (w * 0.08f).toInt()
    val cW      = w - margin * 2
    val paint   = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color    = AColor.BLACK
        textSize = w * 0.022f
    }
    val sl = buildStaticLayout(text, paint, cW)
    var lineIdx = 0
    val lineCount = sl.lineCount

    while (lineIdx < lineCount) {
        val bmp    = Bitmap.createBitmap(w, pageH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(AColor.WHITE)
        var y      = margin.toFloat()

        while (lineIdx < lineCount && y + sl.getLineBottom(lineIdx) - sl.getLineTop(lineIdx) < pageH - margin) {
            val lineH = sl.getLineBottom(lineIdx) - sl.getLineTop(lineIdx)
            canvas.save()
            canvas.translate(margin.toFloat(), y - sl.getLineTop(lineIdx))
            sl.draw(canvas)
            canvas.restore()
            y        += lineH
            lineIdx++
        }
        emit(bmp)
    }
}

// ── CSV renderer ──────────────────────────────────────────────────────────────

private suspend fun kotlinx.coroutines.flow.FlowCollector<Bitmap>.emitCsvPages(
    file: File, w: Int
) {
    val lines = file.readLines()
    if (lines.isEmpty()) return
    val sep    = if (file.name.endsWith(".tsv")) '\t' else ','
    val rows   = lines.map { it.split(sep) }
    val colCount = rows.maxOf { it.size }.coerceAtLeast(1)
    emitTablePages(rows, colCount, w, isHeader = true)
}

// ── XLSX renderer (parses xl/worksheets/sheet1.xml) ───────────────────────────

private suspend fun kotlinx.coroutines.flow.FlowCollector<Bitmap>.emitXlsxPages(
    file: File, w: Int
) {
    val rows    = mutableListOf<List<String>>()
    val strings = mutableListOf<String>()   // shared strings

    ZipInputStream(file.inputStream()).use { zis ->
        var entry = zis.nextEntry
        while (entry != null) {
            when (entry.name) {
                "xl/sharedStrings.xml" -> parseSharedStrings(zis.bufferedReader().readText(), strings)
                "xl/worksheets/sheet1.xml" -> parseXlsxSheet(zis.bufferedReader().readText(), strings, rows)
            }
            zis.closeEntry()
            entry = zis.nextEntry
        }
    }
    if (rows.isEmpty()) return
    val colCount = rows.maxOf { it.size }.coerceAtLeast(1)
    emitTablePages(rows, colCount, w, isHeader = true)
}

private fun parseSharedStrings(xml: String, out: MutableList<String>) {
    try {
        val p = XmlPullParserFactory.newInstance().newPullParser().also { it.setInput(xml.reader()) }
        var inT = false; val sb = StringBuilder()
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> if (p.name == "t") { inT = true; sb.clear() }
                XmlPullParser.TEXT      -> if (inT) sb.append(p.text)
                XmlPullParser.END_TAG   -> if (p.name == "t") { out.add(sb.toString()); inT = false }
            }
            ev = p.next()
        }
    } catch (_: Exception) {}
}

private fun parseXlsxSheet(
    xml: String, strings: List<String>, rows: MutableList<List<String>>
) {
    try {
        val p = XmlPullParserFactory.newInstance().newPullParser().also { it.setInput(xml.reader()) }
        var row = mutableListOf<String>()
        var inV = false; var cellType = ""; val sb = StringBuilder()
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> row = mutableListOf()
                    "c"   -> { cellType = p.getAttributeValue(null, "t") ?: ""; inV = false }
                    "v", "t" -> { inV = true; sb.clear() }
                }
                XmlPullParser.TEXT -> if (inV) sb.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "v", "t" -> {
                        val raw = sb.toString()
                        val cell = if (cellType == "s") strings.getOrElse(raw.toIntOrNull() ?: -1) { raw } else raw
                        row.add(cell)
                        inV = false
                    }
                    "row" -> if (row.isNotEmpty()) rows.add(row.toList())
                }
            }
            ev = p.next()
        }
    } catch (_: Exception) {}
}

// ── DOCX renderer (reuses parsing from DocxToPdfScreen, emits bitmaps) ────────

private suspend fun kotlinx.coroutines.flow.FlowCollector<Bitmap>.emitDocxPages(
    file: File, w: Int
) {
    val pageH   = (w * 1.414f).toInt()
    val margin  = (w * 0.07f).toInt()
    val cW      = w - margin * 2

    val xml     = StringBuilder()
    val media   = mutableMapOf<String, ByteArray>()
    val relsMap = mutableMapOf<String, String>()

    ZipInputStream(file.inputStream()).use { zis ->
        var entry = zis.nextEntry
        while (entry != null) {
            when {
                entry.name == "word/document.xml"              -> xml.append(zis.bufferedReader().readText())
                entry.name == "word/_rels/document.xml.rels"   -> parseViewerRels(zis.bufferedReader().readText(), relsMap)
                entry.name.startsWith("word/media/")           -> media[entry.name.substringAfterLast("/")] = zis.readBytes()
            }
            zis.closeEntry(); entry = zis.nextEntry
        }
    }
    if (xml.isEmpty()) return

    val blocks = parseViewerDocXml(xml.toString(), relsMap)

    var bmp    = Bitmap.createBitmap(w, pageH, Bitmap.Config.ARGB_8888).also { Canvas(it).drawColor(AColor.WHITE) }
    var canvas = Canvas(bmp)
    var curY   = margin.toFloat()

    suspend fun flush() { emit(bmp); bmp = Bitmap.createBitmap(w, pageH, Bitmap.Config.ARGB_8888).also { Canvas(it).drawColor(AColor.WHITE) }; canvas = Canvas(bmp); curY = margin.toFloat() }

    for (block in blocks) {
        when (block) {
            is DocBlock.PageBreak -> flush()
            is DocBlock.Paragraph -> {
                if (block.runs.isEmpty()) { curY += w * 0.015f; continue }
                val sb = android.text.SpannableStringBuilder()
                for (run in block.runs) {
                    val s = sb.length; sb.append(run.text); val e = sb.length
                    if (run.bold)   sb.setSpan(android.text.style.StyleSpan(Typeface.BOLD),   s, e, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (run.italic) sb.setSpan(android.text.style.StyleSpan(Typeface.ITALIC), s, e, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                val fs = when (block.headingLevel) { 1 -> w*0.045f; 2 -> w*0.034f; 3 -> w*0.028f; else -> (block.runs.firstOrNull()?.fontSize ?: 11f) / 72f * 96f }
                val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = AColor.BLACK; textSize = fs; if (block.headingLevel > 0) typeface = Typeface.DEFAULT_BOLD }
                val sl = buildStaticLayout(sb, tp, cW)
                val bH = sl.height.toFloat() + w * 0.01f
                if (curY + bH > pageH - margin) flush()
                canvas.save(); canvas.translate(margin.toFloat(), curY); sl.draw(canvas); canvas.restore()
                curY += bH + if (block.headingLevel > 0) w*0.008f else w*0.003f
            }
            is DocBlock.ImageBlock -> {
                val bytes = media[block.name] ?: continue
                val src   = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                val scale = (cW.toFloat() / src.width).coerceAtMost(1f)
                val dW    = (src.width * scale); val dH = (src.height * scale)
                if (curY + dH > pageH - margin) flush()
                canvas.drawBitmap(src, null, android.graphics.RectF(margin.toFloat(), curY, margin + dW, curY + dH), null)
                src.recycle(); curY += dH + w * 0.012f
            }
        }
    }
    if (curY > margin + 10) emit(bmp) else bmp.recycle()
}

// ── PPTX renderer (renders each slide as a bitmap) ────────────────────────────

private suspend fun kotlinx.coroutines.flow.FlowCollector<Bitmap>.emitPptxPages(
    file: File, w: Int
) {
    // Collect slides: ppt/slides/slide1.xml, slide2.xml…
    val slideXmls  = sortedMapOf<Int, String>()
    val slideMedia = mutableMapOf<String, ByteArray>()
    val slideRels  = mutableMapOf<Int, MutableMap<String, String>>()

    ZipInputStream(file.inputStream()).use { zis ->
        var entry = zis.nextEntry
        while (entry != null) {
            val n = entry.name
            val slideNum = Regex("ppt/slides/slide(\\d+)\\.xml").find(n)?.groupValues?.get(1)?.toIntOrNull()
            val relsNum  = Regex("ppt/slides/_rels/slide(\\d+)\\.xml\\.rels").find(n)?.groupValues?.get(1)?.toIntOrNull()
            when {
                slideNum != null              -> slideXmls[slideNum] = zis.bufferedReader().readText()
                relsNum  != null              -> {
                    val m = mutableMapOf<String, String>()
                    parseViewerRels(zis.bufferedReader().readText(), m)
                    slideRels[relsNum] = m
                }
                n.startsWith("ppt/media/")   -> slideMedia[n.substringAfterLast("/")] = zis.readBytes()
            }
            zis.closeEntry(); entry = zis.nextEntry
        }
    }

    val h = (w * 0.5625f).toInt()   // 16:9 slide ratio

    slideXmls.forEach { (num, xml) ->
        val rels = slideRels[num] ?: emptyMap()
        val bmp  = renderPptxSlide(xml, rels, slideMedia, w, h)
        emit(bmp)
    }
}

private fun renderPptxSlide(
    xml   : String,
    rels  : Map<String, String>,
    media : Map<String, ByteArray>,
    w     : Int,
    h     : Int
): Bitmap {
    val bmp    = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.drawColor(AColor.WHITE)

    // Background tint
    val bgPaint = Paint().apply { color = AColor.parseColor("#F5F5F5") }
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)

    // Collect text shapes and images from XML
    val shapes = mutableListOf<Pair<android.graphics.RectF, String>>()  // rect → text
    val images = mutableListOf<Pair<android.graphics.RectF, String>>()  // rect → rId

    try {
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
        val parser  = factory.newPullParser(); parser.setInput(xml.reader())

        var inSp   = false; var inSpPr = false; var inTxBody = false
        var inPara = false; var inRun  = false
        var curOff = android.graphics.RectF(); var curExt = android.graphics.RectF()
        val curText = StringBuilder()
        var curRId  = ""

        fun emxToFloat(emx: String?, total: Int): Float {
            val v = emx?.toLongOrNull() ?: return 0f
            return v.toFloat() / 914400f * 96f / 1f   // EMU → pixels at 96dpi
        }

        var ev = parser.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            val ln = parser.name ?: ""
            when (ev) {
                XmlPullParser.START_TAG -> when (ln) {
                    "sp"      -> { inSp = true; curText.clear(); curRId = "" }
                    "spPr"    -> inSpPr = true
                    "txBody"  -> inTxBody = true
                    "p"       -> if (inTxBody) { inPara = true }
                    "r"       -> if (inTxBody) inRun = true
                    "off"     -> if (inSpPr) {
                        curOff = android.graphics.RectF(
                            emxToFloat(parser.getAttributeValue(null, "x"), w),
                            emxToFloat(parser.getAttributeValue(null, "y"), h), 0f, 0f
                        )
                    }
                    "ext"     -> if (inSpPr) {
                        curExt = android.graphics.RectF(
                            0f, 0f,
                            emxToFloat(parser.getAttributeValue(null, "cx"), w),
                            emxToFloat(parser.getAttributeValue(null, "cy"), h)
                        )
                    }
                    "blip"    -> {
                        curRId = parser.getAttributeValue(
                            "http://schemas.openxmlformats.org/officeDocument/2006/relationships", "embed"
                        ) ?: parser.getAttributeValue(null, "r:embed") ?: ""
                    }
                    "t"       -> { /* text follows */ }
                }
                XmlPullParser.TEXT -> {
                    if (inRun && inTxBody) curText.append(parser.text)
                }
                XmlPullParser.END_TAG -> when (ln) {
                    "spPr"   -> inSpPr   = false
                    "r"      -> inRun    = false
                    "p"      -> if (inTxBody) { curText.append("\n"); inPara = false }
                    "txBody" -> inTxBody = false
                    "sp"     -> {
                        if (inSp) {
                                                val rect = android.graphics.RectF(
                                curOff.left * w / 9144000f,
                                curOff.top  * h / 5143500f,
                                (curOff.left + curExt.right) * w / 9144000f,
                                (curOff.top  + curExt.bottom) * h / 5143500f
                            )
                            val txt = curText.toString().trim()
                            if (txt.isNotEmpty()) shapes.add(rect to txt)
                            if (curRId.isNotEmpty()) images.add(rect to curRId)
                        }
                        inSp = false; curText.clear()
                    }
                    "pic"    -> {
                        if (curRId.isNotEmpty()) {
                            val rect = android.graphics.RectF(
                                curOff.left  * w / 9144000f,
                                curOff.top   * h / 5143500f,
                                (curOff.left + curExt.right)  * w / 9144000f,
                                (curOff.top  + curExt.bottom) * h / 5143500f
                            )
                            images.add(rect to curRId)
                        }
                    }
                }
            }
            ev = parser.next()
        }
    } catch (_: Exception) {}

    // Draw images
    images.forEach { (rect, rId) ->
        val imgName = rels[rId] ?: return@forEach
        val bytes   = media[imgName] ?: return@forEach
        val src     = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@forEach
        val dst     = if (rect.width() > 2 && rect.height() > 2) rect
                      else android.graphics.RectF(0f, 0f, w.toFloat(), h.toFloat())
        canvas.drawBitmap(src, null, dst, null)
        src.recycle()
    }

    // Draw text
    shapes.forEach { (rect, text) ->
        if (rect.width() < 4 || rect.height() < 4) return@forEach
        val fs  = (rect.height() * 0.18f).coerceIn(w * 0.018f, w * 0.055f)
        val tp  = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = AColor.BLACK; textSize = fs }
        val sl  = buildStaticLayout(text, tp, rect.width().toInt().coerceAtLeast(1))
        canvas.save()
        canvas.translate(rect.left, rect.top + (rect.height() - sl.height) / 2f)
        sl.draw(canvas)
        canvas.restore()
    }

    return bmp
}

// ── Table rendering (shared by CSV + XLSX) ────────────────────────────────────

private suspend fun kotlinx.coroutines.flow.FlowCollector<Bitmap>.emitTablePages(
    rows: List<List<String>>, colCount: Int, w: Int, isHeader: Boolean
) {
    val pageH    = (w * 1.414f).toInt()
    val margin   = (w * 0.04f).toInt()
    val colW     = ((w - margin * 2) / colCount.toFloat()).toInt().coerceAtLeast(60)
    val rowH     = (w * 0.045f).toInt()
    val textSize = w * 0.022f

    val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AColor.parseColor("#1565C0") }
    val evenPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AColor.parseColor("#F5F8FF") }
    val oddPaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AColor.WHITE }
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AColor.parseColor("#DDDDEE"); strokeWidth = 1f }
    val textPaint   = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AColor.BLACK; this.textSize = textSize
    }
    val headerTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AColor.WHITE; this.textSize = textSize; typeface = Typeface.DEFAULT_BOLD
    }

    var bmp    = Bitmap.createBitmap(w, pageH, Bitmap.Config.ARGB_8888)
    var canvas = Canvas(bmp); canvas.drawColor(AColor.WHITE)
    var curY   = margin

    suspend fun flush() {
        emit(bmp)
        bmp = Bitmap.createBitmap(w, pageH, Bitmap.Config.ARGB_8888)
        canvas = Canvas(bmp); canvas.drawColor(AColor.WHITE)
        curY = margin
    }

    rows.forEachIndexed { rowIdx, row ->
        if (curY + rowH > pageH - margin) flush()

        val bg = when {
            rowIdx == 0 && isHeader -> headerPaint
            rowIdx % 2 == 0         -> evenPaint
            else                    -> oddPaint
        }
        canvas.drawRect(margin.toFloat(), curY.toFloat(),
            (w - margin).toFloat(), (curY + rowH).toFloat(), bg)

        for (col in 0 until colCount) {
            val cell = row.getOrElse(col) { "" }
            val x    = margin + col * colW
            val tp   = if (rowIdx == 0 && isHeader) headerTextPaint else textPaint
            // Clip text to cell width
            val maxChars = (colW / (textSize * 0.55f)).toInt().coerceAtLeast(3)
            val display  = if (cell.length > maxChars) cell.take(maxChars - 1) + "…" else cell
            canvas.drawText(display, x.toFloat() + 6, curY + rowH * 0.65f, tp)
            // Column separator
            canvas.drawLine((x + colW).toFloat(), curY.toFloat(),
                (x + colW).toFloat(), (curY + rowH).toFloat(), borderPaint)
        }
        // Row separator
        canvas.drawLine(margin.toFloat(), (curY + rowH).toFloat(),
            (w - margin).toFloat(), (curY + rowH).toFloat(), borderPaint)
        curY += rowH
    }
    if (curY > margin + rowH) emit(bmp) else bmp.recycle()
}

// ── Shared XML helper: parse .rels ────────────────────────────────────────────

private fun parseViewerRels(xml: String, out: MutableMap<String, String>) {
    try {
        val p = XmlPullParserFactory.newInstance().newPullParser().also { it.setInput(xml.reader()) }
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && p.name == "Relationship") {
                val id     = p.getAttributeValue(null, "Id")     ?: ""
                val target = p.getAttributeValue(null, "Target") ?: ""
                if (id.isNotEmpty() && (target.contains("media/") || target.contains("image"))) {
                    out[id] = target.substringAfterLast("/")
                }
            }
            ev = p.next()
        }
    } catch (_: Exception) {}
}

// ── Shared docx XML parser ────────────────────────────────────────────────────
// (mirrors DocxToPdfScreen logic but lives here for standalone viewer use)

private fun parseViewerDocXml(xml: String, relsMap: Map<String, String>): List<DocBlock> {
    val blocks = mutableListOf<DocBlock>()
    try {
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
        val parser  = factory.newPullParser(); parser.setInput(xml.reader())
        var inBody = false; var inPara = false; var inRun = false; var inRPr = false
        var curBold = false; var curItalic = false; var curFontSz = 11f
        var paraStyle = ""; var paraRuns = mutableListOf<DocRun>()
        val runText = StringBuilder()

        fun flushRun() { val t = runText.toString(); if (t.isNotEmpty()) { paraRuns.add(DocRun(t, curBold, curItalic, curFontSz)); runText.clear() } }
        fun flushPara() {
            val h = when { paraStyle.contains("Heading1", true) -> 1; paraStyle.contains("Heading2", true) -> 2; paraStyle.contains("Heading3", true) -> 3; paraStyle == "Title" -> 1; else -> 0 }
            blocks.add(DocBlock.Paragraph(paraRuns.toList(), h)); paraRuns.clear(); paraStyle = ""
        }

        var ev = parser.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            val ln = parser.name ?: ""
            when (ev) {
                XmlPullParser.START_TAG -> when (ln) {
                    "body"    -> inBody = true
                    "p"       -> if (inBody) { inPara = true }
                    "r"       -> if (inPara) { inRun = true; curBold = false; curItalic = false }
                    "rPr"     -> inRPr = true
                    "pStyle"  -> if (inPara) paraStyle = parser.getAttributeValue(null, "w:val") ?: ""
                    "b"       -> if (inRPr) curBold   = true
                    "i"       -> if (inRPr) curItalic = true
                    "sz"      -> if (inRPr) { (parser.getAttributeValue(null, "w:val") ?: "").toFloatOrNull()?.let { curFontSz = (it / 2f).coerceIn(7f, 72f) } }
                    "br"      -> { val t = parser.getAttributeValue(null, "w:type") ?: ""; if (t == "page") { flushRun(); flushPara(); blocks.add(DocBlock.PageBreak) } else runText.append("\n") }
                    "blip"    -> {
                        val rId = parser.getAttributeValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "embed") ?: parser.getAttributeValue(null, "r:embed") ?: ""
                        if (rId.isNotEmpty()) relsMap[rId]?.let { flushRun(); blocks.add(DocBlock.Paragraph(paraRuns.toList())); paraRuns.clear(); blocks.add(DocBlock.ImageBlock(it)) }
                    }
                }
                XmlPullParser.TEXT    -> if (inRun && inPara && !inRPr) runText.append(parser.text)
                XmlPullParser.END_TAG -> when (ln) {
                    "rPr"  -> inRPr  = false
                    "r"    -> { flushRun(); inRun = false }
                    "p"    -> { flushRun(); if (inPara) flushPara(); inPara = false }
                    "body" -> inBody = false
                }
            }
            ev = parser.next()
        }
    } catch (_: Exception) {}
    return blocks
}

// ── StaticLayout compat helper ────────────────────────────────────────────────

private fun buildStaticLayout(text: CharSequence, paint: TextPaint, width: Int): StaticLayout =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(2f, 1.25f)
            .setIncludePad(false)
            .build()
    } else {
        @Suppress("DEPRECATION")
        StaticLayout(text, paint, width.coerceAtLeast(1),
            Layout.Alignment.ALIGN_NORMAL, 1.25f, 2f, false)
    }
