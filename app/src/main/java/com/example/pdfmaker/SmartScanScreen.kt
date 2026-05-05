package com.example.pdfmaker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.view.OrientationEventListener
import android.view.Surface
import androidx.exifinterface.media.ExifInterface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.max

// ── Enums ─────────────────────────────────────────────────────────────────────

enum class IdCardCaptureSide { NONE, SINGLE, BOTH }

private enum class IdCardStep { IDLE, CAPTURE_FRONT, FLIP_CARD, CAPTURE_BACK }

// ── Top-level data class — must be outside the composable for type inference ──

data class CapturedDoc(val file: File, val thumb: Bitmap)

// ── CameraX coroutine helper ──────────────────────────────────────────────────

private suspend fun Context.getCameraProvider(): ProcessCameraProvider =
    suspendCancellableCoroutine { cont ->
        ProcessCameraProvider.getInstance(this).also { future ->
            future.addListener({ cont.resume(future.get()) },
                ContextCompat.getMainExecutor(this))
        }
    }

// ── Image helpers — top-level so they can be called from any coroutine ────────

/**
 * Read the EXIF rotation tag, rotate pixels accordingly, write a corrected JPEG,
 * and clear the EXIF orientation to NORMAL. After this call the file is always
 * upright — no downstream code ever needs to read EXIF again.
 */
private fun bakeExifRotation(src: File): File {
    val degrees = try {
        when (ExifInterface(src.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
        )) {
            ExifInterface.ORIENTATION_ROTATE_90  ->  90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else                                 ->   0f
        }
    } catch (_: Exception) { 0f }

    if (degrees == 0f) return src   // already upright — nothing to do

    // Sample down to ≤2048 px longest side to keep rotation memory safe
    val sizeOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(src.absolutePath, sizeOpts)
    var s = 1
    while (max(sizeOpts.outWidth, sizeOpts.outHeight) / s > 2048) s *= 2

    val raw = BitmapFactory.decodeFile(
        src.absolutePath,
        BitmapFactory.Options().apply {
            inSampleSize      = s
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
    ) ?: return src

    val rotated = Bitmap.createBitmap(
        raw, 0, 0, raw.width, raw.height,
        Matrix().apply { postRotate(degrees) }, true
    ).also { if (it !== raw) raw.recycle() }

    val out = File(src.parent, "corrected_${System.currentTimeMillis()}.jpg")
    out.outputStream().use { rotated.compress(Bitmap.CompressFormat.JPEG, 95, it) }
    rotated.recycle()

    // Mark as NORMAL so nothing re-rotates it
    try {
        ExifInterface(out.absolutePath).run {
            setAttribute(ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL.toString())
            saveAttributes()
        }
    } catch (_: Exception) {}

    return out
}

/** Decode a small thumbnail from an already-upright file (pixels already baked). */
private fun smallThumb(file: File, sizePx: Int): Bitmap {
    val sizeOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, sizeOpts)
    var s = 1
    while (max(sizeOpts.outWidth, sizeOpts.outHeight) / s > sizePx * 2) s *= 2
    return BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = s }
    ) ?: Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
}

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

// ── Flip-card overlay ─────────────────────────────────────────────────────────

@Composable
private fun FlipCardOverlay(onReady: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth(0.80f)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xF0141420))
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier.size(72.dp).background(Color(0xFF1E2240), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.CreditCard, null, tint = Color(0xFFFFD700),
                modifier = Modifier.size(40.dp))
        }
        Text("Front side captured!", color = Color.White,
            fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text("Now flip the card over and position the back side inside the frame.",
            color = Color(0xFFCCCCCC), fontSize = 14.sp, textAlign = TextAlign.Center)
        Button(
            onClick  = onReady,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFD700)),
            shape    = RoundedCornerShape(25.dp)
        ) {
            Text("Ready — scan back side",
                color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}

// ── Canvas overlays ───────────────────────────────────────────────────────────

@Composable
fun GridOverlay() {
    Canvas(Modifier.fillMaxSize()) {
        val sw  = 0.8.dp.toPx()
        val col = Color.White.copy(alpha = 0.38f)
        drawLine(col, Offset(size.width / 3f,   0f), Offset(size.width / 3f,   size.height), sw)
        drawLine(col, Offset(size.width * 2/3f, 0f), Offset(size.width * 2/3f, size.height), sw)
        drawLine(col, Offset(0f, size.height / 3f),  Offset(size.width, size.height / 3f),   sw)
        drawLine(col, Offset(0f, size.height * 2/3f),Offset(size.width, size.height * 2/3f), sw)
    }
}

@Composable
fun DocsCornerOverlay(alpha: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        val l = w * 0.1f; val t = h * 0.14f; val r = w - l; val b = h - t
        val len = 38.dp.toPx(); val sw = 3.dp.toPx()
        val col = Color(0xFF4F8EF7).copy(alpha = alpha)
        drawLine(col, Offset(l, t + len), Offset(l, t),       sw)
        drawLine(col, Offset(l, t),       Offset(l + len, t), sw)
        drawLine(col, Offset(r - len, t), Offset(r, t),       sw)
        drawLine(col, Offset(r, t),       Offset(r, t + len), sw)
        drawLine(col, Offset(r, b - len), Offset(r, b),       sw)
        drawLine(col, Offset(r, b),       Offset(r - len, b), sw)
        drawLine(col, Offset(l, b - len), Offset(l, b),       sw)
        drawLine(col, Offset(l, b),       Offset(l + len, b), sw)
    }
}

