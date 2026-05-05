package com.example.pdfmaker

import android.graphics.*
import kotlin.math.*

object ImageProcessing {

    // ── Public filter entry-point ─────────────────────────────────────────────

    fun applyFilter(source: Bitmap, filter: ImageFilter): Bitmap = when (filter) {
        ImageFilter.ORIGINAL   -> source
        ImageFilter.AI_ENHANCE -> applyAiEnhance(source)
        ImageFilter.DOCS       -> applyDocsFilter(source)
        ImageFilter.BW2        -> applyBw2Filter(source)
        ImageFilter.SUPER      -> applySuperFilter(source)
        else -> {
            val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            Canvas(result).drawBitmap(source, 0f, 0f, Paint().also {
                it.colorFilter = ColorMatrixColorFilter(matrixFor(filter))
            })
            result
        }
    }

    // ── AI Enhance ────────────────────────────────────────────────────────────
    // Full pipeline: bilateral denoise → CLAHE local contrast → multi-scale USM
    // Processes luma in YCbCr space so colour channels are never damaged.
    private fun applyAiEnhance(source: Bitmap): Bitmap {
        val W = source.width; val H = source.height
        val pixels = IntArray(W * H).also { source.getPixels(it, 0, W, 0, 0, W, H) }

        // 1. Convert to YCbCr float arrays
        val yArr  = FloatArray(W * H)
        val cbArr = FloatArray(W * H)
        val crArr = FloatArray(W * H)
        for (i in pixels.indices) {
            val r = (pixels[i] shr 16 and 0xFF).toFloat()
            val g = (pixels[i] shr 8  and 0xFF).toFloat()
            val b = (pixels[i]        and 0xFF).toFloat()
            yArr[i]  =  0.299f*r + 0.587f*g + 0.114f*b
            cbArr[i] = -0.168736f*r - 0.331264f*g + 0.5f*b + 128f
            crArr[i] =  0.5f*r - 0.418688f*g - 0.081312f*b + 128f
        }

        // 2. Bilateral denoising on luma only
        val yDenoised = bilateralFilterLuma(yArr, W, H, spatialSigma = 2.5f, rangeSigma = 18f)

        // 3. CLAHE on luma
        val yClahe = clahe(yDenoised, W, H, tileSize = 48, clipLimit = 2.8f)

        // 4. Multi-scale unsharp mask on CLAHE output
        val ySharp = multiScaleUSM(yClahe, W, H)

        // 5. Convert back to ARGB
        val out = IntArray(W * H)
        for (i in out.indices) {
            val y  = ySharp[i]
            val cb = cbArr[i] - 128f
            val cr = crArr[i] - 128f
            val r = (y + 1.402f*cr)               .roundToInt().coerceIn(0, 255)
            val g = (y - 0.344136f*cb - 0.714136f*cr).roundToInt().coerceIn(0, 255)
            val bOut = (y + 1.772f*cb)             .roundToInt().coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bOut
        }
        val bm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        bm.setPixels(out, 0, W, 0, 0, W, H)
        return bm
    }

    // ── Bilateral filter (luma only, fast approx with 7×7 kernel) ────────────
    // Spatial Gaussian × Range Gaussian — blurs uniform regions but preserves edges.
    private fun bilateralFilterLuma(
        y           : FloatArray,
        W           : Int, H: Int,
        spatialSigma: Float,
        rangeSigma  : Float
    ): FloatArray {
        val out    = FloatArray(W * H)
        val r      = (2.5f * spatialSigma).roundToInt().coerceIn(1, 6)
        val ss2    = 2f * spatialSigma * spatialSigma
        val rs2    = 2f * rangeSigma   * rangeSigma

        // Precompute spatial kernel weights
        val kSize  = 2*r+1
        val spatW  = FloatArray(kSize * kSize)
        for (dy in -r..r) for (dx in -r..r)
            spatW[(dy+r)*kSize+(dx+r)] = exp(-(dx*dx+dy*dy).toFloat()/ss2)

        for (y0 in 0 until H) {
            for (x0 in 0 until W) {
                val center = y[y0*W+x0]
                var wSum = 0f; var acc = 0f
                var ki = 0
                for (dy in -r..r) {
                    val ny = (y0+dy).coerceIn(0, H-1)
                    for (dx in -r..r) {
                        val nx   = (x0+dx).coerceIn(0, W-1)
                        val diff = y[ny*W+nx] - center
                        val w    = spatW[ki++] * exp(-(diff*diff)/rs2)
                        acc  += y[ny*W+nx] * w
                        wSum += w
                    }
                }
                out[y0*W+x0] = if (wSum > 0f) acc/wSum else center
            }
        }
        return out
    }

