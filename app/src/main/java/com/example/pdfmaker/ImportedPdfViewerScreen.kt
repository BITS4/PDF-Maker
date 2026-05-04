package com.tajapps.pdfmaker

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import java.io.File

// ── Annotation data ───────────────────────────────────────────────────────────

data class DrawStroke(val points: List<Offset>, val color: Color, val strokeWidth: Float)
data class TextAnnotation(val x: Float, val y: Float, val text: String, val color: Color, val sizeSp: Float)
data class SignatureOverlay(val x: Float, val y: Float, val width: Float, val bitmap: Bitmap)

// ── Live (interactive) annotation items shown during edit ─────────────────────

data class LiveText(
    val id       : String = java.util.UUID.randomUUID().toString(),
    val text     : String,
    val color    : Color,
    val sizeSp   : Float,
    val x        : Float,   // px within page Box
    val y        : Float
)

data class LiveSignature(
    val id          : String = java.util.UUID.randomUUID().toString(),
    val bitmap      : Bitmap,
    val x           : Float,  // px
    val y           : Float,
    val scaleFactor : Float = 1f  // relative to initial (40% of page width)
)

class PageAnnotations {
    var strokes    by mutableStateOf<List<DrawStroke>>(emptyList())
    var texts      by mutableStateOf<List<TextAnnotation>>(emptyList())
    var signatures by mutableStateOf<List<SignatureOverlay>>(emptyList())
}

// ── Edit mode ─────────────────────────────────────────────────────────────────
// NONE        → normal view  (Edit / Convert / Share bar)
// EDIT_PICKER → sub-picker   (X / Doodle / Text / Signature bar)
// DOODLE      → draw on page (size+colour toolbar)
// TEXT        → tap-to-add   (hint + X / +ADD / ✓ bar)
// SIGNATURE   → full-screen pad (handled as separate composable)

enum class PdfEditMode { NONE, EDIT_PICKER, DOODLE, TEXT, SIGNATURE }
enum class ConvertTarget { NONE, WORD, PPT }

// ── Palette ───────────────────────────────────────────────────────────────────

private val penPalette = listOf(
    Color.Black, Color(0xFF555555), Color(0xFFAAAAAA), Color(0xFFDDDDDD),
    Color.Red, Color(0xFF00C853), Color(0xFF2196F3), Color(0xFFFF00FF)
)

// ── PdfRenderer helpers ───────────────────────────────────────────────────────

internal fun renderPage(context: Context, uri: Uri, pageIndex: Int, widthPx: Int): Bitmap? {
    return try {
        val fd  = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
        val rdr = PdfRenderer(fd)
        if (pageIndex >= rdr.pageCount) { rdr.close(); fd.close(); return null }
        val page = rdr.openPage(pageIndex)
        val h    = (widthPx * page.height.toFloat() / page.width.toFloat()).toInt().coerceAtLeast(1)
        val bmp  = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bmp).drawColor(android.graphics.Color.WHITE)
        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close(); rdr.close(); fd.close()
        bmp
    } catch (_: Exception) { null }
}

internal fun pdfPageCount(context: Context, uri: Uri): Int {
    return try {
        val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: return 0
        val r = PdfRenderer(fd); val cnt = r.pageCount; r.close(); fd.close(); cnt
    } catch (_: Exception) { 0 }
}

// ── Build annotated PDF and save ──────────────────────────────────────────────

private fun buildAnnotatedPdf(
    context       : Context,
    sourceUri     : Uri,
    annotations   : List<PageAnnotations>,
    density       : Float,
    scaledDensity : Float,  // displayMetrics.scaledDensity — how Compose converts sp → px
    pageBoxW      : Int,    // screen width of the page Box (px) used during editing
    pageBoxH      : Int,    // screen height of the page Box (px)
    destName      : String
): File? { // nullable — catch returns null
    return try {
    val wPx    = context.resources.displayMetrics.widthPixels
    val count  = pdfPageCount(context, sourceUri)
    val pdfDoc = PdfDocument()
    for (i in 0 until count) {
        val base = renderPage(context, sourceUri, i, wPx) ?: continue
        val comb = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        val cv   = android.graphics.Canvas(comb).also { it.drawBitmap(base, 0f, 0f, null) }
        annotations.getOrNull(i)?.let { ann ->
            val stkPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeCap = android.graphics.Paint.Cap.ROUND
                strokeJoin = android.graphics.Paint.Join.ROUND
            }
            ann.strokes.forEach { s ->
                if (s.points.size < 2) return@forEach
                stkPaint.color = s.color.toArgb(); stkPaint.strokeWidth = s.strokeWidth
                val p = android.graphics.Path()
                p.moveTo(s.points[0].x, s.points[0].y)
                s.points.drop(1).forEach { pt -> p.lineTo(pt.x, pt.y) }
                cv.drawPath(p, stkPaint)
            }
            val tPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            ann.texts.forEach { t ->
                // t.x/y are screen px (pageBox space); sizeSp is the raw sp value
                // that Compose rendered via Text(fontSize = sizeSp.sp).
                // Compose uses scaledDensity to convert sp → px:
                //   screenPx = sizeSp * scaledDensity
                // The bitmap is renderPage() width = displayW; shown at pageBoxW on screen.
                // So bitmap coords = screen coords * (bitmapW / pageBoxW).
                val scaleX = if (pageBoxW > 0) comb.width.toFloat()  / pageBoxW else 1f
                val scaleY = if (pageBoxH > 0) comb.height.toFloat() / pageBoxH else 1f
                // Text size in bitmap pixels = same visual proportion as on screen
                val bmpTextSizePx = t.sizeSp * scaledDensity * scaleY
                tPaint.color    = t.color.toArgb()
                tPaint.textSize = bmpTextSizePx
                // drawText baseline = top + ascent ≈ top + 0.8 * textSize
                cv.drawText(t.text, t.x * scaleX, t.y * scaleY + bmpTextSizePx * 0.85f, tPaint)
            }
            ann.signatures.forEach { sig ->
                val sw = (sig.width * comb.width).toInt()
                val sh = (sw.toFloat() / sig.bitmap.width * sig.bitmap.height).toInt()
                cv.drawBitmap(Bitmap.createScaledBitmap(sig.bitmap, sw, sh, true),
                    sig.x * comb.width, sig.y * comb.height, null)
            }
        }
        val info = PdfDocument.PageInfo.Builder(comb.width, comb.height, i + 1).create()
        val pg   = pdfDoc.startPage(info)
        pg.canvas.drawBitmap(comb, 0f, 0f, null)
        pdfDoc.finishPage(pg)
    }
        val out = File(context.cacheDir, destName)
        out.outputStream().use { pdfDoc.writeTo(it) }
        pdfDoc.close()
        out
    } catch (_: Exception) { null }
}

