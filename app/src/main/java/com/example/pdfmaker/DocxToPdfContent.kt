package com.example.pdfmaker

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val DocxAccent = Color(0xFF1565C0)
internal val DocxBackground = Color(0xFF0D0D16)
internal val DocxCard = Color(0xFF14141F)
internal val DocxPrimaryText = Color.White
internal val DocxSecondaryText = Color(0xFF9999BB)

@Composable
internal fun DocxToPdfContent(
    state: DocxToPdfUiState,
    onBack: () -> Unit,
    onChoose: () -> Unit,
    onChange: () -> Unit,
    onConvert: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onConvertAnother: () -> Unit,
    onDismissError: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(DocxBackground)
            .statusBarsPadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            DocxTopBar(
                canChange = state.phase == DocxToPdfPhase.READY,
                onBack = onBack,
                onChange = onChange,
            )
            when (state.phase) {
                DocxToPdfPhase.PICK -> {
                    DocxPickContent(onChoose)
                }

                DocxToPdfPhase.PREPARING -> {
                    DocxPreparingContent()
                }

                DocxToPdfPhase.READY -> {
                    DocxReadyContent(state.input, onConvert)
                }

                DocxToPdfPhase.CONVERTING -> {
                    DocxConvertingContent(state.progress, state.progressText)
                }

                DocxToPdfPhase.DONE -> {
                    DocxDoneContent(state.result, onOpen, onShare, onConvertAnother)
                }
            }
        }
        state.errorMessage?.let { message ->
            DocxErrorDialog(
                title =
                    when {
                        state.result != null -> "Sharing unavailable"
                        state.input != null -> "Conversion unavailable"
                        else -> "Document unavailable"
                    },
                message = message,
                onDismiss = onDismissError,
            )
        }
    }
}

@Composable
private fun DocxTopBar(
    canChange: Boolean,
    onBack: () -> Unit,
    onChange: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A1A2A))
                .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = DocxPrimaryText)
        }
        Text(
            text = "DOCX to PDF",
            color = DocxPrimaryText,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        if (canChange) {
            TextButton(onClick = onChange) {
                Text("Change", color = DocxSecondaryText, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun DocxPickContent(onChoose: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        DocxDocumentBadge(icon = Icons.Default.Description)
        Text(
            text = "Convert Word to PDF",
            color = DocxPrimaryText,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 22.dp),
        )
        Text(
            text = "Converts text, headings, formatting, images, and page breaks from a .docx file.",
            color = DocxSecondaryText,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp),
        )
        Button(
            onClick = onChoose,
            modifier = Modifier.fillMaxWidth(0.75f).padding(top = 36.dp),
            colors = ButtonDefaults.buttonColors(containerColor = DocxAccent),
        ) {
            Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                text = "Choose .docx file",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun DocxPreparingContent() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        DocxSpinner(progress = 15, color = DocxAccent)
        Text(
            text = "Checking document…",
            color = DocxPrimaryText,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 28.dp),
        )
        Text(
            text = "Reading provider metadata securely",
            color = DocxSecondaryText,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun DocxErrorDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("OK")
            }
        },
    )
}
