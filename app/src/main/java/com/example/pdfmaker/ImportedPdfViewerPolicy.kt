package com.example.pdfmaker

import java.io.IOException

internal enum class ImportedViewerBackAction {
    CANCEL_OPERATION,
    DISCARD_DOODLE,
    DISCARD_OVERLAYS,
    CLOSE_SIGNATURE_PAD,
    CLOSE_EDITOR,
    CLOSE_CONVERT,
    NAVIGATE_BACK,
}

/** Pure navigation, naming, and user-facing failure policy for imported PDFs. */
internal object ImportedPdfViewerPolicy {
    const val MAX_TITLE_LENGTH = 120

    fun backAction(
        editMode: PdfEditMode,
        showConvert: Boolean,
        operationTarget: ConvertTarget,
    ): ImportedViewerBackAction =
        when {
            operationTarget != ConvertTarget.NONE -> ImportedViewerBackAction.CANCEL_OPERATION
            editMode == PdfEditMode.DOODLE -> ImportedViewerBackAction.DISCARD_DOODLE
            editMode == PdfEditMode.TEXT -> ImportedViewerBackAction.DISCARD_OVERLAYS
            editMode == PdfEditMode.SIGNATURE -> ImportedViewerBackAction.CLOSE_SIGNATURE_PAD
            editMode == PdfEditMode.EDIT_PICKER -> ImportedViewerBackAction.CLOSE_EDITOR
            showConvert -> ImportedViewerBackAction.CLOSE_CONVERT
            else -> ImportedViewerBackAction.NAVIGATE_BACK
        }

    fun documentTitle(lastPathSegment: String?): String {
        val path = lastPathSegment.orEmpty()
        val plainLeaf = path.substringAfterLast('/')
        val encodedSeparator = plainLeaf.lastIndexOf("%2f", ignoreCase = true)
        val encodedLeaf = if (encodedSeparator >= 0) plainLeaf.substring(encodedSeparator + 3) else plainLeaf
        val withoutExtension =
            if (encodedLeaf.endsWith(".pdf", ignoreCase = true)) encodedLeaf.dropLast(4) else encodedLeaf
        val sanitized = withoutExtension.filterNot(Char::isISOControl).trim().take(MAX_TITLE_LENGTH)
        return sanitized.ifBlank { "Document" }
    }

    fun loadFailureMessage(error: Exception): String =
        when (error) {
            is SecurityException -> "Access to this PDF was denied. Re-select it and allow file access."
            is IOException -> "This PDF could not be read. Check the file and available storage, then try again."
            is IllegalArgumentException -> "This PDF is invalid, encrypted, too large, or has too many pages."
            is IllegalStateException -> "This PDF could not be prepared safely. Free some storage and try again."
            else -> "The PDF could not be opened safely. Try another copy of the document."
        }
}
