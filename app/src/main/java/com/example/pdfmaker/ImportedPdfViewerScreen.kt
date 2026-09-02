package com.example.pdfmaker

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import java.io.File

@Composable
fun ImportedPdfViewerScreen(
    pdfUri: Uri,
    onBack: () -> Unit,
    onShareFile: (File) -> Unit,
    initialEditMode: PdfEditMode = PdfEditMode.NONE,
) {
    val context = LocalContext.current
    val operations = rememberPdfEditorOperationController()
    val state = remember(pdfUri, initialEditMode) { ImportedPdfViewerState(initialEditMode) }
    val density = LocalDensity.current
    val displayScale = remember(density) { ImportedPdfDisplayScale(density.density * density.fontScale) }
    val latestShareFile = rememberUpdatedState(onShareFile)
    val actions =
        remember(context, state, operations, displayScale) {
            ImportedPdfViewerActions(
                context = context,
                state = state,
                operations = operations,
                displayScale = displayScale,
                onShareFile = { file -> latestShareFile.value(file) },
            )
        }

    ImportedPdfViewerLifecycleEffect(state, operations)
    ImportedPdfSourceEffect(context, pdfUri, state, operations)
    ImportedPdfPageCacheEffect(
        context = context,
        state = state,
        displayWidth =
            context.resources.displayMetrics.widthPixels
                .coerceAtLeast(1),
    )

    val backAction = state.backAction(operations.target)
    val dispatchBack = {
        if (backAction == ImportedViewerBackAction.NAVIGATE_BACK) {
            onBack()
        } else {
            state.handleBack(backAction, operations)
        }
    }
    BackHandler(enabled = backAction != ImportedViewerBackAction.NAVIGATE_BACK, onBack = dispatchBack)

    when {
        state.loadError != null -> {
            ImportedPdfLoadError(message = state.loadError.orEmpty(), onBack = onBack)
        }

        state.workingUri == null -> {
            ImportedPdfLoading()
        }

        else -> {
            ImportedPdfViewerContent(
                state = state,
                operations = operations,
                actions = actions,
                onBack = dispatchBack,
            )
        }
    }
}

@Composable
private fun ImportedPdfLoadError(
    message: String,
    onBack: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color(0xFF1A1A1A))) {
        ViewerErrorView(
            message = message,
            onBack = onBack,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

@Composable
private fun ImportedPdfLoading() {
    Box(
        Modifier.fillMaxSize().background(Color(0xFF1A1A1A)),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = AccentBlue)
    }
}
