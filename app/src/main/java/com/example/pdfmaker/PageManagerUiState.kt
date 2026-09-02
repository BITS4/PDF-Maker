package com.example.pdfmaker

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.io.File

internal data class PageState(
    val bitmap: Bitmap,
    val rotation: Int = 0,
    val deleted: Boolean = false,
)

internal data class PageEdit(
    val rotation: Int,
    val deleted: Boolean,
)

internal data class PageManagerSaveRequest(
    val generation: Long,
    val baseName: String,
    val edits: List<PageEdit>,
)

internal data class PageManagerSavedOutput(
    val file: File,
    val catalogEntry: PdfFile,
)

internal data class PageManagerPageSwap(
    val obsoletePages: List<PageState>,
)

@Stable
internal class PageManagerUiState {
    private val operation = PageManagerOperationState()

    var phase by mutableStateOf(PageManagerPhase.PICK)
        private set
    var pickedName by mutableStateOf("document")
        private set
    var pages by mutableStateOf<List<PageState>>(emptyList())
        private set
    var output by mutableStateOf<PageManagerSavedOutput?>(null)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    val activePages: List<PageState>
        get() = pages.filterNot(PageState::deleted)

    val hasChanges: Boolean
        get() = pages.any { it.deleted || it.rotation != 0 }

    val canSave: Boolean
        get() = hasChanges && PageEditPolicy.canSave(pages.map(PageState::deleted))

    val backAction: PageManagerBackAction
        get() = PageManagerPolicy.backAction(phase)

    fun beginSelection(): Long {
        errorMessage = null
        output = null
        return operation.beginSelection().also { syncPhase() }
    }

    fun selectionSucceeded(
        operationGeneration: Long,
        displayName: String,
        replacement: List<PageState>,
    ): PageManagerPageSwap? {
        PageEditPolicy.requireSupportedPageCount(replacement.size)
        if (!operation.selectionSucceeded(operationGeneration)) return null
        val obsolete = pages
        pages = replacement
        pickedName = displayName
        output = null
        errorMessage = null
        syncPhase()
        return PageManagerPageSwap(obsolete)
    }

    fun selectionFailed(
        operationGeneration: Long,
        message: String,
    ): Boolean {
        if (!operation.selectionFailed(operationGeneration)) return false
        errorMessage = message.ifBlank { DEFAULT_LOAD_ERROR }
        syncPhase()
        return true
    }

    fun beginSave(): PageManagerSaveRequest? {
        val generation = operation.beginSave(canSave) ?: return null
        errorMessage = null
        syncPhase()
        return PageManagerSaveRequest(
            generation = generation,
            baseName = pickedName,
            edits = pages.map { page -> PageEdit(page.rotation, page.deleted) },
        )
    }

    fun saveSucceeded(
        operationGeneration: Long,
        result: PageManagerSavedOutput,
    ): Boolean {
        if (!operation.saveSucceeded(operationGeneration)) return false
        output = result
        errorMessage = null
        syncPhase()
        return true
    }

    fun saveFailed(
        operationGeneration: Long,
        message: String,
    ): Boolean {
        if (!operation.saveFailed(operationGeneration)) return false
        errorMessage = message.ifBlank { DEFAULT_SAVE_ERROR }
        syncPhase()
        return true
    }

    fun rotateClockwise(index: Int) {
        updatePage(index) { page -> page.copy(rotation = PageEditPolicy.rotateClockwise(page.rotation)) }
    }

    fun rotateCounterClockwise(index: Int) {
        updatePage(index) { page -> page.copy(rotation = PageEditPolicy.rotateCounterClockwise(page.rotation)) }
    }

    fun toggleDeleted(index: Int) {
        updatePage(index) { page -> page.copy(deleted = !page.deleted) }
    }

    fun editAgain() {
        if (!operation.editAgain()) return
        pages = pages.map { page -> page.copy(rotation = 0, deleted = false) }
        output = null
        syncPhase()
    }

    fun cancelActive(): Boolean =
        operation.cancelActive().also { canceled ->
            if (canceled) syncPhase()
        }

    fun dismissError() {
        errorMessage = null
    }

    fun detachPages(): List<PageState> {
        operation.invalidate()
        val detached = pages
        pages = emptyList()
        output = null
        errorMessage = null
        syncPhase()
        return detached
    }

    private fun updatePage(
        index: Int,
        update: (PageState) -> PageState,
    ) {
        if (phase != PageManagerPhase.EDIT || index !in pages.indices) return
        pages = pages.toMutableList().also { mutable -> mutable[index] = update(mutable[index]) }
    }

    private fun syncPhase() {
        phase = operation.phase
    }

    private companion object {
        const val DEFAULT_LOAD_ERROR = "This PDF could not be prepared safely."
        const val DEFAULT_SAVE_ERROR = "The edited PDF could not be saved safely."
    }
}

@Composable
internal fun rememberPageManagerUiState(): PageManagerUiState = remember { PageManagerUiState() }
