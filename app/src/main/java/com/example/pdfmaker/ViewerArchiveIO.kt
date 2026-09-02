package com.example.pdfmaker

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

internal const val MAX_VIEWER_XML_BYTES = 8 * 1024 * 1024
internal const val MAX_VIEWER_MEDIA_BYTES = 20 * 1024 * 1024
internal const val MAX_VIEWER_TEXT_BYTES = 1024 * 1024
internal const val MAX_VIEWER_SOURCE_BYTES = 100L * 1024L * 1024L
internal const val MAX_VIEWER_ARCHIVE_ENTRIES = 2_000
internal const val MAX_VIEWER_ARCHIVE_BYTES = 64L * 1024L * 1024L
internal const val MAX_VIEWER_ARCHIVE_ENTRY_BYTES = 32 * 1024 * 1024
internal const val MAX_VIEWER_MEDIA_ITEMS = 64
internal const val MAX_VIEWER_RENDERED_PAGES = 20
internal const val MAX_VIEWER_TABLE_ROWS = 500
internal const val MAX_VIEWER_TABLE_COLUMNS = 64
internal const val MAX_VIEWER_CELL_CHARACTERS = 8_192
internal const val MAX_VIEWER_RELATIONSHIPS = 1_000
internal const val MAX_VIEWER_DOCUMENT_BLOCKS = 2_000
internal const val MAX_VIEWER_RUNS_PER_PARAGRAPH = 512
internal const val MAX_VIEWER_SLIDE_ELEMENTS = 512

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

/** Tracks all expanded ZIP data, including entries the preview renderer does not use. */
internal class ViewerArchiveBudget(
    private val maximumEntries: Int = MAX_VIEWER_ARCHIVE_ENTRIES,
    private val maximumExpandedBytes: Long = MAX_VIEWER_ARCHIVE_BYTES,
) {
    var entryCount: Int = 0
        private set
    var expandedBytes: Long = 0
        private set

    init {
        require(maximumEntries > 0) { "maximumEntries must be positive" }
        require(maximumExpandedBytes > 0) { "maximumExpandedBytes must be positive" }
    }

    fun beginEntry(name: String) {
        if (entryCount >= maximumEntries) throw IOException("Document archive contains too many entries")
        if (!isSafeViewerArchiveEntryName(name)) {
            throw IOException("Document archive contains an unsafe entry name")
        }
        entryCount += 1
    }

    fun readEntry(input: InputStream, maximumEntryBytes: Int): ByteArray =
        consume(input, maximumEntryBytes, capture = true) ?: byteArrayOf()

    fun readXml(input: InputStream): String =
        SafeDocxInput.decodeXml(
            readEntry(input, MAX_VIEWER_XML_BYTES),
            MAX_VIEWER_XML_BYTES.toLong(),
        )

    fun skipEntry(input: InputStream) {
        consume(input, MAX_VIEWER_ARCHIVE_ENTRY_BYTES, capture = false)
    }

    private fun consume(
        input: InputStream,
        maximumEntryBytes: Int,
        capture: Boolean,
    ): ByteArray? {
        require(maximumEntryBytes > 0) { "maximumEntryBytes must be positive" }
        val output = if (capture) ByteArrayOutputStream(minOf(maximumEntryBytes, 16 * 1024)) else null
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var entryBytes = 0L
        var emptyReads = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) {
                emptyReads += 1
                if (emptyReads > 32) throw IOException("Document archive stream made no progress")
                continue
            }
            emptyReads = 0
            if (entryBytes > maximumEntryBytes.toLong() - count) {
                throw IOException("Document archive entry exceeds its preview limit")
            }
            if (expandedBytes > maximumExpandedBytes - count) {
                throw IOException("Document archive expands beyond its preview limit")
            }
            entryBytes += count
            expandedBytes += count
            output?.write(buffer, 0, count)
        }
        return output?.toByteArray()
    }
}

internal fun readBoundedViewerFile(file: File, maximumBytes: Long): ByteArray {
    require(maximumBytes > 0) { "maximumBytes must be positive" }
    require(file.isFile) { "Preview source is unavailable" }
    require(file.length() in 0..maximumBytes) { "Preview source exceeds its size limit" }
    return file.inputStream().use { input ->
        val output = ByteArrayOutputStream(minOf(file.length(), 16L * 1024L).toInt())
        BoundedIo.copy(input, output, maximumBytes)
        output.toByteArray()
    }
}

internal fun readBoundedViewerText(file: File): String =
    String(readBoundedViewerFile(file, MAX_VIEWER_TEXT_BYTES.toLong()), StandardCharsets.UTF_8)

internal fun isSafeViewerArchiveEntryName(name: String): Boolean {
    if (name.isBlank() || name.length > 240 || name.startsWith('/') || name.startsWith('\\')) return false
    if ('\u0000' in name || ':' in name || '\\' in name) return false
    return name.split('/').none { it == "." || it == ".." }
}
