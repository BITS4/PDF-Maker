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
    private const val MAX_TEXT_BYTES = 2 * 1024 * 1024
    private const val MAX_ARCHIVE_ENTRIES = 4_096

    fun isAllowedSource(file: File): Boolean = file.isFile && file.length() in 1..MAX_SOURCE_BYTES

    fun readXml(input: InputStream): String = SafeDocxInput.decodeXml(
        readBoundedViewerEntry(input, MAX_VIEWER_XML_BYTES),
        MAX_VIEWER_XML_BYTES.toLong(),
    )

    fun readTextPrefix(file: File): String {
        require(isAllowedSource(file)) { "Preview source is empty or too large" }
        return file.inputStream().use { input ->
            BoundedIo.readPrefix(input, minOf(file.length(), MAX_TEXT_BYTES.toLong()).toInt())
                .toString(Charsets.UTF_8)
        }
    }

    fun validateArchiveEntry(index: Int, name: String) {
        if (index !in 1..MAX_ARCHIVE_ENTRIES) {
            throw IOException("Document archive contains too many entries")
        }
        if (name.isBlank() || name.startsWith('/') || '\u0000' in name || '\\' in name ||
            name.split('/').any { it == ".." }
        ) {
            throw IOException("Document archive contains an invalid entry name")
        }
    }

    fun decodeImage(input: InputStream, targetSize: Int): Bitmap? {
        require(targetSize in 1..2_048) { "Invalid thumbnail size" }
        val bytes = readBoundedViewerEntry(input, MAX_IMAGE_BYTES)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val target = RenderSizing.fitWithin(bounds.outWidth, bounds.outHeight, targetSize) ?: return null
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > target.width * 2 ||
            bounds.outHeight / sampleSize > target.height * 2
        ) {
            sampleSize *= 2
        }
        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        )
    }
}
