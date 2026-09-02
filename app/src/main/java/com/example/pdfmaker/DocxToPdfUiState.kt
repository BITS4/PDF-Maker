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

internal data class DocxConversionRequest(
    val generation: Long,
    val input: DocxInputMetadata,
)

internal data class DocxSavedResult(
    val file: File,
    val catalogEntry: PdfFile,
    val sizeBytes: Long,
)

@Stable
internal class DocxToPdfUiState {
    var phase by mutableStateOf(DocxToPdfPhase.PICK)
        private set
    var input by mutableStateOf<DocxInputMetadata?>(null)
        private set
    var progress by mutableIntStateOf(0)
        private set
    var progressText by mutableStateOf("")
        private set
    var result by mutableStateOf<DocxSavedResult?>(null)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    private var generation by mutableLongStateOf(0L)
    private var selectionFallback by mutableStateOf(DocxToPdfPhase.PICK)

    val backAction: DocxToPdfBackAction
        get() = DocxToPdfPolicy.backAction(phase)

    fun beginSelection(): Long {
        selectionFallback =
            when {
                result != null -> DocxToPdfPhase.DONE
                input != null -> DocxToPdfPhase.READY
                else -> DocxToPdfPhase.PICK
            }
        generation = DocxToPdfPolicy.nextGeneration(generation)
        phase = DocxToPdfPhase.PREPARING
        progress = 0
        progressText = ""
        errorMessage = null
        return generation
    }

    fun selectionSucceeded(
        operationGeneration: Long,
        selectedInput: DocxInputMetadata,
    ): Boolean {
        if (!accepts(operationGeneration, DocxToPdfPhase.PREPARING)) return false
        require(selectedInput.displayName.isNotBlank()) { "DOCX display name cannot be blank" }
        require(selectedInput.sizeBytes >= 0L) { "DOCX size cannot be negative" }
        input = selectedInput
        result = null
        phase = DocxToPdfPhase.READY
        return true
    }

    fun selectionFailed(
        operationGeneration: Long,
        message: String,
    ): Boolean {
        if (!accepts(operationGeneration, DocxToPdfPhase.PREPARING)) return false
        phase = selectionFallback
        errorMessage = message.ifBlank { DEFAULT_SELECTION_ERROR }
        return true
    }

    fun beginConversion(): DocxConversionRequest? {
        val selectedInput = input ?: return null
        if (phase != DocxToPdfPhase.READY) return null
        generation = DocxToPdfPolicy.nextGeneration(generation)
        phase = DocxToPdfPhase.CONVERTING
        progress = 0
        progressText = "Preparing conversion…"
        result = null
        errorMessage = null
        return DocxConversionRequest(generation, selectedInput)
    }

    fun reportProgress(
        operationGeneration: Long,
        percentage: Int,
        label: String?,
    ): Boolean {
        if (!accepts(operationGeneration, DocxToPdfPhase.CONVERTING)) return false
        progress = DocxToPdfPolicy.clampProgress(percentage)
        progressText = DocxToPdfPolicy.progressLabel(label)
        return true
    }

    fun conversionSucceeded(
        operationGeneration: Long,
        output: DocxSavedResult,
    ): Boolean {
        if (!accepts(operationGeneration, DocxToPdfPhase.CONVERTING)) return false
        require(output.sizeBytes > 0L && output.catalogEntry.filePath.isNotBlank()) {
            "Converted PDF is unavailable"
        }
        result = output
        progress = 100
        progressText = "Done"
        phase = DocxToPdfPhase.DONE
        return true
    }

    fun conversionFailed(
        operationGeneration: Long,
        message: String,
    ): Boolean {
        if (!accepts(operationGeneration, DocxToPdfPhase.CONVERTING)) return false
        phase = if (input == null) DocxToPdfPhase.PICK else DocxToPdfPhase.READY
        progress = 0
        progressText = ""
        errorMessage = message.ifBlank { DEFAULT_CONVERSION_ERROR }
        return true
    }

    fun shareFailed(
        expectedResult: DocxSavedResult,
        message: String,
    ): Boolean {
        if (phase != DocxToPdfPhase.DONE || result !== expectedResult) return false
        errorMessage = message.ifBlank { DEFAULT_SHARE_ERROR }
        return true
    }

    fun dismissError() {
        errorMessage = null
    }

    fun cancelActive(): Boolean {
        if (phase != DocxToPdfPhase.PREPARING && phase != DocxToPdfPhase.CONVERTING) return false
        generation = DocxToPdfPolicy.nextGeneration(generation)
        phase =
            if (phase == DocxToPdfPhase.PREPARING) {
                selectionFallback
            } else if (input == null) {
                DocxToPdfPhase.PICK
            } else {
                DocxToPdfPhase.READY
            }
        progress = 0
        progressText = ""
        return true
    }

    fun reset() {
        generation = DocxToPdfPolicy.nextGeneration(generation)
        phase = DocxToPdfPhase.PICK
        input = null
        result = null
        progress = 0
        progressText = ""
        errorMessage = null
        selectionFallback = DocxToPdfPhase.PICK
    }

    private fun accepts(
        operationGeneration: Long,
        expectedPhase: DocxToPdfPhase,
    ): Boolean = generation == operationGeneration && phase == expectedPhase

    private companion object {
        const val DEFAULT_SELECTION_ERROR = "The Word document could not be selected safely."
        const val DEFAULT_CONVERSION_ERROR = "The Word document could not be converted safely."
        const val DEFAULT_SHARE_ERROR = "The saved PDF could not be shared."
    }
}

@Composable
internal fun rememberDocxToPdfUiState(): DocxToPdfUiState = remember { DocxToPdfUiState() }
