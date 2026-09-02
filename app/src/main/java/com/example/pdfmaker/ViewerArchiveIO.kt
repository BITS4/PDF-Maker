package com.example.pdfmaker

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

internal object ViewerResourceLimits {
    const val MAX_XML_BYTES = 8 * 1024 * 1024
    const val MAX_MEDIA_BYTES = 20 * 1024 * 1024
    const val MAX_TEXT_BYTES = 1024 * 1024
    const val MAX_SOURCE_BYTES = 100L * 1024L * 1024L
    const val MAX_ARCHIVE_ENTRIES = 2_000
    const val MAX_ARCHIVE_BYTES = 64L * 1024L * 1024L
    const val MAX_ARCHIVE_ENTRY_BYTES = 32 * 1024 * 1024
    const val MAX_MEDIA_ITEMS = 64
    const val MAX_RENDERED_PAGES = 20
    const val MAX_TABLE_ROWS = 500
    const val MAX_TABLE_COLUMNS = 64
    const val MAX_CELL_CHARACTERS = 8_192
    const val MAX_RELATIONSHIPS = 1_000
    const val MAX_DOCUMENT_BLOCKS = 2_000
    const val MAX_RUNS_PER_PARAGRAPH = 512
    const val MAX_SLIDE_ELEMENTS = 512
    const val MAX_OOXML_COORDINATE = 100_000_000f
}

internal fun readBoundedViewerEntry(
    input: InputStream,
    maxBytes: Int,
): ByteArray {
    require(maxBytes > 0) { "maxBytes must be positive" }
    val output = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
    BoundedIo.copy(input, output, maxBytes.toLong())
    return output.toByteArray()
}

/** Tracks all expanded ZIP data, including entries the preview renderer does not use. */
internal class ViewerArchiveBudget(
    private val maximumEntries: Int = ViewerResourceLimits.MAX_ARCHIVE_ENTRIES,
    private val maximumExpandedBytes: Long = ViewerResourceLimits.MAX_ARCHIVE_BYTES,
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

    fun readEntry(
        input: InputStream,
        maximumEntryBytes: Int,
    ): ByteArray = consume(input, maximumEntryBytes, capture = true) ?: byteArrayOf()

    fun readXml(input: InputStream): String =
        SafeDocxInput.decodeXml(
            readEntry(input, ViewerResourceLimits.MAX_XML_BYTES),
            ViewerResourceLimits.MAX_XML_BYTES.toLong(),
        )

    fun skipEntry(input: InputStream) {
        consume(input, ViewerResourceLimits.MAX_ARCHIVE_ENTRY_BYTES, capture = false)
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
        var finished = false
        while (!finished) {
            val count = input.read(buffer)
            when {
                count < 0 -> {
                    finished = true
                }

                count == 0 -> {
                    emptyReads += 1
                    requireArchiveProgress(emptyReads)
                }

                else -> {
                    emptyReads = 0
                    requireEntryCapacity(entryBytes, count, maximumEntryBytes)
                    requireArchiveCapacity(expandedBytes, count, maximumExpandedBytes)
                    entryBytes += count
                    expandedBytes += count
                    output?.write(buffer, 0, count)
                }
            }
        }
        return output?.toByteArray()
    }
}

private fun requireArchiveProgress(emptyReads: Int) {
    if (emptyReads > 32) throw IOException("Document archive stream made no progress")
}

private fun requireEntryCapacity(
    currentBytes: Long,
    incomingBytes: Int,
    maximumBytes: Int,
) {
    if (currentBytes > maximumBytes.toLong() - incomingBytes) {
        throw IOException("Document archive entry exceeds its preview limit")
    }
}

private fun requireArchiveCapacity(
    currentBytes: Long,
    incomingBytes: Int,
    maximumBytes: Long,
) {
    if (currentBytes > maximumBytes - incomingBytes) {
        throw IOException("Document archive expands beyond its preview limit")
    }
}

internal fun readBoundedViewerFile(
    file: File,
    maximumBytes: Long,
): ByteArray {
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
    String(readBoundedViewerFile(file, ViewerResourceLimits.MAX_TEXT_BYTES.toLong()), StandardCharsets.UTF_8)

internal fun isSafeViewerArchiveEntryName(name: String): Boolean =
    when {
        name.isBlank() -> false
        name.length > 240 -> false
        name.startsWith('/') || name.startsWith('\\') -> false
        '\u0000' in name -> false
        ':' in name || '\\' in name -> false
        else -> name.split('/').none { it == "." || it == ".." }
    }
