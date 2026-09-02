package com.example.pdfmaker

import java.io.IOException

internal enum class CompressLevel(
    val jpegQuality: Int,
    val maxDimensionPx: Int,
    val estimatedReduction: String,
) {
    LOW(jpegQuality = 82, maxDimensionPx = 1_600, estimatedReduction = "20–40%"),
    MEDIUM(jpegQuality = 60, maxDimensionPx = 1_200, estimatedReduction = "40–65%"),
    HIGH(jpegQuality = 35, maxDimensionPx = 800, estimatedReduction = "65–85%"),
}

internal enum class CompressionPhase {
    PICK,
    PREPARING,
    READY,
    COMPRESSING,
    DONE,
}

internal enum class CompressionBackAction {
    CANCEL_OPERATION,
    NAVIGATE_BACK,
}

internal enum class CompressionFailureStage {
    SELECT,
    COMPRESS,
    VERIFY,
    SHARE,
}

internal object CompressionPolicy {
    const val MAX_PAGES = PageEditPolicy.MAX_EDITABLE_PAGES
    const val MAX_ENCODED_PAGE_BYTES = 16L * 1024L * 1024L
    const val MAX_OUTPUT_BYTES = 512L * 1024L * 1024L
    const val MAX_DISPLAY_NAME_LENGTH = 80
    private const val WORK_PROGRESS_MAXIMUM = 90
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
        require(pageCount in 1..MAX_PAGES) {
            "PDF must contain between 1 and $MAX_PAGES pages"
        }
        return pageCount
    }

    fun renderSize(
        pageWidth: Int,
        pageHeight: Int,
        maximumDimension: Int,
    ): PixelSize =
        requireNotNull(
            RenderSizing.fitWithin(
                sourceWidth = pageWidth,
                sourceHeight = pageHeight,
                maxDimension = maximumDimension,
            ),
        ) { "PDF page has invalid dimensions" }

    fun pageProgress(
        pageIndex: Int,
        pageCount: Int,
    ): Int {
        requirePageCount(pageCount)
        require(pageIndex in 0 until pageCount) { "PDF page index is outside the document" }
        return ((pageIndex.toLong() * WORK_PROGRESS_MAXIMUM) / pageCount).toInt()
    }

    fun clampProgress(progress: Int): Int = progress.coerceIn(0, 100)

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

    fun savedPercent(
        originalBytes: Long,
        compressedBytes: Long,
    ): Int? {
        if (originalBytes <= 0L) return null
        require(compressedBytes >= 0L) { "Compressed size cannot be negative" }
        val ratio = compressedBytes.toDouble() / originalBytes.toDouble()
        return ((1.0 - ratio) * 100.0).toInt().coerceIn(0, 100)
    }

    fun backAction(phase: CompressionPhase): CompressionBackAction =
        when (phase) {
            CompressionPhase.PREPARING,
            CompressionPhase.COMPRESSING,
            -> CompressionBackAction.CANCEL_OPERATION

            else -> CompressionBackAction.NAVIGATE_BACK
        }

    fun nextGeneration(current: Long): Long {
        require(current >= 0L) { "Operation generation cannot be negative" }
        return if (current == Long.MAX_VALUE) 1L else current + 1L
    }

    fun failureMessage(
        stage: CompressionFailureStage,
        error: Exception,
    ): String =
        when (stage) {
            CompressionFailureStage.SELECT -> {
                when (error) {
                    is SecurityException -> "Access to this PDF was denied. Re-select it and allow file access."
                    is IOException -> "This PDF could not be read. Check the file and try again."
                    else -> "This file is not a supported PDF. Choose another document."
                }
            }

            CompressionFailureStage.COMPRESS -> {
                when (error) {
                    is SecurityException -> "Access to this PDF was lost. Re-select the file and try again."
                    is IOException -> "The compressed PDF could not be written. Check available storage and try again."
                    else -> "This PDF could not be compressed safely. Try a different file or compression level."
                }
            }

            CompressionFailureStage.VERIFY -> {
                "The compressed PDF could not be verified. The incomplete output was removed."
            }

            CompressionFailureStage.SHARE -> {
                "The compressed PDF is saved, but it could not be shared. Try another compatible app."
            }
        }
}