// ── PDF → DOCX ────────────────────────────────────────────────────────────────

@Suppress("SpellCheckingInspection")
private fun pdfToDocx(context: Context, uri: Uri, destName: String, onProg: (Int) -> Unit): File? {
    return try {
    val wPx   = context.resources.displayMetrics.widthPixels
    val count = pdfPageCount(context, uri)
    if (count == 0) return null
    val imgList = mutableListOf<Pair<String, ByteArray>>()
    val relsList = mutableListOf<String>()
    val body  = StringBuilder()

    for (i in 0 until count) {
        onProg((i + 1) * 85 / count)
        val bmp = renderPage(context, uri, i, wPx) ?: continue
        val bos = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, 88, bos)
        val img = "image${i+1}.jpg"; val rId = "rId${200+i}"
        imgList += "word/media/$img" to bos.toByteArray()
        relsList += """<Relationship Id="$rId" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/$img"/>"""
        val eW = 5486400L; val eH = (eW * bmp.height / bmp.width.toFloat()).toLong()
        body.append("""<w:p><w:r><w:drawing><wp:inline><wp:extent cx="$eW" cy="$eH"/>
          <wp:docPr id="${i+1}" name="$img"/>
          <a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture">
            <pic:pic><pic:nvPicPr><pic:cNvPr id="${i+1}" name="$img"/><pic:cNvPicPr/></pic:nvPicPr>
            <pic:blipFill><a:blip r:embed="$rId"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>
            <pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$eW" cy="$eH"/></a:xfrm>
            <a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr>
          </pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>""")
        if (i < count - 1) body.append("""<w:p><w:r><w:br w:type="page"/></w:r></w:p>""")
    }

    val docXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:wpc="http://schemas.microsoft.com/office/word/2010/wordprocessingCanvas"
  xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
  xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
  xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
  xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
  xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture">
<w:body>$body</w:body></w:document>"""

    val docRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
  ${relsList.joinToString("\n  ")}
</Relationships>"""

    val ct = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml"  ContentType="application/xml"/>
  <Default Extension="jpg"  ContentType="image/jpeg"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
  <Override PartName="/word/styles.xml"   ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
</Types>"""

    val rootRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""

    val styles = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:style w:type="paragraph" w:styleId="Normal"><w:name w:val="Normal"/></w:style>
</w:styles>"""

    val out = File(context.cacheDir, destName)
    val zos = java.util.zip.ZipOutputStream(out.outputStream())
    val addEntry: (String, ByteArray) -> Unit = { name, b ->
        zos.putNextEntry(java.util.zip.ZipEntry(name)); zos.write(b); zos.closeEntry()
    }
    addEntry("[Content_Types].xml",          ct.toByteArray())
    addEntry("_rels/.rels",                  rootRels.toByteArray())
    addEntry("word/document.xml",            docXml.toByteArray())
    addEntry("word/styles.xml",              styles.toByteArray())
    addEntry("word/_rels/document.xml.rels", docRels.toByteArray())
    imgList.forEach { (n, b) -> addEntry(n, b) }
    zos.close(); onProg(100)
    out
    } catch (_: Exception) { null }
}

// ── PDF → PPTX ────────────────────────────────────────────────────────────────

@Suppress("SpellCheckingInspection")
private fun pdfToPptx(context: Context, uri: Uri, destName: String, onProg: (Int) -> Unit): File? {
    return try {
    val wPx   = context.resources.displayMetrics.widthPixels
    val count = pdfPageCount(context, uri)
    if (count == 0) return null
    val imgList2 = mutableListOf<Pair<String, ByteArray>>()
    val slides = mutableListOf<String>(); val slideRels = mutableListOf<String>()
    val slideIdList = StringBuilder()

    for (i in 0 until count) {
        onProg((i + 1) * 85 / count)
        val bmp = renderPage(context, uri, i, wPx) ?: continue
        val bos = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, 88, bos)
        val img = "image${i+1}.jpg"
        imgList2 += "ppt/media/$img" to bos.toByteArray()
        val sW = 9144000L; val sH = 6858000L
        val iH = (sW * bmp.height / bmp.width.toFloat()).toLong()
        val iY = ((sH - iH) / 2).coerceAtLeast(0)
        slides += """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
  xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
  xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
  <p:cSld><p:spTree>
    <p:pic><p:nvPicPr><p:cNvPr id="${i+2}" name="$img"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr>
      <p:blipFill><a:blip r:embed="rId1"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>
      <p:spPr><a:xfrm><a:off x="0" y="$iY"/><a:ext cx="$sW" cy="$iH"/></a:xfrm>
        <a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr></p:pic>
  </p:spTree></p:cSld></p:sld>"""
        slideRels += """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="../media/$img"/>
</Relationships>"""
        slideIdList.append("""<p:sldId id="${256+i}" r:id="rId${10+i}"/>""")
    }

    val slideRelsEntries = slides.indices.joinToString("\n  ") { i ->
        """<Relationship Id="rId${10+i}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide${i+1}.xml"/>"""
    }
    val pres = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
  xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
  xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
  <p:sldMasterIdLst/><p:sldSz cx="9144000" cy="6858000"/>
  <p:sldIdLst>$slideIdList</p:sldIdLst></p:presentation>"""

    val ctTypes = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml"  ContentType="application/xml"/>
  <Default Extension="jpg"  ContentType="image/jpeg"/>
  <Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>""")
        slides.indices.forEach { i -> append("""
  <Override PartName="/ppt/slides/slide${i+1}.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>""") }
        append("\n</Types>")
    }

    val out = File(context.cacheDir, destName)
    val zos = java.util.zip.ZipOutputStream(out.outputStream())
    val addEntry: (String, ByteArray) -> Unit = { name, b ->
        zos.putNextEntry(java.util.zip.ZipEntry(name)); zos.write(b); zos.closeEntry()
    }
    addEntry("[Content_Types].xml",  ctTypes.toByteArray())
    addEntry("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/>
</Relationships>""".toByteArray())
    addEntry("ppt/presentation.xml", pres.toByteArray())
    addEntry("ppt/_rels/presentation.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  $slideRelsEntries
</Relationships>""".toByteArray())
    slides.forEachIndexed { i, s ->
        addEntry("ppt/slides/slide${i+1}.xml", s.toByteArray())
        addEntry("ppt/slides/_rels/slide${i+1}.xml.rels", slideRels[i].toByteArray())
    }
    imgList2.forEach { (n, b) -> addEntry(n, b) }
    zos.close(); onProg(100)
    out
    } catch (_: Exception) { null }
}

