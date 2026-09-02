package com.example.pdfmaker

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
fun SmartScanScreen(
    onDocsDone: (List<Uri>) -> Unit,
    onIdCardDone: (frontUri: Uri, backUri: Uri?) -> Unit,
    onBack: () -> Unit,
) {
    val permissionState = rememberSmartScanPermissionState()
    if (!SmartScanPermissionPolicy.canBindCamera(permissionState.status)) {
        CameraPermissionDeniedScreen(
            status = permissionState.status,
            onRequestPermission = permissionState.requestPermission,
            onOpenSettings = permissionState.openSettings,
            onBack = onBack,
        )
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val latestHaptic = rememberUpdatedState(haptic)
    val latestDocsDone = rememberUpdatedState(onDocsDone)
    val latestIdCardDone = rememberUpdatedState(onIdCardDone)
    val state =
        remember(context, scope) {
            SmartScanSessionState(
                context = context,
                scope = scope,
                workspace = SmartScanWorkspace.create(context.cacheDir),
                performCaptureHaptic = {
                    latestHaptic.value.performHapticFeedback(HapticFeedbackType.LongPress)
                },
                onDocsDone = { uris -> latestDocsDone.value(uris) },
                onIdCardDone = { front, back -> latestIdCardDone.value(front, back) },
            )
        }

    SmartScanWorkspaceEffects(state, context.cacheDir)
    SmartScanCameraBindingEffect(state, LocalLifecycleOwner.current)
    SmartScanCameraStateEffects(state)
    SmartScanOrientationEffect(state)

    val galleryLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            state.handoffDocuments(uris)
        }
    SmartScanContent(
        state = state,
        onBack = onBack,
        onOpenGallery = { galleryLauncher.launch("image/*") },
    )
}

@Composable
private fun SmartScanContent(
    state: SmartScanSessionState,
    onBack: () -> Unit,
    onOpenGallery: () -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "corners")
    val cornerAlpha by transition.animateFloat(
        initialValue = 0.65f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "corner alpha",
    )

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        SmartScanCameraPreview(
            previewView = state.previewView,
            scanMode = state.scanMode,
            gridOn = state.gridOn,
            showFlash = state.showFlash,
            cornerAlpha = cornerAlpha,
            onSizeChanged = state::updatePreviewSize,
        )
        SmartScanTopControls(
            torchOn = state.torchOn,
            gridOn = state.gridOn,
            controlsEnabled = !state.isCapturing,
            onBack = onBack,
            onToggleTorch = state::toggleTorch,
            onToggleGrid = state::toggleGrid,
        )
        state.errorMessage?.let { message ->
            SmartScanErrorBanner(
                message = message,
                onDismiss = state::dismissError,
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 56.dp),
            )
        }
        SmartScanStepLabel(
            side = state.idCardSide,
            step = state.idCardStep,
            modifier = Modifier.align(Alignment.Center).offset(y = (-130).dp),
        )
        SmartScanBottomControls(
            state =
                SmartScanControlsState(
                    scanMode = state.scanMode,
                    idCardSide = state.idCardSide,
                    idCardStep = state.idCardStep,
                    isCapturing = state.isCapturing,
                    capturedDocs = state.capturedDocs,
                ),
            onSelectDocs = state::selectDocumentMode,
            onSelectIdCard = state::openIdCardSetup,
            onProceedDocs = { state.handoffDocuments(emptyList()) },
            onCapture = state::capture,
            onOpenGallery = onOpenGallery,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        AnimatedVisibility(
            visible = state.idCardStep == IdCardStep.FLIP_CARD,
            enter = fadeIn(tween(200)) + scaleIn(initialScale = 0.9f, animationSpec = tween(200)),
            exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.95f, animationSpec = tween(150)),
            modifier = Modifier.align(Alignment.Center),
        ) {
            FlipCardOverlay(onReady = state::readyForBackCapture)
        }
        if (state.showIdCardSetup) {
            IdCardSetupOverlay(
                onSelect = state::selectIdCardSide,
                onDismiss = state::dismissIdCardSetup,
            )
        }
    }
}
