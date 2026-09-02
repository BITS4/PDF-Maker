package com.example.pdfmaker

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.graphics.asImageBitmap
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

// ── Quality options ───────────────────────────────────────────────────────────


// ── Internal state machine ────────────────────────────────────────────────────

private enum class JpgConvertState { PICK, PREVIEW, CONVERTING, DONE, ERROR }

// ── Screen ────────────────────────────────────────────────────────────────────

@Composable
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
    var pickedUri    by remember { mutableStateOf<Uri?>(null) }
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

    // Page selection (null = all pages)
    var allPages     by remember { mutableStateOf(true) }
    var pageFrom     by remember { mutableIntStateOf(1) }
    var pageTo       by remember { mutableIntStateOf(1) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pickedUri  = uri
            pickedName = uri.lastPathSegment
                ?.substringAfterLast("/")
                ?.substringAfterLast("%2F")
                ?.removeSuffix(".pdf")
                ?.take(40) ?: "document"
            pickedSizeKb = context.contentResolver
                .openFileDescriptor(uri, "r")?.use { it.statSize / 1024 } ?: 0L

            // Get page count + load preview thumbnails
            scope.launch(Dispatchers.IO) {
                val cnt  = pdfPageCount(context, uri)
                val prvs = (0 until minOf(cnt, 6)).mapNotNull { i ->
                    renderPage(context, uri, i, 400)
                }
                withContext(Dispatchers.Main) {
                    pageCount = cnt
                    pageFrom  = 1
                    pageTo    = cnt
                    previews  = prvs
                    state     = JpgConvertState.PREVIEW
                }
            }
        }
    }

    fun startConvert() {
        val uri = pickedUri ?: return
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

        scope.launch(Dispatchers.IO) {
            try {
                val files = convertPdfToJpg(
                    context   = context,
                    uri       = uri,
                    baseName  = pickedName,
                    quality   = quality,
                    fromPage  = from,
                    toPage    = to
                ) { p, txt ->
                    scope.launch(Dispatchers.Main) { progress = p; progressText = txt }
                }
                withContext(Dispatchers.Main) {
                    resultFiles = files
                    state = if (files.isNotEmpty()) JpgConvertState.DONE else JpgConvertState.ERROR.also {
                        errorMsg = "No pages could be converted."
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    errorMsg = e.message ?: "Unknown error"
                    state    = JpgConvertState.ERROR
                }
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
                    pickedUri = null
                    previews = emptyList()
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

                // ── 2. Preview + settings ─────────────────────────────────────
                JpgConvertState.PREVIEW -> {
                    Column(
                        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // File info card
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                .background(cardBg).padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier.size(46.dp).clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF1E1E30)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.PictureAsPdf, null,
                                    tint = Color(0xFFE53935), modifier = Modifier.size(26.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("$pickedName.pdf", color = textPri,
                                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("$pageCount pages · ${jpgFormatSize(pickedSizeKb)}",
                                    color = textSec, fontSize = 12.sp)
                            }
                        }

                        // Page previews (up to 6)
                        if (previews.isNotEmpty()) {
                            Text("Preview", color = textSec,
                                fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                itemsIndexed(previews) { idx, bmp ->
                                    Box(
                                        Modifier.size(width = 72.dp, height = 96.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFF1E1E2E))
                                    ) {
                                        Image(bmp.asImageBitmap(), null,
                                            modifier     = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Fit)
                                        // Page number badge
                                        Box(
                                            Modifier.align(Alignment.BottomEnd)
                                                .padding(4.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(Color(0xAA000000))
                                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                        ) {
                                            Text("${idx + 1}", color = Color.White,
                                                fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                                if (pageCount > 6) {
                                    item {
                                        Box(
                                            Modifier.size(width = 72.dp, height = 96.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color(0xFF1E1E2E)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("+${pageCount - 6} more",
                                                color = textSec, fontSize = 11.sp,
                                                textAlign = TextAlign.Center)
                                        }
                                    }
                                }
                            }
                        }

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
                    Column(
                        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(Modifier.height(12.dp))
                        Box(
                            Modifier.size(72.dp).clip(CircleShape)
                                .background(Color(0xFF1A2A1A)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.CheckCircle, null,
                                tint = Color(0xFF4CAF50), modifier = Modifier.size(38.dp))
                        }
                        Spacer(Modifier.height(10.dp))
                        Text("${resultFiles.size} JPG ${if (resultFiles.size == 1) "image" else "images"} ready",
                            color = textPri, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        val totalKb = resultFiles.sumOf { it.length() / 1024 }
                        Text("Total size: ${jpgFormatSize(totalKb)}", color = textSec, fontSize = 13.sp)
                        Spacer(Modifier.height(16.dp))

                        // Thumbnail grid
                        LazyVerticalGrid(
                            columns      = GridCells.Fixed(3),
                            modifier     = Modifier.weight(1f),
                            contentPadding = PaddingValues(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement   = Arrangement.spacedBy(6.dp)
                        ) {
                            itemsIndexed(resultFiles) { idx, file ->
                                val bmp = remember(file.absolutePath) {
                                    android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                                }
                                Box(
                                    Modifier
                                        .aspectRatio(0.75f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF1E1E2E))
                                ) {
                                    if (bmp != null) {
                                        Image(bmp.asImageBitmap(), null,
                                            modifier     = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop)
                                    }
                                    Box(
                                        Modifier.align(Alignment.BottomStart)
                                            .fillMaxWidth()
                                            .background(Color(0xAA000000))
                                            .padding(horizontal = 6.dp, vertical = 3.dp)
                                    ) {
                                        Text("Page ${idx + 1}", color = Color.White,
                                            fontSize = 10.sp, fontWeight = FontWeight.Medium)
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        // Save to Gallery button (full width)
                        Button(
                            onClick = {
                                saveJpgsToGallery(context, resultFiles)
                                savedToGallery = true
                            },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape    = RoundedCornerShape(12.dp),
                            colors   = ButtonDefaults.buttonColors(
                                containerColor = if (savedToGallery) Color(0xFF388E3C) else Color(0xFF4CAF50)
                            )
                        ) {
                            Icon(
                                if (savedToGallery) Icons.Default.CheckCircle else Icons.Default.SaveAlt,
                                null, modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (savedToGallery) "Saved to Gallery!" else "Save to Gallery",
                                fontWeight = FontWeight.Bold, fontSize = 15.sp
                            )
                        }
                        Spacer(Modifier.height(8.dp))

                        // Share All + New row
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Share all as ZIP
                            Button(
                                onClick = { shareAllAsZip(context, resultFiles, pickedName) },
                                modifier = Modifier.weight(1f).height(50.dp),
                                shape    = RoundedCornerShape(12.dp),
                                colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF7043))
                            ) {
                                Icon(Icons.Default.Share, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Share All", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                            // Convert another
                            OutlinedButton(
                                onClick = {
                                    pickedUri = null; previews = emptyList()
                                    resultFiles = emptyList()
                                    savedToGallery = false
                                    state = JpgConvertState.PICK
                                },
                                modifier = Modifier.weight(1f).height(50.dp),
                                shape    = RoundedCornerShape(12.dp),
                                border   = androidx.compose.foundation.BorderStroke(
                                    1.dp, textSec.copy(alpha = 0.4f)
                                )
                            ) {
                                Icon(Icons.Default.Add, null, tint = textSec,
                                    modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("New", color = textSec,
                                    fontWeight = FontWeight.Medium, fontSize = 14.sp)
                            }
                        }
                    }
                }

                // ── 5. Error ──────────────────────────────────────────────────
                JpgConvertState.ERROR -> {
                    JpgErrorPanel(
                        message = errorMsg,
                        primaryText = textPri,
                        secondaryText = textSec,
                        onRetry = { state = JpgConvertState.PREVIEW },
                    )
                }
            }
        }
    }
}
