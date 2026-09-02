package com.example.pdfmaker

import android.content.Context
import android.net.Uri
import android.os.OperationCanceledException
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException

internal class DocxToPdfActions(
    private val context: Context,
    private val scope: CoroutineScope,
    private val state: DocxToPdfUiState,
) {
    private var activeJob: Job? = null
    private var selectedUri: Uri? = null
    private var released = false

    fun select(
        uri: Uri,
        fallbackName: String? = null,
    ) {
        if (released) return
        val generation = state.beginSelection()
        launchTracked { prepareSelection(uri, fallbackName, generation) }
    }

    fun startConversion() {
        if (released) return
        val uri = selectedUri ?: return
        val request = state.beginConversion() ?: return
        launchTracked { convert(uri, request) }
    }

    fun shareResult() {
        if (released) return
        val result = state.result ?: return
        launchTracked {
            val shared = withContext(Dispatchers.IO) { shareDocxPdf(context, result.file) }
            currentCoroutineContext().ensureActive()
            if (!shared) {
                state.shareFailed(
                    result,
                    DocxToPdfPolicy.failureMessage(
                        DocxToPdfFailureStage.SHARE,
                        IllegalStateException("Share target unavailable"),
                    ),
                )
            }
        }
    }

    fun cancelActive(): Boolean {
        val cancelled = state.cancelActive()
        if (cancelled) activeJob?.cancel()
        return cancelled
    }

    fun reset() {
        activeJob?.cancel()
        selectedUri = null
        state.reset()
    }

    fun dismissError() = state.dismissError()

    fun release() {
        if (released) return
        released = true
        state.cancelActive()
        activeJob?.cancel()
        activeJob = null
        selectedUri = null
    }

    private suspend fun prepareSelection(
        uri: Uri,
        fallbackName: String?,
        generation: Long,
    ) {
        try {
            val input = readDocxInputMetadata(context, uri, fallbackName)
            currentCoroutineContext().ensureActive()
            if (state.selectionSucceeded(generation, input)) selectedUri = uri
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            rejectSelection(generation, error)
        } catch (error: SecurityException) {
            rejectSelection(generation, error)
        } catch (error: IllegalArgumentException) {
            rejectSelection(generation, error)
        } catch (error: IllegalStateException) {
            rejectSelection(generation, error)
        } catch (error: OperationCanceledException) {
            rejectSelection(generation, error)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun convert(
        uri: Uri,
        request: DocxConversionRequest,
    ) {
        var unclaimedOutput: File? = null
        var failureStage = DocxToPdfFailureStage.CONVERT
        try {
            val output =
                withContext(Dispatchers.IO) {
                    val converted =
                        docxToPdf(
                            context = context,
                            uri = uri,
                            baseName = request.input.displayName,
                            onProgress = { progress, text ->
                                reportProgress(request.generation, progress, text)
                            },
                        )
                    unclaimedOutput = converted.file
                    currentCoroutineContext().ensureActive()
                    failureStage = DocxToPdfFailureStage.VERIFY
                    verifyConvertedOutput(converted)
                }
            currentCoroutineContext().ensureActive()
            if (state.conversionSucceeded(request.generation, output)) {
                unclaimedOutput = null
                FileCache.prependFile(output.catalogEntry)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            reject(request.generation, failureStage, error)
        } finally {
            unclaimedOutput?.let { deleteUnclaimedOutput(it) }
        }
    }

    private fun reportProgress(
        generation: Long,
        progress: Int,
        text: String,
    ) {
        scope.launch(Dispatchers.Main.immediate) {
            state.reportProgress(generation, progress, text)
        }
    }

    private fun reject(
        generation: Long,
        stage: DocxToPdfFailureStage,
        error: Exception,
    ) {
        Timber
            .tag("DocxToPdf")
            .w(
                ObservabilityPolicy.sanitizedThrowable(error),
                "event=docx_operation_failed stage=%s",
                stage.name.lowercase(),
            )
        val message = DocxToPdfPolicy.failureMessage(stage, error)
        if (stage == DocxToPdfFailureStage.SELECT) {
            state.selectionFailed(generation, message)
        } else {
            state.conversionFailed(generation, message)
        }
    }

    private fun rejectSelection(
        generation: Long,
        error: Exception,
    ) {
        reject(generation, DocxToPdfFailureStage.SELECT, error)
    }

    private fun launchTracked(operation: suspend () -> Unit) {
        activeJob?.cancel()
        lateinit var launchedJob: Job
        launchedJob =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    operation()
                } finally {
                    if (activeJob === launchedJob) activeJob = null
                }
            }
        activeJob = launchedJob
        launchedJob.start()
    }
}

@Composable
internal fun rememberDocxToPdfActions(state: DocxToPdfUiState): DocxToPdfActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(context, scope, state) { DocxToPdfActions(context, scope, state) }
}

private fun verifyConvertedOutput(converted: DocxPdfResult): DocxSavedResult {
    val file = converted.file
    require(file.isFile && file.length() in 1..DocxConversionPolicy.MAX_OUTPUT_BYTES) {
        "Converted PDF output is missing or exceeds its limit"
    }
    SecureDocumentTypePolicy.requirePlainPdfFile(file)
    val verifiedPageCount = PdfFileMetadata.pageCount(file)
    require(verifiedPageCount == converted.pageCount && verifiedPageCount in 1..DocxConversionPolicy.MAX_PAGES) {
        "Converted PDF page metadata is inconsistent"
    }
    val sizeBytes = file.length()
    val catalogEntry =
        PdfFile(
            name = file.name,
            filePath = file.absolutePath,
            size = FileRepository.formatSize(sizeBytes),
            date = FileRepository.formatDate(file.lastModified()),
            pageCount = verifiedPageCount,
            lastModified = file.lastModified(),
        )
    return DocxSavedResult(file = file, catalogEntry = catalogEntry, sizeBytes = sizeBytes)
}

private suspend fun deleteUnclaimedOutput(file: File) {
    withContext(NonCancellable + Dispatchers.IO) {
        if (file.exists() && !file.delete()) {
            Timber.tag("DocxToPdf").w("event=unclaimed_output_delete_failed")
        }
    }
}
