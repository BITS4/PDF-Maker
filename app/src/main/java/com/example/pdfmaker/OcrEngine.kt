package com.example.pdfmaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

internal suspend fun runOcr(
    context: Context,
    uri: Uri,
    onProgress: (Int, Int) -> Unit,
    onDone: (List<Pair<Int, String>>) -> Unit,
    onError: (String) -> Unit,
) {
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    val pendingArtifact = AtomicReference<ImportedDocumentArtifact?>()
    try {
        val document = importOcrDocument(context, uri, pendingArtifact)
        currentCoroutineContext().ensureActive()
        val results = recognizeOcrDocument(document, recognizer, onProgress)
        currentCoroutineContext().ensureActive()
        withContext(Dispatchers.Main) { onDone(results) }
    } catch (error: CancellationException) {
        throw error
    } catch (error: IOException) {
        reportOcrFailure(error, onError)
    } catch (error: SecurityException) {
        reportOcrFailure(error, onError)
    } catch (error: IllegalArgumentException) {
        reportOcrFailure(error, onError)
    } catch (error: IllegalStateException) {
        reportOcrFailure(error, onError)
    } catch (error: MlKitException) {
        reportOcrFailure(error, onError)
    } finally {
        withContext(NonCancellable + Dispatchers.IO) {
            pendingArtifact.getAndSet(null)?.close()
        }
        recognizer.close()
    }
}

private suspend fun importOcrDocument(
    context: Context,
    uri: Uri,
    pendingArtifact: AtomicReference<ImportedDocumentArtifact?>,
): ImportedDocumentArtifact =
    withContext(Dispatchers.IO) {
        val operationContext = currentCoroutineContext()
        when (
            val imported =
                SafeDocumentImporter.import(
                    context = context,
                    // The imported bytes, not provider-controlled metadata, determine the accepted format.
                    request = IncomingDocumentRequest(uri, declaredMimeType = null),
                    retention = IncomingImportRetention.OPERATION_TEMPORARY,
                    beforeChunk = { operationContext.ensureActive() },
                )
        ) {
            is IncomingImportResult.Imported -> {
                imported.artifact.also(pendingArtifact::set)
            }

            is IncomingImportResult.Rejected -> {
                error(imported.message)
            }
        }
    }

private suspend fun recognizeOcrDocument(
    document: ImportedDocumentArtifact,
    recognizer: TextRecognizer,
    onProgress: (Int, Int) -> Unit,
): List<Pair<Int, String>> =
    try {
        when (document.kind) {
            IncomingDocumentKind.PDF -> recognizePdfDocument(document.file, recognizer, onProgress)
            IncomingDocumentKind.DOCX -> error("Choose a PDF or image for OCR")
            else -> recognizeImageDocument(document.file, recognizer, onProgress)
        }
    } finally {
        withContext(NonCancellable + Dispatchers.IO) { document.close() }
    }

private suspend fun recognizePdfDocument(
    file: File,
    recognizer: TextRecognizer,
    onProgress: (Int, Int) -> Unit,
): List<Pair<Int, String>> =
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            val total = OcrResourcePolicy.requirePdfPageCount(renderer.pageCount)
            val results = ArrayList<Pair<Int, String>>(total)
            var recognizedCharacters = 0
            var renderedPixels = 0L
            withContext(Dispatchers.Main) { onProgress(0, total) }
            for (index in 0 until total) {
                currentCoroutineContext().ensureActive()
                val pageResult =
                    recognizePdfPage(renderer, index, recognizer, recognizedCharacters, renderedPixels)
                recognizedCharacters = pageResult.totalCharacters
                renderedPixels = pageResult.totalRenderedPixels
                results += index + 1 to pageResult.text
                withContext(Dispatchers.Main) { onProgress(index + 1, total) }
            }
            results
        }
    }

private suspend fun recognizeImageDocument(
    file: File,
    recognizer: TextRecognizer,
    onProgress: (Int, Int) -> Unit,
): List<Pair<Int, String>> {
    currentCoroutineContext().ensureActive()
    withContext(Dispatchers.Main) { onProgress(0, 1) }
    val bitmap =
        withContext(Dispatchers.IO) { decodeBoundedOcrImage(file) }
            ?: error("The image could not be decoded safely")
    val text =
        try {
            currentCoroutineContext().ensureActive()
            val recognizedText = recognizeBitmapText(bitmap, recognizer)
            currentCoroutineContext().ensureActive()
            OcrResourcePolicy.acceptRecognizedText(1, recognizedText, 0).text
        } finally {
            bitmap.recycle()
        }
    withContext(Dispatchers.Main) { onProgress(1, 1) }
    return listOf(1 to text)
}

private suspend fun reportOcrFailure(
    error: Exception,
    onError: (String) -> Unit,
) {
    val message = UserVisibleFailureReporter.message(UserFailureStage.OCR, error)
    withContext(Dispatchers.Main) { onError(message) }
}

private data class OcrPageResult(
    val text: String,
    val totalCharacters: Int,
    val totalRenderedPixels: Long,
)

private suspend fun recognizePdfPage(
    renderer: PdfRenderer,
    index: Int,
    recognizer: TextRecognizer,
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
            val matrix =
                android.graphics.Matrix().apply {
                    setScale(
                        size.width.toFloat() / page.width.toFloat(),
                        size.height.toFloat() / page.height.toFloat(),
                    )
                }
            page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            currentCoroutineContext().ensureActive()
            val recognizedText = recognizeBitmapText(bitmap, recognizer)
            currentCoroutineContext().ensureActive()
            val accepted =
                OcrResourcePolicy.acceptRecognizedText(
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
    recognizer: TextRecognizer,
): String =
    withContext(Dispatchers.IO) {
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
