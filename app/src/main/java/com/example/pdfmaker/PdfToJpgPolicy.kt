package com.example.pdfmaker

internal data class PdfToJpgResultStatus(
    val savedToGallery: Boolean,
    val savingToGallery: Boolean,
    val galleryMessage: String?,
    val sharing: Boolean,
    val shareMessage: String?,
)

internal enum class PdfToJpgFailureStage {
    LOAD,
    CONVERT,
    SHARE_PREPARE,
    SHARE_LAUNCH,
}

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
    const val MAX_DISPLAY_NAME_LENGTH = 80
    private val unsafeDirectionalCharacters =
        setOf(
            '\u061C',
            '\u200E',
            '\u200F',
            '\u202A',
            '\u202B',
            '\u202C',
            '\u202D',
            '\u202E',
            '\u2066',
            '\u2067',
            '\u2068',
            '\u2069',
        )

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

    fun displayBaseName(pathSegment: String?): String {
        val plainLeaf = pathSegment.orEmpty().substringAfterLast('/').substringAfterLast('\\')
        val encodedSeparator = plainLeaf.lastIndexOf("%2f", ignoreCase = true)
        val encodedLeaf = if (encodedSeparator >= 0) plainLeaf.substring(encodedSeparator + 3) else plainLeaf
        val withoutExtension =
            if (encodedLeaf.endsWith(".pdf", ignoreCase = true)) encodedLeaf.dropLast(4) else encodedLeaf
        val sanitized =
            withoutExtension
                .filterNot { character ->
                    character.isISOControl() || character in unsafeDirectionalCharacters
                }.trim()
                .take(MAX_DISPLAY_NAME_LENGTH)
        return sanitized.ifBlank { "document" }
    }

    fun failureMessage(
        stage: PdfToJpgFailureStage,
        error: Throwable,
    ): String =
        when (stage) {
            PdfToJpgFailureStage.LOAD ->
                when (error) {
                    is SecurityException -> "Access to this PDF was denied. Re-select it and allow file access."
                    is java.io.IOException -> "This PDF could not be read. Check the file and try again."
                    else -> "This file is not a supported PDF. Choose another document."
                }

            PdfToJpgFailureStage.CONVERT ->
                when (error) {
                    is SecurityException -> "Access to this PDF was lost. Re-select it and try again."
                    is java.io.IOException -> "The JPG files could not be written. Check available storage and try again."
                    else -> "This PDF could not be converted safely. Try another file or quality setting."
                }

            PdfToJpgFailureStage.SHARE_PREPARE,
            PdfToJpgFailureStage.SHARE_LAUNCH,
            -> {
                shareFailureMessage(stage)
            }
        }

    fun shareFailureMessage(stage: PdfToJpgFailureStage): String {
        require(stage == PdfToJpgFailureStage.SHARE_PREPARE || stage == PdfToJpgFailureStage.SHARE_LAUNCH) {
            "Only share failures have a fixed share message"
        }
        return if (stage == PdfToJpgFailureStage.SHARE_PREPARE) {
            "The images are saved, but a secure share package could not be prepared."
        } else {
            "The images are saved, but no compatible sharing app could be opened."
        }
    }
}
