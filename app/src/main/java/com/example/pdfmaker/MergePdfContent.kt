package com.example.pdfmaker

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class MergePdfColors(
    val background: Color = Color(0xFF0D0D16),
    val bar: Color = Color(0xFF1A1A2A),
    val card: Color = Color(0xFF14141F),
    val primaryText: Color = Color.White,
    val secondaryText: Color = Color(0xFF9999BB),
    val accent: Color = AccentBlue,
    val action: Color = Color(0xFFFF7043),
)

internal data class MergePdfCallbacks(
    val onBack: () -> Unit,
    val onSelectFiles: () -> Unit,
    val onRename: () -> Unit,
    val onMerge: () -> Unit,
    val onRemove: (Int) -> Unit,
    val onMove: (Int, MergeItemMove) -> Unit,
    val onOpen: () -> Unit,
    val onShare: () -> Unit,
    val onReset: () -> Unit,
    val onRetry: () -> Unit,
)

@Composable
internal fun MergePdfContent(
    state: MergePdfUiState,
    colors: MergePdfColors,
    callbacks: MergePdfCallbacks,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            MergePdfTopBar(
                phase = state.phase,
                colors = colors,
                onBack = callbacks.onBack,
                onSelectFiles = callbacks.onSelectFiles,
            )
            when (state.phase) {
                MergeState.EMPTY -> {
                    MergeEmptyPanel(onSelectFiles = callbacks.onSelectFiles)
                }

                MergeState.READY -> {
                    MergePdfReadyContent(
                        items = state.items,
                        outputName = state.outputName,
                        summary = state.summary,
                        colors = colors,
                        callbacks = callbacks,
                    )
                }

                MergeState.MERGING -> {
                    MergePdfProgressContent(
                        progress = state.progress,
                        progressText = state.progressText,
                        colors = colors,
                    )
                }

                MergeState.DONE -> {
                    MergePdfDoneContent(
                        file = state.resultFile,
                        shareMessage = state.shareMessage,
                        summary = state.summary,
                        colors = colors,
                        callbacks = callbacks,
                    )
                }

                MergeState.ERROR -> {
                    MergePdfErrorContent(
                        message = state.errorMessage,
                        colors = colors,
                        onRetry = callbacks.onRetry,
                    )
                }
            }
        }
    }
}

@Composable
private fun MergePdfTopBar(
    phase: MergeState,
    colors: MergePdfColors,
    onBack: () -> Unit,
    onSelectFiles: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(colors.bar)
                .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = colors.primaryText,
            )
        }
        Text(
            text = "Merge PDF",
            color = colors.primaryText,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        if (phase == MergeState.READY) {
            IconButton(onClick = onSelectFiles) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add PDF files",
                    tint = colors.accent,
                )
            }
        }
    }
}
