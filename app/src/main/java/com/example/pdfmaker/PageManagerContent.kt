package com.example.pdfmaker

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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pages
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun PageManagerContent(
    state: PageManagerUiState,
    actions: PageManagerActions,
    onChoosePdf: () -> Unit,
    onBack: () -> Unit,
    onOpenFile: (PdfFile) -> Unit,
) {
    Box(Modifier.fillMaxSize().background(currentBg)) {
        Column(Modifier.fillMaxSize()) {
            PageManagerTopBar(state, actions::save, onBack)
            when (state.phase) {
                PageManagerPhase.PICK -> PageManagerPickContent(onChoosePdf)
                PageManagerPhase.PREPARING -> PageManagerProgressContent("Preparing PDF…")
                PageManagerPhase.EDIT -> PageManagerEditorContent(state, actions, onChoosePdf)
                PageManagerPhase.SAVING -> PageManagerProgressContent("Saving PDF…")
                PageManagerPhase.DONE -> PageManagerDoneContent(state, actions::editAgain, onOpenFile)
            }
        }
    }
    state.errorMessage?.let { message ->
        PageManagerErrorDialog(message, actions::dismissError)
    }
}

@Composable
private fun PageManagerTopBar(
    state: PageManagerUiState,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
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
            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = currentText)
        }
        Text(
            text = "Manage Pages",
            color = currentText,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        if (state.phase == PageManagerPhase.EDIT && state.hasChanges) {
            if (state.pages.any(PageState::deleted)) {
                Text("${state.activePages.size} pages", color = currentTextSecond, fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
            }
            TextButton(onClick = onSave, enabled = state.canSave) {
                Text("Save", color = AccentBlue, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun PageManagerPickContent(onChoosePdf: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.size(100.dp).clip(CircleShape).background(Color(0xFF1A2340)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Pages, null, tint = Color(0xFF4F8EF7), modifier = Modifier.size(52.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text("Manage Pages", color = currentText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Rotate or delete pages from any PDF",
            color = currentTextSecond,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onChoosePdf,
            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.padding(horizontal = 32.dp).fillMaxWidth(),
        ) {
            Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Choose PDF", fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}

@Composable
private fun PageManagerEditorContent(
    state: PageManagerUiState,
    actions: PageManagerActions,
    onChoosePdf: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.PictureAsPdf, null, tint = Color(0xFFEF5350), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = state.pickedName,
                color = currentTextSecond,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onChoosePdf) {
                Text("Change", color = AccentBlue, fontSize = 12.sp)
            }
        }
        PageManagerHints()
        PageManagerPageGrid(
            pages = state.pages,
            onRotateClockwise = actions::rotateClockwise,
            onRotateCounterClockwise = actions::rotateCounterClockwise,
            onToggleDeleted = actions::toggleDeleted,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun PageManagerProgressContent(label: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = AccentBlue, modifier = Modifier.size(52.dp))
            Spacer(Modifier.height(16.dp))
            Text(label, color = currentText, fontSize = 15.sp)
        }
    }
}

@Composable
private fun PageManagerDoneContent(
    state: PageManagerUiState,
    onEditAgain: () -> Unit,
    onOpenFile: (PdfFile) -> Unit,
) {
    val output = state.output ?: return
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.size(90.dp).clip(CircleShape).background(Color(0xFF0F2420)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF26C6A0), modifier = Modifier.size(50.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text("Saved!", color = currentText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(output.file.name, color = currentTextSecond, fontSize = 13.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(32.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { onOpenFile(output.catalogEntry) }, shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Open")
            }
            Button(
                onClick = onEditAgain,
                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Edit Again", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun PageManagerErrorDialog(
    message: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        title = { Text("Page Manager") },
        text = { Text(message) },
    )
}
