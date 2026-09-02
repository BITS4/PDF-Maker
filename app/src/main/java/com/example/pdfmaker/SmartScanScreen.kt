package com.example.pdfmaker

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.net.Uri
import android.view.OrientationEventListener
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import java.io.File

// ── Enums ─────────────────────────────────────────────────────────────────────

// ── Main composable ───────────────────────────────────────────────────────────

@Composable
fun SmartScanScreen(
    onDocsDone  : (List<Uri>) -> Unit,
    onIdCardDone: (frontUri: Uri, backUri: Uri?) -> Unit,
    onBack      : () -> Unit
) {
    val context        = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope          = rememberCoroutineScope()
    val haptic         = LocalHapticFeedback.current

    // ── Camera permission guard ───────────────────────────────────────────
    val hasCameraPermission = ContextCompat.checkSelfPermission(
        context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    if (!hasCameraPermission) {
        CameraPermissionDeniedScreen(onBack = onBack)
        return
    }


    // Camera state
    var imageCapture   by remember { mutableStateOf<ImageCapture?>(null) }
    var camera         by remember { mutableStateOf<Camera?>(null) }
    var torchOn        by remember { mutableStateOf(false) }
    var gridOn         by remember { mutableStateOf(false) }
    var isCapturing    by remember { mutableStateOf(false) }
    var showFlash      by remember { mutableStateOf(false) }

    // Physical device rotation — tracked by OrientationEventListener independently
    // of the portrait-locked screen so ImageCapture writes the correct EXIF tag.
    var deviceRotation by remember { mutableIntStateOf(Surface.ROTATION_0) }

    // Mode & ID-card flow
    var scanMode        by remember { mutableStateOf(ScanMode.DOCS) }
    var showIdCardSetup by remember { mutableStateOf(false) }
    var idCardSide      by remember { mutableStateOf(IdCardCaptureSide.NONE) }
    var idCardStep      by remember { mutableStateOf(IdCardStep.IDLE) }

    var frontFile by remember { mutableStateOf<File?>(null) }
    var backFile  by remember { mutableStateOf<File?>(null) }

    // Docs: list of corrected captures + thumbnails
    var capturedDocs by remember { mutableStateOf<List<CapturedDoc>>(emptyList()) }

    var previewWidth  by remember { mutableIntStateOf(0) }
    var previewHeight by remember { mutableIntStateOf(0) }

    val previewView = remember { PreviewView(context) }

    // ── Bind camera ───────────────────────────────────────────────────────────
    LaunchedEffect(previewWidth, previewHeight) {
        if (previewWidth <= 0 || previewHeight <= 0) return@LaunchedEffect
        val provider = context.getCameraProvider()
        val preview  = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setJpegQuality(97)
            .setTargetRotation(deviceRotation)
            .build()

        val viewport     = previewView.viewPort
        val useCaseGroup = if (viewport != null) {
            UseCaseGroup.Builder()
                .addUseCase(preview)
                .addUseCase(capture)
                .setViewPort(viewport)
                .build()
        } else null

        imageCapture = capture
        try {
            provider.unbindAll()
            camera = if (useCaseGroup != null) {
                provider.bindToLifecycle(
                    lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, useCaseGroup)
            } else {
                provider.bindToLifecycle(
                    lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
            }
        } catch (_: Exception) {}
    }

    LaunchedEffect(torchOn) { camera?.cameraControl?.enableTorch(torchOn) }
    LaunchedEffect(showFlash) { if (showFlash) { delay(120); showFlash = false } }

    // ── OrientationEventListener — the ONLY reliable fix for portrait-locked apps ──
    // Display.getRotation() always returns 0 when the screen is locked to portrait,
    // so ImageCapture would always write EXIF=0° and deliver sideways JPEGs.
    // OrientationEventListener reads the raw accelerometer and is not affected.
    DisposableEffect(Unit) {
        val orientationListener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val rot = when (orientation) {
                    in  45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else             -> Surface.ROTATION_0
                }
                deviceRotation = rot
                imageCapture?.setTargetRotation(rot)
            }
        }
        orientationListener.enable()
        onDispose { orientationListener.disable() }
    }

    // Turn off torch when navigating away
    DisposableEffect(Unit) {
        onDispose { camera?.cameraControl?.enableTorch(false) }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris -> if (uris.isNotEmpty()) onDocsDone(uris) }

    // ── Capture ───────────────────────────────────────────────────────────────
    fun doCapture() {
        val ic = imageCapture ?: return
        if (isCapturing) return
        isCapturing = true
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)

        val file     = File(context.cacheDir, "scan_${System.currentTimeMillis()}.jpg")
        val isIdCard = scanMode == ScanMode.ID_CARD && idCardSide != IdCardCaptureSide.NONE

        ic.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(out: ImageCapture.OutputFileResults) {
                    showFlash   = true
                    isCapturing = false

                    if (isIdCard) {
                        when {
                            idCardSide == IdCardCaptureSide.SINGLE -> {
                                scope.launch {
                                    val fixed = withContext(Dispatchers.IO) { bakeExifRotation(file) }
                                    delay(200)
                                    camera?.cameraControl?.enableTorch(false)
                                    onIdCardDone(Uri.fromFile(fixed), null)
                                }
                            }
                            idCardStep == IdCardStep.CAPTURE_FRONT -> {
                                scope.launch(Dispatchers.IO) {
                                    val fixed = bakeExifRotation(file)
                                    withContext(Dispatchers.Main) {
                                        frontFile  = fixed
                                        idCardStep = IdCardStep.FLIP_CARD
                                    }
                                }
                            }
                            idCardStep == IdCardStep.CAPTURE_BACK -> {
                                scope.launch {
                                    val fixed = withContext(Dispatchers.IO) { bakeExifRotation(file) }
                                    backFile = fixed
                                    delay(200)
                                    camera?.cameraControl?.enableTorch(false)
                                    val fUri = frontFile?.let { Uri.fromFile(it) }
                                    val bUri = backFile?.let  { Uri.fromFile(it) }
                                    if (fUri != null) onIdCardDone(fUri, bUri)
                                }
                            }
                            else -> { /* unreachable */ }
                        }
                    } else {
                        // Docs: bake rotation immediately → thumbnail → add to list
                        scope.launch(Dispatchers.IO) {
                            val corrected = bakeExifRotation(file)
                            val thumb     = smallThumb(corrected, 96)
                            withContext(Dispatchers.Main) {
                                capturedDocs = capturedDocs + CapturedDoc(corrected, thumb)
                            }
                        }
                    }
                }
                override fun onError(exc: ImageCaptureException) { isCapturing = false }
            }
        )
    }

    fun proceedDocs() {
        if (capturedDocs.isEmpty()) return
        camera?.cameraControl?.enableTorch(false)
        onDocsDone(capturedDocs.map { Uri.fromFile(it.file) })
    }

    // ── Corner animation ──────────────────────────────────────────────────────
    val transition  = rememberInfiniteTransition(label = "corners")
    val cornerAlpha by transition.animateFloat(
        0.65f, 1f,
        infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "ca"
    )

    // ── UI ────────────────────────────────────────────────────────────────────
    Box(Modifier.fillMaxSize().background(Color.Black)) {

        AndroidView(
            factory  = { previewView },
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { lc ->
                    if (lc.size.width  != previewWidth ||
                        lc.size.height != previewHeight) {
                        previewWidth  = lc.size.width
                        previewHeight = lc.size.height
                    }
                }
        )

        if (gridOn) GridOverlay()

        if (scanMode == ScanMode.DOCS) DocsCornerOverlay(cornerAlpha)
        else                           IdCardCornerOverlay(cornerAlpha)

        AnimatedVisibility(visible = showFlash, enter = fadeIn(tween(40)), exit = fadeOut(tween(120))) {
            Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.7f)))
        }

        // ── Top bar ───────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { torchOn = !torchOn }) {
                Icon(
                    if (torchOn) Icons.Default.FlashOn else Icons.Default.FlashOff, null,
                    tint = if (torchOn) Color(0xFFFFD700) else Color.White
                )
            }
            IconButton(onClick = { gridOn = !gridOn }) {
                Icon(Icons.Default.GridOn, null,
                    tint = if (gridOn) Color(0xFFFFD700) else Color.White)
            }
            IconButton(onClick = {}) {
                Icon(Icons.Default.MoreVert, null, tint = Color.White)
            }
        }

        // ── Step label ────────────────────────────────────────────────────────
        val stepLabel = when {
            idCardSide == IdCardCaptureSide.BOTH && idCardStep == IdCardStep.CAPTURE_FRONT -> "Front side"
            idCardSide == IdCardCaptureSide.BOTH && idCardStep == IdCardStep.CAPTURE_BACK  -> "Back side"
            else -> ""
        }
        AnimatedVisibility(
            visible  = stepLabel.isNotEmpty(),
            enter    = fadeIn() + scaleIn(initialScale = 0.92f),
            exit     = fadeOut(),
            modifier = Modifier.align(Alignment.Center).offset(y = (-130).dp)
        ) {
            Box(
                Modifier
                    .background(Color(0xCC000000), RoundedCornerShape(12.dp))
                    .padding(horizontal = 24.dp, vertical = 10.dp)
            ) {
                Text(stepLabel, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }

        // ── Bottom bar ────────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xBB0D0D16))
                .navigationBarsPadding()
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (idCardSide == IdCardCaptureSide.NONE) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = { scanMode = ScanMode.DOCS }) {
                        Text("Docs",
                            color    = if (scanMode == ScanMode.DOCS) Color(0xFFFFD700) else Color.Gray,
                            fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.width(32.dp))
                    TextButton(onClick = { scanMode = ScanMode.ID_CARD; showIdCardSetup = true }) {
                        Text("ID card",
                            color    = if (scanMode == ScanMode.ID_CARD) Color(0xFFFFD700) else Color.Gray,
                            fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    }
                }
            } else {
                Text(
                    if (idCardSide == IdCardCaptureSide.BOTH) "Both sides" else "Single side",
                    color = Color(0xFFFFD700), fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 8.dp),
                verticalAlignment   = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (scanMode == ScanMode.DOCS && capturedDocs.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(2.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                            .clickable { proceedDocs() }
                    ) {
                        Image(capturedDocs.last().thumb.asImageBitmap(), null,
                            contentScale = ContentScale.Crop,
                            modifier     = Modifier.fillMaxSize())
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(3.dp)
                                .size(20.dp).background(AccentBlue, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(capturedDocs.size.toString(),
                                color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    Spacer(Modifier.size(56.dp))
                }

                val shutterEnabled = !isCapturing && idCardStep != IdCardStep.FLIP_CARD
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .border(3.5.dp, Color.White, CircleShape)
                        .background(
                            if (!shutterEnabled) Color.Gray.copy(alpha = 0.3f)
                            else Color.White.copy(alpha = 0.12f),
                            CircleShape
                        )
                        .clickable(enabled = shutterEnabled) { doCapture() },
                    contentAlignment = Alignment.Center
                ) {
                    Box(Modifier.size(60.dp).background(
                        if (!shutterEnabled) Color.Gray else Color.White, CircleShape))
                }

                Column(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = 0.15f))
                        .clickable { galleryLauncher.launch("image/*") },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.PhotoLibrary, null,
                        tint = Color.White, modifier = Modifier.size(24.dp))
                    Text("Albums", color = Color.White.copy(alpha = 0.9f), fontSize = 9.sp)
                }
            }
        }

        // ── Flip card overlay ─────────────────────────────────────────────────
        AnimatedVisibility(
            visible  = idCardStep == IdCardStep.FLIP_CARD,
            enter    = fadeIn(tween(200)) + scaleIn(initialScale = 0.9f, animationSpec = tween(200)),
            exit     = fadeOut(tween(150)) + scaleOut(targetScale = 0.95f, animationSpec = tween(150)),
            modifier = Modifier.align(Alignment.Center)
        ) {
            FlipCardOverlay(onReady = { idCardStep = IdCardStep.CAPTURE_BACK })
        }

        // ── ID card setup overlay ─────────────────────────────────────────────
        if (showIdCardSetup) {
            IdCardSetupOverlay(
                onSelect = { side ->
                    idCardSide      = side
                    showIdCardSetup = false
                    frontFile       = null
                    backFile        = null
                    capturedDocs    = emptyList()
                    idCardStep      = if (side == IdCardCaptureSide.BOTH)
                                          IdCardStep.CAPTURE_FRONT
                                      else IdCardStep.IDLE
                },
                onDismiss = {
                    showIdCardSetup = false
                    scanMode        = ScanMode.DOCS
                    idCardSide      = IdCardCaptureSide.NONE
                    idCardStep      = IdCardStep.IDLE
                }
            )
        }
    }
}
