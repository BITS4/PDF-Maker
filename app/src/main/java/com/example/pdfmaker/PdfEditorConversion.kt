package com.example.pdfmaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.util.Log
import androidx.compose.ui.graphics.toArgb
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val PDF_EDITOR_LOG_TAG = "PdfEditor"

internal fun renderPage(
    context: Context,
    uri: Uri,
    pageIndex: Int,
    widthPx: Int,
): Bitmap? =
    try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                if (pageIndex !in 0 until renderer.pageCount) {
                    null
                } else {
                    renderer.openPage(pageIndex).use { page ->
                        val height =
                            (widthPx * page.height.toFloat() / page.width.coerceAtLeast(1))
                                .toInt()
                                .coerceAtLeast(1)
                        Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                            Canvas(bitmap).drawColor(android.graphics.Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
            }
        }
    } catch (ignoredError: Exception) {
        Log.w(PDF_EDITOR_LOG_TAG, "Unable to render PDF page $pageIndex", ignoredError)
        null
    }

internal fun pdfPageCount(
    context: Context,
    uri: Uri,
): Int =
    try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            PdfRenderer(descriptor).use { it.pageCount }
        } ?: 0
    } catch (ignoredError: Exception) {
        Log.w(PDF_EDITOR_LOG_TAG, "Unable to read PDF page count", ignoredError)
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
): File? =
    try {
        val width = context.resources.displayMetrics.widthPixels
        val count = pdfPageCount(context, sourceUri)
        val document = PdfDocument()
        try {
            for (index in 0 until count) {
                val base = renderPage(context, sourceUri, index, width) ?: continue
                val combined = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(combined).also { it.drawBitmap(base, 0f, 0f, null) }
                annotations.getOrNull(index)?.let {
                    drawAnnotations(canvas, combined, it, scaledDensity, pageBoxW, pageBoxH)
                }
                val info = PdfDocument.PageInfo.Builder(combined.width, combined.height, index + 1).create()
                val page = document.startPage(info)
                page.canvas.drawBitmap(combined, 0f, 0f, null)
                document.finishPage(page)
                base.recycle()
                combined.recycle()
            }
            pdfMakerCacheFile(context, destName).also { output ->
                output.outputStream().use { document.writeTo(it) }
            }
        } finally {
            document.close()
        }
    } catch (ignoredError: Exception) {
        Log.w(PDF_EDITOR_LOG_TAG, "Unable to export annotated PDF", ignoredError)
        null
    }

private fun drawAnnotations(
    canvas: Canvas,
    bitmap: Bitmap,
    annotations: PageAnnotations,
    scaledDensity: Float,
    pageBoxWidth: Int,
    pageBoxHeight: Int,
) {
    val strokePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    annotations.strokes.forEach { stroke ->
        if (stroke.points.size < 2) return@forEach
        strokePaint.color = stroke.color.toArgb()
        strokePaint.strokeWidth = stroke.strokeWidth
        val path =
            Path().apply {
                moveTo(stroke.points.first().x, stroke.points.first().y)
                stroke.points.drop(1).forEach { lineTo(it.x, it.y) }
            }
        canvas.drawPath(path, strokePaint)
    }

    val scaleX = if (pageBoxWidth > 0) bitmap.width.toFloat() / pageBoxWidth else 1f
    val scaleY = if (pageBoxHeight > 0) bitmap.height.toFloat() / pageBoxHeight else 1f
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    annotations.texts.forEach { text ->
        val textSize = text.sizeSp * scaledDensity * scaleY
        textPaint.color = text.color.toArgb()
        textPaint.textSize = textSize
        canvas.drawText(text.text, text.x * scaleX, text.y * scaleY + textSize * 0.85f, textPaint)
    }
    annotations.signatures.forEach { signature ->
        val width = (signature.width * bitmap.width).toInt().coerceAtLeast(1)
        val sourceWidth = signature.bitmap.width.coerceAtLeast(1)
        val height = (width.toFloat() / sourceWidth * signature.bitmap.height).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(signature.bitmap, width, height, true)
        canvas.drawBitmap(scaled, signature.x * bitmap.width, signature.y * bitmap.height, null)
        if (scaled !== signature.bitmap) scaled.recycle()
    }
}

