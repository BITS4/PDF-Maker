package com.example.pdfmaker

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

/** Bounded DOCX staging and per-entry reads for conversion tools. */
object SafeDocxInput {
    const val MAX_DOCX_BYTES = 100L * 1024L * 1024L
    const val MAX_XML_BYTES = 10L * 1024L * 1024L
    const val MAX_RELATIONSHIPS_BYTES = 2L * 1024L * 1024L
    const val MAX_MEDIA_BYTES = 20L * 1024L * 1024L
    const val MAX_CONVERSION_BYTES = 75L * 1024L * 1024L
    const val MAX_ENTRIES = 2_000

    fun stage(input: InputStream, directory: File, maximumBytes: Long = MAX_DOCX_BYTES): File {
        require(maximumBytes > 0) { "Maximum DOCX size must be positive" }
        check((directory.exists() && directory.isDirectory) || directory.mkdirs()) {
            "Could not create the DOCX staging directory"
        }
        val temporary = File(directory, ".docx-${UUID.randomUUID()}.tmp")
        try {
            input.use { source ->
                FileOutputStream(temporary).use { output ->
                    val copied = BoundedIo.copy(source, output, maximumBytes)
                    require(copied > 0) { "The DOCX is empty" }
                    output.flush()
                    output.fd.sync()
                }
            }
            require(ImportedDocumentInspector.inspect(temporary) == IncomingDocumentKind.DOCX) {
                "The selected content is not a safe DOCX"
            }
            return temporary
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    fun readEntry(input: InputStream, maximumBytes: Long): ByteArray {
        val output = ByteArrayOutputStream()
        BoundedIo.copy(input, output, maximumBytes)
        return output.toByteArray()
    }

    fun decodeXml(bytes: ByteArray, maximumBytes: Long = MAX_XML_BYTES): String {
        require(bytes.size.toLong() <= maximumBytes) { "XML part exceeds its size limit" }
        val xml = bytes.toString(Charsets.UTF_8)
        require(!xml.contains("<!DOCTYPE", ignoreCase = true)) { "DOCTYPE is not allowed" }
        require(!xml.contains("<!ENTITY", ignoreCase = true)) { "XML entities are not allowed" }
        return xml
    }
}
