package com.example.pdfmaker

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

@Composable
fun PageManagerScreen(
    onBack: () -> Unit,
    onOpenFile: (PdfFile) -> Unit = {},
) {
    val state = rememberPageManagerUiState()
    val actions = rememberPageManagerActions(state)
    val latestOnBack by rememberUpdatedState(onBack)
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(actions::select)
        }
    val dispatchBack = {
        when (state.backAction) {
            PageManagerBackAction.CANCEL_OPERATION -> actions.cancelActive()
            PageManagerBackAction.NAVIGATE_BACK -> latestOnBack()
        }
    }

    BackHandler(
        enabled = state.backAction == PageManagerBackAction.CANCEL_OPERATION,
        onBack = dispatchBack,
    )
    DisposableEffect(actions) {
        onDispose(actions::release)
    }

    PageManagerContent(
        state = state,
        actions = actions,
        onChoosePdf = { picker.launch(arrayOf("application/pdf")) },
        onBack = dispatchBack,
        onOpenFile = onOpenFile,
    )
}