// ── Canvas draw helper ────────────────────────────────────────────────────────

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStroke(s: DrawStroke) {
    if (s.points.size < 2) return
    val p = Path()
    p.moveTo(s.points[0].x, s.points[0].y)
    s.points.drop(1).forEach { p.lineTo(it.x, it.y) }
    drawPath(p, s.color, style = Stroke(width = s.strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// SIGNATURE PAD — full-screen, separate composable
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
fun SignaturePadScreen(
    onConfirm: (Bitmap?) -> Unit,
    onCancel : () -> Unit
) {
    var strokes     by remember { mutableStateOf<List<DrawStroke>>(emptyList()) }
    var redoStack   by remember { mutableStateOf<List<DrawStroke>>(emptyList()) }
    var activePath  by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var penColor    by remember { mutableStateOf(Color.Black) }
    var penSize     by remember { mutableFloatStateOf(5f) }
    var canvasW     by remember { mutableIntStateOf(1) }
    var canvasH     by remember { mutableIntStateOf(1) }
    val hasContent  = strokes.isNotEmpty()

    Column(Modifier.fillMaxSize().background(Color(0xFF0D0D16)).statusBarsPadding()) {
        // ── Top bar ───────────────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCancel) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
            }
            Text("Add signature", color = Color.White,
                fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).padding(start = 4.dp))
            TextButton(
                onClick  = { strokes = emptyList(); redoStack = emptyList() },
                enabled  = hasContent
            ) { Text("Reset", color = if (hasContent) Color.White else Color(0xFF555566)) }
        }

        // ── Drawing canvas ────────────────────────────────────────────────────
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.White)
                .onGloballyPositioned { canvasW = it.size.width; canvasH = it.size.height }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { activePath = listOf(it); redoStack = emptyList() },
                        onDrag      = { ch, _ -> ch.consume(); activePath = activePath + ch.position },
                        onDragEnd   = {
                            if (activePath.size >= 2) strokes = strokes + DrawStroke(activePath, penColor, penSize)
                            activePath = emptyList()
                        },
                        onDragCancel = { activePath = emptyList() }
                    )
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                strokes.forEach    { drawStroke(it) }
                if (activePath.size >= 2) drawStroke(DrawStroke(activePath, penColor, penSize))
            }
            if (!hasContent && activePath.isEmpty()) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Sign here",
                        color = Color(0xFFBBBBCC), fontSize = 24.sp, fontWeight = FontWeight.Light)
                    Spacer(Modifier.height(6.dp))
                    Text("Add a signature to your document.",
                        color = Color(0xFFCCCCDD), fontSize = 14.sp)
                }
            }
        }

        // ── Toolbar ───────────────────────────────────────────────────────────
        Column(
            Modifier.fillMaxWidth().background(Color(0xFF1A1A2A))
                .navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Size", color = Color.White, fontSize = 13.sp, modifier = Modifier.width(42.dp))
                Slider(
                    value         = penSize, onValueChange = { penSize = it },
                    valueRange    = 2f..40f, modifier = Modifier.weight(1f),
                    colors        = SliderDefaults.colors(thumbColor = AccentBlue, activeTrackColor = AccentBlue)
                )
                Text("${penSize.toInt()}", color = Color.White, fontSize = 13.sp,
                    modifier = Modifier.width(28.dp), textAlign = TextAlign.End)
            }
            Spacer(Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(penPalette) { _, c ->
                    val sel = c == penColor
                    Box(
                        Modifier.size(38.dp)
                            .clip(CircleShape)
                            .background(if (sel) Color(0xFFFFD700) else Color.Transparent)
                            .padding(if (sel) 3.dp else 0.dp)
                            .clip(CircleShape).background(c)
                            .border(1.dp, Color(0xFF333344), CircleShape)
                            .clickable { penColor = c }
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Close, null, tint = Color.White)
                }
                Row {
                    IconButton(
                        onClick  = { if (strokes.isNotEmpty()) { redoStack += strokes.last(); strokes = strokes.dropLast(1) } },
                        enabled  = hasContent
                    ) { Icon(Icons.AutoMirrored.Filled.Undo, null, tint = if (hasContent) Color.White else Color(0xFF555566)) }
                    IconButton(
                        onClick  = { if (redoStack.isNotEmpty()) { strokes += redoStack.last(); redoStack = redoStack.dropLast(1) } },
                        enabled  = redoStack.isNotEmpty()
                    ) { Icon(Icons.AutoMirrored.Filled.Redo, null, tint = if (redoStack.isNotEmpty()) Color.White else Color(0xFF555566)) }
                }
                IconButton(onClick = {
                    if (strokes.isEmpty()) { onConfirm(null); return@IconButton }
                    val bmp = Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.ARGB_8888)
                    val cv  = android.graphics.Canvas(bmp)
                    cv.drawColor(android.graphics.Color.WHITE)
                    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        style = android.graphics.Paint.Style.STROKE
                        strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND
                    }
                    strokes.forEach { s ->
                        p.color = s.color.toArgb(); p.strokeWidth = s.strokeWidth
                        val path = android.graphics.Path()
                        s.points.firstOrNull()?.let { path.moveTo(it.x, it.y) }
                        s.points.drop(1).forEach { path.lineTo(it.x, it.y) }
                        cv.drawPath(path, p)
                    }
                    onConfirm(bmp)
                }) { Icon(Icons.Default.Check, null, tint = AccentBlue) }
            }
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// CONVERTING PROGRESS OVERLAY
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
fun ConvertingOverlay(target: ConvertTarget, progress: Int, onCancel: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .fillMaxWidth(0.85f)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xF0E0E0E8))
                .padding(top = 6.dp, bottom = 24.dp)
        ) {
            // X dismiss
            IconButton(
                onClick  = onCancel,
                modifier = Modifier.align(Alignment.TopEnd).size(40.dp)
            ) { Icon(Icons.Default.Close, null, tint = Color(0xFF666677)) }

            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(10.dp))
                // Linear progress bar (like the screenshot)
                LinearProgressIndicator(
                    progress      = { progress / 100f },
                    modifier      = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                    color         = AccentBlue,
                    trackColor    = Color(0xFFCCCCDD)
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    "Converting… ($progress%)",
                    color = Color(0xFF222233), fontSize = 17.sp, fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "to ${if (target == ConvertTarget.WORD) "Word (.docx)" else "PowerPoint (.pptx)"}",
                    color = Color(0xFF666677), fontSize = 13.sp
                )
            }
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// MAIN PDF VIEWER SCREEN
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
fun ImportedPdfViewerScreen(
    pdfUri          : Uri,
    onBack          : () -> Unit,
    onShareFile     : (File) -> Unit,
    initialEditMode : PdfEditMode = PdfEditMode.NONE
) {
    val context   = LocalContext.current
    val scope     = rememberCoroutineScope()
    val density   = LocalDensity.current.density
    val displayW  = context.resources.displayMetrics.widthPixels

    // ── PDF state ─────────────────────────────────────────────────────────────
    var pageCount   by remember { mutableIntStateOf(0) }
    var curPage     by remember { mutableIntStateOf(0) }
    var pageBitmaps by remember { mutableStateOf<Map<Int, Bitmap>>(emptyMap()) }
    var pdfTitle    by remember { mutableStateOf("Document") }
    val annotations = remember { mutableStateListOf<PageAnnotations>() }

    // ── Edit / convert state ──────────────────────────────────────────────────
    var editMode      by remember { mutableStateOf(initialEditMode) }
    var showConvert   by remember { mutableStateOf(false) }
    var convertTarget by remember { mutableStateOf(ConvertTarget.NONE) }
    var convertProg   by remember { mutableIntStateOf(0) }

    // ── Doodle state ──────────────────────────────────────────────────────────
    var doodleStrokes  by remember { mutableStateOf<List<DrawStroke>>(emptyList()) }
    var doodleRedo     by remember { mutableStateOf<List<DrawStroke>>(emptyList()) }
    var activePath     by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var doodleColor    by remember { mutableStateOf(Color.Black) }
    var doodleSize     by remember { mutableFloatStateOf(5f) }

    // ── Live text / signature overlay state ──────────────────────────────────
    var liveTexts      by remember { mutableStateOf<List<LiveText>>(emptyList()) }
    var liveSignatures by remember { mutableStateOf<List<LiveSignature>>(emptyList()) }
    var selectedItemId by remember { mutableStateOf<String?>(null) }
    var pageBoxW       by remember { mutableIntStateOf(1) }
    var pageBoxH       by remember { mutableIntStateOf(1) }

    // ── Text input dialog state ───────────────────────────────────────────────
    var showTextDialog by remember { mutableStateOf(false) }
    var tapPos         by remember { mutableStateOf(Offset.Zero) }
    var textInput      by remember { mutableStateOf("") }
    var textColor      by remember { mutableStateOf(Color.Black) }
    var textSize       by remember { mutableFloatStateOf(18f) }

    // ── Back handler ──────────────────────────────────────────────────────────
    BackHandler(enabled = editMode != PdfEditMode.NONE || showConvert) {
        when {
            editMode == PdfEditMode.DOODLE      -> { doodleStrokes = emptyList(); doodleRedo = emptyList(); editMode = PdfEditMode.EDIT_PICKER }
            editMode == PdfEditMode.TEXT        -> { liveTexts = emptyList(); selectedItemId = null; editMode = PdfEditMode.EDIT_PICKER }
            editMode == PdfEditMode.EDIT_PICKER -> editMode = PdfEditMode.NONE
            showConvert                         -> showConvert = false
        }
    }

    // ── Load PDF on first entry ───────────────────────────────────────────────
    LaunchedEffect(pdfUri) {
        withContext(Dispatchers.IO) {
            val cnt = pdfPageCount(context, pdfUri)
            val title = pdfUri.lastPathSegment
                ?.removeSuffix(".pdf")
                ?.substringAfterLast("/")
                ?.substringAfterLast("%2F") ?: "Document"
            withContext(Dispatchers.Main) {
                pageCount = cnt
                pdfTitle  = title
                repeat(cnt) { annotations.add(PageAnnotations()) }
            }
        }
    }

    // ── Render pages lazily ───────────────────────────────────────────────────
    LaunchedEffect(curPage, pageCount) {
        if (pageCount == 0) return@LaunchedEffect
        listOf(curPage, curPage + 1, curPage - 1)
            .filter { it in 0 until pageCount }
            .filter { !pageBitmaps.containsKey(it) }
            .forEach { idx ->
                val bmp = withContext(Dispatchers.IO) { renderPage(context, pdfUri, idx, displayW) }
                if (bmp != null) pageBitmaps = pageBitmaps + (idx to bmp)
            }
    }

    // ── Commit helpers ────────────────────────────────────────────────────────
    fun commitDoodle() {
        annotations.getOrNull(curPage)?.let { it.strokes = it.strokes + doodleStrokes }
        doodleStrokes = emptyList(); doodleRedo = emptyList(); activePath = emptyList()
    }
    fun commitText() {
        val ann = annotations.getOrNull(curPage) ?: return
        // Store in screen-pixel coords / sp exactly as the user sees them.
        // buildAnnotatedPdf() will apply the bitmap scale itself.
        ann.texts = ann.texts + liveTexts.map { lt ->
            TextAnnotation(
                x      = lt.x,
                y      = lt.y,
                text   = lt.text,
                color  = lt.color,
                sizeSp = lt.sizeSp
            )
        }
        liveTexts = emptyList(); selectedItemId = null
    }
    fun commitSignature() {
        val ann = annotations.getOrNull(curPage) ?: return
        if (pageBoxW < 2) return
        ann.signatures = ann.signatures + liveSignatures.map { ls ->
            // Actual screen width of signature = initW * scaleFactor (px)
            // where initW = pageBoxW * 0.4
            val screenWidthPx = pageBoxW * 0.4f * ls.scaleFactor
            // Normalise to 0-1 of page width
            val normW = (screenWidthPx / pageBoxW).coerceIn(0.05f, 1f)
            SignatureOverlay(
                x      = ls.x / pageBoxW,
                y      = ls.y / pageBoxH,
                width  = normW,
                bitmap = ls.bitmap
            )
        }
        liveSignatures = emptyList(); selectedItemId = null
    }

    // ── Signature pad (replaces the whole screen) ─────────────────────────────
    if (editMode == PdfEditMode.SIGNATURE) {
        SignaturePadScreen(
            onConfirm = { bmp ->
                if (bmp != null) {
                    // Place signature as a live interactive overlay in center of page
                    val cx = (pageBoxW * 0.3f).coerceAtLeast(40f)
                    val cy = (pageBoxH * 0.5f).coerceAtLeast(40f)
                    liveSignatures = liveSignatures + LiveSignature(
                        bitmap = bmp, x = cx, y = cy, scaleFactor = 1f
                    )
                    editMode = PdfEditMode.TEXT  // reuse TEXT picker bar (X / + / ✓) for adjust+confirm
                } else {
                    editMode = PdfEditMode.EDIT_PICKER
                }
            },
            onCancel = { editMode = PdfEditMode.EDIT_PICKER }
        )
        return
    }

    // ── Colors ────────────────────────────────────────────────────────────────
    val bgDark  = Color(0xFF0D0D16)
    val barBg   = Color(0xFF1A1A2A)
    val textPri = Color.White
    val textSec = Color(0xFF9999BB)

    Box(Modifier.fillMaxSize().background(bgDark).statusBarsPadding()) {


            // ── Top app bar ───────────────────────────────────────────────────

            // ── PDF page ──────────────────────────────────────────────────────
            Box(
                Modifier
                    .fillMaxWidth()
                    .fillMaxSize()
                    .background(Color(0xFFCCCCCC))
                    .then(when (editMode) {
                        PdfEditMode.DOODLE -> Modifier.pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart  = { activePath = listOf(it); doodleRedo = emptyList() },
                                onDrag       = { ch, _ -> ch.consume(); activePath = activePath + ch.position },
                                onDragEnd    = { if (activePath.size >= 2) doodleStrokes = doodleStrokes + DrawStroke(activePath, doodleColor, doodleSize); activePath = emptyList() },
                                onDragCancel = { activePath = emptyList() }
                            )
                        }
                        PdfEditMode.TEXT -> Modifier.pointerInput(Unit) {
                            detectTapGestures { offset -> tapPos = offset; textInput = ""; showTextDialog = true }
                        }
                        else -> Modifier
                    })
            ) {
                val bmp = pageBitmaps[curPage]
                if (bmp != null) {
                    Image(
                        bmp.asImageBitmap(), null,
                        modifier = Modifier.fillMaxSize()
                            .onGloballyPositioned { pageBoxW = it.size.width; pageBoxH = it.size.height }
                    )
                    // ── Permanent annotations (Canvas) ────────────────────────
                    Canvas(Modifier.fillMaxSize()) {
                        annotations.getOrNull(curPage)?.strokes?.forEach { drawStroke(it) }
                        annotations.getOrNull(curPage)?.signatures?.forEach { sig ->
                            val sw = (sig.width * size.width).toInt()
                            val sh = (sw.toFloat() / sig.bitmap.width * sig.bitmap.height).toInt()
                            drawImage(
                                image     = sig.bitmap.asImageBitmap(),
                                dstOffset = IntOffset((sig.x * size.width).toInt(), (sig.y * size.height).toInt()),
                                dstSize   = androidx.compose.ui.unit.IntSize(sw, sh)
                            )
                        }
                        // Committed texts are rendered as Compose Text() composables
                        // below the Canvas block — same renderer as live editing.
                        // Live doodle
                        if (editMode == PdfEditMode.DOODLE) {
                            doodleStrokes.forEach { drawStroke(it) }
                            if (activePath.size >= 2) drawStroke(DrawStroke(activePath, doodleColor, doodleSize))
                        }
                    }
                    // ── Committed text overlays (static, same renderer as live) ───
                    annotations.getOrNull(curPage)?.texts?.forEach { t ->
                        CommittedTextOverlay(t)
                    }
                    // ── Live interactive text overlays ────────────────────────
                    liveTexts.forEach { lt ->
                        val sel = selectedItemId == lt.id
                        LiveTextOverlay(
                            item     = lt,
                            selected = sel,
                            onSelect = { selectedItemId = lt.id },
                            onUpdate = { newX, newY, newSz ->
                                liveTexts = liveTexts.map { if (it.id == lt.id) it.copy(x=newX, y=newY, sizeSp=newSz) else it }
                            }
                        )
                    }
                    // ── Live interactive signature overlays ───────────────────
                    liveSignatures.forEach { ls ->
                        val sel   = selectedItemId == ls.id
                        val initW = (pageBoxW * 0.4f).coerceAtLeast(80f)
                        val w     = (initW * ls.scaleFactor).coerceAtLeast(40f)
                        val h     = if (ls.bitmap.width > 0) (w / ls.bitmap.width * ls.bitmap.height) else w
                        LiveSignatureOverlay(
                            item     = ls,
                            dispW    = w,
                            dispH    = h,
                            selected = sel,
                            onSelect = { selectedItemId = ls.id },
                            onUpdate = { newX, newY, newScale ->
                                liveSignatures = liveSignatures.map { if (it.id == ls.id) it.copy(x=newX, y=newY, scaleFactor=newScale) else it }
                            }
                        )
                    }
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AccentBlue)
                    }
                }

                // Page badge
                if (pageCount > 1) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(10.dp)
                            .background(Color(0xCC000000), RoundedCornerShape(20.dp))
                            .padding(horizontal = 14.dp, vertical = 5.dp)
                    ) {
                        Text("${curPage+1}/$pageCount", color = Color.White,
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

        // ── Top bar overlaid (never affects page size) ───────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .background(bgDark.copy(alpha = 0.92f))
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = textPri)
            }
            Text(pdfTitle, color = textPri, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, modifier = Modifier.weight(1f).padding(start = 2.dp))
            if (editMode == PdfEditMode.DOODLE) {
                TextButton(onClick = { doodleStrokes = emptyList(); doodleRedo = emptyList() }) {
                    Text("Reset", color = textPri, fontWeight = FontWeight.Medium)
                }
            } else if (editMode == PdfEditMode.TEXT) {
                TextButton(onClick = { liveTexts = emptyList(); liveSignatures = emptyList(); selectedItemId = null }) {
                    Text("Reset", color = textPri, fontWeight = FontWeight.Medium)
                }
            } else {
                IconButton(onClick = {}) { Icon(Icons.Default.Edit, null, tint = textPri) }
                IconButton(onClick = {}) { Icon(Icons.Default.Search, null, tint = textPri) }
                IconButton(onClick = {}) { Icon(Icons.Default.MoreVert, null, tint = textPri) }
            }
        }

        // ── "Tap anywhere" hint overlaid below top bar ────────────────────────
        if (editMode == PdfEditMode.TEXT) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopStart)
                    .padding(top = 60.dp, start = 16.dp, end = 16.dp)
            ) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xCC333344))
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text("Tap anywhere to add text", color = textPri, fontSize = 14.sp)
                }
            }
        }

        // ── Bottom bars overlaid at bottom ────────────────────────────────────
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth()) {

            // ── Bottom bars — state-driven ────────────────────────────────────
            when {
                // ── 1. DOODLE toolbar ─────────────────────────────────────────
                editMode == PdfEditMode.DOODLE -> {
                    Column(
                        Modifier.fillMaxWidth().background(barBg)
                            .navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        // Stroke size
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Size", color = textPri, fontSize = 13.sp, modifier = Modifier.width(42.dp))
                            Slider(
                                value = doodleSize, onValueChange = { doodleSize = it },
                                valueRange = 2f..40f, modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(thumbColor = AccentBlue, activeTrackColor = AccentBlue)
                            )
                            Text("${doodleSize.toInt()}", color = textPri, fontSize = 13.sp,
                                modifier = Modifier.width(28.dp), textAlign = TextAlign.End)
                        }
                        Spacer(Modifier.height(6.dp))
                        // Colour palette
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            itemsIndexed(penPalette) { _, c ->
                                val sel = c == doodleColor
                                Box(
                                    Modifier.size(36.dp).clip(CircleShape)
                                        .background(if (sel) Color(0xFFFFD700) else Color.Transparent)
                                        .padding(if (sel) 3.dp else 0.dp)
                                        .clip(CircleShape).background(c)
                                        .border(1.dp, Color(0xFF333344), CircleShape)
                                        .clickable { doodleColor = c }
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        // Action row
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment     = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { doodleStrokes = emptyList(); doodleRedo = emptyList(); editMode = PdfEditMode.EDIT_PICKER }) {
                                Icon(Icons.Default.Close, null, tint = textPri)
                            }
                            Row {
                                IconButton(
                                    onClick  = { if (doodleStrokes.isNotEmpty()) { doodleRedo += doodleStrokes.last(); doodleStrokes = doodleStrokes.dropLast(1) } },
                                    enabled  = doodleStrokes.isNotEmpty()
                                ) { Icon(Icons.AutoMirrored.Filled.Undo, null, tint = if (doodleStrokes.isNotEmpty()) textPri else Color(0xFF555566)) }
                                IconButton(
                                    onClick  = { if (doodleRedo.isNotEmpty()) { doodleStrokes += doodleRedo.last(); doodleRedo = doodleRedo.dropLast(1) } },
                                    enabled  = doodleRedo.isNotEmpty()
                                ) { Icon(Icons.AutoMirrored.Filled.Redo, null, tint = if (doodleRedo.isNotEmpty()) textPri else Color(0xFF555566)) }
                            }
                            IconButton(onClick = { commitDoodle(); editMode = PdfEditMode.NONE }) {
                                Icon(Icons.Default.Check, null, tint = AccentBlue)
                            }
                        }
                    }
                }

                // ── 2. TEXT toolbar ───────────────────────────────────────────
                editMode == PdfEditMode.TEXT -> {
                    Row(
                        Modifier.fillMaxWidth().background(barBg)
                            .navigationBarsPadding().padding(horizontal = 16.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { liveTexts = emptyList(); liveSignatures = emptyList(); selectedItemId = null; editMode = PdfEditMode.EDIT_PICKER }) {
                            Icon(Icons.Default.Close, null, tint = textPri)
                        }
                        // ADD button (pill style — matches screenshot)
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(24.dp))
                                .background(Color(0xFF2A2A3A))
                                .border(1.dp, Color(0xFF444455), RoundedCornerShape(24.dp))
                                .clickable { tapPos = Offset(100f, 200f); textInput = ""; showTextDialog = true }
                                .padding(horizontal = 28.dp, vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Add, null, tint = textPri, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("ADD", color = textPri, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            }
                        }
                        IconButton(onClick = { commitText(); commitSignature(); editMode = PdfEditMode.NONE }) {
                            Icon(Icons.Default.Check, null, tint = AccentBlue)
                        }
                    }
                }

                // ── 3. EDIT PICKER bar (X | Doodle | Text | Signature) ────────
                editMode == PdfEditMode.EDIT_PICKER -> {
                    Row(
                        Modifier.fillMaxWidth().background(barBg)
                            .navigationBarsPadding().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { editMode = PdfEditMode.NONE }) {
                            Box(
                                Modifier.size(36.dp).clip(CircleShape).background(Color(0xFF2A2A3A)),
                                contentAlignment = Alignment.Center
                            ) { Icon(Icons.Default.Close, null, tint = textPri, modifier = Modifier.size(20.dp)) }
                        }
                        EditPickerItem(Icons.Default.Brush,      "Doodle",    textSec) { editMode = PdfEditMode.DOODLE }
                        EditPickerItem(Icons.Default.TextFields, "Text",      textSec) { editMode = PdfEditMode.TEXT }
                        EditPickerItem(Icons.Default.Draw,       "Signature", textSec) { editMode = PdfEditMode.SIGNATURE }
                    }
                }

                // ── 4. CONVERT bar (X | To Word | To PPT) ────────────────────
                showConvert -> {
                    Row(
                        Modifier.fillMaxWidth().background(barBg)
                            .navigationBarsPadding().padding(vertical = 10.dp, horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { showConvert = false }) {
                            Box(
                                Modifier.size(36.dp).clip(CircleShape).background(Color(0xFF2A2A3A)),
                                contentAlignment = Alignment.Center
                            ) { Icon(Icons.Default.Close, null, tint = textPri, modifier = Modifier.size(20.dp)) }
                        }
                        // To Word
                        ConvertItem(
                            icon    = Icons.Default.Description,
                            label   = "To Word",
                            iconBg  = Color(0xFF1565C0),
                            badge   = null
                        ) {
                            showConvert   = false
                            convertTarget = ConvertTarget.WORD
                            convertProg   = 0
                            val ts = System.currentTimeMillis()
                            scope.launch(Dispatchers.IO) {
                                val file = pdfToDocx(context, pdfUri, "doc_$ts.docx") { p ->
                                    scope.launch(Dispatchers.Main) { convertProg = p }
                                }
                                withContext(Dispatchers.Main) {
                                    convertTarget = ConvertTarget.NONE
                                    file?.let { onShareFile(it) }
                                }
                            }
                        }
                        // To PPT
                        ConvertItem(
                            icon   = Icons.Default.Slideshow,
                            label  = "To PPT",
                            iconBg = Color(0xFFB71C1C),
                            badge  = Color.Red
                        ) {
                            showConvert   = false
                            convertTarget = ConvertTarget.PPT
                            convertProg   = 0
                            val ts = System.currentTimeMillis()
                            scope.launch(Dispatchers.IO) {
                                val file = pdfToPptx(context, pdfUri, "ppt_$ts.pptx") { p ->
                                    scope.launch(Dispatchers.Main) { convertProg = p }
                                }
                                withContext(Dispatchers.Main) {
                                    convertTarget = ConvertTarget.NONE
                                    file?.let { onShareFile(it) }
                                }
                            }
                        }
                    }
                }

                // ── 5. NORMAL bar (Edit | Convert | Share) ────────────────────
                else -> {
                    Row(
                        Modifier.fillMaxWidth().background(barBg)
                            .navigationBarsPadding().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        NormalBarItem(Icons.Default.Edit,      "Edit",    textSec) { editMode = PdfEditMode.EDIT_PICKER }
                        NormalBarItem(Icons.Default.SwapHoriz, "Convert", textSec) { showConvert = true }
                        NormalBarItem(Icons.Default.Share,     "Share",   textSec) {
                            // Build annotated PDF then share
                            scope.launch(Dispatchers.IO) {
                                val ts   = System.currentTimeMillis()
                                val scaledDen = context.resources.displayMetrics.scaledDensity
                                val file = buildAnnotatedPdf(context, pdfUri, annotations, density, scaledDen, pageBoxW, pageBoxH, "shared_$ts.pdf")
                                file?.let {
                                    val shareUri = FileProvider.getUriForFile(context, "${context.packageName}.provider", it)
                                    val intent   = Intent(Intent.ACTION_SEND).apply {
                                        type     = "application/pdf"
                                        putExtra(Intent.EXTRA_STREAM, shareUri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    withContext(Dispatchers.Main) {
                                        context.startActivity(Intent.createChooser(intent, "Share PDF"))
                                    }
                                }
                            }
                        }
                    }
                }
            }

        } // bottom bars Box

        // ── Converting overlay (floats over everything) ───────────────────────
        if (convertTarget != ConvertTarget.NONE) {
            ConvertingOverlay(
                target   = convertTarget,
                progress = convertProg,
                onCancel = { convertTarget = ConvertTarget.NONE }
            )
        }
    } // Box

    // ── Text input dialog ─────────────────────────────────────────────────────
    if (showTextDialog) {
        Dialog(onDismissRequest = { showTextDialog = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF1E1E2E))
                    .padding(20.dp)
            ) {
                Text("Add Text", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value         = textInput,
                    onValueChange = { textInput = it },
                    label         = { Text("Type here…", color = textSec) },
                    textStyle     = androidx.compose.ui.text.TextStyle(color = Color.White),
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = AccentBlue,
                        unfocusedBorderColor = Color(0xFF444455),
                        cursorColor          = AccentBlue
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Size:", color = textSec, fontSize = 13.sp)
                    Slider(
                        value = textSize, onValueChange = { textSize = it },
                        valueRange = 8f..60f,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        colors = SliderDefaults.colors(thumbColor = AccentBlue, activeTrackColor = AccentBlue)
                    )
                    Text("${textSize.toInt()}", color = Color.White, fontSize = 13.sp)
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(penPalette) { _, c ->
                        val sel = c == textColor
                        Box(
                            Modifier.size(30.dp).clip(CircleShape)
                                .background(if (sel) Color(0xFFFFD700) else Color.Transparent)
                                .padding(if (sel) 3.dp else 0.dp)
                                .clip(CircleShape).background(c)
                                .border(1.dp, Color(0xFF333344), CircleShape)
                                .clickable { textColor = c }
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { showTextDialog = false }) {
                        Text("Cancel", color = textSec)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (textInput.isNotBlank())
                                liveTexts = liveTexts + LiveText(
                                    text = textInput, color = textColor, sizeSp = textSize,
                                    x = tapPos.x, y = tapPos.y
                                )
                            showTextDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                    ) { Text("Add", color = Color.White) }
                }
            }
        }
    }
}

