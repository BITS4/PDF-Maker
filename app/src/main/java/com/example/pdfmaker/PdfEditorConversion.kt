package com.example.pdfmaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.compose.ui.graphics.toArgb
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal fun renderPage(context: Context, uri: Uri, pageIndex: Int, widthPx: Int): Bitmap? {
    return try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                if (pageIndex !in 0 until renderer.pageCount) return null
                renderer.openPage(pageIndex).use { page ->
                    val height = (widthPx * page.height.toFloat() / page.width).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                        android.graphics.Canvas(bitmap).drawColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }
    } catch (_: Exception) {
        null
    }
}

internal fun pdfPageCount(context: Context, uri: Uri): Int = try {
    context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
        PdfRenderer(descriptor).use { it.pageCount }
    } ?: 0
} catch (_: Exception) {
    0
}

internal fun buildAnnotatedPdf(
    context: Context,
    sourceUri: Uri,
    annotations: List<PageAnnotations>,
    @Suppress("UNUSED_PARAMETER") density: Float,
    scaledDensity: Float,
    pageBoxW: Int,
    pageBoxH: Int,
    destName: String,
): File? = try {
    val width = context.resources.displayMetrics.widthPixels
    val count = pdfPageCount(context, sourceUri)
    val document = PdfDocument()
    try {
        for (index in 0 until count) {
            val base = renderPage(context, sourceUri, index, width) ?: continue
            val combined = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(combined).also { it.drawBitmap(base, 0f, 0f, null) }
            annotations.getOrNull(index)?.let { drawAnnotations(canvas, combined, it, scaledDensity, pageBoxW, pageBoxH) }
            val info = PdfDocument.PageInfo.Builder(combined.width, combined.height, index + 1).create()
            val page = document.startPage(info)
            page.canvas.drawBitmap(combined, 0f, 0f, null)
            document.finishPage(page)
            base.recycle()
            combined.recycle()
        }
        File(context.cacheDir, destName).also { output ->
        output.outputStream().use { document.writeTo(it) }
        }
    } finally {
        document.close()
    }
} catch (_: Exception) {
    null
}

private fun drawAnnotations(
    canvas: android.graphics.Canvas,
    bitmap: Bitmap,
    annotations: PageAnnotations,
    scaledDensity: Float,
    pageBoxWidth: Int,
    pageBoxHeight: Int,
) {
    val strokePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }
    annotations.strokes.forEach { stroke ->
        if (stroke.points.size < 2) return@forEach
        strokePaint.color = stroke.color.toArgb()
        strokePaint.strokeWidth = stroke.strokeWidth
        val path = android.graphics.Path().apply {
            moveTo(stroke.points.first().x, stroke.points.first().y)
            stroke.points.drop(1).forEach { lineTo(it.x, it.y) }
        }
        canvas.drawPath(path, strokePaint)
    }

    val scaleX = if (pageBoxWidth > 0) bitmap.width.toFloat() / pageBoxWidth else 1f
    val scaleY = if (pageBoxHeight > 0) bitmap.height.toFloat() / pageBoxHeight else 1f
    val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    annotations.texts.forEach { text ->
        val textSize = text.sizeSp * scaledDensity * scaleY
        textPaint.color = text.color.toArgb()
        textPaint.textSize = textSize
        canvas.drawText(text.text, text.x * scaleX, text.y * scaleY + textSize * 0.85f, textPaint)
    }
    annotations.signatures.forEach { signature ->
        val width = (signature.width * bitmap.width).toInt().coerceAtLeast(1)
        val height = (width.toFloat() / signature.bitmap.width * signature.bitmap.height).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(signature.bitmap, width, height, true)
        canvas.drawBitmap(scaled, signature.x * bitmap.width, signature.y * bitmap.height, null)
        if (scaled !== signature.bitmap) scaled.recycle()
    }
}

