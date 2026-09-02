package com.example.pdfmaker

import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Bounded signature validation for plaintext entering or leaving secure-document storage. */
internal object SecureDocumentTypePolicy {
    const val SIGNATURE_BYTES = 64

    fun isPlainPdf(prefix: ByteArray): Boolean =
        !SecureDocumentCodec.isEncrypted(prefix) &&
            ImportedDocumentInspector.signature(prefix) == IncomingDocumentKind.PDF

    fun requirePlainPdf(prefix: ByteArray) {
        require(isPlainPdf(prefix)) { "Document plaintext is not a PDF" }
    }

    fun requirePlainPdfFile(file: File) {
        require(file.isFile) { "Document plaintext is unavailable" }
        file.inputStream().use { input ->
            requirePlainPdf(BoundedIo.readPrefix(input, SIGNATURE_BYTES))
        }
    }

    fun verifiedInput(input: InputStream): InputStream {
        val buffered = BufferedInputStream(input, SIGNATURE_BYTES)
        buffered.mark(SIGNATURE_BYTES)
        val prefix = BoundedIo.readPrefix(buffered, SIGNATURE_BYTES)
        buffered.reset()
        requirePlainPdf(prefix)
        return buffered
    }
}

/** Captures only the bounded signature prefix while forwarding decrypted bytes to disk. */
internal class PlainPdfValidatingOutputStream(
    private val destination: OutputStream,
) : OutputStream() {
    private val prefix = ByteArray(SecureDocumentTypePolicy.SIGNATURE_BYTES)
    private var prefixSize = 0

    override fun write(value: Int) {
        if (prefixSize < prefix.size) {
            prefix[prefixSize] = value.toByte()
            prefixSize += 1
        }
        destination.write(value)
    }

    override fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length) {
            "Invalid output buffer range"
        }
        val captured = minOf(length, prefix.size - prefixSize)
        if (captured > 0) {
            bytes.copyInto(prefix, prefixSize, offset, offset + captured)
            prefixSize += captured
        }
        destination.write(bytes, offset, length)
    }

    override fun flush() = destination.flush()

    fun requirePlainPdf() {
        SecureDocumentTypePolicy.requirePlainPdf(prefix.copyOf(prefixSize))
    }
}