    // ── CLAHE — Contrast Limited Adaptive Histogram Equalisation ─────────────
    // Divides image into tiles, clips histogram at clipLimit×average, then
    // redistributes excess uniformly; bilinear-interpolates tile mappings.
    private fun clahe(
        y        : FloatArray,
        W        : Int, H: Int,
        tileSize : Int,
        clipLimit: Float
    ): FloatArray {
        val cols = ceil(W.toFloat()/tileSize).toInt()
        val rows = ceil(H.toFloat()/tileSize).toInt()

        // Build one lookup table (0..255 → 0..255) per tile
        val luts = Array(rows) { Array(cols) { FloatArray(256) } }

        for (tr in 0 until rows) {
            for (tc in 0 until cols) {
                val x0 = tc * tileSize; val x1 = min(x0 + tileSize, W)
                val y0 = tr * tileSize; val y1 = min(y0 + tileSize, H)
                val n  = (x1-x0)*(y1-y0)

                // Histogram
                val hist = IntArray(256)
                for (py in y0 until y1) for (px in x0 until x1)
                    hist[y[py*W+px].roundToInt().coerceIn(0,255)]++

                // Clip & redistribute
                val clip   = (clipLimit * n / 256f).roundToInt().coerceAtLeast(1)
                var excess = 0
                for (b in hist.indices) { if (hist[b] > clip) { excess += hist[b]-clip; hist[b]=clip } }
                val perBin = excess / 256
                for (b in hist.indices) hist[b] += perBin

                // CDF → LUT
                var cdf = 0f; val scale = 255f / n
                for (b in 0..255) { cdf += hist[b]; luts[tr][tc][b] = (cdf*scale).coerceIn(0f,255f) }
            }
        }

        // Bilinear interpolation between 4 nearest tile LUTs
        val out = FloatArray(W * H)
        for (py in 0 until H) {
            for (px in 0 until W) {
                val bin = y[py*W+px].roundToInt().coerceIn(0,255)

                // Tile coordinates (tile centre)
                val tcf = (px.toFloat() / tileSize) - 0.5f
                val trf = (py.toFloat() / tileSize) - 0.5f
                val tc0 = floor(tcf).toInt().coerceIn(0, cols-1)
                val tr0 = floor(trf).toInt().coerceIn(0, rows-1)
                val tc1 = (tc0+1).coerceIn(0, cols-1)
                val tr1 = (tr0+1).coerceIn(0, rows-1)
                val wx  = (tcf - tc0).coerceIn(0f,1f)
                val wy  = (trf - tr0).coerceIn(0f,1f)

                val v = (1f-wy)*((1f-wx)*luts[tr0][tc0][bin] + wx*luts[tr0][tc1][bin]) +
                            wy *((1f-wx)*luts[tr1][tc0][bin] + wx*luts[tr1][tc1][bin])
                out[py*W+px] = v
            }
        }
        return out
    }

