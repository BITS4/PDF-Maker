package com.example.pdfmaker

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import java.io.File

internal data class MergeRequest(
    val generation: Long,
    val uris: List<Uri>,
    val requestedName: String,
    val totalPages: Int,
)

@Stable
internal class MergePdfUiState(
    private val operation: MergeOperationState = MergeOperationState(),
) {
    var items by mutableStateOf<List<MergeItem>>(emptyList())
        private set
    var phase by
        mutableStateOf(
            when {
                operation.isActive -> MergeState.MERGING
                operation.result != null -> MergeState.DONE
                else -> MergeState.EMPTY
            },
        )
        private set
    var progress by mutableIntStateOf(0)
        private set
    var progressText by mutableStateOf("")
        private set
    var resultFile by mutableStateOf(operation.result?.file)
        private set
    var resultPdfFile by mutableStateOf(operation.result?.catalogEntry)
        private set
    var errorMessage by mutableStateOf("")
        private set
    var shareMessage by mutableStateOf<String?>(null)
        private set
    var outputName by mutableStateOf(MergeScreenPolicy.defaultOutputName(0))
        private set
    var showRenameDialog by mutableStateOf(false)
    var showPreMergeDialog by mutableStateOf(false)

    private var completedMerges = if (operation.result == null) 0 else 1

    val summary: MergeSummary
        get() =
            MergeScreenPolicy.summary(
                pageCounts = items.map(MergeItem::pageCount),
                sizesKb = items.map(MergeItem::sizeKb),
            )

    fun addLoaded(loaded: List<MergeItem>) {
        if (loaded.isEmpty() || phase == MergeState.MERGING || phase == MergeState.DONE) return
        items = items + loaded
        phase = MergeState.READY
        errorMessage = ""
        shareMessage = null
    }

    fun reject(message: String) {
        if (phase == MergeState.MERGING || phase == MergeState.DONE) return
        errorMessage = message.ifBlank { DefaultMergeError }
        shareMessage = null
        phase = MergeState.ERROR
    }

    fun rename(name: String) {
        if (phase == MergeState.MERGING || phase == MergeState.DONE) return
        name.trim().takeIf(String::isNotEmpty)?.let { outputName = it }
    }

    fun remove(index: Int): MergeItem? {
        if (phase != MergeState.READY) return null
        val removed = items.getOrNull(index) ?: return null
        items = items.toMutableList().apply { removeAt(index) }
        if (items.isEmpty()) phase = MergeState.EMPTY
        return removed
    }

    fun move(
        index: Int,
        direction: MergeItemMove,
    ) {
        if (phase != MergeState.READY) return
        items = MergeScreenPolicy.moved(items, index, direction)
    }

    fun beginMerge(requestedName: String): MergeRequest? {
        if (phase != MergeState.READY) return null
        if (!MergeScreenPolicy.canMerge(items.size)) return null
        rename(requestedName)
        val generation = operation.begin() ?: return null
        phase = MergeState.MERGING
        progress = 0
        progressText = ""
        errorMessage = ""
        shareMessage = null
        resultFile = null
        resultPdfFile = null
        return MergeRequest(
            generation = generation,
            uris = items.map(MergeItem::uri),
            requestedName = outputName,
            totalPages = summary.pageCount,
        )
    }

    fun reportProgress(
        generation: Long,
        percent: Int,
        message: String,
    ): Boolean {
        if (phase != MergeState.MERGING || !operation.accepts(generation)) return false
        progress = MergeScreenPolicy.progress(percent)
        progressText = message
        return true
    }

    fun mergeSucceeded(
        generation: Long,
        file: File,
        pdfFile: PdfFile,
    ): Boolean {
        if (phase != MergeState.MERGING) return false
        if (!operation.complete(generation, MergeOwnedResult(file, pdfFile))) return false
        resultFile = file
        resultPdfFile = pdfFile
        completedMerges += 1
        outputName = MergeScreenPolicy.defaultOutputName(completedMerges)
        progress = 100
        phase = MergeState.DONE
        errorMessage = ""
        shareMessage = null
        return true
    }

    fun mergeFailed(
        generation: Long,
        message: String,
    ): Boolean {
        if (phase != MergeState.MERGING || !operation.fail(generation)) return false
        progress = 0
        progressText = ""
        errorMessage = message.ifBlank { DefaultMergeError }
        shareMessage = null
        phase = MergeState.ERROR
        return true
    }

    fun reportShareResult(launched: Boolean): Boolean {
        if (phase != MergeState.DONE || resultFile == null || resultPdfFile == null) return false
        shareMessage = if (launched) null else ShareFailureMessage
        return true
    }

    fun retry() {
        if (phase != MergeState.ERROR) return
        phase = if (items.isEmpty()) MergeState.EMPTY else MergeState.READY
        errorMessage = ""
        shareMessage = null
    }

    fun reset(): List<MergeItem> {
        val released = items
        operation.clear()
        items = emptyList()
        resultFile = null
        resultPdfFile = null
        progress = 0
        progressText = ""
        errorMessage = ""
        shareMessage = null
        phase = MergeState.EMPTY
        return released
    }

    fun cancelActiveMerge(): Boolean {
        if (phase != MergeState.MERGING || !operation.cancel()) return false
        progress = 0
        progressText = ""
        errorMessage = ""
        shareMessage = null
        resultFile = null
        resultPdfFile = null
        phase = if (MergeScreenPolicy.canMerge(items.size)) MergeState.READY else MergeState.EMPTY
        return true
    }

    private companion object {
        const val DefaultMergeError = "The selected PDFs could not be merged safely."
        const val ShareFailureMessage =
            "The merged PDF is saved, but no compatible sharing app could be opened. Tap Share to try again."
    }
}

@Composable
internal fun rememberMergePdfUiState(): MergePdfUiState {
    val state = remember { MergePdfUiState() }
    val latestItems by rememberUpdatedState(state.items)
    DisposableEffect(state) {
        onDispose {
            BitmapOwnership.retire(latestItems.mapNotNull(MergeItem::thumb))
        }
    }
    return state
}
