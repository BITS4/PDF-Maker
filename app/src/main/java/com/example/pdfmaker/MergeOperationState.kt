package com.example.pdfmaker

import java.io.File

internal data class MergeOwnedResult(
    val file: File,
    val catalogEntry: PdfFile,
)

/** Pure single-flight ownership gate for one merge generation and its accepted result. */
internal class MergeOperationState {
    private var generation = 0L
    private var activeGeneration: Long? = null

    var result: MergeOwnedResult? = null
        private set

    val isActive: Boolean
        get() = activeGeneration != null

    fun begin(): Long? {
        if (activeGeneration != null || result != null) return null
        generation = MergeScreenPolicy.nextGeneration(generation)
        activeGeneration = generation
        return generation
    }

    fun accepts(operationGeneration: Long): Boolean = activeGeneration == operationGeneration

    fun complete(
        operationGeneration: Long,
        completedResult: MergeOwnedResult,
    ): Boolean {
        if (!accepts(operationGeneration)) return false
        activeGeneration = null
        result = completedResult
        return true
    }

    fun fail(operationGeneration: Long): Boolean {
        if (!accepts(operationGeneration)) return false
        activeGeneration = null
        return true
    }

    fun cancel(): Boolean {
        if (activeGeneration == null) return false
        generation = MergeScreenPolicy.nextGeneration(generation)
        activeGeneration = null
        return true
    }

    fun clear() {
        cancel()
        result = null
    }
}
