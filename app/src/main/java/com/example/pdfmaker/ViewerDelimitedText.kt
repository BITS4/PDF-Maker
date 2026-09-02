package com.example.pdfmaker

internal fun delimiterForFileName(fileName: String): Char = if (fileName.substringAfterLast('.', "").equals("tsv", ignoreCase = true)) '\t' else ','

internal fun parseDelimitedRows(
    text: String,
    delimiter: Char,
): List<List<String>> {
    if (text.isEmpty()) return emptyList()

    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val cell = StringBuilder()
    var inQuotes = false
    var index = 0

    fun finishCell() {
        row += cell.toString()
        cell.clear()
    }

    fun finishRow() {
        finishCell()
        rows += row
        row = mutableListOf()
    }

    while (index < text.length) {
        val char = text[index]
        when {
            char == '"' && inQuotes && text.getOrNull(index + 1) == '"' -> {
                cell.append('"')
                index += 1
            }
            char == '"' -> inQuotes = !inQuotes
            char == delimiter && !inQuotes -> finishCell()
            (char == '\r' || char == '\n') && !inQuotes -> {
                finishRow()
                if (char == '\r' && text.getOrNull(index + 1) == '\n') index += 1
            }
            else -> cell.append(char)
        }
        index += 1
    }

    if (cell.isNotEmpty() || row.isNotEmpty()) finishRow()
    return rows
}
