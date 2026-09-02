package com.example.pdfmaker

import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.viewinterop.AndroidView

@Composable
internal fun SmartScanCameraPreview(
    previewView: PreviewView,
    scanMode: ScanMode,
    gridOn: Boolean,
    showFlash: Boolean,
    cornerAlpha: Float,
    onSizeChanged: (width: Int, height: Int) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { previewView },
            modifier =
                Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { coordinates ->
                        onSizeChanged(coordinates.size.width, coordinates.size.height)
                    },
        )

        if (gridOn) GridOverlay()
        if (scanMode == ScanMode.DOCS) {
            DocsCornerOverlay(cornerAlpha)
        } else {
            IdCardCornerOverlay(cornerAlpha)
        }

        AnimatedVisibility(
            visible = showFlash,
            enter = fadeIn(tween(40)),
            exit = fadeOut(tween(120)),
        ) {
            Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.7f)))
        }
    }
}
