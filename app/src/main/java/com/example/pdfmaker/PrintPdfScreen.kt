package com.example.pdfmaker

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PrintPdfScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var pickedName by remember { mutableStateOf("") }
    var isPrinting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val filePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                pickedName = printableDisplayName(uri.lastPathSegment)
                isPrinting = true
                errorMessage = null
                startPdfPrintJob(
                    context = context,
                    sourceUri = uri,
                    requestedName = pickedName,
                    onFinished = { isPrinting = false },
                    onFailure = { message ->
                        isPrinting = false
                        errorMessage = message
                    },
                )
            }
        }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(currentBg),
    ) {
        PrintPdfTopBar(onBack)
        PrintPdfBody(
            pickedName = pickedName,
            isPrinting = isPrinting,
            errorMessage = errorMessage,
            onChoosePdf = { filePicker.launch(arrayOf("application/pdf")) },
        )
    }
}

@Composable
private fun PrintPdfTopBar(onBack: () -> Unit) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(currentCard)
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = currentText)
        }
        Text(
            text = "Print PDF",
            color = currentText,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun PrintPdfBody(
    pickedName: String,
    isPrinting: Boolean,
    errorMessage: String?,
    onChoosePdf: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (isPrinting) {
            CircularProgressIndicator(color = AccentBlue, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(20.dp))
            Text("Preparing print preview…", color = currentText, fontSize = 15.sp)
            Spacer(Modifier.height(8.dp))
            Text(pickedName, color = currentTextSecond, fontSize = 13.sp)
        } else {
            PrintPdfPrompt(errorMessage, onChoosePdf)
        }
    }
}

@Composable
private fun PrintPdfPrompt(
    errorMessage: String?,
    onChoosePdf: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(100.dp)
                .clip(CircleShape)
                .background(Color(0xFF1A2A3A)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Print,
            contentDescription = null,
            tint = Color(0xFF4F8EF7),
            modifier = Modifier.size(52.dp),
        )
    }
    Spacer(Modifier.height(24.dp))
    Text("Print a PDF", color = currentText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    Text(
        text = "Send selected PDF pages to a printer or save them as a new PDF",
        color = currentTextSecond,
        fontSize = 14.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 32.dp),
    )
    errorMessage?.let { PrintPdfError(it) }
    Spacer(Modifier.height(32.dp))
    Button(
        onClick = onChoosePdf,
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F8EF7)),
        shape = RoundedCornerShape(14.dp),
        modifier =
            Modifier
                .padding(horizontal = 32.dp)
                .fillMaxWidth()
                .height(52.dp),
    ) {
        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text("Choose PDF to Print", fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

@Composable
private fun PrintPdfError(message: String) {
    Spacer(Modifier.height(16.dp))
    Surface(
        color = Color(0xFF2A1A1A),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.padding(horizontal = 32.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = BadgeRed,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(message, color = BadgeRed, fontSize = 12.sp)
        }
    }
}

private fun printableDisplayName(rawName: String?): String {
    val providerName =
        rawName
            ?.substringAfterLast('/')
            ?.substringAfterLast("%2F")
            ?.substringBeforeLast('.')
    return "${SafeFileName.baseName(providerName, "document")}.pdf"
}
