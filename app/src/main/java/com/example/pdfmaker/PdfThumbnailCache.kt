package com.example.pdfmaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

// ── Universal Thumbnail Cache ─────────────────────────────────────────────────

object PdfThumbnailCache {

    private val cache = ConcurrentHashMap<String, Bitmap>()

    suspend fun getThumbnail(context: Context, filePath: String, sizePx: Int = 200): Bitmap? {
        cache[filePath]?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val bmp = generateThumbnail(filePath, sizePx) ?: return@withContext null
                cache[filePath] = bmp
                bmp
            } catch (_: Exception) { null }
        }
    }

    private fun generateThumbnail(filePath: String, sizePx: Int): Bitmap? {
        val file = File(filePath)
        if (!file.exists()) return null
        return when (file.extension.lowercase()) {
            "pdf"              -> pdfThumb(file, sizePx)
            "jpg", "jpeg",
            "png", "webp",
            "bmp", "gif"       -> imageThumb(file, sizePx)
            "docx", "doc"      -> docxThumb(file, sizePx)
            "pptx", "ppt"      -> pptxThumb(file, sizePx)
            "xlsx", "xls"      -> xlsxThumb(file, sizePx)
            "csv", "tsv"       -> csvThumb(file, sizePx)
            "txt", "md", "log" -> txtThumb(file, sizePx)
            else               -> null
        }
    }

    // ── PDF ──────────────────────────────────────────────────────────────────

    private fun pdfThumb(file: File, sizePx: Int): Bitmap? {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val rdr = PdfRenderer(pfd)
        if (rdr.pageCount == 0) { rdr.close(); pfd.close(); return null }
        val page   = rdr.openPage(0)
        val aspect = page.width.toFloat() / page.height.toFloat()
        val w = if (aspect > 1f) sizePx else (sizePx * aspect).toInt().coerceAtLeast(1)
        val h = if (aspect > 1f) (sizePx / aspect).toInt().coerceAtLeast(1) else sizePx
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.WHITE)
        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close(); rdr.close(); pfd.close()
        return bmp
    }

    // ── Image ─────────────────────────────────────────────────────────────────

    private fun imageThumb(file: File, sizePx: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        val sample = (opts.outWidth / sizePx).coerceAtLeast(1)
        val opts2  = BitmapFactory.Options().apply { inSampleSize = sample }
        val src    = BitmapFactory.decodeFile(file.absolutePath, opts2) ?: return null
        val aspect = src.width.toFloat() / src.height.toFloat()
        val w = if (aspect > 1f) sizePx else (sizePx * aspect).toInt().coerceAtLeast(1)
        val h = if (aspect > 1f) (sizePx / aspect).toInt().coerceAtLeast(1) else sizePx
        val result = Bitmap.createScaledBitmap(src, w, h, true)
        if (result !== src) src.recycle()
        return result
    }

    // ── DOCX ──────────────────────────────────────────────────────────────────

    private fun docxThumb(file: File, sizePx: Int): Bitmap? {
        // Extract first paragraph texts and first embedded image
        val texts  = mutableListOf<String>()
        var firstImg: Bitmap? = null
        val rels   = mutableMapOf<String, String>()

        ZipInputStream(file.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                when {
                    entry.name == "word/_rels/document.xml.rels" -> {
                        parseThumbRels(zis.bufferedReader().readText(), rels)
                    }
                    entry.name == "word/document.xml" && texts.size < 8 -> {
                        extractDocxTexts(zis.bufferedReader().readText(), texts)
                    }
                    entry.name.startsWith("word/media/") && firstImg == null -> {
                        val bytes = zis.readBytes()
                        firstImg = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return renderDocumentThumb(
            sizePx   = sizePx,
            badgeLabel = "DOCX",
            badgeColor = android.graphics.Color.parseColor("#1565C0"),
            texts    = texts,
            image    = firstImg
        )
    }

    private fun extractDocxTexts(xml: String, out: MutableList<String>) {
        try {
            val p  = XmlPullParserFactory.newInstance().newPullParser().also { it.setInput(xml.reader()) }
            val sb = StringBuilder()
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT && out.size < 10) {
                when (ev) {
                    XmlPullParser.TEXT     -> sb.append(p.text)
                    XmlPullParser.END_TAG  -> if (p.name == "p") {
                        val t = sb.toString().trim()
                        if (t.isNotEmpty()) out.add(t)
                        sb.clear()
                    }
                }
                ev = p.next()
            }
        } catch (_: Exception) {}
    }

    // ── PPTX ──────────────────────────────────────────────────────────────────

    private fun pptxThumb(file: File, sizePx: Int): Bitmap? {
        val texts   = mutableListOf<String>()
        var firstImg: Bitmap? = null

        ZipInputStream(file.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                when {
                    entry.name == "ppt/slides/slide1.xml" -> {
                        extractPptxTexts(zis.bufferedReader().readText(), texts)
                    }
                    entry.name.startsWith("ppt/media/") && firstImg == null -> {
                        val bytes = zis.readBytes()
                        firstImg  = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        // PPTX thumb is landscape 16:9
        val w = sizePx; val h = (sizePx * 0.5625f).toInt()
        val bmp    = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(android.graphics.Color.parseColor("#1A1A2E"))

        if (firstImg != null) {
            val scale  = w.toFloat() / firstImg!!.width.coerceAtLeast(1)
            val dH     = (firstImg!!.height * scale).toInt()
            canvas.drawBitmap(firstImg!!, null,
                android.graphics.RectF(0f, 0f, w.toFloat(), dH.toFloat()), null)
            firstImg!!.recycle()
            // darken overlay
            val ov = Paint().apply { color = android.graphics.Color.argb(100, 0, 0, 0) }
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), ov)
        }

        // Draw badge
        drawBadge(canvas, "PPTX", android.graphics.Color.parseColor("#E65100"), 6f, 6f, w * 0.07f)

        // Draw text
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color    = android.graphics.Color.WHITE
            textSize = h * 0.12f
            typeface = Typeface.DEFAULT_BOLD
        }
        texts.take(1).forEachIndexed { _, t ->
            canvas.drawText(t.take(30), w * 0.06f, h * 0.55f, tp)
        }
        if (texts.size > 1) {
            tp.textSize = h * 0.09f; tp.typeface = Typeface.DEFAULT
            canvas.drawText(texts[1].take(40), w * 0.06f, h * 0.72f, tp)
        }
        return bmp
    }

    private fun extractPptxTexts(xml: String, out: MutableList<String>) {
        try {
            val p  = XmlPullParserFactory.newInstance().newPullParser().also { it.setInput(xml.reader()) }
            val sb = StringBuilder()
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT && out.size < 6) {
                when (ev) {
                    XmlPullParser.TEXT    -> sb.append(p.text)
                    XmlPullParser.END_TAG -> if (p.name == "a:p") {
                        val t = sb.toString().trim()
                        if (t.isNotEmpty()) out.add(t)
                        sb.clear()
                    }
                }
                ev = p.next()
            }
        } catch (_: Exception) {}
    }

    // ── XLSX ──────────────────────────────────────────────────────────────────

    private fun xlsxThumb(file: File, sizePx: Int): Bitmap? {
        val rows    = mutableListOf<List<String>>()
        val strings = mutableListOf<String>()
        ZipInputStream(file.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                when (entry.name) {
                    "xl/sharedStrings.xml"      -> parseThumbSharedStrings(zis.bufferedReader().readText(), strings)
                    "xl/worksheets/sheet1.xml"  -> parseThumbXlsxRows(zis.bufferedReader().readText(), strings, rows)
                }
                zis.closeEntry(); entry = zis.nextEntry
            }
        }
        return renderTableThumb(sizePx, rows.take(8), "XLSX",
            android.graphics.Color.parseColor("#2E7D32"),
            android.graphics.Color.parseColor("#1B5E20"))
    }

    private fun parseThumbSharedStrings(xml: String, out: MutableList<String>) {
        try {
            val p = XmlPullParserFactory.newInstance().newPullParser().also { it.setInput(xml.reader()) }
            var inT = false; val sb = StringBuilder(); var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> if (p.name == "t") { inT = true; sb.clear() }
                    XmlPullParser.TEXT      -> if (inT) sb.append(p.text)
                    XmlPullParser.END_TAG   -> if (p.name == "t") { out.add(sb.toString()); inT = false }
                }
                ev = p.next()
            }
        } catch (_: Exception) {}
    }

    private fun parseThumbXlsxRows(xml: String, strings: List<String>, rows: MutableList<List<String>>) {
        try {
            val p = XmlPullParserFactory.newInstance().newPullParser().also { it.setInput(xml.reader()) }
            var row = mutableListOf<String>(); var inV = false; var t = ""; val sb = StringBuilder()
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT && rows.size < 10) {
                when (ev) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "row" -> row = mutableListOf()
                        "c"   -> { t = p.getAttributeValue(null, "t") ?: ""; inV = false }
                        "v"   -> { inV = true; sb.clear() }
                    }
                    XmlPullParser.TEXT -> if (inV) sb.append(p.text)
                    XmlPullParser.END_TAG -> when (p.name) {
                        "v"   -> { val raw = sb.toString(); row.add(if (t=="s") strings.getOrElse(raw.toIntOrNull() ?: -1) { raw } else raw); inV=false }
                        "row" -> if (row.isNotEmpty()) rows.add(row.toList())
                    }
                }
                ev = p.next()
            }
        } catch (_: Exception) {}
    }

    // ── CSV ───────────────────────────────────────────────────────────────────

    private fun csvThumb(file: File, sizePx: Int): Bitmap? {
        val sep  = if (file.extension.lowercase() == "tsv") '\t' else ','
        val rows = file.readLines().take(8).map { it.split(sep) }
        return renderTableThumb(sizePx, rows, "CSV",
            android.graphics.Color.parseColor("#6A1B9A"),
            android.graphics.Color.parseColor("#4A148C"))
    }

    // ── TXT ───────────────────────────────────────────────────────────────────

    private fun txtThumb(file: File, sizePx: Int): Bitmap? {
        val lines = file.readLines().filter { it.isNotBlank() }.take(12)
        return renderDocumentThumb(
            sizePx     = sizePx,
            badgeLabel = "TXT",
            badgeColor = android.graphics.Color.parseColor("#37474F"),
            texts      = lines,
            image      = null
        )
    }

    // ── Shared renderers ──────────────────────────────────────────────────────

    private fun renderDocumentThumb(
        sizePx    : Int,
        badgeLabel: String,
        badgeColor: Int,
        texts     : List<String>,
        image     : Bitmap?
    ): Bitmap {
        val h      = (sizePx * 1.33f).toInt()   // portrait A4-ish
        val bmp    = Bitmap.createBitmap(sizePx, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(android.graphics.Color.WHITE)

        // Light page rule lines
        val linePaint = Paint().apply { color = android.graphics.Color.parseColor("#F0F0F0"); strokeWidth = 1f }
        val lineStep  = h * 0.07f
        var ly        = h * 0.12f
        while (ly < h - h * 0.08f) { canvas.drawLine(sizePx * 0.08f, ly, sizePx * 0.92f, ly, linePaint); ly += lineStep }

        // Optional first image preview
        var textStartY = h * 0.1f
        if (image != null) {
            val imgH = (h * 0.28f)
            canvas.drawBitmap(image, null,
                android.graphics.RectF(sizePx * 0.08f, h * 0.1f, sizePx * 0.92f, h * 0.1f + imgH), null)
            image.recycle()
            textStartY = h * 0.1f + imgH + h * 0.03f
        }

        // Text lines
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color    = android.graphics.Color.BLACK
            textSize = sizePx * 0.055f
        }
        val tpBold = Paint(tp).apply { typeface = Typeface.DEFAULT_BOLD; textSize = sizePx * 0.065f }
        var curY = textStartY
        texts.take(9).forEachIndexed { i, line ->
            val p     = if (i == 0) tpBold else tp
            val avail = sizePx * 0.84f
            val maxCh = (avail / (p.textSize * 0.52f)).toInt().coerceAtLeast(4)
            val disp  = if (line.length > maxCh) line.take(maxCh - 1) + "…" else line
            canvas.drawText(disp, sizePx * 0.08f, curY + p.textSize, p)
            curY += p.textSize * 1.55f
            if (curY > h * 0.88f) return@forEachIndexed
        }

        // Badge
        drawBadge(canvas, badgeLabel, badgeColor, sizePx * 0.06f, sizePx * 0.06f, sizePx * 0.07f)
        return bmp
    }

    private fun renderTableThumb(
        sizePx    : Int,
        rows      : List<List<String>>,
        badgeLabel: String,
        headerColor: Int,
        badgeBg   : Int
    ): Bitmap {
        val h       = (sizePx * 1.1f).toInt()
        val bmp     = Bitmap.createBitmap(sizePx, h, Bitmap.Config.ARGB_8888)
        val canvas  = Canvas(bmp)
        canvas.drawColor(android.graphics.Color.WHITE)

        if (rows.isEmpty()) {
            drawBadge(canvas, badgeLabel, badgeBg, sizePx * 0.06f, sizePx * 0.06f, sizePx * 0.07f)
            return bmp
        }

        val colCount  = rows.maxOf { it.size }.coerceIn(1, 6)
        val rowH      = h / (rows.size + 1).coerceAtLeast(4).toFloat()
        val colW      = sizePx.toFloat() / colCount

        val hdrPaint  = Paint().apply { color = headerColor }
        val evenPaint = Paint().apply { color = android.graphics.Color.parseColor("#F8FFF8") }
        val oddPaint  = Paint().apply { color = android.graphics.Color.WHITE }
        val borderP   = Paint().apply { color = android.graphics.Color.parseColor("#CCDDCC"); strokeWidth = 0.5f }
        val tp        = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color    = android.graphics.Color.BLACK
            textSize = rowH * 0.48f
        }
        val tpH = Paint(tp).apply { color = android.graphics.Color.WHITE; typeface = Typeface.DEFAULT_BOLD }

        rows.forEachIndexed { rIdx, row ->
            val top    = rIdx * rowH
            val bottom = top + rowH
            val bg     = when { rIdx == 0 -> hdrPaint; rIdx % 2 == 0 -> evenPaint; else -> oddPaint }
            canvas.drawRect(0f, top, sizePx.toFloat(), bottom, bg)

            for (col in 0 until colCount) {
                val cell  = row.getOrElse(col) { "" }
                val maxCh = (colW / (tp.textSize * 0.52f)).toInt().coerceAtLeast(2)
                val disp  = if (cell.length > maxCh) cell.take(maxCh - 1) + "…" else cell
                val p     = if (rIdx == 0) tpH else tp
                canvas.drawText(disp, col * colW + colW * 0.05f, top + rowH * 0.70f, p)
                canvas.drawLine((col + 1) * colW, top, (col + 1) * colW, bottom, borderP)
            }
            canvas.drawLine(0f, bottom, sizePx.toFloat(), bottom, borderP)
        }

        drawBadge(canvas, badgeLabel, badgeBg, sizePx * 0.04f, sizePx * 0.04f, sizePx * 0.06f)
        return bmp
    }

    // ── Badge helper ──────────────────────────────────────────────────────────

    private fun drawBadge(canvas: Canvas, label: String, bgColor: Int, x: Float, y: Float, textSize: Float) {
        val tp  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color    = android.graphics.Color.WHITE
            this.textSize = textSize
            typeface = Typeface.DEFAULT_BOLD
        }
        val pad = textSize * 0.4f
        val tw  = tp.measureText(label)
        val bg  = Paint().apply { color = bgColor }
        canvas.drawRoundRect(
            android.graphics.RectF(x, y, x + tw + pad * 2, y + textSize + pad * 1.2f),
            textSize * 0.3f, textSize * 0.3f, bg
        )
        canvas.drawText(label, x + pad, y + textSize, tp)
    }

    // ── Rels parser (for DOCX image mapping) ──────────────────────────────────

    private fun parseThumbRels(xml: String, out: MutableMap<String, String>) {
        try {
            val p = XmlPullParserFactory.newInstance().newPullParser().also { it.setInput(xml.reader()) }
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && p.name == "Relationship") {
                    val id     = p.getAttributeValue(null, "Id") ?: ""
                    val target = p.getAttributeValue(null, "Target") ?: ""
                    if (id.isNotEmpty()) out[id] = target.substringAfterLast("/")
                }
                ev = p.next()
            }
        } catch (_: Exception) {}
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun invalidate(filePath: String) = cache.remove(filePath)
    fun clear() = cache.clear()
}

// ── Composable helper ─────────────────────────────────────────────────────────

@Composable
fun rememberPdfThumbnail(context: Context, filePath: String, sizePx: Int = 200): Bitmap? {
    var bitmap by remember(filePath) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(filePath) {
        bitmap = PdfThumbnailCache.getThumbnail(context, filePath, sizePx)
    }
    return bitmap
}
