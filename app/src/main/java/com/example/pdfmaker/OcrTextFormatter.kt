package com.example.pdfmaker

object OcrTextFormatter {
    fun format(pages: List<Pair<Int, String>>): String =
        pages.joinToString("\n\n") { (page, text) -> "=== Page $page ===\n$text" }
}
