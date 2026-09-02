package com.example.pdfmaker

internal object CompressionPolicy {
    const val MAX_PAGES = 200
    const val MAX_ENCODED_PAGE_BYTES = 16L * 1024L * 1024L
    const val MAX_OUTPUT_BYTES = 512L * 1024L * 1024L

    fun requirePageCount(pageCount: Int): Int {
        require(pageCount in 1..MAX_PAGES) {
            "PDF must contain between 1 and $MAX_PAGES pages"
        }
        return pageCount
    }

    fun renderSize(pageWidth: Int, pageHeight: Int, maximumDimension: Int): PixelSize =
        requireNotNull(
            RenderSizing.fitWithin(
                sourceWidth = pageWidth,
                sourceHeight = pageHeight,
                maxDimension = maximumDimension,
            ),
        ) { "PDF page has invalid dimensions" }
}
