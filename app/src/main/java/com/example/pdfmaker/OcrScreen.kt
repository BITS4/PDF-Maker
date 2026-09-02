package com.example.pdfmaker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

private enum class OcrState { PICK, RUNNING, DONE, ERROR }

@Composable
fun OcrScreen(onBack: () -> Unit) {
    val context   = LocalContext.current
    val scope     = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var ocrState   by remember { mutableStateOf(OcrState.PICK) }
    var pickedUri  by remember { mutableStateOf<Uri?>(null) }
    var pickedName by remember { mutableStateOf("") }
    var progress   by remember { mutableIntStateOf(0) }
    var totalPgs   by remember { mutableIntStateOf(0) }
    var pageTexts  by remember { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }
    var errMsg     by remember { mutableStateOf("") }
    var showCopied by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pickedUri  = uri
            pickedName = uri.lastPathSegment
                ?.substringAfterLast("/")?.substringAfterLast("%2F")?.take(40) ?: "file"
            ocrState = OcrState.RUNNING
            scope.launch {
                runOcr(
                    context    = context,
                    uri        = uri,
                    onProgress = { cur, tot -> progress = cur; totalPgs = tot },
                    onDone     = { texts -> pageTexts = texts; ocrState = OcrState.DONE },
                    onError    = { msg -> errMsg = msg; ocrState = OcrState.ERROR }
                )
            }
        }
    }

    Box(Modifier.fillMaxSize().background(currentBg)) {
        Column(Modifier.fillMaxSize()) {

            // ── Top bar ───────────────────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth().background(currentCard).statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = currentText)
                }
                Text(
                    "OCR – Extract Text", color = currentText, fontSize = 18.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
                )
                if (ocrState == OcrState.DONE) {
                    // Copy all button
                    IconButton(onClick = {
                        clipboard.setText(AnnotatedString(OcrTextFormatter.format(pageTexts)))
                        showCopied = true
                        scope.launch { delay(2000); showCopied = false }
                    }) {
                        Icon(Icons.Default.ContentCopy, null, tint = AccentBlue)
                    }
                    // Save as .txt
                    IconButton(onClick = {
                        val txt = OcrTextFormatter.format(pageTexts)
                        scope.launch(Dispatchers.IO) {
                            val result = runCatching {
                                OutputStore.writeUnique(
                                    getPdfMakerDir(context),
                                    "${SafeFileName.baseName(pickedName)}_ocr",
                                    "txt",
                                ) { it.write(txt.toByteArray(Charsets.UTF_8)) }
                            }
                            withContext(Dispatchers.Main) {
                                result.fold(
                                    onSuccess = { file ->
                                        FileCache.prependFile(
                                            PdfFile(
                                                file.nameWithoutExtension,
                                                file.absolutePath,
                                                FileRepository.formatSize(file.length()),
                                                FileRepository.formatDate(file.lastModified()),
                                                0,
                                                file.lastModified(),
                                            ),
                                        )
                                        Toast.makeText(context, "Text saved", Toast.LENGTH_SHORT).show()
                                    },
                                    onFailure = {
                                        Toast.makeText(context, "Could not save text", Toast.LENGTH_SHORT).show()
                                    },
                                )
                            }
                        }
                    }) {
                        Icon(Icons.Default.Save, null, tint = AccentBlue)
                    }
                }
            }

            // ── Content ───────────────────────────────────────────────────────
            when (ocrState) {

                OcrState.PICK -> {
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            Modifier.size(100.dp).clip(CircleShape).background(Color(0xFF1A2A1A)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.DocumentScanner, null,
                                tint = Color(0xFF26C6A0), modifier = Modifier.size(52.dp))
                        }
                        Spacer(Modifier.height(24.dp))
                        Text("Extract Text (OCR)", color = currentText, fontSize = 20.sp,
                            fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Recognizes text from PDFs and images using on-device AI",
                            color = currentTextSecond, fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )
                        Spacer(Modifier.height(32.dp))
                        Button(
                            onClick  = { filePicker.launch(arrayOf("application/pdf")) },
                            colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350)),
                            shape    = RoundedCornerShape(14.dp),
                            modifier = Modifier.padding(horizontal = 32.dp).fillMaxWidth()
                        ) {
                            Icon(Icons.Default.PictureAsPdf, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Choose PDF", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick  = { filePicker.launch(arrayOf("image/jpeg", "image/png", "image/webp", "image/bmp")) },
                            colors   = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                            shape    = RoundedCornerShape(14.dp),
                            modifier = Modifier.padding(horizontal = 32.dp).fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Image, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Choose Image", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                        Spacer(Modifier.height(20.dp))
                        Surface(
                            color    = Color(0xFF1A2A1A),
                            shape    = RoundedCornerShape(12.dp),
                            modifier = Modifier.padding(horizontal = 32.dp)
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Info, null, tint = Color(0xFF26C6A0),
                                    modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Works offline — no internet needed",
                                    color = currentTextSecond, fontSize = 12.sp)
                            }
                        }
                    }
                }

                OcrState.RUNNING -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                progress = { if (totalPgs > 0) progress.toFloat() / totalPgs else 0f },
                                color    = Color(0xFF26C6A0),
                                modifier = Modifier.size(72.dp),
                                strokeWidth = 6.dp
                            )
                            Spacer(Modifier.height(20.dp))
                            Text("Extracting text…", color = currentText, fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                if (totalPgs > 0) "Page $progress of $totalPgs" else "Processing…",
                                color = currentTextSecond, fontSize = 13.sp
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(pickedName, color = currentTextSecond, fontSize = 12.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 32.dp))
                        }
                    }
                }

                OcrState.DONE -> {
                    val pages = pageTexts
                    var selectedPage by remember { mutableIntStateOf(0) }

                    Box(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize()) {
                            // Stats bar
                            Row(
                                Modifier.fillMaxWidth().background(currentCard)
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.TextFields, null, tint = Color(0xFF26C6A0),
                                    modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "${pages.size} page(s) · ${pages.sumOf { it.second.length }} chars",
                                    color = currentTextSecond, fontSize = 12.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { ocrState = OcrState.PICK; pageTexts = emptyList() }) {
                                    Text("New file", color = AccentBlue, fontSize = 12.sp)
                                }
                            }

                            // Page chips
                            if (pages.size > 1) {
                                Row(
                                    Modifier.horizontalScroll(rememberScrollState())
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    pages.forEachIndexed { idx, (num, _) ->
                                        val sel = idx == selectedPage
                                        Box(
                                            Modifier.clip(RoundedCornerShape(20.dp))
                                                .background(if (sel) Color(0xFF26C6A0) else currentCard)
                                                .clickable { selectedPage = idx }
                                                .padding(horizontal = 14.dp, vertical = 6.dp)
                                        ) {
                                            Text(
                                                "p$num",
                                                color = if (sel) Color.White else currentTextSecond,
                                                fontSize = 12.sp,
                                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal
                                            )
                                        }
                                    }
                                }
                            }

                            // Text area
                            val text = pages.getOrNull(selectedPage)?.second ?: ""
                            if (text.isBlank()) {
                                Box(
                                    Modifier.weight(1f).fillMaxWidth(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(Icons.Default.TextFields, null,
                                            tint = currentTextSecond, modifier = Modifier.size(48.dp))
                                        Spacer(Modifier.height(12.dp))
                                        Text("No text found on this page",
                                            color = currentTextSecond, fontSize = 14.sp)
                                        Text("Try a clearer scan",
                                            color = currentTextSecond, fontSize = 12.sp)
                                    }
                                }
                            } else {
                                LazyColumn(Modifier.weight(1f).padding(16.dp)) {
                                    item {
                                        SelectionContainer {
                                            Text(
                                                text,
                                                color      = currentText,
                                                fontSize   = 14.sp,
                                                fontFamily = FontFamily.Monospace,
                                                lineHeight = 22.sp
                                            )
                                        }
                                    }
                                }
                            }

                            // Bottom action bar
                            Row(
                                Modifier.fillMaxWidth().background(currentCard)
                                    .navigationBarsPadding()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick  = {
                                        clipboard.setText(AnnotatedString(text))
                                        showCopied = true
                                        scope.launch { delay(2000); showCopied = false }
                                    },
                                    shape    = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.ContentCopy, null,
                                        modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Copy Page")
                                }
                                Button(
                                    onClick = {
                                        val all = OcrTextFormatter.format(pages)
                                        clipboard.setText(AnnotatedString(all))
                                        showCopied = true
                                        scope.launch { delay(2000); showCopied = false }
                                    },
                                    colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFF26C6A0)),
                                    shape    = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.SelectAll, null,
                                        modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Copy All", fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        // Copied snackbar — use explicit non-scoped overload to avoid ColumnScope clash
                        if (showCopied) {
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 80.dp)
                        ) {
                            Surface(color = Color(0xFF26C6A0), shape = RoundedCornerShape(20.dp)) {
                                Row(
                                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Check, null, tint = Color.White,
                                        modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Copied to clipboard", color = Color.White, fontSize = 13.sp)
                                }
                            }
                        }
                        } // end if showCopied
                    }
                }

                OcrState.ERROR -> {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.ErrorOutline, null, tint = BadgeRed,
                            modifier = Modifier.size(56.dp))
                        Spacer(Modifier.height(16.dp))
                        Text(errMsg, color = currentText, fontSize = 15.sp,
                            textAlign = TextAlign.Center)
                        Spacer(Modifier.height(24.dp))
                        Button(
                            onClick = { ocrState = OcrState.PICK },
                            colors  = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                        ) {
                            Text("Try Again")
                        }
                    }
                }
            }
        }
    }
}

