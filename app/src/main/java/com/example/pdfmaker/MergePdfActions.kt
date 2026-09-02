package com.example.pdfmaker

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
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
                if (file != null) {
                    shareMergedPdf(context, file)?.let(state::reject)
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
    Timber
        .tag("MergePdf")
        .w("PDF merge failed (%s)", error.javaClass.simpleName)
    return when (error) {
        is IllegalArgumentException,
        is IllegalStateException,
        -> error.message ?: "The selected PDFs are not valid for merging."
        is SecurityException -> "The selected PDFs are no longer accessible."
        else -> "The selected PDFs could not be merged safely."
    }
}

private fun shareMergedPdf(
    context: Context,
    file: File,
): String? =
    try {
        val uri =
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                file,
            )
        val sendIntent =
            Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                clipData = ClipData.newRawUri("Merged PDF", uri)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        context.startActivity(Intent.createChooser(sendIntent, "Share merged PDF"))
        null
    } catch (error: ActivityNotFoundException) {
        reportShareFailure(error, "No compatible app is available to share this PDF.")
    } catch (error: IllegalArgumentException) {
        reportShareFailure(error, "The merged PDF could not be shared safely.")
    } catch (error: SecurityException) {
        reportShareFailure(error, "Permission to share the merged PDF was denied.")
    }

private fun reportShareFailure(
    error: RuntimeException,
    message: String,
): String {
    Timber
        .tag("MergePdf")
        .w("Merged PDF share failed (%s)", error.javaClass.simpleName)
    return message
}
