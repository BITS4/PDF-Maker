package com.example.pdfmaker

import android.content.Context
import android.net.Uri
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

internal class PageManagerActions(
    private val context: Context,
    private val scope: CoroutineScope,
    private val state: PageManagerUiState,
) {
    private var activeJob: Job? = null
    private var activeSource: StagedPdfSource? = null
    private var released = false

    fun select(uri: Uri) {
        if (released) return
        val generation = state.beginSelection()
        launchTracked { prepareSelection(uri, generation) }
    }

    fun save() {
        if (released) return
        val source = activeSource ?: return
        val request = state.beginSave() ?: return
        launchTracked { performSave(source, request) }
    }

    fun rotateClockwise(index: Int) = state.rotateClockwise(index)

    fun rotateCounterClockwise(index: Int) = state.rotateCounterClockwise(index)

    fun toggleDeleted(index: Int) = state.toggleDeleted(index)

    fun editAgain() = state.editAgain()

    fun dismissError() = state.dismissError()

    fun cancelActive() {
        if (state.cancelActive()) activeJob?.cancel()
    }

    fun release() {
        if (released) return
        released = true
        val job = activeJob
        activeJob = null
        val source = activeSource
        activeSource = null
        val pages = state.detachPages()
        val releaseResources = {
            BitmapOwnership.retire(pages.map(PageState::bitmap))
            scope.launch(NonCancellable + Dispatchers.IO) { source?.close() }
            Unit
        }
        job?.cancel()
        if (job == null) {
            releaseResources()
        } else {
            job.invokeOnCompletion { releaseResources() }
        }
    }

    private suspend fun prepareSelection(
        uri: Uri,
        generation: Long,
    ) {
        var pendingSource: StagedPdfSource? = null
        var pendingPages: List<PageState> = emptyList()
        try {
            val loaded =
                withContext(Dispatchers.IO) {
                    val operationContext = currentCoroutineContext()
                    val source =
                        SafePdfInput.fromUri(context, uri) {
                            operationContext.ensureActive()
                        }
                    pendingSource = source
                    operationContext.ensureActive()
                    val pages = loadPageStates(source)
                    pendingPages = pages
                    LoadedPageManagerSelection(
                        source = source,
                        pages = pages,
                        displayName = PageManagerPolicy.displayBaseName(uri.lastPathSegment),
                    )
                }
            currentCoroutineContext().ensureActive()
            val swap = state.selectionSucceeded(generation, loaded.displayName, loaded.pages)
            if (swap != null) {
                val obsoleteSource = activeSource
                activeSource = loaded.source
                pendingSource = null
                pendingPages = emptyList()
                BitmapOwnership.retire(swap.obsoletePages.map(PageState::bitmap))
                closeStagedSource(obsoleteSource)
            }
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
        } finally {
            closeStagedSource(pendingSource)
            recyclePageStates(pendingPages)
        }
    }

    private suspend fun performSave(
        source: StagedPdfSource,
        request: PageManagerSaveRequest,
    ) {
        var unclaimedOutput: File? = null
        try {
            when (
                val result =
                    withContext(Dispatchers.IO) {
                        savePages(
                            context = context,
                            source = source,
                            edits = request.edits,
                            baseName = request.baseName,
                        )
                    }
            ) {
                is PageManagerSaveResult.Failed -> {
                    rejectSave(request.generation, result.stage, result.error)
                }

                is PageManagerSaveResult.Saved -> {
                    unclaimedOutput = result.file
                    currentCoroutineContext().ensureActive()
                    val saved = withContext(Dispatchers.IO) { createSavedOutput(result) }
                    if (state.saveSucceeded(request.generation, saved)) {
                        unclaimedOutput = null
                        FileCache.prependFile(saved.catalogEntry)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            rejectSave(request.generation, PageManagerFailureStage.VERIFY, error)
        } catch (error: SecurityException) {
            rejectSave(request.generation, PageManagerFailureStage.VERIFY, error)
        } catch (error: IllegalArgumentException) {
            rejectSave(request.generation, PageManagerFailureStage.VERIFY, error)
        } catch (error: IllegalStateException) {
            rejectSave(request.generation, PageManagerFailureStage.VERIFY, error)
        } finally {
            unclaimedOutput?.let { output -> deleteUnclaimedOutput(output) }
        }
    }

    private fun rejectSelection(
        generation: Long,
        error: Exception,
    ) {
        logFailure("selection_failed", error)
        state.selectionFailed(
            generation,
            PageManagerPolicy.failureMessage(PageManagerFailureStage.SELECT, error),
        )
    }

    private fun rejectSave(
        generation: Long,
        stage: PageManagerFailureStage,
        error: Exception,
    ) {
        logFailure("save_failed", error)
        state.saveFailed(generation, PageManagerPolicy.failureMessage(stage, error))
    }

    private fun launchTracked(operation: suspend () -> Unit) {
        val previous = activeJob
        previous?.cancel()
        lateinit var launchedJob: Job
        launchedJob =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    previous?.join()
                    currentCoroutineContext().ensureActive()
                    operation()
                } finally {
                    if (activeJob === launchedJob) activeJob = null
                }
            }
        activeJob = launchedJob
        launchedJob.start()
    }
}

private suspend fun closeStagedSource(source: StagedPdfSource?) {
    if (source == null) return
    withContext(NonCancellable + Dispatchers.IO) { source.close() }
}

@Composable
internal fun rememberPageManagerActions(state: PageManagerUiState): PageManagerActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(context, scope, state) { PageManagerActions(context, scope, state) }
}

private data class LoadedPageManagerSelection(
    val source: StagedPdfSource,
    val pages: List<PageState>,
    val displayName: String,
)

private fun createSavedOutput(result: PageManagerSaveResult.Saved): PageManagerSavedOutput {
    val file = result.file
    require(file.isFile && file.length() > 0L) { "Edited PDF output is unavailable" }
    return PageManagerSavedOutput(
        file = file,
        catalogEntry =
            PdfFile(
                name = file.nameWithoutExtension,
                filePath = file.absolutePath,
                size = FileRepository.formatSize(file.length()),
                date = FileRepository.formatDate(file.lastModified()),
                pageCount = result.pageCount,
                lastModified = file.lastModified(),
            ),
    )
}

private suspend fun deleteUnclaimedOutput(file: File) {
    withContext(NonCancellable + Dispatchers.IO) {
        if (file.exists() && !file.delete()) {
            Timber.tag("PageManager").w("event=unclaimed_output_delete_failed")
        }
    }
}

private fun logFailure(
    event: String,
    error: Exception,
) {
    Timber
        .tag("PageManager")
        .w(ObservabilityPolicy.sanitizedThrowable(error), "event=%s", event)
}
