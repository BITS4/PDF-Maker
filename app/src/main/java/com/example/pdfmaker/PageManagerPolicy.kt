package com.example.pdfmaker

internal enum class PageManagerPhase {
    PICK,
    PREPARING,
    EDIT,
    SAVING,
    DONE,
}

internal enum class PageManagerBackAction {
    CANCEL_OPERATION,
    NAVIGATE_BACK,
}

internal enum class PageManagerFailureStage {
    SELECT,
    SAVE,
    VERIFY,
}

/** Pure lifecycle and presentation policy for Page Manager operations. */
internal object PageManagerPolicy {
    const val MAX_OUTPUT_BYTES = 512L * 1024L * 1024L
    const val MAX_DISPLAY_NAME_LENGTH = 80

    fun nextGeneration(current: Long): Long = if (current == Long.MAX_VALUE) 1L else current + 1L

    fun backAction(phase: PageManagerPhase): PageManagerBackAction =
        when (phase) {
            PageManagerPhase.PREPARING,
            PageManagerPhase.SAVING,
            -> PageManagerBackAction.CANCEL_OPERATION

            else -> PageManagerBackAction.NAVIGATE_BACK
        }

    fun displayBaseName(pathSegment: String?): String {
        val plainLeaf = pathSegment.orEmpty().substringAfterLast('/').substringAfterLast('\\')
        val encodedSeparator = plainLeaf.lastIndexOf("%2f", ignoreCase = true)
        val encodedLeaf = if (encodedSeparator >= 0) plainLeaf.substring(encodedSeparator + 3) else plainLeaf
        val withoutExtension =
            if (encodedLeaf.endsWith(".pdf", ignoreCase = true)) encodedLeaf.dropLast(4) else encodedLeaf
        return withoutExtension
            .filterNot { it.isISOControl() || it in unsafeDirectionalCharacters }
            .trim()
            .take(MAX_DISPLAY_NAME_LENGTH)
            .ifBlank { "document" }
    }

    fun failureMessage(
        stage: PageManagerFailureStage,
        error: Exception,
    ): String =
        when (stage) {
            PageManagerFailureStage.SELECT -> {
                UserVisibleFailurePolicy.message(UserFailureStage.PAGE_PREVIEW_LOAD, error)
            }

            PageManagerFailureStage.SAVE,
            PageManagerFailureStage.VERIFY,
            -> {
                UserVisibleFailurePolicy.message(UserFailureStage.PDF_EDITOR_OPERATION, error)
            }
        }

    private val unsafeDirectionalCharacters =
        setOf(
            '\u061C',
            '\u200E',
            '\u200F',
            '\u202A',
            '\u202B',
            '\u202C',
            '\u202D',
            '\u202E',
            '\u2066',
            '\u2067',
            '\u2068',
            '\u2069',
        )
}

/** Rejects completions from canceled or superseded asynchronous operations. */
internal class PageManagerOperationState {
    var phase: PageManagerPhase = PageManagerPhase.PICK
        private set
    var hasSelection: Boolean = false
        private set
    private var generation = 0L
    private var selectionFallback = PageManagerPhase.PICK

    fun beginSelection(): Long {
        generation = PageManagerPolicy.nextGeneration(generation)
        selectionFallback = if (hasSelection) PageManagerPhase.EDIT else PageManagerPhase.PICK
        phase = PageManagerPhase.PREPARING
        return generation
    }

    fun selectionSucceeded(operationGeneration: Long): Boolean {
        if (!accepts(operationGeneration, PageManagerPhase.PREPARING)) return false
        hasSelection = true
        phase = PageManagerPhase.EDIT
        return true
    }

    fun selectionFailed(operationGeneration: Long): Boolean {
        if (!accepts(operationGeneration, PageManagerPhase.PREPARING)) return false
        phase = selectionFallback
        return true
    }

    fun beginSave(canSave: Boolean): Long? {
        if (!hasSelection || phase != PageManagerPhase.EDIT || !canSave) return null
        generation = PageManagerPolicy.nextGeneration(generation)
        phase = PageManagerPhase.SAVING
        return generation
    }

    fun saveSucceeded(operationGeneration: Long): Boolean {
        if (!accepts(operationGeneration, PageManagerPhase.SAVING)) return false
        phase = PageManagerPhase.DONE
        return true
    }

    fun saveFailed(operationGeneration: Long): Boolean {
        if (!accepts(operationGeneration, PageManagerPhase.SAVING)) return false
        phase = PageManagerPhase.EDIT
        return true
    }

    fun editAgain(): Boolean {
        if (!hasSelection || phase != PageManagerPhase.DONE) return false
        phase = PageManagerPhase.EDIT
        return true
    }

    fun cancelActive(): Boolean {
        if (phase != PageManagerPhase.PREPARING && phase != PageManagerPhase.SAVING) return false
        generation = PageManagerPolicy.nextGeneration(generation)
        phase = if (phase == PageManagerPhase.PREPARING) selectionFallback else PageManagerPhase.EDIT
        return true
    }

    fun invalidate() {
        generation = PageManagerPolicy.nextGeneration(generation)
        hasSelection = false
        phase = PageManagerPhase.PICK
        selectionFallback = PageManagerPhase.PICK
    }

    private fun accepts(
        operationGeneration: Long,
        expectedPhase: PageManagerPhase,
    ): Boolean = generation == operationGeneration && phase == expectedPhase
}
