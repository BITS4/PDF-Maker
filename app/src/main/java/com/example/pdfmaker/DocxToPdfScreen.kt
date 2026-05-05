package com.example.pdfmaker

import android.content.Context
import android.os.Environment
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream

// ── Internal state machine ────────────────────────────────────────────────────

private enum class DocxState { PICK, READY, CONVERTING, DONE, ERROR }


// ── Screen ────────────────────────────────────────────────────────────────────

@Composable
fun DocxToPdfScreen(onBack: () -> Unit, onOpenFile: (PdfFile) -> Unit) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    val bgDark  = Color(0xFF0D0D16)
    val barBg   = Color(0xFF1A1A2A)
    val cardBg  = Color(0xFF14141F)
    val textPri = Color.White
    val textSec = Color(0xFF9999BB)
    val accent  = Color(0xFF1565C0)   // Word-blue theme

    var state        by remember { mutableStateOf(DocxState.PICK) }
    var pickedUri    by remember { mutableStateOf<Uri?>(null) }
    var pickedName   by remember { mutableStateOf("") }
    var pickedSizeKb by remember { mutableStateOf(0L) }
    var progress     by remember { mutableIntStateOf(0) }
    var progressText by remember { mutableStateOf("") }
    var resultFile   by remember { mutableStateOf<File?>(null) }
    var errorMsg     by remember { mutableStateOf("") }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pickedUri  = uri
            pickedName = uri.lastPathSegment
                ?.substringAfterLast("/")
                ?.substringAfterLast("%2F")
                ?.removeSuffix(".docx")
                ?.take(40) ?: "document"
            pickedSizeKb = context.contentResolver
                .openFileDescriptor(uri, "r")?.use { it.statSize / 1024 } ?: 0L
            state = DocxState.READY
        }
    }

    fun startConvert() {
        val uri = pickedUri ?: return
        state    = DocxState.CONVERTING
        progress = 0
        scope.launch(Dispatchers.IO) {
            try {
                val file = docxToPdf(context, uri, pickedName) { p, txt ->
                    scope.launch(Dispatchers.Main) { progress = p; progressText = txt }
                }
                withContext(Dispatchers.Main) {
                    if (file != null) {
                        resultFile = file
                        FileCache.prependFile(
                            PdfFile(
                                name         = file.name,
                                filePath     = file.absolutePath,
                                size         = docxFormatSize(file.length() / 1024),
                                date         = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date()),
                                pageCount    = 1,
                                lastModified = file.lastModified()
                            )
                        )
                        state = DocxState.DONE
                    } else {
                        errorMsg = "Conversion failed. The file may be password-protected or use unsupported formatting."
                        state    = DocxState.ERROR
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    errorMsg = e.message ?: "Unknown error"
                    state    = DocxState.ERROR
                }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(bgDark).statusBarsPadding()) {
        Column(Modifier.fillMaxSize()) {

            // ── Top bar ───────────────────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth().background(barBg)
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = textPri)
                }
                Text("Docx to PDF", color = textPri,
                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).padding(start = 4.dp))
                if (state == DocxState.READY) {
                    TextButton(onClick = { pickedUri = null; state = DocxState.PICK }) {
                        Text("Change", color = textSec, fontSize = 13.sp)
                    }
                }
            }

            when (state) {

                // ── 1. Pick ───────────────────────────────────────────────────
                DocxState.PICK -> {
                    Column(
                        Modifier.fillMaxSize().padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            Modifier.size(100.dp).clip(CircleShape)
                                .background(Color(0xFF0D1A2E))
                                .border(2.dp, accent.copy(alpha = 0.5f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Description, null,
                                tint = accent, modifier = Modifier.size(46.dp))
                        }
                        Spacer(Modifier.height(22.dp))
                        Text("Convert Word to PDF", color = textPri,
                            fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Converts text, headings, bold/italic and\nembedded images from your .docx file.",
                            color   = textSec, fontSize = 14.sp,
                            textAlign = TextAlign.Center, lineHeight = 20.sp
                        )
                        Spacer(Modifier.height(36.dp))
                        Button(
                            onClick  = { filePicker.launch(arrayOf(
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                "application/msword"
                            )) },
                            modifier = Modifier.fillMaxWidth(0.75f).height(54.dp),
                            shape    = RoundedCornerShape(14.dp),
                            colors   = ButtonDefaults.buttonColors(containerColor = accent)
                        ) {
                            Icon(Icons.Default.FileOpen, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Choose .docx File", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                    }
                }

                // ── 2. Ready ──────────────────────────────────────────────────
                DocxState.READY -> {
                    Column(
                        Modifier.fillMaxSize().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // File card
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                                .background(cardBg).padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier.size(54.dp).clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF0D1A2E)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Description, null,
                                    tint = accent, modifier = Modifier.size(30.dp))
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text("$pickedName.docx", color = textPri,
                                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(docxFormatSize(pickedSizeKb), color = textSec, fontSize = 12.sp)
                            }
                        }

                        // What will be converted info card
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                .background(cardBg).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("What gets converted", color = textPri,
                                fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            FeatureRow(Icons.Default.FormatAlignLeft,  "Paragraphs & text",         accent)
                            FeatureRow(Icons.Default.FormatBold,       "Bold & italic formatting",  accent)
                            FeatureRow(Icons.Default.Title,            "Headings (H1, H2, H3)",     accent)
                            FeatureRow(Icons.Default.Image,            "Embedded images",           accent)
                            FeatureRow(Icons.Default.InsertPageBreak,  "Page breaks",               accent)
                        }

                        Spacer(Modifier.weight(1f))

                        Button(
                            onClick  = { startConvert() },
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            shape    = RoundedCornerShape(14.dp),
                            colors   = ButtonDefaults.buttonColors(containerColor = accent)
                        ) {
                            Icon(Icons.Default.PictureAsPdf, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Convert to PDF", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    }
                }

                // ── 3. Converting ─────────────────────────────────────────────
                DocxState.CONVERTING -> {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        DocxSpinner(progress = progress, color = accent)
                        Spacer(Modifier.height(28.dp))
                        Text("Converting…", color = textPri,
                            fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text(progressText, color = textSec, fontSize = 13.sp)
                        Spacer(Modifier.height(6.dp))
                        Text("$progress%", color = Color(accent.value),
                            fontSize = 32.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(20.dp))
                        LinearProgressIndicator(
                            progress   = { progress / 100f },
                            modifier   = Modifier.fillMaxWidth().height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color      = accent,
                            trackColor = Color(0xFF2A2A40)
                        )
                    }
                }

                // ── 4. Done ───────────────────────────────────────────────────
                DocxState.DONE -> {
                    val file = resultFile
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            Modifier.size(100.dp).clip(CircleShape)
                                .background(Color(0xFF1A3020)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.CheckCircle, null,
                                tint = Color(0xFF4CAF50), modifier = Modifier.size(52.dp))
                        }
                        Spacer(Modifier.height(20.dp))
                        Text("Conversion Complete!", color = textPri,
                            fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text("${pickedName}.pdf", color = textSec, fontSize = 14.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(8.dp))
                        if (file != null) {
                            Text(docxFormatSize(file.length() / 1024),
                                color = Color(0xFF4CAF50), fontSize = 13.sp)
                        }
                        Spacer(Modifier.height(32.dp))

                        // Open + Share row
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    if (file != null) onOpenFile(PdfFile(
                                        name         = file.name,
                                        filePath     = file.absolutePath,
                                        size         = docxFormatSize(file.length() / 1024),
                                        date         = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date()),
                                        pageCount    = 1,
                                        lastModified = file.lastModified()
                                    ))
                                },
                                modifier = Modifier.weight(1f).height(52.dp),
                                shape    = RoundedCornerShape(14.dp),
                                colors   = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                            ) {
                                Icon(Icons.Default.OpenInNew, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Open", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            }
                            Button(
                                onClick = { if (file != null) shareDocxPdf(context, file) },
                                modifier = Modifier.weight(1f).height(52.dp),
                                shape    = RoundedCornerShape(14.dp),
                                colors   = ButtonDefaults.buttonColors(containerColor = accent)
                            ) {
                                Icon(Icons.Default.Share, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Share", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick  = { pickedUri = null; state = DocxState.PICK },
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            shape    = RoundedCornerShape(14.dp),
                            border   = androidx.compose.foundation.BorderStroke(1.dp, textSec.copy(alpha = 0.4f))
                        ) {
                            Icon(Icons.Default.Add, null, tint = textSec, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Convert Another", color = textSec, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        }
                    }
                }

                // ── 5. Error ──────────────────────────────────────────────────
                DocxState.ERROR -> {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            Modifier.size(90.dp).clip(CircleShape).background(Color(0xFF2A1010)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.ErrorOutline, null,
                                tint = Color(0xFFF44336), modifier = Modifier.size(46.dp))
                        }
                        Spacer(Modifier.height(18.dp))
                        Text("Conversion Failed", color = textPri,
                            fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(errorMsg, color = textSec, fontSize = 13.sp,
                            textAlign = TextAlign.Center)
                        Spacer(Modifier.height(28.dp))
                        Button(
                            onClick  = { state = DocxState.READY },
                            modifier = Modifier.fillMaxWidth(0.6f).height(50.dp),
                            shape    = RoundedCornerShape(14.dp),
                            colors   = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                        ) { Text("Try Again", fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }
}

// ── Helper composables ────────────────────────────────────────────────────────

@Composable
private fun FeatureRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, color = Color(0xFF9999BB), fontSize = 13.sp)
    }
}

@Composable
private fun DocxSpinner(progress: Int, color: Color) {
    val inf = rememberInfiniteTransition(label = "spin")
    val angle by inf.animateFloat(
        initialValue  = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label         = "angle"
    )
    ComposeCanvas(Modifier.size(110.dp)) {
        drawArc(Color(0xFF2A2A40), 0f, 360f, false,
            style = Stroke(10.dp.toPx(), cap = StrokeCap.Round))
        drawArc(color, angle - 90f, (progress * 3.6f).coerceAtLeast(10f), false,
            style = Stroke(10.dp.toPx(), cap = StrokeCap.Round))
    }
}

// ── Core conversion: docx → PDF ───────────────────────────────────────────────

private fun docxToPdf(
    context  : Context,
    uri      : Uri,
    baseName : String,
    onProg   : (Int, String) -> Unit
): File? {
    return try {
        onProg(5, "Reading document…")

        // ── Step 1: unzip .docx and collect parts ─────────────────────────────
        val xmlContent  = StringBuilder()
        val mediaImages = mutableMapOf<String, ByteArray>()   // "image1.jpeg" → bytes
        val relsMap     = mutableMapOf<String, String>()       // rId → target filename

        context.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(raw).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    when {
                        name == "word/document.xml" -> {
                            xmlContent.append(zis.bufferedReader().readText())
                        }
                        name == "word/_rels/document.xml.rels" -> {
                            // Parse relationship file to map rId → media filename
                            parseRels(zis.bufferedReader().readText(), relsMap)
                        }
                        name.startsWith("word/media/") -> {
                            val imgName = name.substringAfterLast("/")
                            mediaImages[imgName] = zis.readBytes()
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } ?: return null

        if (xmlContent.isEmpty()) return null
        onProg(30, "Parsing content…")

        // ── Step 2: parse document.xml into blocks ────────────────────────────
        val blocks = parseDocXml(xmlContent.toString(), relsMap)

        onProg(50, "Rendering pages…")

        // ── Step 3: render blocks onto PdfDocument ────────────────────────────
        val pageW = 595   // A4 points width
        val pageH = 842   // A4 points height
        val marginL = 56f
        val marginR = 56f
        val marginT = 60f
        val marginB = 60f
        val contentW = pageW - marginL - marginR

        val pdfDoc  = PdfDocument()
        var pageNum = 1
        var info    = PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create()
        var page    = pdfDoc.startPage(info)
        var canvas: Canvas = page.canvas
        var curY    = marginT

        fun newPage() {
            pdfDoc.finishPage(page)
            pageNum++
            info   = PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create()
            page   = pdfDoc.startPage(info)
            canvas = page.canvas
            curY   = marginT
        }

        fun ensureSpace(needed: Float) {
            if (curY + needed > pageH - marginB) newPage()
        }

        val totalBlocks = blocks.size.coerceAtLeast(1)
        blocks.forEachIndexed { idx, block ->
            onProg(50 + idx * 45 / totalBlocks, "Rendering…")

            when (block) {
                is DocBlock.PageBreak -> {
                    newPage()
                }

                is DocBlock.Paragraph -> {
                    if (block.runs.isEmpty()) {
                        // Empty paragraph = spacing
                        curY += 8f
                        return@forEachIndexed
                    }

                    // Build combined text with formatting spans using android.text
                    val sb = android.text.SpannableStringBuilder()
                    for (run in block.runs) {
                        val start = sb.length
                        sb.append(run.text)
                        val end = sb.length
                        if (run.bold) sb.setSpan(
                            android.text.style.StyleSpan(Typeface.BOLD), start, end,
                            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        if (run.italic) sb.setSpan(
                            android.text.style.StyleSpan(Typeface.ITALIC), start, end,
                            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }

                    val baseFontPt = when (block.headingLevel) {
                        1 -> 22f; 2 -> 17f; 3 -> 14f; else -> block.runs.firstOrNull()?.fontSize ?: 11f
                    }
                    // Convert pt → px (72 pt = 1 inch, PDF coordinates are in points)
                    val textSizePx = baseFontPt

                    val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                        color    = AColor.BLACK
                        textSize = textSizePx
                        if (block.headingLevel > 0) typeface = Typeface.DEFAULT_BOLD
                    }

                    val sl = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        StaticLayout.Builder.obtain(sb, 0, sb.length, tp, contentW.toInt())
                            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                            .setLineSpacing(2f, 1.2f)
                            .setIncludePad(false)
                            .build()
                    } else {
                        @Suppress("DEPRECATION")
                        StaticLayout(sb, tp, contentW.toInt(),
                            Layout.Alignment.ALIGN_NORMAL, 1.2f, 2f, false)
                    }

                    val blockH = sl.height.toFloat() + if (block.headingLevel > 0) 8f else 4f
                    ensureSpace(blockH)

                    canvas.save()
                    canvas.translate(marginL, curY)
                    sl.draw(canvas)
                    canvas.restore()

                    // Underline for headings
                    if (block.headingLevel == 1) {
                        val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color       = AColor.parseColor("#CCCCCC")
                            strokeWidth = 0.5f
                        }
                        canvas.drawLine(marginL, curY + sl.height + 3,
                            marginL + contentW, curY + sl.height + 3, lp)
                    }

                    curY += blockH + (if (block.headingLevel > 0) 4f else 2f)
                }

                is DocBlock.ImageBlock -> {
                    val imgBytes = mediaImages[block.name] ?: return@forEachIndexed
                    val bmp = BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.size)
                        ?: return@forEachIndexed

                    val maxImgW = contentW
                    val scale   = (maxImgW / bmp.width.toFloat()).coerceAtMost(1f)
                    val dispW   = bmp.width  * scale
                    val dispH   = bmp.height * scale

                    ensureSpace(dispH + 8f)

                    val dst = android.graphics.RectF(marginL, curY, marginL + dispW, curY + dispH)
                    canvas.drawBitmap(bmp, null, dst, null)
                    bmp.recycle()
                    curY += dispH + 10f
                }
            }
        }

        pdfDoc.finishPage(page)
        onProg(97, "Saving…")

        val dir  = getPdfMakerDir(context)
        val file = File(dir, "$baseName.pdf")
        file.outputStream().use { pdfDoc.writeTo(it) }
        pdfDoc.close()

        onProg(100, "Done!")
        file
    } catch (_: Exception) { null }
}

// ── Parse word/_rels/document.xml.rels ───────────────────────────────────────

private fun parseRels(xml: String, out: MutableMap<String, String>) {
    try {
        val factory = XmlPullParserFactory.newInstance()
        val parser  = factory.newPullParser()
        parser.setInput(xml.reader())
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "Relationship") {
                val id     = parser.getAttributeValue(null, "Id") ?: ""
                val target = parser.getAttributeValue(null, "Target") ?: ""
                if (id.isNotEmpty() && target.contains("media/")) {
                    out[id] = target.substringAfterLast("/")
                }
            }
            event = parser.next()
        }
    } catch (_: Exception) {}
}

