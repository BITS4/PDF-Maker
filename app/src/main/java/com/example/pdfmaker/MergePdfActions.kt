package com.example.pdfmaker

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import java.util.concurrent.atomic.AtomicReference

internal class MergePdfActions(
    private val context: Context,
    private val scope: CoroutineScope,
    private val state: MergePdfUiState,
    private val selectFilesAction: () -> Unit,
    private val openFile: (PdfFile) -> Unit,
) {
    private var activeJob: Job? = null
    private var released = false

    fun selectFiles() {
        if (!released && state.phase != MergeState.MERGING) selectFilesAction()
    }

    fun startMerge(requestedName: String) {
        if (released) return
        val request = state.beginMerge(requestedName) ?: return
        launchTracked { performMerge(request) }
    }

    fun openResult() {
        if (!released) state.resultPdfFile?.let(openFile)
    }

    fun shareResult() {
        if (released) return
        val file = state.resultFile ?: return
        val launched =
            DocumentShareAdapter.share(
                context = context,
                file = file,
                chooserTitle = "Share merged PDF",
                requestedMimeType = "application/pdf",
            )
        state.reportShareResult(launched)
    }

    fun removeItem(index: Int) {
        state.remove(index)?.thumb?.let { BitmapOwnership.retire(listOf(it)) }
    }

    fun retry() {
        if (!released) state.retry()
    }

    fun reset() {
        if (released) return
        val thumbnails = state.reset().mapNotNull(MergeItem::thumb)
        activeJob?.cancel()
        activeJob = null
        BitmapOwnership.retire(thumbnails)
    }

    fun exit(onBack: () -> Unit) {
        if (!released) {
            state.cancelActiveMerge()
            activeJob?.cancel()
            activeJob = null
        }
        onBack()
    }

    fun release() {
        if (released) return
        released = true
        state.cancelActiveMerge()
        activeJob?.cancel()
        activeJob = null
    }

    private suspend fun performMerge(request: MergeRequest) {
        val unclaimedOutput = AtomicReference<File?>(null)
        try {
            val file =
                withContext(Dispatchers.IO) {
                    mergePdfs(
                        context = context,
                        uris = request.uris,
                        baseName = request.requestedName,
                        onProg = { percent, message ->
                            reportProgress(request.generation, percent, message)
                        },
                    ).also(unclaimedOutput::set)
                }
            currentCoroutineContext().ensureActive()
            val pdfFile = file.toPdfFile(request.totalPages)
            if (state.mergeSucceeded(request.generation, file, pdfFile)) {
                unclaimedOutput.compareAndSet(file, null)
                cacheWithoutInvalidatingResult(pdfFile)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            reject(request.generation, error)
        } catch (error: IllegalArgumentException) {
            reject(request.generation, error)
        } catch (error: IllegalStateException) {
            reject(request.generation, error)
        } catch (error: SecurityException) {
            reject(request.generation, error)
        } finally {
            unclaimedOutput.getAndSet(null)?.let { deleteUnclaimedOutput(it) }
        }
    }

    private fun reportProgress(
        generation: Long,
        percent: Int,
        message: String,
    ) {
        scope.launch(Dispatchers.Main.immediate) {
            state.reportProgress(generation, percent, message)
        }
    }

    private fun reject(
        generation: Long,
        error: Exception,
    ) {
        logFailure("merge_failed", error)
        state.mergeFailed(generation, mergeFailureMessage(error))
    }

    private fun cacheWithoutInvalidatingResult(pdfFile: PdfFile) {
        try {
            FileCache.prependFile(pdfFile)
        } catch (error: IllegalArgumentException) {
            logFailure("result_cache_unavailable", error)
        } catch (error: IllegalStateException) {
            logFailure("result_cache_unavailable", error)
        } catch (error: SecurityException) {
            logFailure("result_cache_unavailable", error)
        }
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

    private suspend fun deleteUnclaimedOutput(file: File) {
        withContext(NonCancellable + Dispatchers.IO) {
            if (!OwnedImportCleanup.erase(file)) {
                Timber.tag("MergePdf").w("event=unclaimed_output_cleanup_deferred")
            }
        }
    }

    private fun logFailure(
        event: String,
        error: Exception,
    ) {
        Timber
            .tag("MergePdf")
            .w(ObservabilityPolicy.sanitizedThrowable(error), "event=%s", event)
    }
}

@Composable
internal fun rememberMergePdfActions(
    state: MergePdfUiState,
    onOpenFile: (PdfFile) -> Unit,
): MergePdfActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestOnOpenFile = rememberUpdatedState(onOpenFile)
    val selectFiles =
        rememberMergeInputPicker(
            currentItems = state.items,
            onLoaded = state::addLoaded,
            onError = state::reject,
        )
    val latestSelectFiles = rememberUpdatedState(selectFiles)
    return remember(state, context, scope) {
        MergePdfActions(
            context = context,
            scope = scope,
            state = state,
            selectFilesAction = { latestSelectFiles.value() },
            openFile = { pdfFile -> latestOnOpenFile.value(pdfFile) },
        )
    }
}

private fun File.toPdfFile(totalPages: Int): PdfFile =
    PdfFile(
        name = name,
        filePath = absolutePath,
        size = mergeFormatSize(length() / 1024L),
        date = FileRepository.formatDate(lastModified()),
        pageCount = totalPages,
        lastModified = lastModified(),
    )

private fun mergeFailureMessage(error: Exception): String = UserVisibleFailureReporter.message(UserFailureStage.PDF_MERGE, error)
