package com.example.pdfmaker

internal fun delimiterForFileName(fileName: String): Char = if (fileName.substringAfterLast('.', "").equals("tsv", ignoreCase = true)) '\t' else ','

internal fun parseDelimitedRows(
    text: String,
    delimiter: Char,
    maximumRows: Int = MAX_VIEWER_TABLE_ROWS,
    maximumColumns: Int = MAX_VIEWER_TABLE_COLUMNS,
    maximumCellCharacters: Int = MAX_VIEWER_CELL_CHARACTERS,
): List<List<String>> {
    require(text.length <= MAX_VIEWER_TEXT_BYTES) { "Delimited preview exceeds its text limit" }
    require(maximumRows > 0 && maximumColumns > 0 && maximumCellCharacters > 0) {
        "Delimited preview limits must be positive"
    }
    if (text.isEmpty()) return emptyList()

    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val cell = StringBuilder()
    var inQuotes = false
    var index = 0

    fun finishCell() {
        if (row.size < maximumColumns) row += cell.toString()
        cell.clear()
    }

    fun finishRow() {
        finishCell()
        if (rows.size < maximumRows) rows += row
        row = mutableListOf()
    }

    while (index < text.length && rows.size < maximumRows) {
        val char = text[index]
        when {
            char == '"' && inQuotes && text.getOrNull(index + 1) == '"' -> {
                if (cell.length < maximumCellCharacters) cell.append('"')
                index += 1
            }
            char == '"' -> inQuotes = !inQuotes
            char == delimiter && !inQuotes -> finishCell()
            (char == '\r' || char == '\n') && !inQuotes -> {
                finishRow()
                if (char == '\r' && text.getOrNull(index + 1) == '\n') index += 1
            }
            else -> if (cell.length < maximumCellCharacters) cell.append(char)
        }
        index += 1
    }

    if (rows.size < maximumRows && (cell.isNotEmpty() || row.isNotEmpty())) finishRow()
    return rows
}
