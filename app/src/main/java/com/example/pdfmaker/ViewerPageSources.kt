package com.example.pdfmaker

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.text.TextPaint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

internal fun pageStreamForFile(
    file: PdfFile,
    kind: ViewerFileKind,
    targetWidth: Int,
): Flow<Bitmap> =
    flow {
        val source = File(file.filePath)
        require(source.isFile) { "Document is unavailable" }

        when (kind) {
            ViewerFileKind.PDF -> emitPdfPages(source, targetWidth)
            ViewerFileKind.IMAGE -> emitImagePage(source, targetWidth)
            ViewerFileKind.TXT -> emitTextPages(source.readText(), targetWidth)
            ViewerFileKind.CSV -> emitCsvPages(source, targetWidth)
            ViewerFileKind.DOCX -> emitDocxPages(source, targetWidth)
            ViewerFileKind.XLSX -> emitXlsxPages(source, targetWidth)
            ViewerFileKind.PPTX -> emitPptxPages(source, targetWidth)
            ViewerFileKind.UNSUPPORTED -> Unit
        }
    }.flowOn(Dispatchers.IO)

private suspend fun FlowCollector<Bitmap>.emitPdfPages(
    file: File,
    width: Int,
) {
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            for (index in 0 until renderer.pageCount) {
                renderer.openPage(index).use { page ->
                    val scale = width.toFloat() / page.width.coerceAtLeast(1)
                    val height = (page.height * scale).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    Canvas(bitmap).drawColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    emit(bitmap)
                }
            }
        }
    }
}

private suspend fun FlowCollector<Bitmap>.emitImagePage(
    file: File,
    width: Int,
) {
    val source = requireNotNull(BitmapFactory.decodeFile(file.absolutePath)) { "Image could not be decoded" }
    val height = (width.toFloat() / source.width.coerceAtLeast(1) * source.height).toInt().coerceAtLeast(1)
    val bitmap = Bitmap.createScaledBitmap(source, width, height, true)
    if (bitmap !== source) source.recycle()
    emit(bitmap)
}

private suspend fun FlowCollector<Bitmap>.emitTextPages(
    text: String,
    width: Int,
) {
    val pageHeight = (width * 1.414f).toInt()
    val margin = (width * 0.08f).toInt()
    val contentWidth = width - margin * 2
    val paint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = width * 0.022f
        }
    val layout = buildViewerStaticLayout(text, paint, contentWidth)
    var firstLine = 0

    while (firstLine < layout.lineCount) {
        val contentHeight = pageHeight - margin * 2
        var lastLine = firstLine
        while (
            lastLine + 1 < layout.lineCount &&
            layout.getLineBottom(lastLine + 1) - layout.getLineTop(firstLine) <= contentHeight
        ) {
            lastLine += 1
        }
        val bitmap = Bitmap.createBitmap(width, pageHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.save()
        canvas.clipRect(margin, margin, width - margin, pageHeight - margin)
        canvas.translate(margin.toFloat(), margin.toFloat() - layout.getLineTop(firstLine))
        layout.draw(canvas)
        canvas.restore()
        emit(bitmap)
        firstLine = lastLine + 1
    }
}

private suspend fun FlowCollector<Bitmap>.emitCsvPages(
    file: File,
    width: Int,
) {
    val rows = parseDelimitedRows(file.readText(), delimiterForFileName(file.name))
    if (rows.isEmpty()) return
    emitViewerTablePages(rows, rows.maxOf { it.size }.coerceAtLeast(1), width, hasHeader = true)
}

private suspend fun FlowCollector<Bitmap>.emitXlsxPages(
    file: File,
    width: Int,
) {
    var sharedStringsXml: String? = null
    var firstSheetXml: String? = null
    ZipInputStream(file.inputStream().buffered()).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            when (entry.name) {
                "xl/sharedStrings.xml" -> sharedStringsXml = zip.readViewerXml()
                "xl/worksheets/sheet1.xml" -> firstSheetXml = zip.readViewerXml()
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
    }
    val strings = sharedStringsXml?.let(::parseViewerSharedStrings).orEmpty()
    val rows = firstSheetXml?.let { parseViewerSheet(it, strings) }.orEmpty()
    if (rows.isEmpty()) return
    emitViewerTablePages(rows, rows.maxOf { it.size }.coerceAtLeast(1), width, hasHeader = true)
}

internal fun ZipInputStream.readViewerXml(): String = String(readBoundedViewerEntry(this, MAX_VIEWER_XML_BYTES), StandardCharsets.UTF_8)
