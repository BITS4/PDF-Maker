package com.example.pdfmaker

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipInputStream

internal class DocxConversionArchive internal constructor(
    val documentXml: String,
    val relationshipsXml: String?,
    val mediaFiles: Map<String, File>,
    private val extractionDirectory: File,
) : Closeable {
    override fun close() {
        mediaFiles.values.forEach(OwnedImportCleanup::erase)
        extractionDirectory.delete()
    }
}

// Archive entry types are handled explicitly so every uncompressed byte shares one aggregate budget.
@Suppress("CyclomaticComplexMethod")
internal fun extractDocxConversionArchive(
    stagedDocx: File,
    workingDirectory: File,
    beforeChunk: () -> Unit = {},
): DocxConversionArchive {
    check((workingDirectory.exists() && workingDirectory.isDirectory) || workingDirectory.mkdirs()) {
        "Could not create the DOCX working directory"
    }
    val extractionDirectory = File(workingDirectory, ".docx-media-${UUID.randomUUID()}")
    check(extractionDirectory.mkdir()) { "Could not create the DOCX media directory" }

    var documentXml: String? = null
    var relationshipsXml: String? = null
    val mediaFiles = mutableMapOf<String, File>()
    var ownershipTransferred = false
    try {
        ZipInputStream(stagedDocx.inputStream().buffered()).use { zip ->
            var entryCount = 0
            var totalRead = 0L

            fun remainingLimit(perEntryLimit: Long): Long {
                val aggregateRemaining = SafeDocxInput.MAX_CONVERSION_BYTES - totalRead
                require(aggregateRemaining > 0) { "DOCX conversion data exceeds its limit" }
                return minOf(perEntryLimit, aggregateRemaining)
            }

            fun readXmlPart(limit: Long): String {
                val bytes = SafeDocxInput.readEntry(zip, remainingLimit(limit), beforeChunk)
                totalRead += bytes.size
                return SafeDocxInput.decodeXml(bytes, limit)
            }

            fun extractMediaPart(mediaName: String) {
                require(mediaName.isNotBlank() && mediaName !in mediaFiles) {
                    "DOCX contains invalid or duplicate media entries"
                }
                DocxConversionPolicy.requireCanAdd(
                    mediaFiles.size,
                    DocxConversionPolicy.MAX_MEDIA_ITEMS,
                    "media items",
                )
                val output = File(extractionDirectory, "${UUID.randomUUID()}.media")
                var completed = false
                try {
                    val copied =
                        FileOutputStream(output).use { destination ->
                            BoundedIo.copy(
                                zip,
                                destination,
                                remainingLimit(SafeDocxInput.MAX_MEDIA_BYTES),
                                beforeChunk,
                            )
                        }
                    require(copied > 0) { "DOCX contains an empty media item" }
                    totalRead += copied
                    mediaFiles[mediaName] = output
                    completed = true
                } finally {
                    if (!completed) OwnedImportCleanup.erase(output)
                }
            }

            fun skipPart() {
                val copied =
                    BoundedIo.copy(
                        zip,
                        DISCARDING_OUTPUT,
                        remainingLimit(SafeDocxInput.MAX_CONVERSION_BYTES),
                        beforeChunk,
                    )
                totalRead += copied
            }

            beforeChunk()
            var entry = zip.nextEntry
            while (entry != null) {
                beforeChunk()
                entryCount += 1
                require(entryCount <= SafeDocxInput.MAX_ENTRIES) { "DOCX contains too many entries" }
                when {
                    entry.isDirectory -> {
                        Unit
                    }

                    entry.name == "word/document.xml" -> {
                        require(documentXml == null) { "DOCX contains duplicate document XML" }
                        documentXml = readXmlPart(SafeDocxInput.MAX_XML_BYTES)
                    }

                    entry.name == "word/_rels/document.xml.rels" -> {
                        require(relationshipsXml == null) { "DOCX contains duplicate relationships XML" }
                        relationshipsXml = readXmlPart(SafeDocxInput.MAX_RELATIONSHIPS_BYTES)
                    }

                    entry.name.startsWith("word/media/") -> {
                        extractMediaPart(entry.name.substringAfterLast('/'))
                    }

                    else -> {
                        skipPart()
                    }
                }
                zip.closeEntry()
                beforeChunk()
                entry = zip.nextEntry
            }
        }
        val archive =
            DocxConversionArchive(
                documentXml = requireNotNull(documentXml) { "DOCX document XML is missing" },
                relationshipsXml = relationshipsXml,
                mediaFiles = mediaFiles.toMap(),
                extractionDirectory = extractionDirectory,
            )
        ownershipTransferred = true
        return archive
    } finally {
        if (!ownershipTransferred) {
            mediaFiles.values.forEach(OwnedImportCleanup::erase)
            extractionDirectory.delete()
        }
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
