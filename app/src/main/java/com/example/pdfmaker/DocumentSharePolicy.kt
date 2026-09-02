package com.example.pdfmaker

import java.util.Locale

internal enum class DocumentSendMode {
    SINGLE,
    MULTIPLE,
}

internal data class DocumentSharePlan(
    val mode: DocumentSendMode,
    val mimeType: String,
)

/** Pure validation and MIME-selection policy for outbound document intents. */
internal object DocumentSharePolicy {
    const val MAX_SHARED_FILES = 200
    const val FALLBACK_MIME = "application/octet-stream"
    private val mimeToken = Regex("[a-z0-9!#$&^_.+-]+")

    private val registeredMimeTypes =
        mapOf(
            "pdf" to "application/pdf",
            "doc" to "application/msword",
            "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "xls" to "application/vnd.ms-excel",
            "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "ppt" to "application/vnd.ms-powerpoint",
            "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "csv" to "text/csv",
            "tsv" to "text/tab-separated-values",
            "txt" to "text/plain",
            "md" to "text/markdown",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "png" to "image/png",
            "gif" to "image/gif",
            "webp" to "image/webp",
            "bmp" to "image/bmp",
            "zip" to "application/zip",
        )

    fun mimeType(fileName: String): String = registeredMimeTypes[extension(fileName)] ?: FALLBACK_MIME

    fun sharePlan(
        fileNames: List<String>,
        requestedMimeType: String? = null,
    ): DocumentSharePlan {
        require(fileNames.size in 1..MAX_SHARED_FILES) {
            "A share request must contain between 1 and $MAX_SHARED_FILES files"
        }
        val mimeType =
            if (requestedMimeType == null) {
                commonMimeType(fileNames.map(::mimeType))
            } else {
                requireMimeType(requestedMimeType)
            }
        return DocumentSharePlan(
            mode = if (fileNames.size == 1) DocumentSendMode.SINGLE else DocumentSendMode.MULTIPLE,
            mimeType = mimeType,
        )
    }

    fun requireMimeType(value: String): String {
        val normalized = value.trim().lowercase(Locale.ROOT)
        val parts = normalized.split('/')
        require(parts.size == 2 && parts.all { part -> part.matches(mimeToken) }) {
            "The requested MIME type is invalid"
        }
        return normalized
    }

    private fun commonMimeType(mimeTypes: List<String>): String {
        val distinctTypes = mimeTypes.toSet()
        return distinctTypes.singleOrNull() ?: FALLBACK_MIME
    }

    private fun extension(fileName: String): String =
        fileName
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .substringAfterLast('.', missingDelimiterValue = "")
            .lowercase(Locale.ROOT)
}