@Composable
fun IdCardCornerOverlay(alpha: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val w  = size.width; val h = size.height
        val cW = w * 0.84f; val cH = cW / 1.586f
        val l  = (w - cW) / 2f
        val t  = h / 2f - cH / 2f - h * 0.04f
        val r  = l + cW; val b = t + cH
        val len = 30.dp.toPx(); val sw = 3.5.dp.toPx()
        val col = Color(0xFFFFD700).copy(alpha = alpha)
        drawLine(col, Offset(l, t + len), Offset(l, t),       sw)
        drawLine(col, Offset(l, t),       Offset(l + len, t), sw)
        drawLine(col, Offset(r - len, t), Offset(r, t),       sw)
        drawLine(col, Offset(r, t),       Offset(r, t + len), sw)
        drawLine(col, Offset(r, b - len), Offset(r, b),       sw)
        drawLine(col, Offset(r, b),       Offset(r - len, b), sw)
        drawLine(col, Offset(l, b - len), Offset(l, b),       sw)
        drawLine(col, Offset(l, b),       Offset(l + len, b), sw)
        val dim = Color.Black.copy(alpha = 0.45f)
        drawRect(dim, size = androidx.compose.ui.geometry.Size(w, t))
        drawRect(dim, topLeft = Offset(0f, b), size = androidx.compose.ui.geometry.Size(w, h - b))
        drawRect(dim, topLeft = Offset(0f, t), size = androidx.compose.ui.geometry.Size(l, cH))
        drawRect(dim, topLeft = Offset(r,  t), size = androidx.compose.ui.geometry.Size(w - r, cH))
    }
}

// ── ID card setup overlay ─────────────────────────────────────────────────────

@Composable
fun IdCardSetupOverlay(
    onSelect : (IdCardCaptureSide) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember { mutableStateOf(IdCardCaptureSide.BOTH) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.62f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .clip(RoundedCornerShape(18.dp))
                .background(Color.White)
                .clickable {}
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .background(Color(0xFFF2F2F2), RoundedCornerShape(10.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().height(58.dp)
                        .background(Color(0xFFDDE4FF), RoundedCornerShape(6.dp))
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(42.dp)
                        .background(Color(0xFFBBBBBB), RoundedCornerShape(4.dp)),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Person, null,
                            tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Box(Modifier.width(90.dp).height(7.dp)
                            .background(Color(0xFFAAAAAA), RoundedCornerShape(3.dp)))
                        Box(Modifier.width(120.dp).height(7.dp)
                            .background(Color(0xFFAAAAAA), RoundedCornerShape(3.dp)))
                        Box(Modifier.width(60.dp).height(7.dp)
                            .background(Color(0xFFAAAAAA), RoundedCornerShape(3.dp)))
                    }
                }
                Column(
                    Modifier.fillMaxWidth().height(52.dp)
                        .background(Color(0xFFE8F5E9), RoundedCornerShape(6.dp))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    repeat(3) {
                        Box(Modifier.fillMaxWidth().height(6.dp)
                            .background(Color(0xFF9E9E9E), RoundedCornerShape(2.dp)))
                        if (it < 2) Spacer(Modifier.height(4.dp))
                    }
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.fillMaxWidth(0.65f).height(6.dp)
                        .background(Color(0xFF9E9E9E), RoundedCornerShape(2.dp)))
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "Scans are placed on a single PDF page. Your data is never shared.",
                color = Color.Gray, fontSize = 11.sp, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))

            Row(
                Modifier.fillMaxWidth()
                    .background(Color(0xFFF0F0F0), RoundedCornerShape(12.dp))
            ) {
                listOf(
                    IdCardCaptureSide.SINGLE to "Single side",
                    IdCardCaptureSide.BOTH   to "Both sides"
                ).forEach { (side, label) ->
                    val isSel = selected == side
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSel) AccentBlue else Color.Transparent)
                            .clickable { selected = side }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label,
                            color      = if (isSel) Color.White else Color.Gray,
                            fontSize   = 14.sp,
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Button(
                onClick  = { onSelect(selected) },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors   = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                shape    = RoundedCornerShape(28.dp)
            ) {
                Text("Scan now", color = Color.White,
                    fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
}
