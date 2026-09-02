package com.example.pdfmaker

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect

@Composable
fun DocxToPdfScreen(
    onBack: () -> Unit,
    onOpenFile: (PdfFile) -> Unit,
    initialUri: Uri? = null,
    initialName: String? = null,
) {
    val state = rememberDocxToPdfUiState()
    val actions = rememberDocxToPdfActions(state)
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { actions.select(it) }
        }

    LaunchedEffect(actions, initialUri, initialName) {
        initialUri?.let { actions.select(it, initialName) }
    }
    DisposableEffect(actions) {
        onDispose(actions::release)
    }

    val handleBack = {
        if (!actions.cancelActive()) onBack()
    }
    BackHandler(onBack = handleBack)

    DocxToPdfContent(
        state = state,
        onBack = handleBack,
        onChoose = { picker.launch(arrayOf(DocxToPdfPolicy.DOCX_MIME_TYPE)) },
        onChange = actions::reset,
        onConvert = actions::startConversion,
        onOpen = { state.result?.catalogEntry?.let(onOpenFile) },
        onShare = actions::shareResult,
        onConvertAnother = actions::reset,
        onDismissError = actions::dismissError,
    )
}
