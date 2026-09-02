package com.example.pdfmaker

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

class StagedPdfSource internal constructor(val file: File) : Closeable {
    fun openDescriptor(): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)

    override fun close() {
        file.delete()
    }
}

/** Takes a bounded snapshot of a provider URI so later rendering cannot race changing provider data. */
object SafePdfInput {
    const val MAX_PDF_BYTES = 100L * 1024L * 1024L

    fun fromUri(context: Context, uri: Uri): StagedPdfSource {
        require(uri.scheme == "content" && !uri.authority.isNullOrBlank()) {
            "Only content-provider PDFs can be opened"
        }
        val input = context.contentResolver.openInputStream(uri)
            ?: error("The PDF provider returned no data")
        val directory = File(context.cacheDir, "pdfmaker")
        return StagedPdfSource(stage(input, directory))
    }

    fun stage(input: InputStream, directory: File, maximumBytes: Long = MAX_PDF_BYTES): File {
        require(maximumBytes > 0) { "Maximum PDF size must be positive" }
        check((directory.exists() && directory.isDirectory) || directory.mkdirs()) {
            "Could not create the PDF staging directory"
        }
        val temporary = File(directory, ".source-${UUID.randomUUID()}.pdf")
        try {
            input.use { source ->
                FileOutputStream(temporary).use { output ->
                    val copied = BoundedIo.copy(source, output, maximumBytes)
                    require(copied > 0) { "The PDF is empty" }
                    output.flush()
                    output.fd.sync()
                }
            }
            val prefix = temporary.inputStream().use { BoundedIo.readPrefix(it, 64) }
            require(!SecureDocumentCodec.isEncrypted(prefix)) { "Unlock the PDF before using this tool" }
            require(ImportedDocumentInspector.signature(prefix) == IncomingDocumentKind.PDF) {
                "The selected content is not a PDF"
            }
            return temporary
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }
}
