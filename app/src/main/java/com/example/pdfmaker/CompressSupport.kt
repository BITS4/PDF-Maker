package com.example.pdfmaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.CoroutineContext

internal suspend fun compressPdf(
    context: Context,
    uri: Uri,
    level: CompressLevel,
    baseName: String,
    onProgress: (Int) -> Unit,
): File {
    val operationContext = currentCoroutineContext()
    return withSafePdfRenderer(context, uri) { renderer ->
        val pageCount = CompressionPolicy.requirePageCount(renderer.pageCount)
        val outputDocument = PdfDocument()
        try {
            repeat(pageCount) { pageIndex ->
                operationContext.ensureActive()
                onProgress(CompressionPolicy.pageProgress(pageIndex, pageCount))
                appendCompressedPage(
                    renderer = renderer,
                    outputDocument = outputDocument,
                    pageIndex = pageIndex,
                    level = level,
                    operationContext = operationContext,
                )
                operationContext.ensureActive()
            }

            onProgress(95)
            OutputStore.writeUnique(
                directory = getPdfMakerDir(context),
                requestedBaseName = "compressed_${baseName}_${level.name.lowercase()}",
                extension = "pdf",
                beforeCommit = { operationContext.ensureActive() },
            ) { output ->
                operationContext.ensureActive()
                outputDocument.writeTo(
                    BoundedIo.limit(
                        output = output,
                        maximumBytes = CompressionPolicy.MAX_OUTPUT_BYTES,
                        beforeWrite = { operationContext.ensureActive() },
                    ),
                )
            }
        } finally {
            outputDocument.close()
        }
    }.also { onProgress(100) }
}

private fun appendCompressedPage(
    renderer: PdfRenderer,
    outputDocument: PdfDocument,
    pageIndex: Int,
    level: CompressLevel,
    operationContext: CoroutineContext,
) {
    renderer.openPage(pageIndex).use { sourcePage ->
        val target =
            CompressionPolicy.renderSize(
                pageWidth = sourcePage.width,
                pageHeight = sourcePage.height,
                maximumDimension = level.maxDimensionPx,
            )
        val sourceBitmap = createBitmap(target.width, target.height)
        try {
            Canvas(sourceBitmap).drawColor(Color.WHITE)
            sourcePage.render(
                sourceBitmap,
                null,
                Matrix().apply {
                    setScale(
                        target.width.toFloat() / sourcePage.width.toFloat(),
                        target.height.toFloat() / sourcePage.height.toFloat(),
                    )
                },
                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
            )
            appendEncodedBitmap(
                outputDocument = outputDocument,
                sourceBitmap = sourceBitmap,
                target = target,
                pageNumber = pageIndex + 1,
                jpegQuality = level.jpegQuality,
                operationContext = operationContext,
            )
        } finally {
            sourceBitmap.recycle()
        }
    }
}

private fun appendEncodedBitmap(
    outputDocument: PdfDocument,
    sourceBitmap: Bitmap,
    target: PixelSize,
    pageNumber: Int,
    jpegQuality: Int,
    operationContext: CoroutineContext,
) {
    operationContext.ensureActive()
    val jpegBytes =
        ByteArrayOutputStream().use { encoded ->
            check(
                sourceBitmap.compress(
                    Bitmap.CompressFormat.JPEG,
                    jpegQuality,
                    BoundedIo.limit(
                        output = encoded,
                        maximumBytes = CompressionPolicy.MAX_ENCODED_PAGE_BYTES,
                        beforeWrite = { operationContext.ensureActive() },
                    ),
                ),
            ) { "Could not encode compressed PDF page" }
            encoded.toByteArray()
        }
    operationContext.ensureActive()
    val jpegBitmap =
        requireNotNull(BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)) {
            "Could not decode compressed PDF page"
        }
    try {
        val pageInfo = PdfDocument.PageInfo.Builder(target.width, target.height, pageNumber).create()
        val outputPage = outputDocument.startPage(pageInfo)
        try {
            outputPage.canvas.drawBitmap(jpegBitmap, 0f, 0f, null)
        } finally {
            outputDocument.finishPage(outputPage)
        }
    } finally {
        jpegBitmap.recycle()
    }
}