@Suppress("SpellCheckingInspection")
internal fun pdfToDocx(
    context: Context,
    uri: Uri,
    destName: String,
    onProgress: (Int) -> Unit,
): File? =
    try {
        val width = context.resources.displayMetrics.widthPixels
        val count = pdfPageCount(context, uri)
        check(count > 0) { "PDF has no renderable pages" }
        val images = mutableListOf<Pair<String, ByteArray>>()
        val relationships = mutableListOf<String>()
        val body = StringBuilder()

        for (index in 0 until count) {
            onProgress((index + 1) * 85 / count)
            val bitmap = renderPage(context, uri, index, width) ?: continue
            val bytes = bitmap.toJpegBytes()
            val image = "image${index + 1}.jpg"
            val relationshipId = "rId${200 + index}"
            images += "word/media/$image" to bytes
            relationships += docxImageRelationshipXml(relationshipId, image)
            body.append(docxPictureParagraphXml(index, image, relationshipId, bitmap.width, bitmap.height))
            if (index < count - 1) body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
            bitmap.recycle()
        }

        val parts = buildDocxPackageXml(body.toString(), relationships)
        pdfMakerCacheFile(context, destName).also { output ->
            ZipOutputStream(output.outputStream()).use { zip ->
                zip.addEntry("[Content_Types].xml", parts.contentTypes)
                zip.addEntry("_rels/.rels", parts.rootRelationships)
                zip.addEntry("word/document.xml", parts.document)
                zip.addEntry("word/styles.xml", parts.styles)
                zip.addEntry("word/_rels/document.xml.rels", parts.documentRelationships)
                images.forEach { (name, bytes) -> zip.addEntry(name, bytes) }
            }
            onProgress(100)
        }
    } catch (ignoredError: Exception) {
        Log.w(PDF_EDITOR_LOG_TAG, "Unable to convert PDF to Word", ignoredError)
        null
    }

@Suppress("SpellCheckingInspection")
internal fun pdfToPptx(
    context: Context,
    uri: Uri,
    destName: String,
    onProgress: (Int) -> Unit,
): File? =
    try {
        val width = context.resources.displayMetrics.widthPixels
        val count = pdfPageCount(context, uri)
        check(count > 0) { "PDF has no renderable pages" }
        val images = mutableListOf<Pair<String, ByteArray>>()
        val slides = mutableListOf<String>()
        val slideRelationships = mutableListOf<String>()

        for (index in 0 until count) {
            onProgress((index + 1) * 85 / count)
            val bitmap = renderPage(context, uri, index, width) ?: continue
            val image = "image${index + 1}.jpg"
            images += "ppt/media/$image" to bitmap.toJpegBytes()
            slides += pptxPictureSlideXml(index, image, bitmap.width, bitmap.height)
            slideRelationships += pptxImageRelationshipXml(image)
            bitmap.recycle()
        }

        val parts = buildPptxPackageXml(slides.size)
        pdfMakerCacheFile(context, destName).also { output ->
            ZipOutputStream(output.outputStream()).use { zip ->
                zip.addEntry("[Content_Types].xml", parts.contentTypes)
                zip.addEntry("_rels/.rels", parts.rootRelationships)
                zip.addEntry("ppt/presentation.xml", parts.presentation)
                zip.addEntry("ppt/_rels/presentation.xml.rels", parts.presentationRelationships)
                slides.forEachIndexed { index, slide ->
                    zip.addEntry("ppt/slides/slide${index + 1}.xml", slide)
                    zip.addEntry("ppt/slides/_rels/slide${index + 1}.xml.rels", slideRelationships[index])
                }
                images.forEach { (name, bytes) -> zip.addEntry(name, bytes) }
            }
            onProgress(100)
        }
    } catch (ignoredError: Exception) {
        Log.w(PDF_EDITOR_LOG_TAG, "Unable to convert PDF to PowerPoint", ignoredError)
        null
    }

private fun Bitmap.toJpegBytes(): ByteArray =
    ByteArrayOutputStream().use { stream ->
        compress(Bitmap.CompressFormat.JPEG, 88, stream)
        stream.toByteArray()
    }

private fun ZipOutputStream.addEntry(
    name: String,
    contents: String,
) = addEntry(name, contents.toByteArray())

private fun ZipOutputStream.addEntry(
    name: String,
    contents: ByteArray,
) {
    putNextEntry(ZipEntry(name))
    write(contents)
    closeEntry()
}
