package com.example.pdfmaker

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

internal class MergePdfActions(
    val selectFiles: () -> Unit,
    val startMerge: (String) -> Unit,
    val openResult: () -> Unit,
    val shareResult: () -> Unit,
    val removeItem: (Int) -> Unit,
    val reset: () -> Unit,
)

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
    return remember(state, context, scope, selectFiles) {
        MergePdfActions(
            selectFiles = selectFiles,
            startMerge = { requestedName ->
                val request = state.beginMerge(requestedName)
                if (request != null) {
                    scope.launch {
                        performMerge(context, scope, state, request)
                    }
                }
            },
            openResult = {
                state.resultPdfFile?.let(latestOnOpenFile.value)
            },
            shareResult = {
                val file = state.resultFile
                if (
                    file != null &&
                    !DocumentShareAdapter.share(
                        context = context,
                        file = file,
                        chooserTitle = "Share merged PDF",
                        requestedMimeType = "application/pdf",
                    )
                ) {
                    state.reject("The merged PDF is saved, but it could not be shared.")
                }
            },
            removeItem = { index ->
                state.remove(index)?.thumb?.let { BitmapOwnership.retire(listOf(it)) }
            },
            reset = {
                BitmapOwnership.retire(state.reset().mapNotNull(MergeItem::thumb))
            },
        )
    }
}

private suspend fun performMerge(
    context: Context,
    scope: CoroutineScope,
    state: MergePdfUiState,
    request: MergeRequest,
) {
    try {
        val file =
            withContext(Dispatchers.IO) {
                mergePdfs(
                    context = context,
                    uris = request.uris,
                    baseName = request.requestedName,
                    onProg = { percent, message ->
                        scope.launch {
                            state.reportProgress(percent, message)
                        }
                    },
                )
            }
        val pdfFile = file.toPdfFile(request.totalPages)
        FileCache.prependFile(pdfFile)
        state.mergeSucceeded(file, pdfFile)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: IOException) {
        state.reject(mergeFailureMessage(error))
    } catch (error: IllegalArgumentException) {
        state.reject(mergeFailureMessage(error))
    } catch (error: IllegalStateException) {
        state.reject(mergeFailureMessage(error))
    } catch (error: SecurityException) {
        state.reject(mergeFailureMessage(error))
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

private fun mergeFailureMessage(error: Exception): String {
    return UserVisibleFailureReporter.message(UserFailureStage.PDF_MERGE, error)
}
