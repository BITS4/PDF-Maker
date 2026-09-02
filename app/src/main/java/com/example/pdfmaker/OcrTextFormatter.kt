package com.example.pdfmaker

object OcrTextFormatter {
    fun format(pages: List<Pair<Int, String>>): String {
        require(pages.size <= OcrResourcePolicy.MAX_PDF_PAGES) { "There are too many OCR pages to format" }
        val output = StringBuilder()
        pages.forEachIndexed { index, (page, text) ->
            require(page in 1..OcrResourcePolicy.MAX_PDF_PAGES) { "OCR page number is invalid" }
            require(text.length <= OcrResourcePolicy.MAX_TEXT_CHARACTERS_PER_PAGE) {
                "A page contains too much recognized text"
            }
            val separator = if (index == 0) "" else "\n\n"
            val pageText = "$separator=== Page $page ===\n$text"
            OcrResourcePolicy.requireFormattedLength(output.length, pageText.length)
            output.append(pageText)
        }
        return output.toString()
    }
}
