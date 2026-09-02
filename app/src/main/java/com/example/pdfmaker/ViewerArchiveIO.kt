package com.example.pdfmaker

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

internal const val MAX_VIEWER_XML_BYTES = 8 * 1024 * 1024
internal const val MAX_VIEWER_MEDIA_BYTES = 64 * 1024 * 1024

internal fun readBoundedViewerEntry(
    input: InputStream,
    maxBytes: Int,
): ByteArray {
    require(maxBytes > 0) { "maxBytes must be positive" }
    val output = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        if (total > maxBytes) throw IOException("Document entry exceeds the preview limit")
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
