package com.example.pdfmaker

internal data class PdfToJpgResultStatus(
    val savedToGallery: Boolean,
    val savingToGallery: Boolean,
    val galleryMessage: String?,
    val sharing: Boolean,
    val shareMessage: String?,
)

/** Resource and validation policy shared by PDF-to-JPG preview, export, gallery, and sharing. */
internal object PdfToJpgPolicy {
    const val MAX_EXPORT_PAGES = 200
    const val MAX_RENDER_EDGE = 3_000
    const val MAX_RENDER_PIXELS = 4_000_000L
    const val MAX_JPEG_BYTES = 50L * 1024L * 1024L
    const val MAX_EXPORT_BYTES = 300L * 1024L * 1024L
    const val PREVIEW_COUNT = 6
    const val PREVIEW_EDGE = 400
    const val RESULT_THUMBNAIL_EDGE = 320
    const val RESULT_THUMBNAIL_PIXELS = 160_000L

    fun requirePageCount(pageCount: Int): Int {
        require(pageCount in 1..MAX_EXPORT_PAGES) {
            "PDF must contain between 1 and $MAX_EXPORT_PAGES pages"
        }
        return pageCount
    }

    fun renderSize(
        pageWidth: Int,
        pageHeight: Int,
        requestedMaxEdge: Int,
    ): PixelSize? {
        if (requestedMaxEdge <= 0) return null
        val edgeLimit = requestedMaxEdge.coerceAtMost(MAX_RENDER_EDGE)
        val edgeBounded = RenderSizing.fitWithin(pageWidth, pageHeight, edgeLimit) ?: return null
        val fullyBounded = ImageInputPolicy.fitWithinLimits(
            width = edgeBounded.width,
            height = edgeBounded.height,
            maximumEdge = edgeLimit,
            maximumPixels = MAX_RENDER_PIXELS,
        ) ?: return null
        return PixelSize(fullyBounded.width, fullyBounded.height)
    }

    fun resultThumbnailPlan(width: Int, height: Int): ImageDecodePlan? =
        ImageInputPolicy.decodePlan(
            width = width,
            height = height,
            maximumEdge = RESULT_THUMBNAIL_EDGE,
            maximumPixels = RESULT_THUMBNAIL_PIXELS,
        )

    fun recordExportedFile(currentBytes: Long, fileBytes: Long): Long {
        require(currentBytes in 0..MAX_EXPORT_BYTES) { "Export size is invalid" }
        require(fileBytes in 1..MAX_JPEG_BYTES) { "A converted image exceeds the 50 MB limit" }
        val updated = Math.addExact(currentBytes, fileBytes)
        require(updated <= MAX_EXPORT_BYTES) { "Converted images exceed the 300 MB export limit" }
        return updated
    }

    fun requireShareBatch(fileSizes: List<Long>): Long {
        require(fileSizes.size in 1..MAX_EXPORT_PAGES) { "There are too many images to share" }
        return fileSizes.fold(0L, ::recordExportedFile)
    }
}
