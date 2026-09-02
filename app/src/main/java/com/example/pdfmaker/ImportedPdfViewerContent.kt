package com.example.pdfmaker

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color

@Composable
internal fun ImportedPdfViewerContent(
    state: ImportedPdfViewerState,
    operations: PdfEditorOperationController,
    actions: ImportedPdfViewerActions,
    onBack: () -> Unit,
) {
    if (state.editMode == PdfEditMode.SIGNATURE) {
        SignaturePadScreen(
            onConfirm = state::acceptSignature,
            onCancel = state::cancelSignature,
        )
        return
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF0D0D16)).statusBarsPadding()) {
        ImportedPdfEditorCanvas(state)
        PdfEditorTopBar(
            title = state.pdfTitle,
            editMode = state.editMode,
            onBack = onBack,
            onResetDoodle = state.doodle::reset,
            onResetText = state::resetOverlayEdits,
        )
        AnimatedVisibility(
            visible = state.editMode == PdfEditMode.TEXT,
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            TextEditHint()
        }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
            ImportedPdfEditorBottomBar(state, actions)
        }
        if (operations.target != ConvertTarget.NONE) {
            ConvertingOverlay(operations.target, operations.progress, operations::cancel)
        }
    }

    ImportedPdfViewerDialogs(state, operations)
}

@Composable
private fun ImportedPdfEditorCanvas(state: ImportedPdfViewerState) {
    val doodle = state.doodle
    val overlays = state.overlays
    PdfEditorCanvas(
        bitmap = state.pageBitmaps[state.currentPage],
        annotations = state.annotations.getOrNull(state.currentPage),
        page = state.currentPage,
        pageCount = state.pageCount,
        editMode = state.editMode,
        doodleStrokes = doodle.strokes,
        activePath = doodle.activePath,
        doodleColor = doodle.color,
        doodleSize = doodle.size,
        liveTexts = overlays.liveTexts,
        liveSignatures = overlays.liveSignatures,
        selectedItemId = overlays.selectedItemId,
        pageBoxWidth = overlays.pageBoxWidth,
        onPageSize = overlays::updatePageSize,
        onDoodleStart = doodle::start,
        onDoodlePoint = doodle::append,
        onDoodleEnd = doodle::finish,
        onTextTap = overlays::beginTextAt,
        onSelect = overlays::select,
        onTextUpdate = overlays::updateText,
        onSignatureUpdate = overlays::updateSignature,
    )
}

@Composable
private fun ImportedPdfEditorBottomBar(
    state: ImportedPdfViewerState,
    actions: ImportedPdfViewerActions,
) {
    val doodle = state.doodle
    PdfEditorBottomBar(
        editMode = state.editMode,
        showConvert = state.showConvert,
        doodleSize = doodle.size,
        doodleColor = doodle.color,
        canUndo = doodle.strokes.isNotEmpty(),
        canRedo = doodle.redo.isNotEmpty(),
        onDoodleSize = { doodle.size = it },
        onDoodleColor = { doodle.color = it },
        onCancelDoodle = state::cancelDoodle,
        onUndo = doodle::undo,
        onRedo = doodle::redo,
        onCommitDoodle = state::commitDoodle,
        onCancelText = state::cancelOverlayEdits,
        onAddText = { state.overlays.beginTextAt(Offset(100f, 200f)) },
        onCommitText = state::commitOverlayEdits,
        onMode = state::selectEditMode,
        onShowConvert = state::setConvertVisible,
        onConvertWord = { actions.startOfficeConversion(ConvertTarget.WORD) },
        onConvertPpt = { actions.startOfficeConversion(ConvertTarget.PPT) },
        onShare = actions::shareAnnotatedPdf,
    )
}

@Composable
private fun ImportedPdfViewerDialogs(
    state: ImportedPdfViewerState,
    operations: PdfEditorOperationController,
) {
    val overlays = state.overlays
    if (overlays.showTextDialog) {
        AddTextDialog(
            text = overlays.textInput,
            color = overlays.textColor,
            size = overlays.textSize,
            position = overlays.tapPosition,
            onText = { overlays.textInput = it },
            onColor = { overlays.textColor = it },
            onSize = { overlays.textSize = it },
            onDismiss = overlays::dismissTextDialog,
            onAdd = overlays::addText,
        )
    }
    operations.errorMessage?.let { message ->
        PdfEditorOperationErrorDialog(message = message, onDismiss = operations::dismissError)
    }
}
