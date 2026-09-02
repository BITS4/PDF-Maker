package com.example.pdfmaker

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

private data class LoadedImportedPdf(
    val source: StagedPdfSource,
    val pageCount: Int,
    val title: String,
)

@Composable
internal fun ImportedPdfViewerLifecycleEffect(
    state: ImportedPdfViewerState,
    operations: PdfEditorOperationController,
) {
    DisposableEffect(state, operations) {
        onDispose { state.release(operations) }
    }
}

@Composable
internal fun ImportedPdfSourceEffect(
    context: Context,
    pdfUri: Uri,
    state: ImportedPdfViewerState,
    operations: PdfEditorOperationController,
) {
    LaunchedEffect(state, pdfUri) {
        operations.cancelAndJoin()
        operations.dismissError()
        val obsolete = state.prepareForLoad()
        try {
            withFrameNanos { }
        } finally {
            obsolete.release()
        }
        loadImportedPdf(context, pdfUri, state)
    }
}

@Composable
internal fun ImportedPdfPageCacheEffect(
    context: Context,
    state: ImportedPdfViewerState,
    displayWidth: Int,
) {
    val workingUri = state.workingUri
    val requestedPage = state.currentPage
    val pageCount = state.pageCount
    LaunchedEffect(state, requestedPage, pageCount, workingUri, displayWidth) {
        val sourceUri = workingUri ?: return@LaunchedEffect
        val retainedIndexes = PageBitmapCachePolicy.retainedIndexes(requestedPage, pageCount)
        val cache = state.prunePageCache(retainedIndexes)
        retireAfterFrame(cache.obsolete)

        val rendered = mutableMapOf<Int, Bitmap>()
        try {
            withContext(Dispatchers.IO) {
                retainedIndexes
                    .filter { it !in cache.retained }
                    .sortedBy { index -> kotlin.math.abs(index - requestedPage) }
                    .forEach { index ->
                        coroutineContext.ensureActive()
                        renderPage(context, sourceUri, index, displayWidth)?.let { rendered[index] = it }
                    }
            }
            coroutineContext.ensureActive()
            state.installRenderedPages(cache.retained, rendered)
            rendered.clear()
        } finally {
            recycleDistinctBitmaps(rendered.values)
        }
    }
}

private suspend fun stageImportedPdf(
    context: Context,
    pdfUri: Uri,
): LoadedImportedPdf {
    var pendingSource: StagedPdfSource? = null
    try {
        val count =
            withContext(Dispatchers.IO) {
                SafePdfInput.fromUri(context, pdfUri).also { pendingSource = it }.let { source ->
                    PageEditPolicy.requireSupportedPageCount(PdfFileMetadata.pageCount(source.file))
                }
            }
        coroutineContext.ensureActive()
        val source = checkNotNull(pendingSource)
        pendingSource = null
        return LoadedImportedPdf(
            source = source,
            pageCount = count,
            title = ImportedPdfViewerPolicy.documentTitle(pdfUri.lastPathSegment),
        )
    } finally {
        pendingSource?.close()
    }
}

@Suppress("TooGenericExceptionCaught") // Provider and PdfRenderer failures meet at this user-visible I/O boundary.
private suspend fun loadImportedPdf(
    context: Context,
    pdfUri: Uri,
    state: ImportedPdfViewerState,
) {
    var pending: LoadedImportedPdf? = null
    try {
        pending = stageImportedPdf(context, pdfUri)
        state.acceptSource(pending.source, pending.pageCount, pending.title)
        pending = null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        state.reportLoadFailure(error)
    } finally {
        pending?.source?.close()
    }
}

private suspend fun retireAfterFrame(bitmaps: Collection<Bitmap>) {
    if (bitmaps.isEmpty()) return
    try {
        withFrameNanos { }
    } finally {
        BitmapOwnership.retire(bitmaps)
    }
}
