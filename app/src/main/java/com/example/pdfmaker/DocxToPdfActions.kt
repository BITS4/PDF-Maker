package com.example.pdfmaker

import android.net.Uri
import android.os.OperationCanceledException
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException

internal class DocxToPdfActions(
    private val scope: CoroutineScope,
    private val state: DocxToPdfUiState,
    private val boundaries: DocxToPdfBoundaries,
    private val dispatchers: DocxActionDispatchers,
) {
    private val progressUpdates = Channel<DocxProgressUpdate>(Channel.CONFLATED)
    private val progressCollector =
        scope.launch(dispatchers.main, start = CoroutineStart.UNDISPATCHED) {
            for (update in progressUpdates) {
                state.reportProgress(update.generation, update.percentage, update.label)
            }
        }
    private var activeJob: Job? = null
    private var selectedSource: DocxInputSource? = null
    private var released = false

    fun select(
        uri: Uri,
        fallbackName: String? = null,
    ) = select(DocxInputSource.provider(uri), fallbackName)

    internal fun select(
        source: DocxInputSource,
        fallbackName: String? = null,
    ) {
        if (released) return
        val generation = state.beginSelection()
        launchTracked { prepareSelection(source, fallbackName, generation) }
    }

    fun startConversion() {
        if (released) return
        val source = selectedSource ?: return
        val request = state.beginConversion() ?: return
        launchTracked { convert(source, request) }
    }

    fun shareResult() {
        if (released) return
        val result = state.result ?: return
        launchTracked {
            val shared =
                try {
                    withContext(dispatchers.main) { boundaries.share(result.file) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (expectedBoundaryFailure: RuntimeException) {
                    logFailure("share_launch_failed", expectedBoundaryFailure)
                    false
                }
            currentCoroutineContext().ensureActive()
            if (shared) {
                state.shareSucceeded(result)
            } else {
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
        selectedSource = null
        state.reset()
    }

    fun dismissError() = state.dismissError()

    fun release() {
        if (released) return
        released = true
        state.cancelActive()
        activeJob?.cancel()
        activeJob = null
        selectedSource = null
        progressUpdates.close()
        progressCollector.cancel()
    }

    private suspend fun prepareSelection(
        source: DocxInputSource,
        fallbackName: String?,
        generation: Long,
    ) {
        try {
            val input = withContext(dispatchers.io) { boundaries.inspect(source, fallbackName) }
            currentCoroutineContext().ensureActive()
            if (state.selectionSucceeded(generation, input)) selectedSource = source
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
        source: DocxInputSource,
        request: DocxConversionRequest,
    ) {
        var unclaimedOutput: File? = null
        var failureStage = DocxToPdfFailureStage.CONVERT
        try {
            val converted =
                withContext(dispatchers.io) {
                    boundaries.convert(
                        source = source,
                        baseName = request.input.displayName,
                        onProgress = { percentage, label ->
                            progressUpdates.trySend(
                                DocxProgressUpdate(request.generation, percentage, label),
                            )
                        },
                    )
                }
            unclaimedOutput = converted.file
            currentCoroutineContext().ensureActive()
            failureStage = DocxToPdfFailureStage.VERIFY
            val output = withContext(dispatchers.io) { boundaries.verify(converted) }
            currentCoroutineContext().ensureActive()
            if (state.conversionSucceeded(request.generation, output)) {
                unclaimedOutput = null
                cacheWithoutInvalidatingResult(output.catalogEntry)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            reject(request.generation, failureStage, error)
        } finally {
            unclaimedOutput?.let { deleteUnclaimedOutput(it) }
        }
    }

    private fun cacheWithoutInvalidatingResult(catalogEntry: PdfFile) {
        try {
            boundaries.cache(catalogEntry)
        } catch (expectedBoundaryFailure: RuntimeException) {
            logFailure("result_cache_unavailable", expectedBoundaryFailure)
        }
    }

    private suspend fun deleteUnclaimedOutput(file: File) {
        withContext(NonCancellable + dispatchers.io) {
            try {
                boundaries.deleteUnclaimed(file)
            } catch (error: IOException) {
                logFailure("unclaimed_output_delete_failed", error)
            } catch (expectedBoundaryFailure: RuntimeException) {
                logFailure("unclaimed_output_delete_failed", expectedBoundaryFailure)
            }
        }
    }

    private fun reject(
        generation: Long,
        stage: DocxToPdfFailureStage,
        error: Exception,
    ) {
        logFailure("operation_failed_${stage.name.lowercase()}", error)
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
    ) = reject(generation, DocxToPdfFailureStage.SELECT, error)

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

    private fun logFailure(
        event: String,
        error: Exception,
    ) {
        Timber
            .tag("DocxToPdf")
            .w(
                ObservabilityPolicy.sanitizedThrowable(error),
                "event=%s",
                event,
            )
    }
}

internal data class DocxProgressUpdate(
    val generation: Long,
    val percentage: Int,
    val label: String,
)

@Composable
internal fun rememberDocxToPdfActions(state: DocxToPdfUiState): DocxToPdfActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(context, scope, state) {
        DocxToPdfActions(
            scope = scope,
            state = state,
            boundaries = AndroidDocxToPdfBoundaries(context),
            dispatchers = DocxActionDispatchers.production(),
        )
    }
}
