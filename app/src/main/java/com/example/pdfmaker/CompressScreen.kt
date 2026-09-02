package com.example.pdfmaker

import android.content.Context
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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

// ── Compression level ─────────────────────────────────────────────────────────


// ── State ─────────────────────────────────────────────────────────────────────

private enum class CompressState { PICK, READY, COMPRESSING, DONE, ERROR }

// ── Screen ────────────────────────────────────────────────────────────────────

@Composable
fun CompressScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    val bgDark   = Color(0xFF0D0D16)
    val barBg    = Color(0xFF1A1A2A)
    val cardBg   = Color(0xFF14141F)
    val textPri  = Color.White
    val textSec  = Color(0xFF9999BB)
    val accent   = AccentBlue

    var state          by remember { mutableStateOf(CompressState.PICK) }
    var pickedUri      by remember { mutableStateOf<Uri?>(null) }
    var pickedName     by remember { mutableStateOf("") }
    var pickedSizeKb   by remember { mutableStateOf(0L) }
    var level          by remember { mutableStateOf(CompressLevel.MEDIUM) }
    var progress       by remember { mutableIntStateOf(0) }
    var resultFile     by remember { mutableStateOf<File?>(null) }
    var resultSizeKb   by remember { mutableStateOf(0L) }
    var errorMsg       by remember { mutableStateOf("") }

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
            state = CompressState.READY
        }
    }

    fun startCompress() {
        val uri = pickedUri ?: return
        state   = CompressState.COMPRESSING
        progress = 0
        scope.launch(Dispatchers.IO) {
            try {
                val out = compressPdf(context, uri, level, pickedName) { p ->
                    scope.launch(Dispatchers.Main) { progress = p }
                }
                withContext(Dispatchers.Main) {
                    if (out != null) {
                        resultFile   = out
                        resultSizeKb = out.length() / 1024
                        // Register immediately so it shows on home/files screen
                        val pageCount = PdfFileMetadata.pageCount(out)
                        FileCache.prependFile(
                            PdfFile(
                                name         = out.name,
                                filePath     = out.absolutePath,
                                size         = formatSize(resultSizeKb),
                                date         = java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.getDefault())
                                                   .format(java.util.Date()),
                                pageCount    = pageCount,
                                lastModified = out.lastModified()
                            )
                        )
                        state = CompressState.DONE
                    } else {
                        errorMsg = "Compression failed. The file may be encrypted or corrupted."
                        state    = CompressState.ERROR
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    errorMsg = e.message ?: "Unknown error"
                    state    = CompressState.ERROR
                }
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(bgDark)
            .statusBarsPadding()
    ) {
        Column(Modifier.fillMaxSize()) {

            // ── Top bar ───────────────────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth().background(barBg).padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = textPri)
                }
                Text(
                    "Compress PDF", color = textPri,
                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).padding(start = 4.dp)
                )
            }

            // ── Body ──────────────────────────────────────────────────────────
            when (state) {

                // ── 1. Pick file ──────────────────────────────────────────────
                CompressState.PICK -> {
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            Modifier
                                .size(100.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1E1E30))
                                .border(2.dp, accent.copy(alpha = 0.4f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.FolderOpen, null,
                                tint = accent, modifier = Modifier.size(44.dp)
                            )
                        }
                        Spacer(Modifier.height(24.dp))
                        Text("Select a PDF to compress", color = textPri,
                            fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Text("Choose a file from your device or cloud storage",
                            color = textSec, fontSize = 14.sp, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(36.dp))
                        Button(
                            onClick  = { filePicker.launch(arrayOf("application/pdf")) },
                            modifier = Modifier.fillMaxWidth(0.7f).height(52.dp),
                            shape    = RoundedCornerShape(14.dp),
                            colors   = ButtonDefaults.buttonColors(containerColor = accent)
                        ) {
                            Icon(Icons.Default.FileOpen, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Choose PDF", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                    }
                }

                // ── 2. Ready to compress ──────────────────────────────────────
                CompressState.READY -> {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // File card
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(cardBg)
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier.size(52.dp).clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF1E1E30)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.PictureAsPdf, null,
                                    tint = Color(0xFFE53935), modifier = Modifier.size(28.dp))
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text("$pickedName.pdf", color = textPri,
                                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(formatSize(pickedSizeKb), color = textSec, fontSize = 12.sp)
                            }
                            IconButton(onClick = {
                                pickedUri = null; state = CompressState.PICK
                            }) {
                                Icon(Icons.Default.Close, null, tint = textSec)
                            }
                        }

                        // Compression level label
                        Text("Compression Level", color = textPri,
                            fontSize = 15.sp, fontWeight = FontWeight.SemiBold)

                        // Level cards
                        CompressLevel.entries.forEach { lvl ->
                            val selected = lvl == level
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (selected) Color(0xFF1B2340) else cardBg)
                                    .border(
                                        width = if (selected) 1.5.dp else 0.dp,
                                        color = if (selected) accent else Color.Transparent,
                                        shape = RoundedCornerShape(14.dp)
                                    )
                                    .clickable { level = lvl }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier.size(42.dp).clip(CircleShape)
                                        .background(lvl.color.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(lvl.icon, null, tint = lvl.color,
                                        modifier = Modifier.size(22.dp))
                                }
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(lvl.label, color = textPri,
                                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                    Text(lvl.sub, color = textSec, fontSize = 12.sp)
                                }
                                if (selected) {
                                    Icon(Icons.Default.CheckCircle, null,
                                        tint = accent, modifier = Modifier.size(22.dp))
                                }
                            }
                        }

                        // Estimated reduction hint
                        val estPct = when (level) {
                            CompressLevel.LOW    -> "20–40%"
                            CompressLevel.MEDIUM -> "40–65%"
                            CompressLevel.HIGH   -> "65–85%"
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF1A2030))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Info, null, tint = accent,
                                modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Estimated size reduction: $estPct",
                                color = textSec, fontSize = 12.sp)
                        }

                        Spacer(Modifier.weight(1f))

                        Button(
                            onClick  = { startCompress() },
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            shape    = RoundedCornerShape(14.dp),
                            colors   = ButtonDefaults.buttonColors(containerColor = accent)
                        ) {
                            Icon(Icons.Default.Compress, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Compress", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    }
                }

                // ── 3. Compressing ────────────────────────────────────────────
                CompressState.COMPRESSING -> {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CompressingAnimation(progress = progress, accent = accent)
                        Spacer(Modifier.height(32.dp))
                        Text("Compressing…", color = textPri,
                            fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Text("$progress%", color = accent,
                            fontSize = 32.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(20.dp))
                        LinearProgressIndicator(
                            progress        = { progress / 100f },
                            modifier        = Modifier.fillMaxWidth().height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color           = accent,
                            trackColor      = Color(0xFF2A2A40)
                        )
                    }
                }

                // ── 4. Done ───────────────────────────────────────────────────
                CompressState.DONE -> {
                    val saved = ((pickedSizeKb - resultSizeKb).toFloat() / pickedSizeKb.coerceAtLeast(1) * 100).toInt().coerceIn(0, 100)
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        // Success circle
                        Box(
                            Modifier.size(100.dp).clip(CircleShape)
                                .background(Color(0xFF1A3020)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.CheckCircle, null,
                                tint = Color(0xFF4CAF50), modifier = Modifier.size(52.dp))
                        }
                        Spacer(Modifier.height(24.dp))
                        Text("Compression Complete!", color = textPri,
                            fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(24.dp))

                        // Size comparison card
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(cardBg)
                                .padding(20.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment     = Alignment.CenterVertically
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Original", color = textSec, fontSize = 12.sp)
                                Spacer(Modifier.height(4.dp))
                                Text(formatSize(pickedSizeKb), color = textPri,
                                    fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            }
                            Icon(Icons.Default.ArrowForward, null, tint = accent,
                                modifier = Modifier.size(24.dp))
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Compressed", color = textSec, fontSize = 12.sp)
                                Spacer(Modifier.height(4.dp))
                                Text(formatSize(resultSizeKb), color = Color(0xFF4CAF50),
                                    fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.height(12.dp))

                        // Saved badge
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color(0xFF1A3020))
                                .padding(horizontal = 20.dp, vertical = 8.dp)
                        ) {
                            Text("Saved $saved%", color = Color(0xFF4CAF50),
                                fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(36.dp))

                        // Action buttons
                        val file = resultFile
                        Button(
                            onClick = {
                                if (file != null) shareCompressedFile(context, file)
                            },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape    = RoundedCornerShape(14.dp),
                            colors   = ButtonDefaults.buttonColors(containerColor = accent)
                        ) {
                            Icon(Icons.Default.Share, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Share", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick  = {
                                pickedUri  = null
                                resultFile = null
                                state      = CompressState.PICK
                            },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape    = RoundedCornerShape(14.dp),
                            border   = androidx.compose.foundation.BorderStroke(1.dp, textSec.copy(alpha = 0.4f))
                        ) {
                            Icon(Icons.Default.Add, null,
                                tint = textSec, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Compress Another", color = textSec,
                                fontWeight = FontWeight.Medium, fontSize = 15.sp)
                        }
                    }
                }

                // ── 5. Error ──────────────────────────────────────────────────
                CompressState.ERROR -> {
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
                        Spacer(Modifier.height(20.dp))
                        Text("Compression Failed", color = textPri,
                            fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(10.dp))
                        Text(errorMsg, color = textSec, fontSize = 13.sp,
                            textAlign = TextAlign.Center)
                        Spacer(Modifier.height(32.dp))
                        Button(
                            onClick = { state = CompressState.READY },
                            modifier = Modifier.fillMaxWidth(0.6f).height(50.dp),
                            shape    = RoundedCornerShape(14.dp),
                            colors   = ButtonDefaults.buttonColors(containerColor = accent)
                        ) {
                            Text("Try Again", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
