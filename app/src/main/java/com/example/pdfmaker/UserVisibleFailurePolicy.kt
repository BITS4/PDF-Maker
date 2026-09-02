package com.example.pdfmaker

import timber.log.Timber
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.util.concurrent.CancellationException

internal enum class UserFailureStage(
    val operationPhrase: String,
    val eventCode: String,
) {
    DOCX_CONVERSION("convert the Word document", "docx_conversion"),
    CONVERTED_PDF_SHARE("share the converted PDF", "converted_pdf_share"),
    PAGE_PREVIEW_LOAD("load the PDF pages", "page_preview_load"),
    PDF_EDITOR_OPERATION("complete the document edit", "pdf_editor_operation"),
    SPLIT_PREVIEW_LOAD("load the PDF split preview", "split_preview_load"),
    PDF_SPLIT("split the selected PDF", "pdf_split"),
    MERGE_INPUT_LOAD("load a selected PDF for merging", "merge_input_load"),
    PDF_MERGE("merge the selected PDFs", "pdf_merge"),
    OCR("recognize text in the selected document", "ocr"),
    DOCUMENT_IMPORT("import the selected document", "document_import"),
    DOCUMENT_LOCK("lock the document", "document_lock"),
    DOCUMENT_UNLOCK("unlock the document", "document_unlock"),
    PDF_PRINT("print the selected PDF", "pdf_print"),
}

internal enum class UserFailureKind(
    val eventCode: String,
) {
    ACCESS_DENIED("access_denied"),
    INPUT_OUTPUT("input_output"),
    INVALID_INPUT("invalid_input"),
    UNAVAILABLE("unavailable"),
    SECURE_PROCESSING("secure_processing"),
    UNSUPPORTED("unsupported"),
    UNEXPECTED("unexpected"),
}

/** Converts failures into fixed, stage-specific UI copy without exposing exception content. */
internal object UserVisibleFailurePolicy {
    fun message(
        stage: UserFailureStage,
        error: Throwable,
    ): String {
        if (error is CancellationException) throw error
        return when (classify(error)) {
            UserFailureKind.ACCESS_DENIED -> {
                "Permission was denied while trying to ${stage.operationPhrase}."
            }

            UserFailureKind.INPUT_OUTPUT -> {
                "Storage or provider access failed while trying to ${stage.operationPhrase}."
            }

            UserFailureKind.INVALID_INPUT -> {
                "The app could not ${stage.operationPhrase} because the input is invalid or unsupported."
            }

            UserFailureKind.UNAVAILABLE -> {
                "The app could not ${stage.operationPhrase} because the operation is unavailable."
            }

            UserFailureKind.SECURE_PROCESSING -> {
                "Secure processing failed while trying to ${stage.operationPhrase}."
            }

            UserFailureKind.UNSUPPORTED -> {
                "This device does not support the requested operation to ${stage.operationPhrase}."
            }

            UserFailureKind.UNEXPECTED -> {
                "The app could not ${stage.operationPhrase} safely."
            }
        }
    }

    fun classify(error: Throwable): UserFailureKind =
        when (error) {
            is SecurityException -> UserFailureKind.ACCESS_DENIED

            is IOException -> UserFailureKind.INPUT_OUTPUT

            is IllegalArgumentException -> UserFailureKind.INVALID_INPUT

            is IllegalStateException -> UserFailureKind.UNAVAILABLE

            is GeneralSecurityException,
            is ProviderException,
            -> UserFailureKind.SECURE_PROCESSING

            is UnsupportedOperationException -> UserFailureKind.UNSUPPORTED

            else -> UserFailureKind.UNEXPECTED
        }
}

/** Logs only fixed event/category values and returns the policy-approved UI copy. */
internal object UserVisibleFailureReporter {
    fun message(
        stage: UserFailureStage,
        error: Throwable,
    ): String {
        val safeMessage = UserVisibleFailurePolicy.message(stage, error)
        Timber
            .tag("UserFailure")
            .w(
                ObservabilityPolicy.sanitizedThrowable(error),
                "event=user_visible_document_failure stage=%s category=%s",
                stage.eventCode,
                UserVisibleFailurePolicy.classify(error).eventCode,
            )
        return safeMessage
    }
}
