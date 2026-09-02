package com.example.pdfmaker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.io.File

internal data class CompressionInput(
    val displayName: String,
    val sizeBytes: Long,
)

internal data class CompressionResult(
    val file: File,
    val catalogEntry: PdfFile,
    val sizeBytes: Long,
)

internal data class CompressionRequest(
    val generation: Long,
    val input: CompressionInput,
    val level: CompressLevel,
)

@Stable
internal class CompressUiState {
    var phase by mutableStateOf(CompressionPhase.PICK)
        private set
    var input by mutableStateOf<CompressionInput?>(null)
        private set
    var level by mutableStateOf(CompressLevel.MEDIUM)
        private set
    var progress by mutableIntStateOf(0)
        private set
    var result by mutableStateOf<CompressionResult?>(null)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    private var generation by mutableLongStateOf(0L)
    private var selectionFallback by mutableStateOf(CompressionPhase.PICK)

    val backAction: CompressionBackAction
        get() = CompressionPolicy.backAction(phase)

    val savedPercent: Int?
        get() = result?.let { output -> CompressionPolicy.savedPercent(input?.sizeBytes ?: 0L, output.sizeBytes) }

    fun beginSelection(): Long {
        selectionFallback =
            when {
                result != null -> CompressionPhase.DONE
                input != null -> CompressionPhase.READY
                else -> CompressionPhase.PICK
            }
        generation = CompressionPolicy.nextGeneration(generation)
        phase = CompressionPhase.PREPARING
        progress = 0
        errorMessage = null
        return generation
    }

    fun selectionSucceeded(
        operationGeneration: Long,
        selectedInput: CompressionInput,
    ): Boolean {
        if (!accepts(operationGeneration, CompressionPhase.PREPARING)) return false
        require(selectedInput.sizeBytes >= 0L) { "Selected file size cannot be negative" }
        input = selectedInput
        result = null
        phase = CompressionPhase.READY
        return true
    }

    fun selectionFailed(
        operationGeneration: Long,
        message: String,
    ): Boolean {
        if (!accepts(operationGeneration, CompressionPhase.PREPARING)) return false
        phase = selectionFallback
        errorMessage = message.ifBlank { DEFAULT_ERROR_MESSAGE }
        return true
    }

    fun selectLevel(nextLevel: CompressLevel) {
        if (phase == CompressionPhase.READY) level = nextLevel
    }

    fun beginCompression(): CompressionRequest? {
        val selectedInput = input ?: return null
        if (phase != CompressionPhase.READY) return null
        generation = CompressionPolicy.nextGeneration(generation)
        phase = CompressionPhase.COMPRESSING
        progress = 0
        errorMessage = null
        result = null
        return CompressionRequest(generation, selectedInput, level)
    }

    fun reportProgress(
        operationGeneration: Long,
        percentage: Int,
    ): Boolean {
        if (!accepts(operationGeneration, CompressionPhase.COMPRESSING)) return false
        progress = CompressionPolicy.clampProgress(percentage)
        return true
    }

    fun compressionSucceeded(
        operationGeneration: Long,
        output: CompressionResult,
    ): Boolean {
        if (!accepts(operationGeneration, CompressionPhase.COMPRESSING)) return false
        require(output.sizeBytes >= 0L) { "Compressed file size cannot be negative" }
        result = output
        progress = 100
        phase = CompressionPhase.DONE
        return true
    }

    fun compressionFailed(
        operationGeneration: Long,
        message: String,
    ): Boolean {
        if (!accepts(operationGeneration, CompressionPhase.COMPRESSING)) return false
        progress = 0
        phase = if (input == null) CompressionPhase.PICK else CompressionPhase.READY
        errorMessage = message.ifBlank { DEFAULT_ERROR_MESSAGE }
        return true
    }

    fun shareFailed(message: String): Boolean {
        if (phase != CompressionPhase.DONE || result == null) return false
        errorMessage = message.ifBlank { DEFAULT_SHARE_ERROR_MESSAGE }
        return true
    }

    fun dismissError() {
        errorMessage = null
    }

    fun cancelActive(): Boolean {
        if (phase != CompressionPhase.PREPARING && phase != CompressionPhase.COMPRESSING) return false
        generation = CompressionPolicy.nextGeneration(generation)
        phase =
            if (phase == CompressionPhase.PREPARING) {
                selectionFallback
            } else if (input == null) {
                CompressionPhase.PICK
            } else {
                CompressionPhase.READY
            }
        progress = 0
        return true
    }

    fun reset() {
        generation = CompressionPolicy.nextGeneration(generation)
        phase = CompressionPhase.PICK
        input = null
        result = null
        progress = 0
        errorMessage = null
        selectionFallback = CompressionPhase.PICK
    }

    private fun accepts(
        operationGeneration: Long,
        expectedPhase: CompressionPhase,
    ): Boolean = generation == operationGeneration && phase == expectedPhase

    private companion object {
        const val DEFAULT_ERROR_MESSAGE = "This PDF could not be compressed safely."
        const val DEFAULT_SHARE_ERROR_MESSAGE = "The saved PDF could not be shared."
    }
}

@Composable
internal fun rememberCompressUiState(): CompressUiState = remember { CompressUiState() }
