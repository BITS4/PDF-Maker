package com.example.pdfmaker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
fun MergePdfScreen(
    onBack: () -> Unit,
    onOpenFile: (PdfFile) -> Unit,
) {
    val state = rememberMergePdfUiState()
    val actions = rememberMergePdfActions(state, onOpenFile)
    val colors = remember { MergePdfColors() }

    if (state.showRenameDialog) {
        MergeRenameDialog(
            initialName = state.outputName,
            onConfirm = { name ->
                state.rename(name)
                state.showRenameDialog = false
            },
            onDismiss = { state.showRenameDialog = false },
        )
    }

    if (state.showPreMergeDialog) {
        MergePreflightDialog(
            initialName = state.outputName,
            onConfirm = { name ->
                state.showPreMergeDialog = false
                actions.startMerge(name)
            },
            onDismiss = { state.showPreMergeDialog = false },
        )
    }

    MergePdfContent(
        state = state,
        colors = colors,
        callbacks =
            MergePdfCallbacks(
                onBack = onBack,
                onSelectFiles = actions.selectFiles,
                onRename = { state.showRenameDialog = true },
                onMerge = { state.showPreMergeDialog = true },
                onRemove = actions.removeItem,
                onMove = state::move,
                onOpen = actions.openResult,
                onShare = actions.shareResult,
                onReset = actions.reset,
                onRetry = state::retry,
            ),
    )
}