// ── Small helper composables ──────────────────────────────────────────────────

@Composable
private fun NormalBarItem(
    icon   : androidx.compose.ui.graphics.vector.ImageVector,
    label  : String,
    tint   : Color,
    onClick: () -> Unit
) {
    Column(
        modifier            = Modifier.clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, color = tint, fontSize = 12.sp)
    }
}

@Composable
private fun EditPickerItem(
    icon   : androidx.compose.ui.graphics.vector.ImageVector,
    label  : String,
    tint   : Color,
    onClick: () -> Unit
) {
    Column(
        modifier            = Modifier.clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = tint, fontSize = 12.sp)
    }
}

@Composable
private fun ConvertItem(
    icon   : androidx.compose.ui.graphics.vector.ImageVector,
    label  : String,
    iconBg : Color,
    badge  : Color?,
    onClick: () -> Unit
) {
    Box {
        Column(
            modifier            = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF252535))
                .clickable(onClick = onClick)
                .padding(horizontal = 28.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(iconBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.height(6.dp))
            Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        // Red notification dot (like in screenshot)
        if (badge != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-4).dp, y = 4.dp)
                    .size(10.dp)
                    .background(badge, CircleShape)
            )
        }
    }
}

// ── Draggable interactive text overlay ───────────────────────────────────────

