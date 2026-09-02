package com.example.pdfmaker

/** Defines the bounded working set used by the interactive PDF editor. */
internal object PageBitmapCachePolicy {
    const val NEIGHBOR_RADIUS = 1

    fun retainedIndexes(
        currentPage: Int,
        pageCount: Int,
        radius: Int = NEIGHBOR_RADIUS,
    ): Set<Int> {
        require(radius >= 0) { "Page cache radius cannot be negative" }
        if (pageCount <= 0) return emptySet()
        val current = currentPage.coerceIn(0, pageCount - 1)
        val first = (current - radius).coerceAtLeast(0)
        val last = (current + radius).coerceAtMost(pageCount - 1)
        return (first..last).toCollection(linkedSetOf())
    }
}
