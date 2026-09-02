package com.example.pdfmaker

internal fun delimiterForFileName(fileName: String): Char = if (fileName.substringAfterLast('.', "").equals("tsv", ignoreCase = true)) '\t' else ','

internal fun parseDelimitedRows(
    text: String,
    delimiter: Char,
    maximumRows: Int = ViewerResourceLimits.MAX_TABLE_ROWS,
    maximumColumns: Int = ViewerResourceLimits.MAX_TABLE_COLUMNS,
    maximumCellCharacters: Int = ViewerResourceLimits.MAX_CELL_CHARACTERS,
): List<List<String>> {
    require(text.length <= ViewerResourceLimits.MAX_TEXT_BYTES) { "Delimited preview exceeds its text limit" }
    require(maximumRows > 0 && maximumColumns > 0 && maximumCellCharacters > 0) {
        "Delimited preview limits must be positive"
    }
    if (text.isEmpty()) return emptyList()

    val accumulator = DelimitedRowAccumulator(maximumRows, maximumColumns, maximumCellCharacters)
    var index = 0

    while (index < text.length && accumulator.hasRowCapacity) {
        index += accumulator.consume(text, index, delimiter)
    }
    return accumulator.complete()
}

private class DelimitedRowAccumulator(
    private val maximumRows: Int,
    private val maximumColumns: Int,
    private val maximumCellCharacters: Int,
) {
    private val rows = mutableListOf<List<String>>()
    private var row = mutableListOf<String>()
    private val cell = StringBuilder()
    private var inQuotes = false

    val hasRowCapacity: Boolean
        get() = rows.size < maximumRows

    fun consume(
        text: String,
        index: Int,
        delimiter: Char,
    ): Int {
        val character = text[index]
        if (character == '"' && inQuotes && text.getOrNull(index + 1) == '"') {
            append('"')
            return 2
        }

        return when {
            character == '"' -> {
                inQuotes = !inQuotes
                1
            }

            character == delimiter && !inQuotes -> {
                finishCell()
                1
            }

            (character == '\r' || character == '\n') && !inQuotes -> {
                finishRow()
                if (character == '\r' && text.getOrNull(index + 1) == '\n') 2 else 1
            }

            else -> {
                append(character)
                1
            }
        }
    }

    fun complete(): List<List<String>> {
        if (hasRowCapacity && (cell.isNotEmpty() || row.isNotEmpty())) finishRow()
        return rows
    }

    private fun append(character: Char) {
        if (cell.length < maximumCellCharacters) cell.append(character)
    }

    private fun finishCell() {
        if (row.size < maximumColumns) row += cell.toString()
        cell.clear()
    }

    private fun finishRow() {
        finishCell()
        if (hasRowCapacity) rows += row
        row = mutableListOf()
    }
}