@Composable
fun LiveTextOverlay(
    item    : LiveText,
    selected: Boolean,
    onSelect: () -> Unit,
    onUpdate: (newX: Float, newY: Float, newSizeSp: Float) -> Unit
) {
    var curX   by remember(item.id) { mutableFloatStateOf(item.x) }
    var curY   by remember(item.id) { mutableFloatStateOf(item.y) }
    var curSz  by remember(item.id) { mutableFloatStateOf(item.sizeSp) }

    Box(
        Modifier
            .absoluteOffset { IntOffset(curX.roundToInt(), curY.roundToInt()) }
            .pointerInput(item.id) {
                detectTransformGestures { _, pan, zoom, _ ->
                    curX  += pan.x
                    curY  += pan.y
                    curSz  = (curSz * zoom).coerceIn(8f, 120f)
                    onUpdate(curX, curY, curSz)
                }
            }
            .clickable(onClick = onSelect)
            .then(if (selected) Modifier.border(1.5.dp, AccentBlue.copy(alpha = 0.8f), RoundedCornerShape(4.dp)) else Modifier)
            .padding(6.dp)
    ) {
        Text(
            text  = item.text,
            color = item.color,
            fontSize = curSz.sp,
            fontWeight = FontWeight.Normal
        )
        // Resize handle — bottom-right corner
        if (selected) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 8.dp, y = 8.dp)
                    .size(18.dp)
                    .background(AccentBlue, CircleShape)
                    .border(2.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.OpenWith, null,
                    tint = Color.White, modifier = Modifier.size(10.dp)
                )
            }
        }
    }
}