    // ── Multi-scale unsharp mask ──────────────────────────────────────────────
    // Fine details (r=1) blended with coarser sharpening (r=3) → natural crispness.
    private fun multiScaleUSM(y: FloatArray, W: Int, H: Int): FloatArray {
        // Convert luma float → int pixel array (grayscale ARGB)
        val asPixels = IntArray(W*H) { i ->
            val v = y[i].roundToInt().coerceIn(0,255)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val fine   = boxBlur(asPixels, W, H, radius = 1)
        val coarse = boxBlur(asPixels, W, H, radius = 3)

        // strength 0.55 fine + 0.30 coarse blend
        val out = FloatArray(W*H)
        val sF = 0.55f; val sC = 0.30f; val thr = 3
        for (i in asPixels.indices) {
            val o = asPixels[i] and 0xFF          // luma from grayscale
            val bf = fine[i]   and 0xFF
            val bc = coarse[i] and 0xFF
            val df = o - bf; val dc = o - bc
            var v = o.toFloat()
            if (abs(df) > thr) v += df * sF
            if (abs(dc) > thr) v += dc * sC
            out[i] = v.coerceIn(0f, 255f)
        }
        return out
    }

    // ── "Docs" — high contrast near-B&W with heavy sharpening ────────────────
    private fun applyDocsFilter(source: Bitmap): Bitmap {
        val cm = ColorMatrix().apply {
            setSaturation(0.04f)
            postConcat(scaleMatrix(1.85f, -95f))
        }
        val step1 = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Canvas(step1).drawBitmap(source, 0f, 0f, Paint().also {
            it.colorFilter = ColorMatrixColorFilter(cm)
        })
        return unsharpMask(step1, strength = 0.75f, radius = 2, threshold = 4)
    }

    // ── "B&W2" — crisp black-on-white ────────────────────────────────────────
    private fun applyBw2Filter(source: Bitmap): Bitmap {
        val cm = ColorMatrix().apply {
            setSaturation(0f)
            postConcat(scaleMatrix(2.1f, -110f))
        }
        val step1 = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Canvas(step1).drawBitmap(source, 0f, 0f, Paint().also {
            it.colorFilter = ColorMatrixColorFilter(cm)
        })
        return unsharpMask(step1, strength = 0.6f, radius = 1, threshold = 3)
    }

    // ── "Super" — vivid + sharp for colour docs / ID cards ───────────────────
    private fun applySuperFilter(source: Bitmap): Bitmap {
        val cm = ColorMatrix().apply {
            setSaturation(1.7f)
            postConcat(scaleMatrix(1.3f, -20f))
        }
        val step1 = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Canvas(step1).drawBitmap(source, 0f, 0f, Paint().also {
            it.colorFilter = ColorMatrixColorFilter(cm)
        })
        return unsharpMask(step1, strength = 0.55f, radius = 1, threshold = 5)
    }

    private fun matrixFor(f: ImageFilter): ColorMatrix = when (f) {
        ImageFilter.ORIGINAL, ImageFilter.AI_ENHANCE,
        ImageFilter.DOCS, ImageFilter.BW2, ImageFilter.SUPER -> ColorMatrix()
        ImageFilter.IMAGE -> ColorMatrix().apply {
            setSaturation(1.35f); postConcat(scaleMatrix(1.12f, 10f))
        }
        ImageFilter.ENHANCE -> ColorMatrix().apply {
            setSaturation(1.15f); postConcat(scaleMatrix(1.22f, 15f))
        }
        ImageFilter.ENHANCE2 -> ColorMatrix().apply {
            setSaturation(1.45f); postConcat(scaleMatrix(1.42f, 22f))
        }
        ImageFilter.BW -> ColorMatrix().apply {
            setSaturation(0f); postConcat(scaleMatrix(1.2f, -10f))
        }
        ImageFilter.GRAY -> ColorMatrix().apply {
            setSaturation(0.18f); postConcat(scaleMatrix(1.08f, 5f))
        }
        ImageFilter.INVERT -> ColorMatrix(floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
             0f,-1f, 0f, 0f, 255f,
             0f, 0f,-1f, 0f, 255f,
             0f, 0f, 0f, 1f,   0f
        ))
    }

    private fun scaleMatrix(s: Float, t: Float) = ColorMatrix(floatArrayOf(
        s,0f,0f,0f,t,  0f,s,0f,0f,t,  0f,0f,s,0f,t,  0f,0f,0f,1f,0f
    ))

    // ── Adjustments ───────────────────────────────────────────────────────────

    fun applyAdjustments(source: Bitmap, brightness: Float, contrast: Float, details: Float): Bitmap {
        val cf = 1f + contrast / 100f
        val bf = brightness * 2.55f
        val t  = 128f * (1f - cf)
        val cm = ColorMatrix(floatArrayOf(
            cf,0f,0f,0f,bf+t,  0f,cf,0f,0f,bf+t,  0f,0f,cf,0f,bf+t,  0f,0f,0f,1f,0f
        ))
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Canvas(result).drawBitmap(source, 0f, 0f, Paint().also {
            it.colorFilter = ColorMatrixColorFilter(cm)
        })
        return if (details > 2f) unsharpMask(result, details/120f, radius=1, threshold=3) else result
    }

    // ── Unsharp mask ──────────────────────────────────────────────────────────

