package com.example.pdfmaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.io.FileOutputStream

@Composable
fun PrintPdfScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    var pickedName by remember { mutableStateOf("") }
    var isPrinting by remember { mutableStateOf(false) }
    var errorMsg   by remember { mutableStateOf<String?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pickedName = uri.lastPathSegment
                ?.substringAfterLast("/")?.substringAfterLast("%2F") ?: "document.pdf"
            isPrinting = true
            errorMsg   = null
            printUri(context, uri, pickedName,
                onDone  = { isPrinting = false },
                onError = { msg -> isPrinting = false; errorMsg = msg }
            )
        }
    }

    Box(Modifier.fillMaxSize().background(currentBg)) {
        Column(Modifier.fillMaxSize()) {
            // Top bar
            Row(
                Modifier.fillMaxWidth().background(currentCard).statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = currentText)
                }
                Text("Print PDF", color = currentText, fontSize = 18.sp,
                    fontWeight = FontWeight.Bold)
            }

            // Body
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (isPrinting) {
                    CircularProgressIndicator(color = AccentBlue, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(20.dp))
                    Text("Opening print dialog…", color = currentText, fontSize = 15.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(pickedName, color = currentTextSecond, fontSize = 13.sp)
                } else {
                    Box(Modifier.size(100.dp).clip(CircleShape).background(Color(0xFF1A2A3A)),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Print, null, tint = Color(0xFF4F8EF7),
                            modifier = Modifier.size(52.dp))
                    }
                    Spacer(Modifier.height(24.dp))
                    Text("Print a PDF", color = currentText, fontSize = 20.sp,
                        fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("Sends the PDF directly to your printer or saves as PDF",
                        color = currentTextSecond, fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp))

                    if (errorMsg != null) {
                        Spacer(Modifier.height(16.dp))
                        Surface(color = Color(0xFF2A1A1A), shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.padding(horizontal = 32.dp)) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.ErrorOutline, null, tint = BadgeRed,
                                    modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(errorMsg!!, color = BadgeRed, fontSize = 12.sp)
                            }
                        }
                    }

                    Spacer(Modifier.height(32.dp))
                    Button(
                        onClick  = { filePicker.launch(arrayOf("application/pdf")) },
                        colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F8EF7)),
                        shape    = RoundedCornerShape(14.dp),
                        modifier = Modifier.padding(horizontal = 32.dp).fillMaxWidth().height(52.dp)
                    ) {
                        Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Choose PDF to Print", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                }
            }
        }
    }
}

private fun printUri(
    context : Context,
    uri     : Uri,
    name    : String,
    onDone  : () -> Unit,
    onError : (String) -> Unit
) {
    try {
        val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
        val jobName = name.substringBeforeLast(".")

        val adapter = object : PrintDocumentAdapter() {
            override fun onLayout(
                oldAttributes: PrintAttributes?,
                newAttributes: PrintAttributes,
                cancellationSignal: CancellationSignal?,
                callback: LayoutResultCallback,
                extras: Bundle?
            ) {
                if (cancellationSignal?.isCanceled == true) {
                    callback.onLayoutCancelled(); return
                }
                val info = PrintDocumentInfo.Builder(name)
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .build()
                callback.onLayoutFinished(info, oldAttributes != newAttributes)
            }

            override fun onWrite(
                pages: Array<out PageRange>,
                destination: ParcelFileDescriptor,
                cancellationSignal: CancellationSignal?,
                callback: WriteResultCallback
            ) {
                if (cancellationSignal?.isCanceled == true) {
                    callback.onWriteCancelled()
                    return
                }
                try {
                    SafePdfInput.fromUri(context, uri).use { source ->
                        source.file.inputStream().use { input ->
                            FileOutputStream(destination.fileDescriptor).use { output ->
                                BoundedIo.copy(input, output, SafePdfInput.MAX_PDF_BYTES)
                                output.flush()
                            }
                        }
                    }
                    if (cancellationSignal?.isCanceled == true) {
                        callback.onWriteCancelled()
                    } else {
                        callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                    }
                } catch (e: Exception) {
                    callback.onWriteFailed(e.message ?: "The PDF could not be printed safely")
                }
            }

            override fun onFinish() {
                onDone()
            }
        }

        printManager.print(jobName, adapter, PrintAttributes.Builder().build())
    } catch (e: Exception) {
        onError(e.message ?: "Print failed")
    }
}