@Suppress("SpellCheckingInspection")
internal fun pdfToDocx(context: Context, uri: Uri, destName: String, onProgress: (Int) -> Unit): File? {
    return try {
    val width = context.resources.displayMetrics.widthPixels
    val count = pdfPageCount(context, uri)
    if (count == 0) return null
    val images = mutableListOf<Pair<String, ByteArray>>()
    val relationships = mutableListOf<String>()
    val body = StringBuilder()

    for (index in 0 until count) {
        onProgress((index + 1) * 85 / count)
        val bitmap = renderPage(context, uri, index, width) ?: continue
        val bytes = ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 88, stream)
            stream.toByteArray()
        }
        val image = "image${index + 1}.jpg"
        val relationshipId = "rId${200 + index}"
        images += "word/media/$image" to bytes
        relationships += """<Relationship Id="$relationshipId" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/$image"/>"""
        val imageWidth = 5_486_400L
        val imageHeight = (imageWidth * bitmap.height / bitmap.width.toFloat()).toLong()
        body.append("""<w:p><w:r><w:drawing><wp:inline><wp:extent cx="$imageWidth" cy="$imageHeight"/><wp:docPr id="${index + 1}" name="$image"/><a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:pic><pic:nvPicPr><pic:cNvPr id="${index + 1}" name="$image"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed="$relationshipId"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$imageWidth" cy="$imageHeight"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>""")
        if (index < count - 1) body.append("""<w:p><w:r><w:br w:type="page"/></w:r></w:p>""")
        bitmap.recycle()
    }

    val documentXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"><w:body>$body</w:body></w:document>"""
    val documentRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>${relationships.joinToString("")}</Relationships>"""
    val contentTypes = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="jpg" ContentType="image/jpeg"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/></Types>"""
    val rootRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>"""
    val styles = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:style w:type="paragraph" w:styleId="Normal"><w:name w:val="Normal"/></w:style></w:styles>"""
    File(context.cacheDir, destName).also { output ->
        ZipOutputStream(output.outputStream()).use { zip ->
            zip.addEntry("[Content_Types].xml", contentTypes)
            zip.addEntry("_rels/.rels", rootRels)
            zip.addEntry("word/document.xml", documentXml)
            zip.addEntry("word/styles.xml", styles)
            zip.addEntry("word/_rels/document.xml.rels", documentRels)
            images.forEach { (name, bytes) -> zip.addEntry(name, bytes) }
        }
        onProgress(100)
    }
    } catch (_: Exception) {
        null
    }
}

@Suppress("SpellCheckingInspection")
internal fun pdfToPptx(context: Context, uri: Uri, destName: String, onProgress: (Int) -> Unit): File? {
    return try {
    val width = context.resources.displayMetrics.widthPixels
    val count = pdfPageCount(context, uri)
    if (count == 0) return null
    val images = mutableListOf<Pair<String, ByteArray>>()
    val slides = mutableListOf<String>()
    val slideRelationships = mutableListOf<String>()

    for (index in 0 until count) {
        onProgress((index + 1) * 85 / count)
        val bitmap = renderPage(context, uri, index, width) ?: continue
        val bytes = ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 88, stream)
            stream.toByteArray()
        }
        val image = "image${index + 1}.jpg"
        images += "ppt/media/$image" to bytes
        val slideWidth = 9_144_000L
        val slideHeight = 6_858_000L
        val imageHeight = (slideWidth * bitmap.height / bitmap.width.toFloat()).toLong()
        val imageY = ((slideHeight - imageHeight) / 2).coerceAtLeast(0)
        slides += """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><p:cSld><p:spTree><p:pic><p:nvPicPr><p:cNvPr id="${index + 2}" name="$image"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr><p:blipFill><a:blip r:embed="rId1"/><a:stretch><a:fillRect/></a:stretch></p:blipFill><p:spPr><a:xfrm><a:off x="0" y="$imageY"/><a:ext cx="$slideWidth" cy="$imageHeight"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr></p:pic></p:spTree></p:cSld></p:sld>"""
        slideRelationships += """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="../media/$image"/></Relationships>"""
        bitmap.recycle()
    }

    val slideIds = slides.indices.joinToString("") { """<p:sldId id="${256 + it}" r:id="rId${10 + it}"/>""" }
    val slideRels = slides.indices.joinToString("") { """<Relationship Id="rId${10 + it}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide${it + 1}.xml"/>""" }
    val presentation = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><p:sldMasterIdLst/><p:sldSz cx="9144000" cy="6858000"/><p:sldIdLst>$slideIds</p:sldIdLst></p:presentation>"""
    val contentTypes = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="jpg" ContentType="image/jpeg"/><Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>""")
        slides.indices.forEach { append("""<Override PartName="/ppt/slides/slide${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>""") }
        append("</Types>")
    }
    val rootRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/></Relationships>"""
    val presentationRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">$slideRels</Relationships>"""
    File(context.cacheDir, destName).also { output ->
        ZipOutputStream(output.outputStream()).use { zip ->
            zip.addEntry("[Content_Types].xml", contentTypes)
            zip.addEntry("_rels/.rels", rootRels)
            zip.addEntry("ppt/presentation.xml", presentation)
            zip.addEntry("ppt/_rels/presentation.xml.rels", presentationRels)
            slides.forEachIndexed { index, slide ->
                zip.addEntry("ppt/slides/slide${index + 1}.xml", slide)
                zip.addEntry("ppt/slides/_rels/slide${index + 1}.xml.rels", slideRelationships[index])
            }
            images.forEach { (name, bytes) -> zip.addEntry(name, bytes) }
        }
        onProgress(100)
    }
    } catch (_: Exception) {
        null
    }
}

private fun ZipOutputStream.addEntry(name: String, contents: String) =
    addEntry(name, contents.toByteArray())

private fun ZipOutputStream.addEntry(name: String, contents: ByteArray) {
    putNextEntry(ZipEntry(name))
    write(contents)
    closeEntry()
}