    private fun unsharpMask(source: Bitmap, strength: Float, radius: Int, threshold: Int): Bitmap {
        val W = source.width; val H = source.height
        if (W < 3 || H < 3) return source
        val s    = strength.coerceIn(0f, 1f)
        val orig = IntArray(W*H).also { source.getPixels(it, 0, W, 0, 0, W, H) }
        val blur = boxBlur(orig, W, H, radius)
        val out  = IntArray(W*H)
        for (i in orig.indices) {
            val oc = orig[i]; val bc = blur[i]
            fun ch(shift: Int): Int {
                val o = (oc shr shift) and 0xFF
                val b = (bc shr shift) and 0xFF
                val d = o - b
                return if (abs(d) > threshold) (o + (d*s).roundToInt()).coerceIn(0,255) else o
            }
            out[i] = (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
        val bm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        bm.setPixels(out, 0, W, 0, 0, W, H)
        return bm
    }

    // ── Separable box blur — O(W×H) regardless of radius ─────────────────────

    private fun boxBlur(pixels: IntArray, W: Int, H: Int, radius: Int): IntArray {
        val tmp = IntArray(W*H); val out = IntArray(W*H); val r = radius.coerceAtLeast(1)
        // Horizontal
        for (y in 0 until H) {
            var rS=0; var gS=0; var bS=0; val cnt=2*r+1
            for (dx in -r..r) { val x2=dx.coerceIn(0,W-1); val p=pixels[y*W+x2]; rS+=(p shr 16)and 0xFF; gS+=(p shr 8)and 0xFF; bS+=p and 0xFF }
            for (x in 0 until W) {
                tmp[y*W+x]=(0xFF shl 24) or ((rS/cnt) shl 16) or ((gS/cnt) shl 8) or (bS/cnt)
                val rm=pixels[y*W+(x-r).coerceIn(0,W-1)]; val ad=pixels[y*W+(x+r+1).coerceIn(0,W-1)]
                rS+=((ad shr 16)and 0xFF)-((rm shr 16)and 0xFF); gS+=((ad shr 8)and 0xFF)-((rm shr 8)and 0xFF); bS+=(ad and 0xFF)-(rm and 0xFF)
            }
        }
        // Vertical
        for (x in 0 until W) {
            var rS=0; var gS=0; var bS=0; val cnt=2*r+1
            for (dy in -r..r) { val y2=dy.coerceIn(0,H-1); val p=tmp[y2*W+x]; rS+=(p shr 16)and 0xFF; gS+=(p shr 8)and 0xFF; bS+=p and 0xFF }
            for (y in 0 until H) {
                out[y*W+x]=(0xFF shl 24) or ((rS/cnt) shl 16) or ((gS/cnt) shl 8) or (bS/cnt)
                val rm=tmp[(y-r).coerceIn(0,H-1)*W+x]; val ad=tmp[(y+r+1).coerceIn(0,H-1)*W+x]
                rS+=((ad shr 16)and 0xFF)-((rm shr 16)and 0xFF); gS+=((ad shr 8)and 0xFF)-((rm shr 8)and 0xFF); bS+=(ad and 0xFF)-(rm and 0xFF)
            }
        }
        return out
    }

    // ── Rotation ──────────────────────────────────────────────────────────────

    fun rotateBitmap(source: Bitmap, degrees: Float): Bitmap {
        if (degrees % 360f == 0f) return source
        val m = Matrix().also { it.postRotate(degrees) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, m, true)
    }

    // ── Crop (normalised 0-1 RectF) ───────────────────────────────────────────

    fun cropBitmap(source: Bitmap, rect: RectF): Bitmap {
        val x = (rect.left   * source.width ).roundToInt().coerceIn(0, source.width -1)
        val y = (rect.top    * source.height).roundToInt().coerceIn(0, source.height-1)
        val w = ((rect.right -rect.left)*source.width ).roundToInt().coerceIn(1, source.width -x)
        val h = ((rect.bottom-rect.top )*source.height).roundToInt().coerceIn(1, source.height-y)
        return Bitmap.createBitmap(source, x, y, w, h)
    }

    // ── Filter thumbnail ──────────────────────────────────────────────────────

    fun filterThumbnail(source: Bitmap, filter: ImageFilter, size: Int): Bitmap {
        val scaled = Bitmap.createScaledBitmap(source, size, size, true)
        return applyFilter(scaled, filter)
    }

    // ── Load display bitmap ───────────────────────────────────────────────────

    fun loadDisplayBitmap(context: android.content.Context, uri: android.net.Uri, maxPx: Int = 1920): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            val sample = max(1, max(opts.outWidth, opts.outHeight) / maxPx)
            val finalOpts = BitmapFactory.Options().apply { inSampleSize=sample; inPreferredConfig=Bitmap.Config.ARGB_8888 }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, finalOpts) }
        } catch (_: Exception) { null }
    }
}
