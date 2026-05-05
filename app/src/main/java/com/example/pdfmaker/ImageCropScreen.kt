package com.example.pdfmaker

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.Brush
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.*

// ── Quad ──────────────────────────────────────────────────────────────────────

data class Quad(
    val tl: PointF = PointF(0.10f, 0.10f),
    val tr: PointF = PointF(0.90f, 0.10f),
    val br: PointF = PointF(0.90f, 0.90f),
    val bl: PointF = PointF(0.10f, 0.90f)
) {
    fun points() = listOf(tl, tr, br, bl)
    fun withPoint(idx: Int, pt: PointF) = when (idx) {
        0 -> copy(tl = pt); 1 -> copy(tr = pt)
        2 -> copy(br = pt); else -> copy(bl = pt)
    }
}

// ── Perspective warp ──────────────────────────────────────────────────────────

fun perspectiveWarp(src: Bitmap, quad: Quad): Bitmap {
    val w = src.width.toFloat(); val h = src.height.toFloat()
    val tl = PointF(quad.tl.x * w, quad.tl.y * h)
    val tr = PointF(quad.tr.x * w, quad.tr.y * h)
    val br = PointF(quad.br.x * w, quad.br.y * h)
    val bl = PointF(quad.bl.x * w, quad.bl.y * h)
    fun dist(a: PointF, b: PointF) = sqrt((b.x-a.x).pow(2)+(b.y-a.y).pow(2))
    val outW = ((dist(tl,tr)+dist(bl,br))/2f).coerceAtLeast(10f)
    val outH = ((dist(tl,bl)+dist(tr,br))/2f).coerceAtLeast(10f)
    val dst = Bitmap.createBitmap(outW.toInt(), outH.toInt(), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(dst)
    canvas.drawColor(android.graphics.Color.WHITE)
    val matrix = Matrix()
    matrix.setPolyToPoly(
        floatArrayOf(tl.x,tl.y,tr.x,tr.y,br.x,br.y,bl.x,bl.y),0,
        floatArrayOf(0f,0f,outW,0f,outW,outH,0f,outH),0,4)
    canvas.drawBitmap(src, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
    return dst
}

// ── Professional auto-detect: Canny edge detection + Hough Transform ─────────
//
// Pipeline:
//   1. Resize to ≤512px longest side
//   2. Grayscale
//   3. 5×5 Gaussian blur  — noise suppression
//   4. Sobel gx/gy        — gradient magnitude + direction
//   5. Non-maximum suppression — thin edges to 1px
//   6. Hysteresis thresholding — keep strong, connect weak
//   7. Hough Transform    — (ρ,θ) accumulator for all lines
//   8. Peak extraction    — local max with neighbourhood suppression
//   9. Classify peaks     — horizontal (~90°) vs vertical (~0°/180°)
//  10. Intersect 4 lines  — TL, TR, BR, BL corners
//  11. Validate + fallback

fun autoDetectQuad(bmp: Bitmap): Quad {
    // 1. Resize
    val procMax = 512
    val sc  = procMax.toFloat() / maxOf(bmp.width, bmp.height)
    val pw  = (bmp.width  * sc).toInt().coerceAtLeast(1)
    val ph  = (bmp.height * sc).toInt().coerceAtLeast(1)
    val sml = Bitmap.createScaledBitmap(bmp, pw, ph, true)
    val pix = IntArray(pw * ph)
    sml.getPixels(pix, 0, pw, 0, 0, pw, ph)
    sml.recycle()

    // 2. Grayscale
    val gray = FloatArray(pw * ph) { i ->
        val c = pix[i]
        0.299f*(c shr 16 and 0xFF) + 0.587f*(c shr 8 and 0xFF) + 0.114f*(c and 0xFF)
    }

    // 3. 5×5 Gaussian blur (sigma≈1.4, kernel sum=159)
    val gk = floatArrayOf(
        2f, 4f, 5f, 4f,2f,
        4f, 9f,12f, 9f,4f,
        5f,12f,15f,12f,5f,
        4f, 9f,12f, 9f,4f,
        2f, 4f, 5f, 4f,2f
    )
    val blur = FloatArray(pw * ph)
    for (y in 0 until ph) for (x in 0 until pw) {
        var acc = 0f
        for (ky in -2..2) for (kx in -2..2) {
            val nx = (x+kx).coerceIn(0,pw-1); val ny = (y+ky).coerceIn(0,ph-1)
            acc += gray[ny*pw+nx] * gk[(ky+2)*5+(kx+2)]
        }
        blur[y*pw+x] = acc / 159f
    }

    // 4. Sobel gradient
    val gx = FloatArray(pw*ph); val gy = FloatArray(pw*ph); val mag = FloatArray(pw*ph)
    for (y in 1 until ph-1) for (x in 1 until pw-1) {
        val vx = (-blur[(y-1)*pw+(x-1)] + blur[(y-1)*pw+(x+1)]
                  -2*blur[y*pw+(x-1)]   + 2*blur[y*pw+(x+1)]
                  -blur[(y+1)*pw+(x-1)] + blur[(y+1)*pw+(x+1)])
        val vy = (-blur[(y-1)*pw+(x-1)] - 2*blur[(y-1)*pw+x] - blur[(y-1)*pw+(x+1)]
                  +blur[(y+1)*pw+(x-1)] + 2*blur[(y+1)*pw+x] + blur[(y+1)*pw+(x+1)])
        gx[y*pw+x]=vx; gy[y*pw+x]=vy; mag[y*pw+x]=sqrt(vx*vx+vy*vy)
    }

    // 5. Non-maximum suppression
    val nms = FloatArray(pw*ph)
    for (y in 1 until ph-1) for (x in 1 until pw-1) {
        val m = mag[y*pw+x]; if (m < 1f) continue
        val deg = (atan2(gy[y*pw+x].toDouble(), gx[y*pw+x].toDouble())*180.0/PI+180.0)%180.0
        val (n1,n2) = when {
            deg<22.5||deg>=157.5 -> mag[y*pw+(x-1)]     to mag[y*pw+(x+1)]
            deg<67.5             -> mag[(y-1)*pw+(x+1)] to mag[(y+1)*pw+(x-1)]
            deg<112.5            -> mag[(y-1)*pw+x]     to mag[(y+1)*pw+x]
            else                 -> mag[(y-1)*pw+(x-1)] to mag[(y+1)*pw+(x+1)]
        }
        if (m>=n1 && m>=n2) nms[y*pw+x]=m
    }

    // 6. Hysteresis thresholding
    val maxNms = nms.max().coerceAtLeast(1f)
    val highT  = maxNms * 0.20f;  val lowT = maxNms * 0.05f
    val edge   = BooleanArray(pw*ph)
    for (i in nms.indices) if (nms[i]>=highT) edge[i]=true
    // Single forward-pass to connect weak edges
    for (y in 1 until ph-1) for (x in 1 until pw-1) {
        if (!edge[y*pw+x] && nms[y*pw+x]>=lowT)
            if ((-1..1).any{dy->(-1..1).any{dx->edge[(y+dy)*pw+(x+dx)]}}) edge[y*pw+x]=true
    }

    // 7. Hough Transform  x·cos(θ)+y·sin(θ)=ρ
    // Optimisation: step=2° → 90 theta values instead of 180 (halves both
    // the accumulator size and the per-pixel voting work)
    val diag   = sqrt((pw*pw+ph*ph).toDouble()).toInt()+1
    val nRho   = 2*diag+1
    val STEP   = 2                        // degrees per theta bucket
    val nTheta = 180/STEP                 // = 90
    val accum  = IntArray(nRho*nTheta)
    val cosT   = DoubleArray(nTheta){t->cos(t*STEP*PI/180.0)}
    val sinT   = DoubleArray(nTheta){t->sin(t*STEP*PI/180.0)}
    for (y in 0 until ph) for (x in 0 until pw) {
        if (!edge[y*pw+x]) continue
        for (t in 0 until nTheta) {
            val r = (x*cosT[t]+y*sinT[t]).roundToInt()+diag
            if (r in 0 until nRho) accum[r*nTheta+t]++
        }
    }

    // 8. Peak extraction — pre-filter before sorting to avoid allocating 100K+ list
    data class HLine(val rho:Int, val theta:Int, val votes:Int)
    val peaks      = mutableListOf<HLine>()
    val suppressed = BooleanArray(nRho*nTheta)
    val minVotes   = (min(pw,ph)/8).coerceAtLeast(10)
    val rhoSupp    = diag/8
    val thetaSupp  = 9                    // 18° → 9 buckets at step=2

    // Only collect cells above threshold, then sort that small list
    val cands = ArrayList<Int>(256)
    for (i in accum.indices) { if (accum[i] >= minVotes) cands.add(i) }
    cands.sortWith(compareByDescending { accum[it] })

    for (ci in cands) {
        if (suppressed[ci]) continue
        val r=ci/nTheta; val t=ci%nTheta
        peaks += HLine(r-diag, t*STEP, accum[ci])
        for (dr in -rhoSupp..rhoSupp) {
            val nr=r+dr; if (nr !in 0 until nRho) continue
            for (dt in -thetaSupp..thetaSupp)
                suppressed[nr*nTheta+((t+dt+nTheta)%nTheta)] = true
        }
        if (peaks.size>=40) break
    }

    if (peaks.size<4) return edgeScanFallback(edge,pw,ph)

    // 9. Classify: theta≈90° = horizontal,  theta≈0°/180° = vertical
    val horiz = peaks.filter{ it.theta in 60..120 }
    val vert  = peaks.filter{ it.theta<30 || it.theta>150 }
    if (horiz.size<2||vert.size<2) return edgeScanFallback(edge,pw,ph)

    val hSorted = horiz.sortedBy{ it.rho }

    fun vertXPos(l:HLine): Double {
        val t=l.theta*PI/180.0; val ct=cos(t); val st=sin(t)
        return if(abs(ct)>0.05) (l.rho - ph/2.0*st)/ct else l.rho.toDouble()
    }
    val vSorted = vert.sortedBy{ vertXPos(it) }

    // 10. Intersect the 4 boundary lines
    fun intersect(r1:Int,t1:Double,r2:Int,t2:Double): PointF? {
        val c1=cos(t1); val s1=sin(t1); val c2=cos(t2); val s2=sin(t2)
        val det = c1*s2 - s1*c2
        if (abs(det)<1e-9) return null
        return PointF(((r1*s2-r2*s1)/det).toFloat(), ((r2*c1-r1*c2)/det).toFloat())
    }
    fun HLine.rad() = theta*PI/180.0

    val top    = hSorted.first(); val bottom = hSorted.last()
    val left   = vSorted.first(); val right  = vSorted.last()

    val tl = intersect(top.rho,    top.rad(),    left.rho,  left.rad())  ?: return edgeScanFallback(edge,pw,ph)
    val tr = intersect(top.rho,    top.rad(),    right.rho, right.rad()) ?: return edgeScanFallback(edge,pw,ph)
    val br = intersect(bottom.rho, bottom.rad(), right.rho, right.rad()) ?: return edgeScanFallback(edge,pw,ph)
    val bl = intersect(bottom.rho, bottom.rad(), left.rho,  left.rad())  ?: return edgeScanFallback(edge,pw,ph)

    // 11. Normalise + sanity-check (quad must span > 20% of image in each axis)
    fun PointF.norm() = PointF((x/pw).coerceIn(0f,1f), (y/ph).coerceIn(0f,1f))
    val quad = Quad(tl.norm(), tr.norm(), br.norm(), bl.norm())
    val spanW = (quad.tr.x-quad.tl.x+quad.br.x-quad.bl.x)/2f
    val spanH = (quad.bl.y-quad.tl.y+quad.br.y-quad.tr.y)/2f
    return if(spanW>0.20f && spanH>0.20f) quad else edgeScanFallback(edge,pw,ph)
}

/** Fallback: scan the edge map row/column-by-row to find document boundaries. */
private fun edgeScanFallback(edge: BooleanArray, pw: Int, ph: Int): Quad {
    val margin    = (min(pw,ph)*0.04f).toInt()
    val rowThresh = pw*0.07f
    val colThresh = ph*0.07f

    var topY    = (ph*0.05f).toInt()
    var bottomY = (ph*0.95f).toInt()
    var leftX   = (pw*0.05f).toInt()
    var rightX  = (pw*0.95f).toInt()

    for (y in margin until ph/2)
        if ((margin until pw-margin).count{x->edge[y*pw+x]}>rowThresh){ topY=y; break }
    for (y in (ph/2 until ph-margin).reversed())
        if ((margin until pw-margin).count{x->edge[y*pw+x]}>rowThresh){ bottomY=y; break }
    for (x in margin until pw/2)
        if ((margin until ph-margin).count{y->edge[y*pw+x]}>colThresh){ leftX=x; break }
    for (x in (pw/2 until pw-margin).reversed())
        if ((margin until ph-margin).count{y->edge[y*pw+x]}>colThresh){ rightX=x; break }

    val l=(leftX.toFloat()/pw).coerceIn(0.03f,0.45f)
    val r=(rightX.toFloat()/pw).coerceIn(0.55f,0.97f)
    val t=(topY.toFloat()/ph).coerceIn(0.03f,0.45f)
    val b=(bottomY.toFloat()/ph).coerceIn(0.55f,0.97f)
    return Quad(PointF(l,t), PointF(r,t), PointF(r,b), PointF(l,b))
}

// ── Bitmap loader for crop screen ────────────────────────────────────────────
// Rotation is already baked into every JPEG by SmartScanScreen.bakeExifRotation()
// before navigation, so no EXIF handling is needed here at all.
// Two-pass: read dimensions → calculate sample size → decode at 1/N resolution.

private fun loadBitmapForCrop(
    context: android.content.Context,
    uri    : android.net.Uri
): Bitmap? {
    val path = uri.path ?: return null

    // Pass 1: dimensions only (reads JPEG header — milliseconds)
    val sizeOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, sizeOpts)

    // Down-sample so longest side ≤ 1920px
    val maxDim = 1920
    var sampleSize = 1
    var w = sizeOpts.outWidth; var h = sizeOpts.outHeight
    while (maxOf(w, h) > maxDim) { sampleSize *= 2; w /= 2; h /= 2 }

    // Pass 2: decode at calculated sample size — pixels already upright
    return BitmapFactory.decodeFile(
        path,
        BitmapFactory.Options().apply {
            inSampleSize      = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
    )
}

// ── Shimmer loading skeleton shown while the JPEG decodes from disk ─────────

@Composable
private fun CropLoadingSkeleton() {
    // Shimmer sweep animation: moves left→right on a 1200ms loop
    val shimmerTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerX by shimmerTransition.animateFloat(
        initialValue  = -1f,
        targetValue   =  2f,
        animationSpec = infiniteRepeatable(
            animation  = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerX"
    )

    // Pulse animation for the scan-line
    val scanY by shimmerTransition.animateFloat(
        initialValue  = 0.15f,
        targetValue   = 0.85f,
        animationSpec = infiniteRepeatable(
            animation  = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scanY"
    )

    Box(Modifier.fillMaxSize().background(Color(0xFF0D0D14))) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height

            // ── Card-shaped skeleton block (matches ID card guide rect) ──────
            val cardW = w * 0.84f; val cardH = cardW / 1.586f
            val cardL = (w - cardW) / 2f
            val cardT = h / 2f - cardH / 2f - h * 0.04f
            val cardR = cardL + cardW; val cardB = cardT + cardH

            // Dark background rectangle
            drawRoundRect(
                color       = Color(0xFF1A1A2E),
                topLeft     = androidx.compose.ui.geometry.Offset(cardL, cardT),
                size        = androidx.compose.ui.geometry.Size(cardW, cardH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(12f)
            )

            // Shimmer sweep gradient over the card area
            val shimmerBrush = Brush.linearGradient(
                colors = listOf(
                    Color.Transparent,
                    Color.White.copy(alpha = 0.08f),
                    Color.White.copy(alpha = 0.15f),
                    Color.White.copy(alpha = 0.08f),
                    Color.Transparent
                ),
                start = androidx.compose.ui.geometry.Offset(shimmerX * w, cardT),
                end   = androidx.compose.ui.geometry.Offset(shimmerX * w + w * 0.5f, cardB)
            )
            drawRoundRect(
                brush        = shimmerBrush,
                topLeft      = androidx.compose.ui.geometry.Offset(cardL, cardT),
                size         = androidx.compose.ui.geometry.Size(cardW, cardH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(12f)
            )

            // Photo placeholder (left side of card)
            val photoSize = cardH * 0.55f
            val photoL    = cardL + cardW * 0.04f
            val photoT    = cardT + (cardH - photoSize) / 2f
            drawRoundRect(
                color       = Color(0xFF252540),
                topLeft     = androidx.compose.ui.geometry.Offset(photoL, photoT),
                size        = androidx.compose.ui.geometry.Size(photoSize * 0.75f, photoSize),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f)
            )

            // Text line skeletons (right side of card)
            val textL      = photoL + photoSize * 0.75f + cardW * 0.04f
            val lineH      = cardH * 0.07f
            val lineGap    = lineH * 1.8f
            val lineWidths = listOf(0.38f, 0.30f, 0.24f, 0.36f, 0.20f)
            lineWidths.forEachIndexed { idx, widthFrac ->
                val ly = photoT + idx * lineGap
                drawRoundRect(
                    color       = Color(0xFF252540),
                    topLeft     = androidx.compose.ui.geometry.Offset(textL, ly),
                    size        = androidx.compose.ui.geometry.Size(cardW * widthFrac, lineH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f)
                )
            }

            // Yellow corner brackets (same as camera overlay)
            val len = 22f; val sw  = 3.5f
            val col = Color(0xFFFFD700).copy(alpha = 0.6f)
            val pairs = listOf(
                Triple(cardL, cardT,  1f),   // TL
                Triple(cardR, cardT, -1f),   // TR
                Triple(cardR, cardB, -1f),   // BR (will flip y too)
                Triple(cardL, cardB,  1f)    // BL
            )
            pairs.forEachIndexed { i, (cx, cy, sx) ->
                val sy = if (i < 2) 1f else -1f
                drawLine(col, androidx.compose.ui.geometry.Offset(cx, cy + sy * len),
                              androidx.compose.ui.geometry.Offset(cx, cy), sw)
                drawLine(col, androidx.compose.ui.geometry.Offset(cx, cy),
                              androidx.compose.ui.geometry.Offset(cx + sx * len, cy), sw)
            }

            // Animated horizontal scan-line
            val scanLineY = cardT + cardH * scanY
            drawLine(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent,
                                    Color(0xFFFFD700).copy(alpha = 0.7f),
                                    Color(0xFFFFD700).copy(alpha = 0.9f),
                                    Color(0xFFFFD700).copy(alpha = 0.7f),
                                    Color.Transparent),
                    startX = cardL, endX = cardR
                ),
                start       = androidx.compose.ui.geometry.Offset(cardL, scanLineY),
                end         = androidx.compose.ui.geometry.Offset(cardR, scanLineY),
                strokeWidth = 2f
            )
        }

        // Status text below skeleton
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 120.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Preparing image…",
                color    = Color.White.copy(alpha = 0.55f),
                fontSize = 13.sp)
        }
    }
}

