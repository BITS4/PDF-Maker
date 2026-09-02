package com.example.pdfmaker

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.Closeable
import java.io.File
import java.util.Locale

internal data class ImportedImageMetadata(
    val mimeType: String?,
    val width: Int,
    val height: Int,
)

internal interface ImportedDecodedImage : Closeable {
    val width: Int
    val height: Int
}

internal interface ImportedImageDecoder {
    fun readBounds(file: File): ImportedImageMetadata?

    fun decodeSample(file: File, sampleSize: Int): ImportedDecodedImage?
}

/** Performs a bounded real decode before an untrusted image is accepted as an import. */
internal object ImportedImageValidator {
    private const val MAX_SOURCE_PIXELS = 16_000_000L

    fun validate(
        file: File,
        expectedKind: IncomingDocumentKind,
        beforeChunk: () -> Unit = {},
        decoder: ImportedImageDecoder = AndroidImportedImageDecoder,
    ): Boolean {
        if (
            expectedKind !in imageKinds ||
            !file.isFile ||
            !hasSupportedEncodedSize(file.length())
        ) {
            return false
        }

        beforeChunk()
        val bounds = decoder.readBounds(file) ?: return false
        beforeChunk()
        if (
            !mimeMatches(expectedKind, bounds.mimeType) ||
            !hasSafeSourceDimensions(bounds.width, bounds.height)
        ) {
            return false
        }
        val plan = ImageInputPolicy.decodePlan(bounds.width, bounds.height) ?: return false

        beforeChunk()
        return decoder.decodeSample(file, plan.sampleSize)?.use { decoded ->
            beforeChunk()
            val actual = ImageInputPolicy.fitWithinLimits(decoded.width, decoded.height)
            actual != null &&
                actual.width == decoded.width &&
                actual.height == decoded.height
        } ?: false
    }

    internal fun mimeMatches(kind: IncomingDocumentKind, rawMimeType: String?): Boolean {
        val mimeType = rawMimeType?.lowercase(Locale.ROOT) ?: return false
        return when (kind) {
            IncomingDocumentKind.JPEG -> mimeType == "image/jpeg"
            IncomingDocumentKind.PNG -> mimeType == "image/png"
            IncomingDocumentKind.GIF -> mimeType == "image/gif"
            IncomingDocumentKind.WEBP -> mimeType == "image/webp"
            IncomingDocumentKind.BMP -> mimeType == "image/bmp" || mimeType == "image/x-ms-bmp"
            IncomingDocumentKind.PDF,
            IncomingDocumentKind.DOCX,
            -> false
        }
    }

    internal fun hasSupportedEncodedSize(sizeBytes: Long): Boolean =
        sizeBytes in 1..ImageInputPolicy.MAX_ENCODED_BYTES

    private fun hasSafeSourceDimensions(width: Int, height: Int): Boolean =
        width > 0 &&
            height > 0 &&
            width.toLong() * height.toLong() <= MAX_SOURCE_PIXELS

    private val imageKinds =
        setOf(
            IncomingDocumentKind.JPEG,
            IncomingDocumentKind.PNG,
            IncomingDocumentKind.GIF,
            IncomingDocumentKind.WEBP,
            IncomingDocumentKind.BMP,
        )
}

private object AndroidImportedImageDecoder : ImportedImageDecoder {
    override fun readBounds(file: File): ImportedImageMetadata? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return ImportedImageMetadata(options.outMimeType, options.outWidth, options.outHeight)
    }

    override fun decodeSample(file: File, sampleSize: Int): ImportedDecodedImage? =
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
                inScaled = false
            },
        )?.let(::BitmapDecodedImage)

    private class BitmapDecodedImage(private val bitmap: Bitmap) : ImportedDecodedImage {
        override val width: Int
            get() = bitmap.width

        override val height: Int
            get() = bitmap.height

        override fun close() {
            bitmap.recycle()
        }
    }
}
