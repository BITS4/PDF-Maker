package com.example.pdfmaker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import kotlin.math.*

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
    val measuredWidth = ((dist(tl, tr) + dist(bl, br)) / 2f).coerceAtLeast(10f).toInt()
    val measuredHeight = ((dist(tl, bl) + dist(tr, br)) / 2f).coerceAtLeast(10f).toInt()
    val output = requireNotNull(
        ImageInputPolicy.fitWithinLimits(measuredWidth, measuredHeight),
    ) { "Crop output dimensions are invalid" }
    val outW = output.width.toFloat()
    val outH = output.height.toFloat()
    val dst = Bitmap.createBitmap(output.width, output.height, Bitmap.Config.ARGB_8888)
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
    val sc  = min(1f, procMax.toFloat() / maxOf(bmp.width, bmp.height))
    val pw  = (bmp.width  * sc).toInt().coerceAtLeast(1)
    val ph  = (bmp.height * sc).toInt().coerceAtLeast(1)
    val sml = Bitmap.createScaledBitmap(bmp, pw, ph, true)
    val pix = IntArray(pw * ph)
    sml.getPixels(pix, 0, pw, 0, 0, pw, ph)
    if (sml !== bmp) sml.recycle()

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
internal fun edgeScanFallback(edge: BooleanArray, pw: Int, ph: Int): Quad {
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
