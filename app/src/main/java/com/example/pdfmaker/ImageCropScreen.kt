package com.example.pdfmaker

import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.*

// ── Screen ────────────────────────────────────────────────────────────────────

@Composable
// Cancellation is rethrown at each operation; decoding and geometry remain isolated in tested helpers.
@Suppress("CyclomaticComplexMethod", "LongMethod", "ThrowsCount")
fun ImageCropScreen(
    editState  : ImageEditState,
    pageIndex  : Int,
    totalPages : Int,
    isIdCard   : Boolean = false,        // controls page labels (Front/Back vs Page N)
    onNext     : () -> Unit,
    onBack     : () -> Unit,
    onRetake   : (() -> Unit)? = null    // shown only when coming from Smart Scan
) {
    val scope   = rememberCoroutineScope()
    val context = LocalContext.current

    // Phase 1: isLoading — bitmap being decoded from disk
    // Phase 2: isDetecting — Canny+Hough running, image already visible
    // Start isLoading=true immediately if bitmap not yet in memory so the
    // skeleton shows on the very first frame — no black flash before LaunchedEffect runs.
    var isLoading    by remember { mutableStateOf(editState.originalBitmap == null) }
    var isDetecting  by remember { mutableStateOf(false) }
    var quad         by remember { mutableStateOf(Quad()) }
    var autoDetected by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }

    // Two-phase load:
    //   Phase 1 (IO thread)      — decode JPEG with EXIF correction → show image
    //   Phase 2 (Default thread) — run Canny+Hough → snap corner handles
    // The screen is never black; it shows a spinner only while decoding, then
    // the image immediately, then the handles animate in.
    LaunchedEffect(editState.uri) {
        if (editState.originalBitmap == null) {
            // ── Phase 1: decode ───────────────────────────────────────────────
            isLoading = true
            var decoded: android.graphics.Bitmap? = null
            try {
                decoded = withContext(Dispatchers.IO) {
                    BoundedImageDecoder.decode(context, editState.uri).getOrThrow()
                }
                currentCoroutineContext().ensureActive()
                val bmp = requireNotNull(decoded)
                BitmapOwnership.retire(editState.installSource(bmp))
                decoded = null
                isLoading = false

                if (!autoDetected) {
                    isDetecting = true
                    quad = withContext(Dispatchers.Default) { autoDetectQuad(bmp) }
                    autoDetected = true
                    isDetecting = false
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                editState.loadError = "This image could not be loaded safely."
                isDetecting = false
            } finally {
                decoded?.let { BitmapOwnership.retire(listOf(it)) }
                isLoading = false
            }
        } else if (!autoDetected) {
            // Bitmap already in memory (returning from edit screen)
            val bmp = editState.originalBitmap!!
            isDetecting = true
            try {
                quad = withContext(Dispatchers.Default) { autoDetectQuad(bmp) }
                autoDetected = true
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                editState.loadError = "Document edges could not be detected."
            } finally {
                isDetecting = false
            }
        }
    }

    val bitmap = editState.displayBitmap ?: editState.originalBitmap

    // imageRect is NOT Compose state — it's a plain var updated each draw call.
    // This is the key fix for the persistent black-screen bug:
    // Computing imageRect in state (LaunchedEffect / onGloballyPositioned) always
    // races against the bitmap load — the first draw runs before either fires,
    // so imageRect is Rect.Zero → bitmap drawn to a 0×0 destination → invisible.
    // By computing it fresh inside the DrawScope using DrawScope.size (always current),
    // we guarantee imageRect is valid on the very first non-null bitmap frame.
    var imageRect = Rect.Zero                 // updated every draw; read by touch handlers
    var dragIndex  by remember { mutableStateOf(-1) }
    val hrDp       = 20.dp
    val hrPx       = with(LocalDensity.current) { hrDp.toPx() }
    fun normToCanvas(p:PointF) = Offset(imageRect.left+p.x*imageRect.width, imageRect.top+p.y*imageRect.height)
    fun nearestHandle(x:Float,y:Float): Int {
        val touch=Offset(x,y)
        return quad.points().indexOfFirst{pt->(normToCanvas(pt)-touch).getDistance()<hrPx*2.0f}
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // ── Top bar ───────────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White,
                modifier = Modifier.size(26.dp).clickable { onBack() })
            Spacer(Modifier.width(10.dp))
            val isIdCard = ImageToPdfState.isIdCardScan
            val title = when {
                isIdCard && pageIndex == 0 -> "Crop — Front side"
                isIdCard && pageIndex == 1 -> "Crop — Back side"
                totalPages > 1             -> "Crop — Page ${pageIndex + 1} of $totalPages"
                else                       -> "Smart Crop"
            }
            Text(title, color=Color.White, fontSize=16.sp,
                fontWeight=FontWeight.SemiBold, modifier=Modifier.weight(1f))
            // Manual re-trigger button
            IconButton(onClick = {
                if (isDetecting || isProcessing) return@IconButton
                val bmp = bitmap ?: return@IconButton
                isDetecting = true
                scope.launch {
                    try {
                        quad = withContext(Dispatchers.Default) { autoDetectQuad(bmp) }
                    } finally {
                        isDetecting = false
                    }
                }
            }) {
                Icon(Icons.Default.AutoFixHigh, "Auto detect", tint = AccentBlue)
            }
        }

        // ── Crop canvas ───────────────────────────────────────────────────────
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // Phase 1: shimmer skeleton while JPEG is decoded from disk
            androidx.compose.animation.AnimatedVisibility(
                visible = isLoading,
                enter   = androidx.compose.animation.fadeIn(tween(150)),
                exit    = androidx.compose.animation.fadeOut(tween(300))
            ) {
                CropLoadingSkeleton()
            }
            if (!isLoading && bitmap == null && editState.loadError != null) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Default.BrokenImage, null, tint = BadgeRed)
                    Spacer(Modifier.height(8.dp))
                    Text(editState.loadError.orEmpty(), color = Color.White, fontSize = 13.sp)
                }
            }
            // Phase 2: small badge while Canny+Hough runs — image already visible
            if (isDetecting && !isLoading) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                        .background(Color(0xCC111122), RoundedCornerShape(20.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = AccentBlue, strokeWidth = 2.dp,
                            modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Detecting edges…", color = Color.White.copy(alpha = 0.85f),
                            fontSize = 12.sp)
                    }
                }
            }
            Canvas(
                modifier = Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { o -> dragIndex = nearestHandle(o.x, o.y) },
                            onDrag = { _, delta ->
                                if (dragIndex < 0) return@detectDragGestures
                                val cur = quad.points()[dragIndex]
                                quad = quad.withPoint(dragIndex, PointF(
                                    (cur.x + delta.x/imageRect.width).coerceIn(0f,1f),
                                    (cur.y + delta.y/imageRect.height).coerceIn(0f,1f)
                                ))
                            },
                            onDragEnd = { dragIndex = -1 }
                        )
                    }
            ) {
                if (bitmap == null) return@Canvas

                // Compute imageRect fresh every draw from DrawScope.size.
                // This always works — size is the actual canvas size right now.
                // Update the outer var so touch handlers can read the latest rect.
                val bA = bitmap.width.toFloat() / bitmap.height
                val cA = size.width / size.height
                imageRect = if (bA > cA) {
                    val w = size.width; val h = w / bA
                    Rect(0f, (size.height-h)/2f, w, (size.height+h)/2f)
                } else {
                    val h = size.height; val w = h * bA
                    Rect((size.width-w)/2f, 0f, (size.width+w)/2f, size.height)
                }

                // Draw photo
                drawIntoCanvas { c ->
                    val dst = android.graphics.RectF(imageRect.left,imageRect.top,imageRect.right,imageRect.bottom)
                    c.nativeCanvas.drawBitmap(bitmap, null, dst,
                        android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                }

                val pts = quad.points().map { normToCanvas(it) }

                // Semi-transparent mask outside the crop quad
                drawIntoCanvas { c ->
                    val paint = android.graphics.Paint().apply { color=0xAA000000.toInt() }
                    val saved = c.nativeCanvas.save()
                    c.nativeCanvas.clipOutPath(android.graphics.Path().apply {
                        moveTo(pts[0].x,pts[0].y); lineTo(pts[1].x,pts[1].y)
                        lineTo(pts[2].x,pts[2].y); lineTo(pts[3].x,pts[3].y); close()
                    })
                    c.nativeCanvas.drawRect(imageRect.left,imageRect.top,imageRect.right,imageRect.bottom,paint)
                    c.nativeCanvas.restoreToCount(saved)
                }

                // Quad border
                drawPath(Path().apply {
                    moveTo(pts[0].x,pts[0].y); lineTo(pts[1].x,pts[1].y)
                    lineTo(pts[2].x,pts[2].y); lineTo(pts[3].x,pts[3].y); close()
                }, color=Color(0xFF4F8EF7), style=Stroke(width=2.5.dp.toPx()))

                // Edge mid-point pill handles
                listOf(
                    (pts[0]+pts[1])/2f, (pts[1]+pts[2])/2f,
                    (pts[2]+pts[3])/2f, (pts[3]+pts[0])/2f
                ).forEach { mid ->
                    drawRoundRect(
                        color=Color.White.copy(alpha=0.85f),
                        topLeft=mid-Offset(14.dp.toPx(),5.dp.toPx()),
                        size=Size(28.dp.toPx(),10.dp.toPx()),
                        cornerRadius=androidx.compose.ui.geometry.CornerRadius(5.dp.toPx())
                    )
                }

                // Corner handles: glow ring + circle border + white dot
                pts.forEachIndexed { idx, pt ->
                    val active = dragIndex==idx
                    drawCircle(color=Color(0xFF4F8EF7).copy(alpha=if(active)0.40f else 0.18f),
                        radius=hrPx*1.3f, center=pt)
                    drawCircle(color=Color(0xFF4F8EF7), radius=hrPx*0.90f, center=pt,
                        style=Stroke(width=2.5.dp.toPx()))
                    drawCircle(color=Color.White, radius=hrPx*0.50f, center=pt)
                }
            }
        }

        // ── Page counter ──────────────────────────────────────────────────────
        if (totalPages > 1) {
            Box(Modifier.fillMaxWidth().background(Color.Black).padding(vertical=6.dp),
                contentAlignment=Alignment.Center) {
                Box(Modifier.background(Color(0xAA111122),RoundedCornerShape(16.dp))
                    .padding(horizontal=18.dp,vertical=4.dp)) {
                    Text("${pageIndex+1} / $totalPages",
                        color=Color.White, fontSize=13.sp, fontWeight=FontWeight.SemiBold)
                }
            }
        }

        // ── Bottom toolbar ────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0xFF0D0D14))
                .navigationBarsPadding().padding(horizontal=16.dp,vertical=14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Retake — only when came from Smart Scan
            if (onRetake != null) {
                EditControlBtn(Icons.Default.CameraAlt, "Retake",
                    tint=Color(0xFFFF9800)) { onRetake() }
                Spacer(Modifier.width(14.dp))
            }

            // Rotate left  (re-runs auto-detect after rotation)
            EditControlBtn(Icons.AutoMirrored.Filled.RotateLeft, "Left",
                tint = if (isLoading || bitmap == null) Color.White.copy(0.3f) else Color.White) {
                if (isProcessing) return@EditControlBtn
                val bmp = editState.displayBitmap ?: editState.originalBitmap ?: return@EditControlBtn
                isProcessing = true
                scope.launch {
                    var rotated: android.graphics.Bitmap? = null
                    try {
                        rotated = withContext(Dispatchers.Default) {
                            ImageProcessing.rotateBitmap(bmp, -90f)
                        }
                        currentCoroutineContext().ensureActive()
                        val preview = requireNotNull(rotated)
                        BitmapOwnership.retire(editState.installCropPreview(preview))
                        rotated = null
                        quad = withContext(Dispatchers.Default) { autoDetectQuad(preview) }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        editState.loadError = "This image could not be rotated."
                    } finally {
                        rotated?.let { BitmapOwnership.retire(listOf(it)) }
                        isProcessing = false
                    }
                }
            }
            Spacer(Modifier.width(14.dp))

            // No Crop — reset to full image
            TextButton(onClick = {
                quad = Quad(PointF(0f,0f),PointF(1f,0f),PointF(1f,1f),PointF(0f,1f))
            }) {
                Icon(Icons.Default.CropFree, null, tint=Color.White, modifier=Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("No Crop", color=Color.White, fontSize=13.sp)
            }

            Spacer(Modifier.weight(1f))

            // NEXT — apply perspective warp, then go to edit screen
            Button(
                onClick = {
                    val bmp = editState.displayBitmap ?: editState.originalBitmap ?: return@Button
                    isProcessing = true
                    scope.launch {
                        var warped: android.graphics.Bitmap? = null
                        try {
                            warped = withContext(Dispatchers.Default) { perspectiveWarp(bmp, quad) }
                            currentCoroutineContext().ensureActive()
                            BitmapOwnership.retire(
                                editState.commitCroppedSource(requireNotNull(warped)),
                            )
                            warped = null
                            isProcessing = false
                            onNext()
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            editState.loadError = "This image could not be cropped."
                            isProcessing = false
                        } finally {
                            warped?.let { BitmapOwnership.retire(listOf(it)) }
                        }
                    }
                },
                enabled  = !isProcessing && !isLoading && !isDetecting && bitmap != null,
                colors   = ButtonDefaults.buttonColors(containerColor=AccentBlue),
                shape    = RoundedCornerShape(24.dp),
                contentPadding = PaddingValues(horizontal=28.dp,vertical=12.dp)
            ) {
                if (isProcessing) CircularProgressIndicator(color=Color.White,
                    modifier=Modifier.size(18.dp), strokeWidth=2.dp)
                else Text("NEXT", color=Color.White, fontWeight=FontWeight.Bold, fontSize=15.sp)
            }
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────
private operator fun Offset.plus(o:Offset)  = Offset(x+o.x, y+o.y)
private operator fun Offset.div(s:Float)    = Offset(x/s, y/s)
private operator fun Offset.minus(o:Offset) = Offset(x-o.x, y-o.y)
