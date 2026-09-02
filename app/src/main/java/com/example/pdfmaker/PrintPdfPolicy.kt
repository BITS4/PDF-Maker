package com.example.pdfmaker

import kotlin.math.roundToInt

internal data class PrintPageSpan(
    val first: Int,
    val last: Int,
)

internal data class PrintDestinationRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

internal enum class PrintWriteMode {
    ORIGINAL,
    RENDERED_SELECTION,
}

/** Pure validation and sizing rules for Android print jobs. */
internal object PrintPdfPolicy {
    const val MAX_PAGES = 500
    const val MAX_RENDERED_PAGES = 200
    const val MAX_RENDER_EDGE = 4_096
    const val MAX_RENDER_PIXELS = 12_000_000L
    const val MAX_OUTPUT_BYTES = 512L * 1024L * 1024L
    private const val PDF_POINTS_PER_INCH = 72.0

    fun requirePageCount(pageCount: Int): Int {
        require(pageCount in 1..MAX_PAGES) {
            "PDF must contain between 1 and $MAX_PAGES pages"
        }
        return pageCount
    }

    fun selectedPages(
        pageCount: Int,
        requestedRanges: List<PrintPageSpan>,
    ): List<Int> {
        requirePageCount(pageCount)
        if (requestedRanges.isEmpty()) return emptyList()

        val selected = BooleanArray(pageCount)
        requestedRanges.forEach { range ->
            if (range.first > range.last || range.last < 0 || range.first >= pageCount) {
                return@forEach
            }
            val first = range.first.coerceAtLeast(0)
            val last = range.last.coerceAtMost(pageCount - 1)
            for (page in first..last) selected[page] = true
        }
        return selected.indices.filter(selected::get)
    }

    fun collapsedRanges(pages: List<Int>): List<PrintPageSpan> {
        if (pages.isEmpty()) return emptyList()
        val orderedPages = pages.distinct().sorted()
        require(orderedPages.first() >= 0) { "Printed page indexes cannot be negative" }

        val ranges = mutableListOf<PrintPageSpan>()
        var first = orderedPages.first()
        var last = first
        orderedPages.drop(1).forEach { page ->
            if (page.toLong() == last.toLong() + 1L) {
                last = page
            } else {
                ranges += PrintPageSpan(first, last)
                first = page
                last = page
            }
        }
        ranges += PrintPageSpan(first, last)
        return ranges
    }

    fun requireWritableSelection(
        pageCount: Int,
        selectedPages: List<Int>,
    ): PrintWriteMode {
        requirePageCount(pageCount)
        require(selectedPages.isNotEmpty()) { "Choose at least one page to print" }
        require(selectedPages == selectedPages.distinct().sorted()) {
            "Printed pages must be unique and ordered"
        }
        require(selectedPages.first() >= 0 && selectedPages.last() < pageCount) {
            "Printed pages must belong to this PDF"
        }
        if (selectedPages.size == pageCount) return PrintWriteMode.ORIGINAL
        require(selectedPages.size <= MAX_RENDERED_PAGES) {
            "A partial print job cannot exceed $MAX_RENDERED_PAGES pages"
        }
        return PrintWriteMode.RENDERED_SELECTION
    }

    fun renderSize(
        sourceWidth: Int,
        sourceHeight: Int,
        printableWidthPoints: Int,
        printableHeightPoints: Int,
        horizontalDpi: Int,
        verticalDpi: Int,
    ): PixelSize? {
        val hasValidDimensions =
            hasValidRenderDimensions(
                sourceWidth,
                sourceHeight,
                printableWidthPoints,
                printableHeightPoints,
                horizontalDpi,
                verticalDpi,
            )
        if (!hasValidDimensions) return null

        val effectiveDpi = minOf(horizontalDpi, verticalDpi)
        val printableWidthPixels = printableWidthPoints.toDouble() * effectiveDpi / PDF_POINTS_PER_INCH
        val printableHeightPixels = printableHeightPoints.toDouble() * effectiveDpi / PDF_POINTS_PER_INCH
        val scale =
            minOf(
                printableWidthPixels / sourceWidth.toDouble(),
                printableHeightPixels / sourceHeight.toDouble(),
            )
        if (!scale.isFinite() || scale <= 0.0) return null

        val requestedWidth = (sourceWidth * scale).roundToInt().coerceAtLeast(1)
        val requestedHeight = (sourceHeight * scale).roundToInt().coerceAtLeast(1)
        return ImageInputPolicy
            .fitWithinLimits(
                width = requestedWidth,
                height = requestedHeight,
                maximumEdge = MAX_RENDER_EDGE,
                maximumPixels = MAX_RENDER_PIXELS,
            )?.let { bounded -> PixelSize(bounded.width, bounded.height) }
    }

    fun destinationRect(
        sourceWidth: Int,
        sourceHeight: Int,
        contentLeft: Int,
        contentTop: Int,
        contentRight: Int,
        contentBottom: Int,
    ): PrintDestinationRect? {
        val contentWidth = contentRight - contentLeft
        val contentHeight = contentBottom - contentTop
        if (sourceWidth <= 0 || sourceHeight <= 0) return null
        if (contentWidth <= 0 || contentHeight <= 0) return null

        val scale =
            minOf(
                contentWidth.toDouble() / sourceWidth.toDouble(),
                contentHeight.toDouble() / sourceHeight.toDouble(),
            )
        val width = (sourceWidth * scale).roundToInt().coerceIn(1, contentWidth)
        val height = (sourceHeight * scale).roundToInt().coerceIn(1, contentHeight)
        val left = contentLeft + (contentWidth - width) / 2
        val top = contentTop + (contentHeight - height) / 2
        return PrintDestinationRect(left, top, left + width, top + height)
    }

    private fun hasValidRenderDimensions(
        sourceWidth: Int,
        sourceHeight: Int,
        printableWidthPoints: Int,
        printableHeightPoints: Int,
        horizontalDpi: Int,
        verticalDpi: Int,
    ): Boolean {
        if (sourceWidth <= 0 || sourceHeight <= 0) return false
        if (printableWidthPoints <= 0 || printableHeightPoints <= 0) return false
        return horizontalDpi > 0 && verticalDpi > 0
    }
}
