package com.example.pdfmaker

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember

@Composable
fun MergePdfScreen(
    onBack: () -> Unit,
    onOpenFile: (PdfFile) -> Unit,
) {
    val state = rememberMergePdfUiState()
    val actions = rememberMergePdfActions(state, onOpenFile)
    val colors = remember { MergePdfColors() }
    val handleBack = { actions.exit(onBack) }

    BackHandler(onBack = handleBack)
    DisposableEffect(actions) {
        onDispose(actions::release)
    }

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
                onBack = handleBack,
                onSelectFiles = actions::selectFiles,
                onRename = { state.showRenameDialog = true },
                onMerge = { state.showPreMergeDialog = true },
                onRemove = actions::removeItem,
                onMove = state::move,
                onOpen = actions::openResult,
                onShare = actions::shareResult,
                onReset = actions::reset,
                onRetry = actions::retry,
            ),
    )
}