// ── OCR engine ────────────────────────────────────────────────────────────────

private suspend fun runOcr(
    context    : android.content.Context,
    uri        : Uri,
    onProgress : (Int, Int) -> Unit,
    onDone     : (List<Pair<Int, String>>) -> Unit,
    onError    : (String) -> Unit
) {
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    val results    = mutableListOf<Pair<Int, String>>()
    try {
        val imported = withContext(Dispatchers.IO) {
            SafeDocumentImporter.import(
                context,
                IncomingDocumentRequest(uri, context.contentResolver.getType(uri)),
            )
        }
        val document = when (imported) {
            is IncomingImportResult.Imported -> imported
            is IncomingImportResult.Rejected -> error(imported.message)
        }
        try {
            if (document.kind == IncomingDocumentKind.PDF) {
                val descriptor = ParcelFileDescriptor.open(document.file, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = try {
                    PdfRenderer(descriptor)
                } catch (error: Exception) {
                    descriptor.close()
                    throw error
                }
                try {
                    val total = renderer.pageCount
                    require(total in 1..500) { "PDF has an unsafe page count" }
                    withContext(Dispatchers.Main) { onProgress(0, total) }
                    for (index in 0 until total) {
                        val page = renderer.openPage(index)
                        try {
                            val size = RenderSizing.fitWithin(page.width, page.height, 1_600, allowUpscale = true)
                                ?: error("PDF page has invalid dimensions")
                            val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
                            try {
                                Canvas(bitmap).drawColor(AndroidColor.WHITE)
                                val matrix = android.graphics.Matrix().apply {
                                    setScale(
                                        size.width.toFloat() / page.width.toFloat(),
                                        size.height.toFloat() / page.height.toFloat(),
                                    )
                                }
                                page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                val image = InputImage.fromBitmap(bitmap, 0)
                                val recognized = withContext(Dispatchers.IO) { recognizer.process(image).await() }
                                results.add(index + 1 to recognized.text.trim())
                            } finally {
                                bitmap.recycle()
                            }
                        } finally {
                            page.close()
                        }
                        withContext(Dispatchers.Main) { onProgress(index + 1, total) }
                    }
                } finally {
                    renderer.close()
                }
            } else {
                require(document.kind != IncomingDocumentKind.DOCX) { "Choose a PDF or image for OCR" }
                withContext(Dispatchers.Main) { onProgress(0, 1) }
                val bitmap = withContext(Dispatchers.IO) { decodeBoundedOcrImage(document.file) }
                    ?: error("The image could not be decoded safely")
                try {
                    val image = InputImage.fromBitmap(bitmap, 0)
                    val recognized = withContext(Dispatchers.IO) { recognizer.process(image).await() }
                    results.add(1 to recognized.text.trim())
                } finally {
                    bitmap.recycle()
                }
                withContext(Dispatchers.Main) { onProgress(1, 1) }
            }
        } finally {
            document.file.delete()
        }
        withContext(Dispatchers.Main) { onDone(results) }
    } catch (e: Exception) {
        val message = when (e) {
            is IllegalArgumentException, is IllegalStateException -> e.message ?: "OCR failed"
            else -> "OCR failed because the input could not be processed safely"
        }
        withContext(Dispatchers.Main) { onError(message) }
    } finally {
        recognizer.close()
    }
}

private fun decodeBoundedOcrImage(file: java.io.File): Bitmap? {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
    val target = RenderSizing.fitWithin(bounds.outWidth, bounds.outHeight, 2_000) ?: return null
    var sampleSize = 1
    while (bounds.outWidth / sampleSize > target.width * 2 || bounds.outHeight / sampleSize > target.height * 2) {
        sampleSize *= 2
    }
    val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
}