// ── Draggable interactive signature overlay ───────────────────────────────────

@Composable
fun LiveSignatureOverlay(
    item    : LiveSignature,
    dispW   : Float,
    dispH   : Float,
    selected: Boolean,
    onSelect: () -> Unit,
    onUpdate: (newX: Float, newY: Float, newScale: Float) -> Unit
) {
    var curX     by remember(item.id) { mutableFloatStateOf(item.x) }
    var curY     by remember(item.id) { mutableFloatStateOf(item.y) }
    var curScale by remember(item.id) { mutableFloatStateOf(item.scaleFactor) }

    val d = LocalDensity.current
    Box(
        Modifier
            .absoluteOffset { IntOffset(curX.roundToInt(), curY.roundToInt()) }
            .size(width = with(d) { dispW.toDp() }, height = with(d) { dispH.toDp() })
            .pointerInput(item.id) {
                detectTransformGestures { _, pan, zoom, _ ->
                    curX     += pan.x
                    curY     += pan.y
                    curScale  = (curScale * zoom).coerceIn(0.05f, 6f)
                    onUpdate(curX, curY, curScale)
                }
            }
            .clickable(onClick = onSelect)
            .then(if (selected) Modifier.border(1.5.dp, AccentBlue.copy(alpha = 0.8f), RoundedCornerShape(4.dp)) else Modifier)
    ) {
        Image(
            bitmap            = item.bitmap.asImageBitmap(),
            contentDescription = null,
            modifier          = Modifier.fillMaxSize()
        )
        // Resize handle
        if (selected) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 8.dp, y = 8.dp)
                    .size(20.dp)
                    .background(AccentBlue, CircleShape)
                    .border(2.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.OpenWith, null,
                    tint = Color.White, modifier = Modifier.size(11.dp)
                )
            }
        }
    }
}

// ── Committed text overlay — identical renderer to LiveTextOverlay, no gestures ──

@Composable
fun CommittedTextOverlay(t: TextAnnotation) {
    Box(
        Modifier
            .absoluteOffset { IntOffset(t.x.roundToInt(), t.y.roundToInt()) }
            .padding(6.dp)
    ) {
        Text(
            text       = t.text,
            color      = t.color,
            fontSize   = t.sizeSp.sp,
            fontWeight = FontWeight.Normal
        )
    }
}
