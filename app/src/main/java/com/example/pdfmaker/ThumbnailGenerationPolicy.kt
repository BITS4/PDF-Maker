package com.example.pdfmaker

import java.util.Locale

internal enum class ThumbnailSourceKind {
    PDF,
    IMAGE,
    WORD_PROCESSING,
    PRESENTATION,
    SPREADSHEET,
    DELIMITED_TEXT,
    PLAIN_TEXT,
    UNSUPPORTED,
}

/** Pure request and text limits shared by all thumbnail renderers. */
internal object ThumbnailGenerationPolicy {
    const val DEFAULT_SIZE_PX = 200
    const val MAX_SIZE_PX = 2_048
    const val MAX_PREVIEW_ROWS = 8
    const val MAX_PREVIEW_COLUMNS = 6
    const val MAX_TEXT_LINES = 12
    const val MAX_CELL_CHARACTERS = 120
    const val MAX_PARAGRAPH_CHARACTERS = 320

    fun classify(filePath: String): ThumbnailSourceKind =
        when (filePath.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "pdf" -> ThumbnailSourceKind.PDF
            "jpg", "jpeg", "png", "webp", "bmp", "gif" -> ThumbnailSourceKind.IMAGE
            "docx" -> ThumbnailSourceKind.WORD_PROCESSING
            "pptx" -> ThumbnailSourceKind.PRESENTATION
            "xlsx" -> ThumbnailSourceKind.SPREADSHEET
            "csv", "tsv" -> ThumbnailSourceKind.DELIMITED_TEXT
            "txt", "md", "log" -> ThumbnailSourceKind.PLAIN_TEXT
            else -> ThumbnailSourceKind.UNSUPPORTED
        }

    fun requireValidSize(sizePx: Int) {
        require(sizePx in 1..MAX_SIZE_PX) { "Thumbnail size is outside the supported range" }
    }

    fun delimiter(filePath: String): Char = if (filePath.substringAfterLast('.', "").equals("tsv", ignoreCase = true)) '\t' else ','

    fun displayText(
        value: String,
        maximumCharacters: Int,
    ): String {
        require(maximumCharacters > 1) { "Text preview limit must leave room for content" }
        return if (value.length <= maximumCharacters) value else value.take(maximumCharacters - 1) + "…"
    }

    fun delimitedRows(
        content: String,
        delimiter: Char,
        maximumRows: Int = MAX_PREVIEW_ROWS,
        maximumColumns: Int = MAX_PREVIEW_COLUMNS,
        maximumCellCharacters: Int = MAX_CELL_CHARACTERS,
    ): List<List<String>> {
        require(maximumRows > 0 && maximumColumns > 0 && maximumCellCharacters > 0) {
            "Delimited preview limits must be positive"
        }
        return content
            .lineSequence()
            .take(maximumRows)
            .map { line ->
                line
                    .splitToSequence(delimiter)
                    .take(maximumColumns)
                    .map { cell -> cell.take(maximumCellCharacters) }
                    .toList()
            }.toList()
    }

    fun visibleTextLines(
        content: String,
        maximumLines: Int = MAX_TEXT_LINES,
        maximumCharacters: Int = MAX_PARAGRAPH_CHARACTERS,
    ): List<String> {
        require(maximumLines > 0 && maximumCharacters > 0) { "Text preview limits must be positive" }
        return content
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .take(maximumLines)
            .map { line -> line.take(maximumCharacters) }
            .toList()
    }
}
