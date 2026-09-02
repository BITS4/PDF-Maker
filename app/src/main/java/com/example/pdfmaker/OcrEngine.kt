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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    var recognizedCharacters = 0
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
        currentCoroutineContext().ensureActive()
        try {
            if (document.kind == IncomingDocumentKind.PDF) {
                ParcelFileDescriptor.open(document.file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        val total = OcrResourcePolicy.requirePdfPageCount(renderer.pageCount)
                        var renderedPixels = 0L
                        withContext(Dispatchers.Main) { onProgress(0, total) }
                        for (index in 0 until total) {
                            currentCoroutineContext().ensureActive()
                            val pageResult = recognizePdfPage(
                                renderer = renderer,
                                index = index,
                                recognizer = recognizer,
                                currentCharacters = recognizedCharacters,
                                renderedPixels = renderedPixels,
                            )
                            recognizedCharacters = pageResult.totalCharacters
                            renderedPixels = pageResult.totalRenderedPixels
                            results.add(index + 1 to pageResult.text)
                            withContext(Dispatchers.Main) { onProgress(index + 1, total) }
                        }
                    }
                }
            } else {
                require(document.kind != IncomingDocumentKind.DOCX) { "Choose a PDF or image for OCR" }
                currentCoroutineContext().ensureActive()
                withContext(Dispatchers.Main) { onProgress(0, 1) }
                val bitmap = withContext(Dispatchers.IO) { decodeBoundedOcrImage(document.file) }
                    ?: error("The image could not be decoded safely")
                try {
                    currentCoroutineContext().ensureActive()
                    val recognizedText = recognizeBitmapText(bitmap, recognizer)
                    currentCoroutineContext().ensureActive()
                    val accepted = OcrResourcePolicy.acceptRecognizedText(
                        pageNumber = 1,
                        recognizedText = recognizedText,
                        currentCharacters = recognizedCharacters,
                    )
                    results.add(1 to accepted.text)
                } finally {
                    bitmap.recycle()
                }
                withContext(Dispatchers.Main) { onProgress(1, 1) }
            }
        } finally {
            document.file.delete()
        }
        currentCoroutineContext().ensureActive()
        withContext(Dispatchers.Main) { onDone(results.toList()) }
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

private data class OcrPageResult(
    val text: String,
    val totalCharacters: Int,
    val totalRenderedPixels: Long,
)

private suspend fun recognizePdfPage(
    renderer: PdfRenderer,
    index: Int,
    recognizer: com.google.mlkit.vision.text.TextRecognizer,
    currentCharacters: Int,
    renderedPixels: Long,
): OcrPageResult {
    currentCoroutineContext().ensureActive()
    val page = renderer.openPage(index)
    try {
        val size = OcrResourcePolicy.pdfRenderSize(page.width, page.height)
        val updatedPixels = OcrResourcePolicy.updatedRenderedPixels(renderedPixels, size.pixelCount)
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
            currentCoroutineContext().ensureActive()
            val recognizedText = recognizeBitmapText(bitmap, recognizer)
            currentCoroutineContext().ensureActive()
            val accepted = OcrResourcePolicy.acceptRecognizedText(
                pageNumber = index + 1,
                recognizedText = recognizedText,
                currentCharacters = currentCharacters,
            )
            return OcrPageResult(
                text = accepted.text,
                totalCharacters = accepted.totalCharacters,
                totalRenderedPixels = updatedPixels,
            )
        } finally {
            bitmap.recycle()
        }
    } finally {
        page.close()
    }
}

private suspend fun recognizeBitmapText(
    bitmap: Bitmap,
    recognizer: com.google.mlkit.vision.text.TextRecognizer,
): String = withContext(Dispatchers.IO) {
    val task = recognizer.process(InputImage.fromBitmap(bitmap, 0))
    // ML Kit does not expose cancellation for this task. Finish the active page before
    // recycling its bitmap, then let the caller observe cancellation before another page.
    withContext(NonCancellable) { task.await().text }
}

private fun decodeBoundedOcrImage(file: File): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    val target = OcrResourcePolicy.imageDecodeSize(bounds.outWidth, bounds.outHeight)
    val sampleSize = OcrResourcePolicy.imageSampleSize(bounds.outWidth, bounds.outHeight, target)
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
    return try {
        OcrResourcePolicy.requireDecodedImage(bitmap.width, bitmap.height)
        bitmap
    } catch (error: IllegalArgumentException) {
        bitmap.recycle()
        throw error
    }
}