// ── Screen ────────────────────────────────────────────────────────────────────

@Composable
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
            val bmp = withContext(Dispatchers.IO) {
                loadBitmapForCrop(context, editState.uri)
            }
            if (bmp != null) {
                editState.originalBitmap = bmp
                editState.displayBitmap  = bmp
            }
            isLoading = false          // ← image is now visible on screen

            // ── Phase 2: edge detect (image already shown, no black screen) ──
            if (bmp != null && !autoDetected) {
                isDetecting = true
                val detected = withContext(Dispatchers.Default) { autoDetectQuad(bmp) }
                quad         = detected
                autoDetected = true
                isDetecting  = false
            }
        } else if (!autoDetected) {
            // Bitmap already in memory (returning from edit screen)
            val bmp = editState.originalBitmap!!
            isDetecting = true
            val detected = withContext(Dispatchers.Default) { autoDetectQuad(bmp) }
            quad         = detected
            autoDetected = true
            isDetecting  = false
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
                val bmp = bitmap ?: return@IconButton
                scope.launch(Dispatchers.Default) {
                    val d = autoDetectQuad(bmp)
                    withContext(Dispatchers.Main) { quad = d }
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
            EditControlBtn(Icons.Default.RotateLeft, "Left",
                tint = if (isLoading || bitmap == null) Color.White.copy(0.3f) else Color.White) {
                val bmp = editState.displayBitmap ?: editState.originalBitmap ?: return@EditControlBtn
                scope.launch(Dispatchers.Default) {
                    val rot = ImageProcessing.rotateBitmap(bmp, -90f)
                    withContext(Dispatchers.Main) { editState.displayBitmap=rot; editState.totalRotation-=90f }
                    val d = autoDetectQuad(rot)
                    withContext(Dispatchers.Main) { quad=d }
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
                    scope.launch(Dispatchers.Default) {
                        val warped = perspectiveWarp(bmp, quad)
                        withContext(Dispatchers.Main) {
                            editState.displayBitmap  = warped
                            editState.originalBitmap = warped
                            editState.cropRect       = RectF(0f,0f,1f,1f)
                            editState.cropApplied    = false
                        }
                        editState.rebuildFinal()
                        withContext(Dispatchers.Main) { isProcessing=false; onNext() }
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
private fun Offset.getDistance()            = sqrt((x*x+y*y).toDouble()).toFloat()
