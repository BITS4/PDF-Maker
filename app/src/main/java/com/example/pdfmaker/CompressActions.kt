package com.example.pdfmaker

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
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

internal class CompressActions(
    private val context: Context,
    private val scope: CoroutineScope,
    private val state: CompressUiState,
) {
    private var activeJob: Job? = null
    private var selectedUri: Uri? = null

    fun select(uri: Uri) {
        val operationGeneration = state.beginSelection()
        launchTracked {
            try {
                val selected = inspectSelection(context, uri)
                currentCoroutineContext().ensureActive()
                if (state.selectionSucceeded(operationGeneration, selected)) {
                    selectedUri = uri
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IOException) {
                rejectSelection(operationGeneration, error)
            } catch (error: SecurityException) {
                rejectSelection(operationGeneration, error)
            } catch (error: IllegalArgumentException) {
                rejectSelection(operationGeneration, error)
            } catch (error: IllegalStateException) {
                rejectSelection(operationGeneration, error)
            }
        }
    }

    fun startCompression() {
        val sourceUri = selectedUri ?: return
        val request = state.beginCompression() ?: return
        launchTracked { performCompression(sourceUri, request) }
    }

    fun cancelActive() {
        if (state.cancelActive()) activeJob?.cancel()
    }

    fun reset() {
        activeJob?.cancel()
        selectedUri = null
        state.reset()
    }

    fun dismissError() {
        state.dismissError()
    }

    fun shareResult() {
        val file = state.result?.file ?: return
        try {
            shareCompressedFile(context, file)
        } catch (error: ActivityNotFoundException) {
            rejectShare(error)
        } catch (error: SecurityException) {
            rejectShare(error)
        } catch (error: IllegalArgumentException) {
            rejectShare(error)
        } catch (error: IllegalStateException) {
            rejectShare(error)
        }
    }

    fun release() {
        state.cancelActive()
        activeJob?.cancel()
        activeJob = null
    }

    private suspend fun performCompression(
        sourceUri: Uri,
        request: CompressionRequest,
    ) {
        var unclaimedOutput: File? = null
        var failureStage = CompressionFailureStage.COMPRESS
        try {
            val output =
                withContext(Dispatchers.IO) {
                    val file =
                        compressPdf(
                            context = context,
                            uri = sourceUri,
                            level = request.level,
                            baseName = request.input.displayName,
                            onProgress = { percentage -> reportProgress(request.generation, percentage) },
                        )
                    unclaimedOutput = file
                    currentCoroutineContext().ensureActive()
                    failureStage = CompressionFailureStage.VERIFY
                    verifiedResult(file)
                }
            currentCoroutineContext().ensureActive()
            if (state.compressionSucceeded(request.generation, output)) {
                unclaimedOutput = null
                FileCache.prependFile(output.catalogEntry)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            rejectCompression(request.generation, failureStage, error)
        } catch (error: SecurityException) {
            rejectCompression(request.generation, failureStage, error)
        } catch (error: IllegalArgumentException) {
            rejectCompression(request.generation, failureStage, error)
        } catch (error: IllegalStateException) {
            rejectCompression(request.generation, failureStage, error)
        } finally {
            unclaimedOutput?.let { deleteUnclaimedOutput(it) }
        }
    }

    private fun reportProgress(
        operationGeneration: Long,
        percentage: Int,
    ) {
        scope.launch(Dispatchers.Main.immediate) {
            state.reportProgress(operationGeneration, percentage)
        }
    }

    private fun rejectSelection(
        operationGeneration: Long,
        error: Exception,
    ) {
        logFailure("selection_failed", error)
        state.selectionFailed(
            operationGeneration,
            CompressionPolicy.failureMessage(CompressionFailureStage.SELECT, error),
        )
    }

    private fun rejectCompression(
        operationGeneration: Long,
        stage: CompressionFailureStage,
        error: Exception,
    ) {
        logFailure("compression_failed", error)
        state.compressionFailed(operationGeneration, CompressionPolicy.failureMessage(stage, error))
    }

    private fun rejectShare(error: Exception) {
        logFailure("share_failed", error)
        state.shareFailed(CompressionPolicy.failureMessage(CompressionFailureStage.SHARE, error))
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
internal fun rememberCompressActions(state: CompressUiState): CompressActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(context, scope, state) { CompressActions(context, scope, state) }
}

private suspend fun inspectSelection(
    context: Context,
    uri: Uri,
): CompressionInput =
    withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        require(uri.scheme == ContentResolver.SCHEME_CONTENT && !uri.authority.isNullOrBlank()) {
            "Only content-provider PDFs can be selected"
        }
        val reportedSize =
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor -> descriptor.statSize }
                ?: error("The PDF provider returned no file descriptor")
        if (reportedSize > 0L) {
            require(reportedSize <= SafePdfInput.MAX_PDF_BYTES) { "The selected PDF exceeds the input limit" }
        }
        CompressionInput(
            displayName = CompressionPolicy.displayBaseName(uri.lastPathSegment),
            sizeBytes = reportedSize.coerceAtLeast(0L),
        )
    }

private fun verifiedResult(file: File): CompressionResult {
    require(file.isFile && file.length() > 0L) { "Compressed PDF output is missing" }
    val pageCount = CompressionPolicy.requirePageCount(PdfFileMetadata.pageCount(file))
    val sizeBytes = file.length()
    return CompressionResult(
        file = file,
        catalogEntry =
            PdfFile(
                name = file.name,
                filePath = file.absolutePath,
                size = FileRepository.formatSize(sizeBytes),
                date = FileRepository.formatDate(file.lastModified()),
                pageCount = pageCount,
                lastModified = file.lastModified(),
            ),
        sizeBytes = sizeBytes,
    )
}

private suspend fun deleteUnclaimedOutput(file: File) {
    withContext(NonCancellable + Dispatchers.IO) {
        if (file.exists() && !file.delete()) {
            Timber.tag("CompressPdf").w("event=unclaimed_output_delete_failed")
        }
    }
}

private fun shareCompressedFile(
    context: Context,
    file: File,
) {
    require(file.isFile && file.length() > 0L) { "Compressed PDF output is unavailable" }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    val intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            clipData = ClipData.newRawUri("Compressed PDF", uri)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    context.startActivity(Intent.createChooser(intent, "Share compressed PDF"))
}

private fun logFailure(
    event: String,
    error: Exception,
) {
    Timber
        .tag("CompressPdf")
        .w(ObservabilityPolicy.sanitizedThrowable(error), "event=%s", event)
}
