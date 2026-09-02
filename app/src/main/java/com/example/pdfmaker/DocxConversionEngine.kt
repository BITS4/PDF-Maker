package com.example.pdfmaker

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color as AColor
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import java.io.File
import java.util.zip.ZipInputStream
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

// ── Core conversion: docx → PDF ───────────────────────────────────────────────

internal fun docxToPdf(
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

internal fun shareDocxPdf(context: Context, file: File) {
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

internal fun docxFormatSize(kb: Long): String = when {
    kb >= 1024 -> "%.1f MB".format(kb / 1024f)
    else       -> "$kb KB"
}

