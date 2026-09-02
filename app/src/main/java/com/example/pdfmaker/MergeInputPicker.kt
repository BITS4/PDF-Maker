package com.example.pdfmaker

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun rememberMergeInputPicker(
    currentItems: List<MergeItem>,
    onLoaded: (List<MergeItem>) -> Unit,
    onError: (String) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestItems by rememberUpdatedState(currentItems)
    val latestOnLoaded by rememberUpdatedState(onLoaded)
    val latestOnError by rememberUpdatedState(onError)
    var loading by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty() || loading) return@rememberLauncherForActivityResult
        val existing = latestItems
        val remainingSlots = MergePdfPolicy.MAX_SOURCE_FILES - existing.size
        val selectedUris = uris.take(remainingSlots.coerceAtLeast(0))
        if (selectedUris.isEmpty()) {
            latestOnError("No more than ${MergePdfPolicy.MAX_SOURCE_FILES} PDFs can be merged.")
            return@rememberLauncherForActivityResult
        }

        loading = true
        scope.launch(Dispatchers.IO) {
            var pendingItems: List<MergeItem> = emptyList()
            try {
                val batch = loadMergeInputs(
                    context = context,
                    uris = selectedUris,
                    currentPageCount = existing.sumOf(MergeItem::pageCount),
                )
                pendingItems = batch.items
                withContext(Dispatchers.Main) {
                    if (pendingItems.isNotEmpty()) {
                        latestOnLoaded(pendingItems)
                        pendingItems = emptyList()
                    } else {
                        latestOnError(
                            batch.rejectionReasons.firstOrNull()
                                ?: "The selected PDFs could not be read safely.",
                        )
                    }
                }
            } finally {
                BitmapOwnership.retire(pendingItems.mapNotNull(MergeItem::thumb))
                withContext(Dispatchers.Main) { loading = false }
            }
        }
    }

    return { launcher.launch(arrayOf("application/pdf")) }
}
