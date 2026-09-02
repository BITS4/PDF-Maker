package com.example.pdfmaker

internal sealed interface PageSelection {
    data object All : PageSelection

    data class Range(val first: Int, val last: Int) : PageSelection

    data class Custom(val pages: Collection<Int>) : PageSelection
}

internal object PageSelectionPolicy {
    fun resolve(totalPages: Int, selection: PageSelection): List<Int> {
        if (totalPages <= 0) return emptyList()
        return when (selection) {
            PageSelection.All -> (1..totalPages).toList()
            is PageSelection.Range -> {
                val first = selection.first.coerceAtLeast(1)
                val last = selection.last.coerceAtMost(totalPages)
                if (first > last) emptyList() else (first..last).toList()
            }
            is PageSelection.Custom -> selection.pages
                .asSequence()
                .filter { it in 1..totalPages }
                .distinct()
                .sorted()
                .toList()
        }
    }

    fun contiguousRange(totalPages: Int, selection: PageSelection): IntRange? {
        val pages = resolve(totalPages, selection)
        if (pages.isEmpty()) return null
        if (pages.last() - pages.first() + 1 != pages.size) return null
        return pages.first()..pages.last()
    }
}
