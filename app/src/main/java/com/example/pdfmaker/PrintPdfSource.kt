package com.example.pdfmaker

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID

internal class PrintPipelineException(
    val userMessage: String,
    cause: Throwable? = null,
) : IOException(userMessage, cause)

internal class StagedPrintPdfSource(
    val file: File,
    val pageCount: Int,
) : Closeable {
    fun openDescriptor(): ParcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)

    override fun close() {
        file.delete()
    }
}

/** Creates a stable, validated snapshot before the print framework reads page metadata. */
internal object PrintPdfSource {
    private const val HEADER_BYTES = 64
    private const val INVALID_INPUT = "The selected file is not a valid printable PDF."
    private const val INPUT_READ_FAILED =
        "The PDF could not be read safely or is larger than the 100 MB print limit."

    fun stage(
        context: Context,
        uri: Uri,
        checkpoint: () -> Unit,
    ): StagedPrintPdfSource {
        if (uri.scheme != "content" || uri.authority.isNullOrBlank()) {
            throw PrintPipelineException("Choose a PDF from a trusted document provider.")
        }
        checkpoint()

        val directory = File(context.cacheDir, "print")
        if (!((directory.exists() && directory.isDirectory) || directory.mkdirs())) {
            throw PrintPipelineException("The print staging directory could not be created.")
        }
        val temporary = File(directory, ".print-${UUID.randomUUID()}.pdf")
        var completed = false
        try {
            copyProviderContent(context, uri, temporary, checkpoint)
            validateHeader(temporary, checkpoint)
            val pageCount = inspectPageCount(temporary, checkpoint)
            completed = true
            return StagedPrintPdfSource(temporary, pageCount)
        } finally {
            if (!completed) temporary.delete()
        }
    }

    private fun copyProviderContent(
        context: Context,
        uri: Uri,
        destination: File,
        checkpoint: () -> Unit,
    ) {
        val input = openProviderInput(context, uri)
        try {
            input.use { source ->
                FileOutputStream(destination).use { output ->
                    val boundedOutput =
                        BoundedIo.limit(
                            output = output,
                            maximumBytes = SafePdfInput.MAX_PDF_BYTES,
                            beforeWrite = checkpoint,
                        )
                    val copied =
                        BoundedIo.copy(
                            input = source,
                            output = boundedOutput,
                            maximumBytes = SafePdfInput.MAX_PDF_BYTES,
                        )
                    requireCopiedContent(copied)
                    checkpoint()
                    boundedOutput.flush()
                    output.fd.sync()
                }
            }
        } catch (cancelled: OperationCanceledException) {
            propagateCancellation(cancelled)
        } catch (error: PrintPipelineException) {
            propagatePrintFailure(error)
        } catch (error: IOException) {
            failInputRead(error)
        } catch (error: SecurityException) {
            failInputRead(error)
        }
    }

    private fun openProviderInput(
        context: Context,
        uri: Uri,
    ): InputStream {
        val input =
            try {
                context.contentResolver.openInputStream(uri)
            } catch (cancelled: OperationCanceledException) {
                propagateCancellation(cancelled)
            } catch (error: IOException) {
                failInputRead(error)
            } catch (error: SecurityException) {
                throw PrintPipelineException("Access to the selected PDF was denied.", error)
            }
        return input ?: throw PrintPipelineException(INPUT_READ_FAILED)
    }

    private fun requireCopiedContent(copiedBytes: Long) {
        if (copiedBytes <= 0) throw PrintPipelineException("The selected PDF is empty.")
    }

    private fun validateHeader(
        source: File,
        checkpoint: () -> Unit,
    ) {
        checkpoint()
        val header = readHeader(source)
        if (SecureDocumentCodec.isEncrypted(header)) {
            throw PrintPipelineException("Unlock the PDF before printing it.")
        }
        if (ImportedDocumentInspector.signature(header) != IncomingDocumentKind.PDF) {
            throw PrintPipelineException(INVALID_INPUT)
        }
    }

    private fun readHeader(source: File): ByteArray =
        try {
            source.inputStream().use { BoundedIo.readPrefix(it, HEADER_BYTES) }
        } catch (error: IOException) {
            throw PrintPipelineException(INPUT_READ_FAILED, error)
        }

    private fun inspectPageCount(
        source: File,
        checkpoint: () -> Unit,
    ): Int {
        checkpoint()
        val pageCount = readPageCount(source, checkpoint)
        return try {
            PrintPdfPolicy.requirePageCount(pageCount)
        } catch (error: IllegalArgumentException) {
            throw PrintPipelineException(
                "PDFs must contain between 1 and ${PrintPdfPolicy.MAX_PAGES} pages.",
                error,
            )
        }
    }

    private fun readPageCount(
        source: File,
        checkpoint: () -> Unit,
    ): Int =
        try {
            ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    checkpoint()
                    renderer.pageCount
                }
            }
        } catch (cancelled: OperationCanceledException) {
            propagateCancellation(cancelled)
        } catch (error: IOException) {
            failInvalidInput(error)
        } catch (error: SecurityException) {
            failInvalidInput(error)
        } catch (error: IllegalArgumentException) {
            failInvalidInput(error)
        } catch (error: IllegalStateException) {
            failInvalidInput(error)
        }

    private fun propagateCancellation(cancelled: OperationCanceledException): Nothing = throw cancelled

    private fun propagatePrintFailure(error: PrintPipelineException): Nothing = throw error

    private fun failInputRead(cause: Exception): Nothing = throw PrintPipelineException(INPUT_READ_FAILED, cause)

    private fun failInvalidInput(cause: Exception): Nothing = throw PrintPipelineException(INVALID_INPUT, cause)
}
