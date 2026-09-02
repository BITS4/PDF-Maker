package com.example.pdfmaker

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

private val DISPLAY_SIZE_PATTERN = Regex("^([0-9]+(?:[.,][0-9]+)?)\\s*(B|KB|MB|GB|TB)?$", RegexOption.IGNORE_CASE)

internal object FileCatalog {
    fun extension(path: String): String {
        val withoutFragment = path.substringBefore('#').substringBefore('?')
        return withoutFragment.substringAfterLast('.', "").lowercase(Locale.ROOT)
    }

    fun parseDisplaySizeBytes(value: String): Long {
        val match = DISPLAY_SIZE_PATTERN.matchEntire(value.trim()) ?: return 0L
        val amount = match.groupValues[1].replace(',', '.').toBigDecimalOrNull() ?: return 0L
        val multiplier =
            when (match.groupValues[2].uppercase(Locale.ROOT)) {
                "", "B" -> BigDecimal.ONE
                "KB" -> BigDecimal(1_024L)
                "MB" -> BigDecimal(1_024L * 1_024L)
                "GB" -> BigDecimal(1_024L * 1_024L * 1_024L)
                "TB" -> BigDecimal(1_024L).pow(4)
                else -> return 0L
            }
        val bytes = amount.multiply(multiplier).setScale(0, RoundingMode.DOWN)
        if (bytes > BigDecimal.valueOf(Long.MAX_VALUE)) return Long.MAX_VALUE
        return bytes.toLong().coerceAtLeast(0L)
    }

    fun filter(
        files: List<PdfFile>,
        filter: FileTypeFilter,
    ): List<PdfFile> {
        if (filter == FileTypeFilter.ALL) return files
        return files.filter { file -> extension(file.filePath) in filter.extensions }
    }

    fun sort(
        files: List<PdfFile>,
        order: SortOrder,
    ): List<PdfFile> =
        when (order) {
            SortOrder.DATE_DESC -> files.sortedByDescending { it.lastModified }
            SortOrder.DATE_ASC -> files.sortedBy { it.lastModified }
            SortOrder.NAME_ASC -> files.sortedBy { it.name.lowercase(Locale.ROOT) }
            SortOrder.NAME_DESC -> files.sortedByDescending { it.name.lowercase(Locale.ROOT) }
            SortOrder.SIZE_DESC -> files.sortedByDescending { parseDisplaySizeBytes(it.size) }
            SortOrder.SIZE_ASC -> files.sortedBy { parseDisplaySizeBytes(it.size) }
        }
}
