package com.example.pdfmaker

import android.graphics.Bitmap

internal data class SplitPreviewPlan(
    val pageCount: Int,
    val thumbnailSize: PixelSize,
)

internal data class SplitPdfPreview(
    val pageCount: Int,
    val bitmaps: List<Bitmap>,
)

/** Keeps split previews useful without allowing a document to exhaust the app heap. */
internal object SplitPreviewPolicy {
    const val MAX_PREVIEW_PAGES = PageEditPolicy.MAX_EDITABLE_PAGES
    const val THUMBNAIL_WIDTH_PX = 96
    const val MAX_OUTPUT_BYTES = 512L * 1024L * 1024L

    fun plan(
        pageCount: Int,
        pageWidth: Int,
        pageHeight: Int,
    ): SplitPreviewPlan {
        require(pageCount in 1..MAX_PREVIEW_PAGES) {
            "PDF must contain between 1 and $MAX_PREVIEW_PAGES pages"
        }
        val size =
            RenderSizing.fitWithin(
                sourceWidth = pageWidth,
                sourceHeight = pageHeight,
                maxDimension = THUMBNAIL_WIDTH_PX,
                allowUpscale = true,
            ) ?: throw IllegalArgumentException("PDF page has invalid dimensions")
        return SplitPreviewPlan(pageCount, size)
    }
}
