package com.example.pdfmaker

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Bounded decoding primitives for previews of user-controlled documents. */
object ThumbnailInput {
    const val MAX_SOURCE_BYTES = 100L * 1024L * 1024L
    private const val MAX_IMAGE_BYTES = 20 * 1024 * 1024
    private const val MAX_SOURCE_IMAGE_DIMENSION = 32_768
    private const val MAX_SOURCE_IMAGE_PIXELS = 40_000_000L
    private const val MAX_DECODED_IMAGE_PIXELS = 16_000_000L
    private const val MAX_TEXT_BYTES = 2 * 1024 * 1024

    fun isAllowedSource(file: File): Boolean = file.isFile && file.length() in 1..MAX_SOURCE_BYTES

    fun readXml(input: InputStream): String = SafeDocxInput.decodeXml(
        readBoundedViewerEntry(input, ViewerResourceLimits.MAX_XML_BYTES),
        ViewerResourceLimits.MAX_XML_BYTES.toLong(),
    )

    fun readTextPrefix(file: File): String {
        require(isAllowedSource(file)) { "Preview source is empty or too large" }
        return file.inputStream().use { input ->
            BoundedIo.readPrefix(input, minOf(file.length(), MAX_TEXT_BYTES.toLong()).toInt())
                .toString(Charsets.UTF_8)
        }
    }

    fun validateArchiveEntry(index: Int, name: String) {
        if (index !in 1..ViewerResourceLimits.MAX_ARCHIVE_ENTRIES) {
            throw IOException("Document archive contains too many entries")
        }
        if (!isSafeViewerArchiveEntryName(name)) {
            throw IOException("Document archive contains an invalid entry name")
        }
    }

    fun decodeImage(input: InputStream, targetSize: Int): Bitmap? {
        require(targetSize in 1..2_048) { "Invalid thumbnail size" }
        return decodeImage(readBoundedViewerEntry(input, MAX_IMAGE_BYTES), targetSize)
    }

    fun decodeImage(bytes: ByteArray, targetSize: Int): Bitmap? {
        require(targetSize in 1..2_048) { "Invalid thumbnail size" }
        require(bytes.size <= MAX_IMAGE_BYTES) { "Image exceeds its preview limit" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sampleSize = imageSampleSize(bounds.outWidth, bounds.outHeight, targetSize) ?: return null
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        ) ?: return null
        if (decoded.width.toLong() * decoded.height.toLong() > MAX_DECODED_IMAGE_PIXELS) {
            decoded.recycle()
            return null
        }
        return decoded
    }

    fun imageSampleSize(width: Int, height: Int, targetSize: Int): Int? {
        if (width !in 1..MAX_SOURCE_IMAGE_DIMENSION || height !in 1..MAX_SOURCE_IMAGE_DIMENSION) return null
        if (targetSize !in 1..2_048 || width.toLong() * height.toLong() > MAX_SOURCE_IMAGE_PIXELS) return null

        var sampleSize = 1
        while (true) {
            val decodedWidth = (width + sampleSize - 1) / sampleSize
            val decodedHeight = (height + sampleSize - 1) / sampleSize
            val withinTarget = decodedWidth <= targetSize * 2 && decodedHeight <= targetSize * 2
            val withinPixelLimit = decodedWidth.toLong() * decodedHeight.toLong() <= MAX_DECODED_IMAGE_PIXELS
            if (withinTarget && withinPixelLimit) return sampleSize
            if (sampleSize >= MAX_SOURCE_IMAGE_DIMENSION) return null
            sampleSize *= 2
        }
    }
}
