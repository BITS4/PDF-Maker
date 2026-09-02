package com.example.pdfmaker

internal data class MergeInputBatch(
    val items: List<MergeItem>,
    val rejectionReasons: List<String>,
)

internal object MergePdfPolicy {
    const val MAX_SOURCE_FILES = 20
    const val MAX_PAGES_PER_SOURCE = 500
    const val MAX_TOTAL_PAGES = 1_000
    const val MAX_OUTPUT_BYTES = 512L * 1024L * 1024L

    fun requireSourceCount(sourceCount: Int): Int {
        require(sourceCount in 1..MAX_SOURCE_FILES) {
            "Choose between 1 and $MAX_SOURCE_FILES PDF files"
        }
        return sourceCount
    }

    fun updatedTotalPages(
        currentTotal: Int,
        sourcePages: Int,
    ): Int {
        require(sourcePages in 1..MAX_PAGES_PER_SOURCE) {
            "A source PDF must contain between 1 and $MAX_PAGES_PER_SOURCE pages"
        }
        require(currentTotal in 0..MAX_TOTAL_PAGES) { "The current page total is invalid" }
        require(sourcePages <= MAX_TOTAL_PAGES - currentTotal) {
            "Merged PDF exceeds $MAX_TOTAL_PAGES pages"
        }
        return currentTotal + sourcePages
    }
}
