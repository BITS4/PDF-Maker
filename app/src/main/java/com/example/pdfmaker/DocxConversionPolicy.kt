package com.example.pdfmaker

import java.io.File

internal data class DocxPdfResult(
    val file: File,
    val pageCount: Int,
)

internal object DocxConversionPolicy {
    const val MAX_BLOCKS = 10_000
    const val MAX_RUNS_PER_PARAGRAPH = 2_000
    const val MAX_TEXT_CHARACTERS = 5_000_000
    const val MAX_RUN_CHARACTERS = 250_000
    const val MAX_RELATIONSHIPS = 4_096
    const val MAX_MEDIA_ITEMS = 1_000
    const val MAX_PAGES = 500
    const val MAX_OUTPUT_BYTES = 512L * 1024L * 1024L

    fun requireCanAdd(
        currentCount: Int,
        maximum: Int,
        label: String,
    ) {
        require(maximum > 0 && currentCount in 0 until maximum) {
            "DOCX contains too many $label"
        }
    }

    fun requireCanAppend(
        currentLength: Int,
        addedLength: Int,
        maximum: Int,
        label: String,
    ) {
        require(maximum > 0 && currentLength >= 0 && addedLength >= 0) {
            "DOCX $label budget is invalid"
        }
        require(addedLength <= maximum - currentLength) { "DOCX $label exceeds its limit" }
    }

    fun nextPage(currentPage: Int): Int {
        require(currentPage in 1 until MAX_PAGES) { "DOCX exceeds $MAX_PAGES rendered pages" }
        return currentPage + 1
    }
}
