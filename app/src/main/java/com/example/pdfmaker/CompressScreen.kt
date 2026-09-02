package com.example.pdfmaker

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

@Composable
fun CompressScreen(onBack: () -> Unit) {
    val state = rememberCompressUiState()
    val actions = rememberCompressActions(state)
    val latestOnBack by rememberUpdatedState(onBack)
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(actions::select)
        }
    val dispatchBack = {
        when (state.backAction) {
            CompressionBackAction.CANCEL_OPERATION -> actions.cancelActive()
            CompressionBackAction.NAVIGATE_BACK -> latestOnBack()
        }
    }

    BackHandler(
        enabled = state.backAction == CompressionBackAction.CANCEL_OPERATION,
        onBack = dispatchBack,
    )
    DisposableEffect(actions) {
        onDispose(actions::release)
    }

    CompressContent(
        state = state,
        actions = actions,
        onChooseFile = { picker.launch(arrayOf("application/pdf")) },
        onBack = dispatchBack,
    )
}
