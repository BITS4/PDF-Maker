package com.example.pdfmaker

import java.io.File

internal object ThumbnailCachePolicy {
    const val MAX_ENTRIES = 48
    const val MAX_PIXEL_WEIGHT = 24L * 1024L * 1024L

    fun key(
        filePath: String,
        sizePx: Int,
    ): ThumbnailCacheKey {
        val file = File(filePath)
        val canonicalFile = runCatching { file.canonicalFile }.getOrElse { file.absoluteFile }
        return ThumbnailCacheKey(
            canonicalPath = canonicalFile.path,
            requestedSizePx = sizePx,
            sourceBytes = canonicalFile.length(),
            lastModifiedMillis = canonicalFile.lastModified(),
        )
    }

    fun pixelWeight(
        width: Int,
        height: Int,
    ): Long {
        require(width > 0 && height > 0) { "Thumbnail dimensions must be positive" }
        return Math.multiplyExact(width.toLong(), height.toLong())
    }
}

internal data class ThumbnailCacheKey(
    val canonicalPath: String,
    val requestedSizePx: Int,
    val sourceBytes: Long,
    val lastModifiedMillis: Long,
)
