package com.example.pdfmaker

import android.graphics.Bitmap
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
import java.util.zip.ZipInputStream

internal fun pageStreamForFile(
    file: PdfFile,
    kind: ViewerFileKind,
    targetWidth: Int,
): Flow<Bitmap> =
    flow {
        val source = File(file.filePath)
        require(source.isFile) { "Document is unavailable" }
        require(source.length() in 0..ViewerResourceLimits.MAX_SOURCE_BYTES) {
            "Document exceeds the viewer safety limit"
        }
        val safeWidth = viewerRenderWidth(targetWidth)

        when (kind) {
            ViewerFileKind.PDF -> emitPdfPages(source, safeWidth)
            ViewerFileKind.IMAGE -> emitImagePage(source, safeWidth)
            ViewerFileKind.TXT -> emitTextPages(readBoundedViewerText(source), safeWidth)
            ViewerFileKind.CSV -> emitCsvPages(source, safeWidth)
            ViewerFileKind.DOCX -> emitDocxPages(source, safeWidth)
            ViewerFileKind.XLSX -> emitXlsxPages(source, safeWidth)
            ViewerFileKind.PPTX -> emitPptxPages(source, safeWidth)
            ViewerFileKind.UNSUPPORTED -> Unit
        }
    }.flowOn(Dispatchers.IO)

private suspend fun FlowCollector<Bitmap>.emitPdfPages(
    file: File,
    width: Int,
) {
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            for (index in 0 until minOf(renderer.pageCount, ViewerResourceLimits.MAX_RENDERED_PAGES)) {
                renderer.openPage(index).use pageUse@{ page ->
                    val target = RenderSizing.fitWithin(
                        page.width,
                        page.height,
                        width.coerceIn(1, 2_048),
                        allowUpscale = true,
                    ) ?: return@pageUse
                    val bitmap = Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
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
    val source = file.inputStream().use {
        ThumbnailInput.decodeImage(it, width.coerceIn(1, 2_048))
    } ?: error("Image could not be decoded safely")
    val target = RenderSizing.fitWithin(
        source.width,
        source.height,
        width.coerceIn(1, 2_048),
        allowUpscale = true,
    ) ?: error("Image dimensions are invalid")
    val bitmap = Bitmap.createScaledBitmap(source, target.width, target.height, true)
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

    var emittedPages = 0
    while (firstLine < layout.lineCount && emittedPages < ViewerResourceLimits.MAX_RENDERED_PAGES) {
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
        emittedPages += 1
        firstLine = lastLine + 1
    }
}

private suspend fun FlowCollector<Bitmap>.emitCsvPages(
    file: File,
    width: Int,
) {
    val rows = parseDelimitedRows(readBoundedViewerText(file), delimiterForFileName(file.name))
    if (rows.isEmpty()) return
    emitViewerTablePages(rows, rows.maxOf { it.size }.coerceAtLeast(1), width, hasHeader = true)
}

private suspend fun FlowCollector<Bitmap>.emitXlsxPages(
    file: File,
    width: Int,
) {
    var sharedStringsXml: String? = null
    var firstSheetXml: String? = null
    val budget = ViewerArchiveBudget()
    ZipInputStream(file.inputStream().buffered()).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            budget.beginEntry(entry.name)
            when (entry.name) {
                "xl/sharedStrings.xml" -> sharedStringsXml = budget.readXml(zip)
                "xl/worksheets/sheet1.xml" -> firstSheetXml = budget.readXml(zip)
                else -> budget.skipEntry(zip)
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
