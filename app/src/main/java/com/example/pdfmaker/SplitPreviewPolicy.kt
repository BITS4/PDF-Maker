package com.example.pdfmaker

internal data class SplitPreviewPlan(
    val pageCount: Int,
    val thumbnailSize: PixelSize,
)

/** Keeps split previews useful without allowing a document to exhaust the app heap. */
internal object SplitPreviewPolicy {
    const val MAX_PREVIEW_PAGES = PageEditPolicy.MAX_EDITABLE_PAGES
    const val THUMBNAIL_WIDTH_PX = 96

    fun plan(pageCount: Int, pageWidth: Int, pageHeight: Int): SplitPreviewPlan {
        require(pageCount in 1..MAX_PREVIEW_PAGES) {
            "PDF must contain between 1 and $MAX_PREVIEW_PAGES pages"
        }
        val size = RenderSizing.fitWithin(
            sourceWidth = pageWidth,
            sourceHeight = pageHeight,
            maxDimension = THUMBNAIL_WIDTH_PX,
            allowUpscale = true,
        ) ?: throw IllegalArgumentException("PDF page has invalid dimensions")
        return SplitPreviewPlan(pageCount, size)
    }
}
