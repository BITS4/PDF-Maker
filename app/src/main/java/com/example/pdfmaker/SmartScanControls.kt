package com.example.pdfmaker

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun SmartScanTopControls(
    torchOn: Boolean,
    gridOn: Boolean,
    controlsEnabled: Boolean,
    onBack: () -> Unit,
    onToggleTorch: () -> Unit,
    onToggleGrid: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close scanner", tint = Color.White)
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onToggleTorch, enabled = controlsEnabled) {
            Icon(
                if (torchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                "Toggle torch",
                tint = if (torchOn) Color(0xFFFFD700) else Color.White,
            )
        }
        IconButton(onClick = onToggleGrid, enabled = controlsEnabled) {
            Icon(
                Icons.Default.GridOn,
                "Toggle alignment grid",
                tint = if (gridOn) Color(0xFFFFD700) else Color.White,
            )
        }
    }
}

@Composable
internal fun SmartScanStepLabel(
    side: IdCardCaptureSide,
    step: IdCardStep,
    modifier: Modifier = Modifier,
) {
    val label =
        when {
            side == IdCardCaptureSide.BOTH && step == IdCardStep.CAPTURE_FRONT -> "Front side"
            side == IdCardCaptureSide.BOTH && step == IdCardStep.CAPTURE_BACK -> "Back side"
            else -> ""
        }
    AnimatedVisibility(
        visible = label.isNotEmpty(),
        enter = fadeIn() + scaleIn(initialScale = 0.92f),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Box(
            Modifier
                .background(Color(0xCC000000), RoundedCornerShape(12.dp))
                .padding(horizontal = 24.dp, vertical = 10.dp),
        ) {
            Text(label, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
internal fun SmartScanBottomControls(
    state: SmartScanControlsState,
    onSelectDocs: () -> Unit,
    onSelectIdCard: () -> Unit,
    onProceedDocs: () -> Unit,
    onCapture: () -> Unit,
    onOpenGallery: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reachedDocumentLimit = state.capturedDocs.size >= SmartScanPolicy.MAX_DOCUMENT_PAGES
    val controlsEnabled = !state.isCapturing
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(Color(0xBB0D0D16))
                .navigationBarsPadding()
                .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SmartScanModeSelector(
            state = state,
            controlsEnabled = controlsEnabled,
            onSelectDocs = onSelectDocs,
            onSelectIdCard = onSelectIdCard,
        )

        if (reachedDocumentLimit && state.scanMode == ScanMode.DOCS) {
            Text(
                "${SmartScanPolicy.MAX_DOCUMENT_PAGES}-page limit reached",
                color = Color(0xFFFFD166),
                fontSize = 11.sp,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            CapturedDocumentButton(state.capturedDocs, onProceedDocs)

            val shutterEnabled =
                controlsEnabled &&
                    state.idCardStep != IdCardStep.FLIP_CARD &&
                    !(state.scanMode == ScanMode.DOCS && reachedDocumentLimit)
            Box(
                modifier =
                    Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .border(3.5.dp, Color.White, CircleShape)
                        .background(
                            if (shutterEnabled) Color.White.copy(alpha = 0.12f) else Color.Gray.copy(alpha = 0.3f),
                            CircleShape,
                        ).clickable(enabled = shutterEnabled, onClick = onCapture),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(60.dp)
                        .background(if (shutterEnabled) Color.White else Color.Gray, CircleShape),
                )
            }

            val galleryEnabled =
                controlsEnabled && state.scanMode == ScanMode.DOCS && !reachedDocumentLimit
            Column(
                modifier =
                    Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = if (galleryEnabled) 0.15f else 0.07f))
                        .clickable(enabled = galleryEnabled, onClick = onOpenGallery),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Default.PhotoLibrary,
                    contentDescription = "Open albums",
                    tint = Color.White.copy(alpha = if (galleryEnabled) 1f else 0.4f),
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    "Albums",
                    color = Color.White.copy(alpha = if (galleryEnabled) 0.9f else 0.4f),
                    fontSize = 9.sp,
                )
            }
        }
    }
}

@Composable
private fun SmartScanModeSelector(
    state: SmartScanControlsState,
    controlsEnabled: Boolean,
    onSelectDocs: () -> Unit,
    onSelectIdCard: () -> Unit,
) {
    if (state.idCardSide != IdCardCaptureSide.NONE) {
        Text(
            if (state.idCardSide == IdCardCaptureSide.BOTH) "Both sides" else "Single side",
            color = Color(0xFFFFD700),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        TextButton(onClick = onSelectDocs, enabled = controlsEnabled) {
            Text(
                "Docs",
                color = if (state.scanMode == ScanMode.DOCS) Color(0xFFFFD700) else Color.Gray,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.width(32.dp))
        TextButton(onClick = onSelectIdCard, enabled = controlsEnabled) {
            Text(
                "ID card",
                color = if (state.scanMode == ScanMode.ID_CARD) Color(0xFFFFD700) else Color.Gray,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun CapturedDocumentButton(
    capturedDocs: List<CapturedDoc>,
    onProceed: () -> Unit,
) {
    if (capturedDocs.isEmpty()) {
        Spacer(Modifier.size(56.dp))
        return
    }
    Box(
        modifier =
            Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(2.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                .clickable(onClick = onProceed),
    ) {
        Image(
            capturedDocs.last().thumb.asImageBitmap(),
            contentDescription = "Continue with captured pages",
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
        )
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(3.dp)
                .size(20.dp)
                .background(AccentBlue, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                capturedDocs.size.toString(),
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
internal fun SmartScanErrorBanner(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        color = Color(0xEE3A2020),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.WarningAmber, contentDescription = null, tint = Color(0xFFFFB4AB))
            Text(
                text = message,
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss error", tint = Color.White)
            }
        }
    }
}
