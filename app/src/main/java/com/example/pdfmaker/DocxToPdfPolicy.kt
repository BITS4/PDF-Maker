package com.example.pdfmaker

import java.io.IOException
import java.util.Locale

internal enum class DocxToPdfPhase {
    PICK,
    PREPARING,
    READY,
    CONVERTING,
    DONE,
}

internal enum class DocxToPdfBackAction {
    CANCEL_OPERATION,
    NAVIGATE_BACK,
}

internal enum class DocxToPdfFailureStage {
    SELECT,
    CONVERT,
    VERIFY,
    SHARE,
}

internal data class DocxProviderMetadata(
    val displayName: String?,
    val mimeType: String?,
    val reportedSize: Long?,
    val descriptorSize: Long?,
)

internal data class DocxInputMetadata(
    val displayName: String,
    val sizeBytes: Long,
) {
    val fileName: String
        get() = "$displayName.docx"

    val formattedSize: String
        get() = DocxToPdfPolicy.formatSize(sizeBytes)
}

internal object DocxToPdfPolicy {
    const val DOCX_MIME_TYPE =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    private const val CONTENT_SCHEME = "content"
    private const val MAX_AUTHORITY_LENGTH = 255
    private const val MAX_RAW_NAME_LENGTH = 512
    private const val MAX_MIME_LENGTH = 255
    private const val DEFAULT_FAILURE = "The Word document could not be processed safely."
    private val genericMimeTypes =
        setOf(
            "*/*",
            "application/octet-stream",
            "application/zip",
            "application/x-zip-compressed",
        )

    fun requireProviderUri(
        scheme: String?,
        authority: String?,
    ) {
        require(scheme == CONTENT_SCHEME) { "Only content-provider DOCX files can be selected" }
        require(
            !authority.isNullOrBlank() &&
                authority.length <= MAX_AUTHORITY_LENGTH &&
                authority == authority.trim() &&
                authority.none { it.isISOControl() || it.isWhitespace() },
        ) { "The DOCX provider authority is invalid" }
    }

    fun metadata(
        provider: DocxProviderMetadata,
        fallbackName: String?,
        pathSegment: String?,
    ): DocxInputMetadata {
        val mimeType = normalizedMimeType(provider.mimeType)
        val providerName = boundedName(provider.displayName)
        val fallback = boundedName(fallbackName)
        val path = boundedName(pathSegment)
        val selectedName = providerName ?: fallback ?: path
        if (mimeType == null || mimeType in genericMimeTypes) {
            requireGenericNameIsDocx(selectedName)
        }
        val baseName =
            selectedName
                ?.substringAfterLast('/')
                ?.substringAfterLast('\\')
                ?.removeDocxSuffix()
                ?.let { SafeFileName.baseName(it, "document") }
                ?: "document"
        val knownSizes =
            listOfNotNull(
                normalizedSize(provider.reportedSize),
                normalizedSize(provider.descriptorSize),
            )
        val sizeBytes = knownSizes.maxOrNull() ?: 0L
        require(sizeBytes <= SafeDocxInput.MAX_DOCX_BYTES) {
            "The selected DOCX exceeds the input limit"
        }
        return DocxInputMetadata(displayName = baseName, sizeBytes = sizeBytes)
    }

    fun clampProgress(progress: Int): Int = progress.coerceIn(0, 100)

    fun progressLabel(raw: String?): String {
        val safe =
            raw
                .orEmpty()
                .filterNot { it.isISOControl() }
                .trim()
                .take(80)
        return safe.ifBlank { "Converting document…" }
    }

    fun nextGeneration(current: Long): Long {
        require(current >= 0L) { "Operation generation cannot be negative" }
        return if (current == Long.MAX_VALUE) 1L else current + 1L
    }

    fun backAction(phase: DocxToPdfPhase): DocxToPdfBackAction =
        when (phase) {
            DocxToPdfPhase.PREPARING,
            DocxToPdfPhase.CONVERTING,
            -> DocxToPdfBackAction.CANCEL_OPERATION

            else -> DocxToPdfBackAction.NAVIGATE_BACK
        }

    fun failureMessage(
        stage: DocxToPdfFailureStage,
        error: Exception,
    ): String =
        when (stage) {
            DocxToPdfFailureStage.SELECT -> {
                when (error) {
                    is SecurityException -> {
                        "Access to this Word document was denied. Re-select it and allow file access."
                    }

                    is IOException -> {
                        "This Word document could not be read. Check the file and try again."
                    }

                    else -> {
                        "This file is not a supported DOCX document. Choose another file."
                    }
                }
            }

            DocxToPdfFailureStage.CONVERT -> {
                when (error) {
                    is SecurityException -> {
                        "Access to this Word document was lost. Re-select it and try again."
                    }

                    is IOException -> {
                        "The PDF could not be written. Check available storage and try again."
                    }

                    else -> {
                        DEFAULT_FAILURE
                    }
                }
            }

            DocxToPdfFailureStage.VERIFY -> {
                "The converted PDF could not be verified. The incomplete output was removed."
            }

            DocxToPdfFailureStage.SHARE -> {
                "The converted PDF is saved, but it could not be shared. Try another compatible app."
            }
        }

    fun formatSize(bytes: Long): String {
        require(bytes >= 0L) { "File size cannot be negative" }
        return when {
            bytes >= 1_048_576L -> String.format(Locale.ROOT, "%.1f MB", bytes / 1_048_576.0)
            bytes >= 1_024L -> "${bytes / 1_024L} kB"
            else -> "$bytes B"
        }
    }

    private fun normalizedMimeType(raw: String?): String? {
        val value = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
        require(value.length <= MAX_MIME_LENGTH && value.none(Char::isISOControl)) {
            "The DOCX MIME type is invalid"
        }
        val normalized = value.substringBefore(';').trim().lowercase(Locale.ROOT)
        require(normalized == DOCX_MIME_TYPE || normalized in genericMimeTypes) {
            "The selected provider item is not a DOCX document"
        }
        return normalized
    }

    private fun boundedName(raw: String?): String? =
        raw
            ?.takeIf { it.isNotBlank() && it.length <= MAX_RAW_NAME_LENGTH }

    private fun requireGenericNameIsDocx(providerName: String?) {
        val leaf =
            providerName
                ?.substringAfterLast('/')
                ?.substringAfterLast('\\')
                ?.trim()
                ?: return
        val extension = leaf.substringAfterLast('.', missingDelimiterValue = "")
        if (extension.isNotBlank()) {
            require(extension.equals("docx", ignoreCase = true)) {
                "Generic provider metadata does not identify a DOCX document"
            }
        }
    }

    private fun normalizedSize(value: Long?): Long? =
        when {
            value == null || value == -1L -> null
            value >= 0L -> value
            else -> throw IllegalArgumentException("The DOCX size metadata is invalid")
        }

    private fun String.removeDocxSuffix(): String = if (endsWith(".docx", ignoreCase = true)) dropLast(5) else this
}
