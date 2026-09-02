package com.example.pdfmaker

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── Internal state machine ────────────────────────────────────────────────────

private enum class DocxState { PICK, READY, CONVERTING, DONE, ERROR }


// ── Screen ────────────────────────────────────────────────────────────────────

@Composable
fun DocxToPdfScreen(onBack: () -> Unit, onOpenFile: (PdfFile) -> Unit) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    val bgDark  = Color(0xFF0D0D16)
    val barBg   = Color(0xFF1A1A2A)
    val cardBg  = Color(0xFF14141F)
    val textPri = Color.White
    val textSec = Color(0xFF9999BB)
    val accent  = Color(0xFF1565C0)   // Word-blue theme

    var state        by remember { mutableStateOf(DocxState.PICK) }
    var pickedUri    by remember { mutableStateOf<Uri?>(null) }
    var pickedName   by remember { mutableStateOf("") }
    var pickedSizeKb by remember { mutableStateOf(0L) }
    var progress     by remember { mutableIntStateOf(0) }
    var progressText by remember { mutableStateOf("") }
    var resultFile   by remember { mutableStateOf<File?>(null) }
    var errorMsg     by remember { mutableStateOf("") }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pickedUri  = uri
            pickedName = uri.lastPathSegment
                ?.substringAfterLast("/")
                ?.substringAfterLast("%2F")
                ?.removeSuffix(".docx")
                ?.take(40) ?: "document"
            pickedSizeKb = context.contentResolver
                .openFileDescriptor(uri, "r")?.use { it.statSize / 1024 } ?: 0L
            state = DocxState.READY
        }
    }

    fun startConvert() {
        val uri = pickedUri ?: return
        state    = DocxState.CONVERTING
        progress = 0
        scope.launch(Dispatchers.IO) {
            try {
                val file = docxToPdf(context, uri, pickedName) { p, txt ->
                    scope.launch(Dispatchers.Main) { progress = p; progressText = txt }
                }
                withContext(Dispatchers.Main) {
                    if (file != null) {
                        resultFile = file
                        FileCache.prependFile(
                            PdfFile(
                                name         = file.name,
                                filePath     = file.absolutePath,
                                size         = docxFormatSize(file.length() / 1024),
                                date         = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date()),
                                pageCount    = 1,
                                lastModified = file.lastModified()
                            )
                        )
                        state = DocxState.DONE
                    } else {
                        errorMsg = "Conversion failed. The file may be password-protected or use unsupported formatting."
                        state    = DocxState.ERROR
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    errorMsg = e.message ?: "Unknown error"
                    state    = DocxState.ERROR
                }
            }
        }
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
                Text("Docx to PDF", color = textPri,
                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).padding(start = 4.dp))
                if (state == DocxState.READY) {
                    TextButton(onClick = { pickedUri = null; state = DocxState.PICK }) {
                        Text("Change", color = textSec, fontSize = 13.sp)
                    }
                }
            }

            when (state) {

                // ── 1. Pick ───────────────────────────────────────────────────
                DocxState.PICK -> {
                    Column(
                        Modifier.fillMaxSize().padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            Modifier.size(100.dp).clip(CircleShape)
                                .background(Color(0xFF0D1A2E))
                                .border(2.dp, accent.copy(alpha = 0.5f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Description, null,
                                tint = accent, modifier = Modifier.size(46.dp))
                        }
                        Spacer(Modifier.height(22.dp))
                        Text("Convert Word to PDF", color = textPri,
                            fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Converts text, headings, bold/italic and\nembedded images from your .docx file.",
                            color   = textSec, fontSize = 14.sp,
                            textAlign = TextAlign.Center, lineHeight = 20.sp
                        )
                        Spacer(Modifier.height(36.dp))
                        Button(
                            onClick  = { filePicker.launch(arrayOf(
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                "application/msword"
                            )) },
                            modifier = Modifier.fillMaxWidth(0.75f).height(54.dp),
                            shape    = RoundedCornerShape(14.dp),
                            colors   = ButtonDefaults.buttonColors(containerColor = accent)
                        ) {
                            Icon(Icons.Default.FileOpen, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Choose .docx File", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                    }
                }

                // ── 2. Ready ──────────────────────────────────────────────────
                DocxState.READY -> {
                    Column(
                        Modifier.fillMaxSize().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // File card
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                                .background(cardBg).padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier.size(54.dp).clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF0D1A2E)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Description, null,
                                    tint = accent, modifier = Modifier.size(30.dp))
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text("$pickedName.docx", color = textPri,
                                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(docxFormatSize(pickedSizeKb), color = textSec, fontSize = 12.sp)
                            }
                        }

                        // What will be converted info card
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                .background(cardBg).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("What gets converted", color = textPri,
                                fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            FeatureRow(Icons.Default.FormatAlignLeft,  "Paragraphs & text",         accent)
                            FeatureRow(Icons.Default.FormatBold,       "Bold & italic formatting",  accent)
                            FeatureRow(Icons.Default.Title,            "Headings (H1, H2, H3)",     accent)
                            FeatureRow(Icons.Default.Image,            "Embedded images",           accent)
                            FeatureRow(Icons.Default.InsertPageBreak,  "Page breaks",               accent)
                        }

                        Spacer(Modifier.weight(1f))

                        Button(
                            onClick  = { startConvert() },
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            shape    = RoundedCornerShape(14.dp),
                            colors   = ButtonDefaults.buttonColors(containerColor = accent)
                        ) {
                            Icon(Icons.Default.PictureAsPdf, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Convert to PDF", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    }
                }

                // ── 3. Converting ─────────────────────────────────────────────
                DocxState.CONVERTING -> {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        DocxSpinner(progress = progress, color = accent)
                        Spacer(Modifier.height(28.dp))
                        Text("Converting…", color = textPri,
                            fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text(progressText, color = textSec, fontSize = 13.sp)
                        Spacer(Modifier.height(6.dp))
                        Text("$progress%", color = Color(accent.value),
                            fontSize = 32.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(20.dp))
                        LinearProgressIndicator(
                            progress   = { progress / 100f },
                            modifier   = Modifier.fillMaxWidth().height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color      = accent,
                            trackColor = Color(0xFF2A2A40)
                        )
                    }
                }

                // ── 4. Done ───────────────────────────────────────────────────
                DocxState.DONE -> {
                    val file = resultFile
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
                        Text("Conversion Complete!", color = textPri,
                            fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text("${pickedName}.pdf", color = textSec, fontSize = 14.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(8.dp))
                        if (file != null) {
                            Text(docxFormatSize(file.length() / 1024),
                                color = Color(0xFF4CAF50), fontSize = 13.sp)
                        }
                        Spacer(Modifier.height(32.dp))

                        // Open + Share row
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    if (file != null) onOpenFile(PdfFile(
                                        name         = file.name,
                                        filePath     = file.absolutePath,
                                        size         = docxFormatSize(file.length() / 1024),
                                        date         = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date()),
                                        pageCount    = 1,
                                        lastModified = file.lastModified()
                                    ))
                                },
                                modifier = Modifier.weight(1f).height(52.dp),
                                shape    = RoundedCornerShape(14.dp),
                                colors   = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                            ) {
                                Icon(Icons.Default.OpenInNew, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Open", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            }
                            Button(
                                onClick = { if (file != null) shareDocxPdf(context, file) },
                                modifier = Modifier.weight(1f).height(52.dp),
                                shape    = RoundedCornerShape(14.dp),
                                colors   = ButtonDefaults.buttonColors(containerColor = accent)
                            ) {
                                Icon(Icons.Default.Share, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Share", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick  = { pickedUri = null; state = DocxState.PICK },
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            shape    = RoundedCornerShape(14.dp),
                            border   = androidx.compose.foundation.BorderStroke(1.dp, textSec.copy(alpha = 0.4f))
                        ) {
                            Icon(Icons.Default.Add, null, tint = textSec, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Convert Another", color = textSec, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        }
                    }
                }

                // ── 5. Error ──────────────────────────────────────────────────
                DocxState.ERROR -> {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            Modifier.size(90.dp).clip(CircleShape).background(Color(0xFF2A1010)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.ErrorOutline, null,
                                tint = Color(0xFFF44336), modifier = Modifier.size(46.dp))
                        }
                        Spacer(Modifier.height(18.dp))
                        Text("Conversion Failed", color = textPri,
                            fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(errorMsg, color = textSec, fontSize = 13.sp,
                            textAlign = TextAlign.Center)
                        Spacer(Modifier.height(28.dp))
                        Button(
                            onClick  = { state = DocxState.READY },
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