// ── Parse word/document.xml into blocks ───────────────────────────────────────

private fun parseDocXml(xml: String, relsMap: Map<String, String>): List<DocBlock> {
    val blocks = mutableListOf<DocBlock>()
    try {
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
        val parser  = factory.newPullParser()
        parser.setInput(xml.reader())

        var inBody     = false
        var inPara     = false
        var inRun      = false
        var inRPr      = false   // run properties
        var curBold    = false
        var curItalic  = false
        var curFontSz  = 11f     // half-points in docx → divide by 2 for pt
        var paraStyle  = ""
        var paraRuns   = mutableListOf<DocRun>()
        var runText    = StringBuilder()

        fun flushRun() {
            val t = runText.toString()
            if (t.isNotEmpty()) {
                paraRuns.add(DocRun(t, curBold, curItalic, curFontSz))
                runText.clear()
            }
        }

        fun flushPara() {
            val heading = when {
                paraStyle.contains("Heading1", ignoreCase = true) -> 1
                paraStyle.contains("Heading2", ignoreCase = true) -> 2
                paraStyle.contains("Heading3", ignoreCase = true) -> 3
                paraStyle == "Title"                              -> 1
                else -> 0
            }
            blocks.add(DocBlock.Paragraph(paraRuns.toList(), heading))
            paraRuns.clear()
            paraStyle = ""
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            val localName = parser.name ?: ""

            when (event) {
                XmlPullParser.START_TAG -> {
                    when (localName) {
                        "body" -> inBody = true
                        "p"    -> if (inBody) { inPara = true; curBold = false; curItalic = false; curFontSz = 11f }
                        "r"    -> if (inPara) { inRun = true; curBold = false; curItalic = false }
                        "rPr"  -> inRPr = true
                        "pPr"  -> { /* paragraph properties */ }
                        "pStyle" -> if (inPara) paraStyle = parser.getAttributeValue(null, "w:val") ?: ""
                        "b"    -> if (inRPr && inRun) curBold   = true
                        "i"    -> if (inRPr && inRun) curItalic = true
                        "sz"   -> if (inRPr) {
                            val v = (parser.getAttributeValue(null, "w:val") ?: "").toFloatOrNull()
                            if (v != null) curFontSz = (v / 2f).coerceIn(7f, 72f)
                        }
                        "t"    -> { /* text follows */ }
                        "br"   -> {
                            val brType = parser.getAttributeValue(null, "w:type") ?: ""
                            if (brType == "page") {
                                flushRun(); flushPara(); blocks.add(DocBlock.PageBreak)
                            } else {
                                runText.append("\n")
                            }
                        }
                        "drawing", "pict" -> {
                            // Image: look for r:embed attribute in blip or imagedata
                        }
                        "blip" -> {
                            // a:blip r:embed="rIdX"
                            val rId = parser.getAttributeValue(
                                "http://schemas.openxmlformats.org/officeDocument/2006/relationships", "embed"
                            ) ?: parser.getAttributeValue(null, "r:embed") ?: ""
                            if (rId.isNotEmpty()) {
                                val imgName = relsMap[rId]
                                if (imgName != null) {
                                    flushRun()
                                    blocks.add(DocBlock.Paragraph(paraRuns.toList()))
                                    paraRuns.clear()
                                    blocks.add(DocBlock.ImageBlock(imgName))
                                }
                            }
                        }
                        "imagedata" -> {
                            val rId = parser.getAttributeValue(
                                "http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id"
                            ) ?: parser.getAttributeValue(null, "r:id") ?: ""
                            if (rId.isNotEmpty()) {
                                val imgName = relsMap[rId]
                                if (imgName != null) blocks.add(DocBlock.ImageBlock(imgName))
                            }
                        }
                    }
                }

                XmlPullParser.TEXT -> {
                    if (inRun && inPara && !inRPr) {
                        runText.append(parser.text)
                    }
                }

                XmlPullParser.END_TAG -> {
                    when (localName) {
                        "rPr"  -> inRPr  = false
                        "r"    -> { flushRun(); inRun = false; curBold = false; curItalic = false }
                        "p"    -> { flushRun(); if (inPara) flushPara(); inPara = false }
                        "body" -> inBody = false
                    }
                }
            }
            event = parser.next()
        }
    } catch (_: Exception) {}
    return blocks
}

// ── Share ─────────────────────────────────────────────────────────────────────

private fun shareDocxPdf(context: Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        context.startActivity(android.content.Intent.createChooser(
            android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share PDF"
        ))
    } catch (_: Exception) {}
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun docxFormatSize(kb: Long): String = when {
    kb >= 1024 -> "%.1f MB".format(kb / 1024f)
    else       -> "$kb KB"
}
