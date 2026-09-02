package com.example.pdfmaker

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Applies bounded archive checks before an imported ZIP is accepted as a DOCX document. */
internal object ImportedDocxValidator {
    private const val MAX_ENTRIES = 2_000
    private const val MAX_ENTRY_BYTES = 50L * 1024L * 1024L
    private const val MAX_EXPANDED_BYTES = 200L * 1024L * 1024L
    private const val MAX_COMPRESSION_RATIO = 250L

    fun isSafe(
        file: File,
        beforeChunk: () -> Unit,
    ): Boolean =
        try {
            inspectEntries(file, beforeChunk)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            false
        } catch (_: RuntimeException) {
            false
        }

    private fun inspectEntries(
        file: File,
        beforeChunk: () -> Unit,
    ): Boolean {
        val state = ScanState()
        ZipFile(file).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                beforeChunk()
                val entry = entries.nextElement()
                state.validateEntry(entry)
                if (!entry.isDirectory) {
                    state.recordExpanded(entry.name, expandedSize(zip, entry, beforeChunk))
                }
            }
        }
        return state.hasRequiredParts
    }

    private fun expandedSize(
        zip: ZipFile,
        entry: ZipEntry,
        beforeChunk: () -> Unit,
    ): Long =
        zip.getInputStream(entry).use { input ->
            BoundedIo.copy(input, DISCARDING_OUTPUT, MAX_ENTRY_BYTES, beforeChunk)
        }

    private class ScanState {
        private val entryNames = mutableSetOf<String>()
        private var entryCount = 0
        private var expandedTotal = 0L
        private var hasContentTypes = false
        private var hasDocument = false

        val hasRequiredParts: Boolean
            get() = hasContentTypes && hasDocument

        fun validateEntry(entry: ZipEntry) {
            entryCount += 1
            require(entryCount <= MAX_ENTRIES) { "DOCX contains too many entries" }
            require(isSafeZipName(entry.name)) { "DOCX contains an unsafe entry name" }
            require(entryNames.add(entry.name)) { "DOCX contains duplicate entries" }
            if (entry.isDirectory || entry.size < 0) return
            require(entry.size <= MAX_ENTRY_BYTES) { "DOCX entry is too large" }
            require(expandedTotal <= MAX_EXPANDED_BYTES - entry.size) {
                "DOCX expands beyond its limit"
            }
            requireSafeCompressionRatio(entry)
        }

        fun recordExpanded(
            name: String,
            expandedBytes: Long,
        ) {
            require(expandedTotal <= MAX_EXPANDED_BYTES - expandedBytes) {
                "DOCX expands beyond its limit"
            }
            expandedTotal += expandedBytes
            if (name == "[Content_Types].xml") hasContentTypes = true
            if (name == "word/document.xml") hasDocument = true
        }

        private fun requireSafeCompressionRatio(entry: ZipEntry) {
            if (entry.size < 1024L * 1024L || entry.compressedSize <= 0) return
            val roundedRatio = (entry.size + entry.compressedSize - 1L) / entry.compressedSize
            require(roundedRatio <= MAX_COMPRESSION_RATIO) { "DOCX compression ratio is unsafe" }
        }
    }

    private val DISCARDING_OUTPUT =
        object : OutputStream() {
            override fun write(value: Int) = Unit

            override fun write(
                bytes: ByteArray,
                offset: Int,
                length: Int,
            ) = Unit
        }

    private fun isSafeZipName(raw: String): Boolean {
        val hasUnsafeRoot = raw.startsWith('/') || raw.startsWith('\\')
        if (raw.isBlank() || raw.length > 240 || hasUnsafeRoot) return false
        val normalized = raw.replace('\\', '/')
        return normalized.split('/').none { it == "." || it == ".." } && ':' !in normalized
    }
}
