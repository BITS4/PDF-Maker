package com.example.pdfmaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.ui.graphics.toArgb
import java.io.FilterOutputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private const val PDF_EDITOR_LOG_TAG = "PdfEditor"

@Suppress("TooGenericExceptionCaught")
internal fun renderPage(
    context: Context,
    uri: Uri,
    pageIndex: Int,
    widthPx: Int,
): Bitmap? =
    try {
        renderPageOrThrow(context, uri, pageIndex, widthPx)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (ignoredError: Exception) {
        Log.w(PDF_EDITOR_LOG_TAG, "Unable to render PDF page $pageIndex", ignoredError)
        null
    }

@Suppress("TooGenericExceptionCaught")
private fun renderPageOrThrow(
    context: Context,
    uri: Uri,
    pageIndex: Int,
    widthPx: Int,
): Bitmap {
    val descriptor = openPdfDescriptor(context, uri)
    return descriptor.use {
        PdfRenderer(it).use { renderer ->
            require(pageIndex in 0 until renderer.pageCount) { "PDF page is outside the document" }
            renderer.openPage(pageIndex).use { page ->
                val target = PdfEditorRenderPolicy.targetSize(page.width, page.height, widthPx)
                    ?: error("PDF page has invalid dimensions")
                renderScaledPdfPage(page, target)
            }
        }
    }
}

private fun renderScaledPdfPage(page: PdfRenderer.Page, target: PixelSize): Bitmap {
    val bitmap = Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
    var completed = false
    try {
        Canvas(bitmap).drawColor(android.graphics.Color.WHITE)
        val renderScale = requireNotNull(
            RenderSizing.scaleTo(page.width, page.height, target),
        ) { "PDF page has invalid render dimensions" }
        val transform = Matrix().apply {
            setScale(renderScale.scaleX, renderScale.scaleY)
        }
        page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        completed = true
        return bitmap
    } finally {
        if (!completed) bitmap.recycle()
    }
}

@Suppress("TooGenericExceptionCaught")
internal fun pdfPageCount(
    context: Context,
    uri: Uri,
): Int =
    try {
        requirePdfPageCount(context, uri)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (ignoredError: Exception) {
        Log.w(PDF_EDITOR_LOG_TAG, "Unable to read PDF page count", ignoredError)
        0
    }

private fun requirePdfPageCount(context: Context, uri: Uri): Int {
    val descriptor = openPdfDescriptor(context, uri)
    return descriptor.use { PdfRenderer(it).use(PdfRenderer::getPageCount) }
}

private fun openPdfDescriptor(context: Context, uri: Uri): ParcelFileDescriptor =
    when (uri.scheme) {
        "content" -> {
            require(!uri.authority.isNullOrBlank()) { "The PDF provider is invalid" }
            context.contentResolver.openFileDescriptor(uri, "r")
                ?: error("The PDF provider returned no data")
        }

        "file" -> {
            val source = File(requireNotNull(uri.path) { "The PDF path is missing" }).canonicalFile
            require(source.isFile && source.toPath().startsWith(context.cacheDir.canonicalFile.toPath())) {
                "Only app-cached PDF files can be opened"
            }
            ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        else -> throw IllegalArgumentException("Only content-provider or app-cached PDFs can be opened")
    }

internal suspend fun buildAnnotatedPdf(
    context: Context,
    sourceUri: Uri,
    annotations: List<PageAnnotations>,
    @Suppress("UNUSED_PARAMETER") density: Float,
    scaledDensity: Float,
    pageBoxW: Int,
    pageBoxH: Int,
    destName: String,
    onProgress: (Int) -> Unit,
): File {
    val width = context.resources.displayMetrics.widthPixels
    val count = PdfEditorRenderPolicy.requirePageCount(requirePdfPageCount(context, sourceUri))
    val document = PdfDocument()
    try {
        for (index in 0 until count) {
            currentCoroutineContext().ensureActive()
            onProgress((index + 1) * 90 / count)
            val base = renderPageOrThrow(context, sourceUri, index, width)
            try {
                document.appendAnnotatedPage(
                    pageIndex = index,
                    base = base,
                    annotations = annotations.getOrNull(index),
                    scaledDensity = scaledDensity,
                    pageBoxWidth = pageBoxW,
                    pageBoxHeight = pageBoxH,
                )
            } finally {
                base.recycle()
            }
        }
        currentCoroutineContext().ensureActive()
        return OutputStore.writeUnique(
            directory = pdfMakerCacheDirectory(context),
            requestedBaseName = destName.substringBeforeLast('.'),
            extension = "pdf",
        ) { output ->
            document.writeTo(BoundedIo.limit(output, PdfEditorRenderPolicy.MAX_PACKAGE_BYTES))
        }.also { onProgress(100) }
    } finally {
        document.close()
    }
}

private fun PdfDocument.appendAnnotatedPage(
    pageIndex: Int,
    base: Bitmap,
    annotations: PageAnnotations?,
    scaledDensity: Float,
    pageBoxWidth: Int,
    pageBoxHeight: Int,
) {
    val combined = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
    try {
        val canvas = Canvas(combined).also { it.drawBitmap(base, 0f, 0f, null) }
        annotations?.let {
            drawAnnotations(canvas, combined, it, scaledDensity, pageBoxWidth, pageBoxHeight)
        }
        val info = PdfDocument.PageInfo.Builder(combined.width, combined.height, pageIndex + 1).create()
        val page = startPage(info)
        page.canvas.drawBitmap(combined, 0f, 0f, null)
        finishPage(page)
    } finally {
        combined.recycle()
    }
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
internal suspend fun pdfToDocx(
    context: Context,
    uri: Uri,
    destName: String,
    onProgress: (Int) -> Unit,
): File {
    val operationContext = currentCoroutineContext()
    val width = context.resources.displayMetrics.widthPixels
    val count = PdfEditorRenderPolicy.requirePageCount(requirePdfPageCount(context, uri))
    val relationships = mutableListOf<String>()
    val body = StringBuilder()

    return OutputStore.writeUnique(
        directory = pdfMakerCacheDirectory(context),
        requestedBaseName = destName.substringBeforeLast('.'),
        extension = "docx",
    ) { output ->
        boundedZip(output).use { zip ->
            for (index in 0 until count) {
                operationContext.ensureActive()
                onProgress((index + 1) * 85 / count)
                val bitmap = renderPageOrThrow(context, uri, index, width)
                try {
                    val image = "image${index + 1}.jpg"
                    val relationshipId = "rId${200 + index}"
                    zip.addJpegEntry("word/media/$image", bitmap)
                    relationships += docxImageRelationshipXml(relationshipId, image)
                    body.append(docxPictureParagraphXml(index, image, relationshipId, bitmap.width, bitmap.height))
                    if (index < count - 1) body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
                } finally {
                    bitmap.recycle()
                }
            }

            operationContext.ensureActive()
            val parts = buildDocxPackageXml(body.toString(), relationships)
            zip.addEntry("[Content_Types].xml", parts.contentTypes)
            zip.addEntry("_rels/.rels", parts.rootRelationships)
            zip.addEntry("word/document.xml", parts.document)
            zip.addEntry("word/styles.xml", parts.styles)
            zip.addEntry("word/_rels/document.xml.rels", parts.documentRelationships)
        }
    }.also { onProgress(100) }
}

@Suppress("SpellCheckingInspection")
internal suspend fun pdfToPptx(
    context: Context,
    uri: Uri,
    destName: String,
    onProgress: (Int) -> Unit,
): File {
    val operationContext = currentCoroutineContext()
    val width = context.resources.displayMetrics.widthPixels
    val count = PdfEditorRenderPolicy.requirePageCount(requirePdfPageCount(context, uri))
    val slides = mutableListOf<String>()
    val slideRelationships = mutableListOf<String>()

    return OutputStore.writeUnique(
        directory = pdfMakerCacheDirectory(context),
        requestedBaseName = destName.substringBeforeLast('.'),
        extension = "pptx",
    ) { output ->
        boundedZip(output).use { zip ->
            for (index in 0 until count) {
                operationContext.ensureActive()
                onProgress((index + 1) * 85 / count)
                val bitmap = renderPageOrThrow(context, uri, index, width)
                try {
                    val image = "image${index + 1}.jpg"
                    zip.addJpegEntry("ppt/media/$image", bitmap)
                    slides += pptxPictureSlideXml(index, image, bitmap.width, bitmap.height)
                    slideRelationships += pptxImageRelationshipXml(image)
                } finally {
                    bitmap.recycle()
                }
            }

            operationContext.ensureActive()
            val parts = buildPptxPackageXml(slides.size)
            zip.addEntry("[Content_Types].xml", parts.contentTypes)
            zip.addEntry("_rels/.rels", parts.rootRelationships)
            zip.addEntry("ppt/presentation.xml", parts.presentation)
            zip.addEntry("ppt/_rels/presentation.xml.rels", parts.presentationRelationships)
            slides.forEachIndexed { index, slide ->
                zip.addEntry("ppt/slides/slide${index + 1}.xml", slide)
                zip.addEntry("ppt/slides/_rels/slide${index + 1}.xml.rels", slideRelationships[index])
            }
        }
    }.also { onProgress(100) }
}

private fun boundedZip(output: OutputStream): ZipOutputStream =
    ZipOutputStream(
        BoundedIo.limit(CloseShieldOutputStream(output), PdfEditorRenderPolicy.MAX_PACKAGE_BYTES),
    )

private class CloseShieldOutputStream(output: OutputStream) : FilterOutputStream(output) {
    override fun close() {
        flush()
    }
}

private fun ZipOutputStream.addJpegEntry(name: String, bitmap: Bitmap) {
    putNextEntry(ZipEntry(name))
    try {
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, this)) { "Could not encode PDF page" }
    } finally {
        closeEntry()
    }
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
