package com.example.pdfmaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

internal suspend fun runOcr(
    context: Context,
    uri: Uri,
    onProgress: (Int, Int) -> Unit,
    onDone: (List<Pair<Int, String>>) -> Unit,
    onError: (String) -> Unit,
) {
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    val results = mutableListOf<Pair<Int, String>>()
    try {
        val imported = withContext(Dispatchers.IO) {
            SafeDocumentImporter.import(
                context,
                IncomingDocumentRequest(uri, context.contentResolver.getType(uri)),
            )
        }
        val document = when (imported) {
            is IncomingImportResult.Imported -> imported
            is IncomingImportResult.Rejected -> error(imported.message)
        }
        try {
            if (document.kind == IncomingDocumentKind.PDF) {
                val descriptor = ParcelFileDescriptor.open(document.file, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = try {
                    PdfRenderer(descriptor)
                } catch (error: Exception) {
                    descriptor.close()
                    throw error
                }
                try {
                    val total = renderer.pageCount
                    require(total in 1..500) { "PDF has an unsafe page count" }
                    withContext(Dispatchers.Main) { onProgress(0, total) }
                    for (index in 0 until total) {
                        recognizePdfPage(renderer, index, recognizer, results)
                        withContext(Dispatchers.Main) { onProgress(index + 1, total) }
                    }
                } finally {
                    renderer.close()
                }
            } else {
                require(document.kind != IncomingDocumentKind.DOCX) { "Choose a PDF or image for OCR" }
                withContext(Dispatchers.Main) { onProgress(0, 1) }
                val bitmap = withContext(Dispatchers.IO) { decodeBoundedOcrImage(document.file) }
                    ?: error("The image could not be decoded safely")
                try {
                    val image = InputImage.fromBitmap(bitmap, 0)
                    val recognized = withContext(Dispatchers.IO) { recognizer.process(image).await() }
                    results.add(1 to recognized.text.trim())
                } finally {
                    bitmap.recycle()
                }
                withContext(Dispatchers.Main) { onProgress(1, 1) }
            }
        } finally {
            document.file.delete()
        }
        withContext(Dispatchers.Main) { onDone(results) }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        val message = when (error) {
            is IllegalArgumentException, is IllegalStateException -> error.message ?: "OCR failed"
            else -> "OCR failed because the input could not be processed safely"
        }
        withContext(Dispatchers.Main) { onError(message) }
    } finally {
        recognizer.close()
    }
}

private suspend fun recognizePdfPage(
    renderer: PdfRenderer,
    index: Int,
    recognizer: com.google.mlkit.vision.text.TextRecognizer,
    results: MutableList<Pair<Int, String>>,
) {
    val page = renderer.openPage(index)
    try {
        val size = RenderSizing.fitWithin(page.width, page.height, 1_600, allowUpscale = true)
            ?: error("PDF page has invalid dimensions")
        val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).drawColor(Color.WHITE)
            val matrix = android.graphics.Matrix().apply {
                setScale(
                    size.width.toFloat() / page.width.toFloat(),
                    size.height.toFloat() / page.height.toFloat(),
                )
            }
            page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val image = InputImage.fromBitmap(bitmap, 0)
            val recognized = withContext(Dispatchers.IO) { recognizer.process(image).await() }
            results.add(index + 1 to recognized.text.trim())
        } finally {
            bitmap.recycle()
        }
    } finally {
        page.close()
    }
}

private fun decodeBoundedOcrImage(file: File): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    val target = RenderSizing.fitWithin(bounds.outWidth, bounds.outHeight, 2_000) ?: return null
    var sampleSize = 1
    while (bounds.outWidth / sampleSize > target.width * 2 || bounds.outHeight / sampleSize > target.height * 2) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return BitmapFactory.decodeFile(file.absolutePath, options)
}
