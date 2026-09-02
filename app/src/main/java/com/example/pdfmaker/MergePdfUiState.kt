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
    val uris: List<Uri>,
    val requestedName: String,
    val totalPages: Int,
)

@Stable
internal class MergePdfUiState {
    var items by mutableStateOf<List<MergeItem>>(emptyList())
        private set
    var phase by mutableStateOf(MergeState.EMPTY)
        private set
    var progress by mutableIntStateOf(0)
        private set
    var progressText by mutableStateOf("")
        private set
    var resultFile by mutableStateOf<File?>(null)
        private set
    var resultPdfFile by mutableStateOf<PdfFile?>(null)
        private set
    var errorMessage by mutableStateOf("")
        private set
    var shareMessage by mutableStateOf<String?>(null)
        private set
    var outputName by mutableStateOf(MergeScreenPolicy.defaultOutputName(0))
        private set
    var showRenameDialog by mutableStateOf(false)
    var showPreMergeDialog by mutableStateOf(false)

    private var completedMerges = 0

    val summary: MergeSummary
        get() =
            MergeScreenPolicy.summary(
                pageCounts = items.map(MergeItem::pageCount),
                sizesKb = items.map(MergeItem::sizeKb),
            )

    fun addLoaded(loaded: List<MergeItem>) {
        if (loaded.isEmpty()) return
        items = items + loaded
        phase = MergeState.READY
        errorMessage = ""
        shareMessage = null
    }

    fun reject(message: String) {
        errorMessage = message.ifBlank { DefaultMergeError }
        shareMessage = null
        phase = MergeState.ERROR
    }

    fun rename(name: String) {
        name.trim().takeIf(String::isNotEmpty)?.let { outputName = it }
    }

    fun remove(index: Int): MergeItem? {
        val removed = items.getOrNull(index) ?: return null
        items = items.toMutableList().apply { removeAt(index) }
        if (items.isEmpty()) phase = MergeState.EMPTY
        return removed
    }

    fun move(
        index: Int,
        direction: MergeItemMove,
    ) {
        items = MergeScreenPolicy.moved(items, index, direction)
    }

    fun beginMerge(requestedName: String): MergeRequest? {
        if (!MergeScreenPolicy.canMerge(items.size)) return null
        rename(requestedName)
        phase = MergeState.MERGING
        progress = 0
        progressText = ""
        errorMessage = ""
        shareMessage = null
        resultFile = null
        resultPdfFile = null
        return MergeRequest(
            uris = items.map(MergeItem::uri),
            requestedName = outputName,
            totalPages = summary.pageCount,
        )
    }

    fun reportProgress(
        percent: Int,
        message: String,
    ) {
        if (phase != MergeState.MERGING) return
        progress = MergeScreenPolicy.progress(percent)
        progressText = message
    }

    fun mergeSucceeded(
        file: File,
        pdfFile: PdfFile,
    ) {
        resultFile = file
        resultPdfFile = pdfFile
        completedMerges += 1
        outputName = MergeScreenPolicy.defaultOutputName(completedMerges)
        progress = 100
        phase = MergeState.DONE
        errorMessage = ""
        shareMessage = null
    }

    fun reportShareResult(launched: Boolean): Boolean {
        if (phase != MergeState.DONE || resultFile == null || resultPdfFile == null) return false
        shareMessage = if (launched) null else ShareFailureMessage
        return true
    }

    fun retry() {
        phase = if (items.isEmpty()) MergeState.EMPTY else MergeState.READY
        errorMessage = ""
        shareMessage = null
    }

    fun reset(): List<MergeItem> {
        val released = items
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
