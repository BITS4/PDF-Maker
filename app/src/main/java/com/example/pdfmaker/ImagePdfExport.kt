package com.example.pdfmaker

import java.io.File
import java.io.OutputStream

/** Finalizes image-to-PDF output without ever retaining an unprotected fallback. */
object ImagePdfExport {
    fun write(
        directory: File,
        requestedName: String?,
        password: String?,
        pdfWriter: (OutputStream) -> Unit,
    ): Result<File> = runCatching {
        if (password != null) {
            require(password.length in 4..128) { "Password must contain 4 to 128 characters" }
        }
        val output = OutputStore.writeUnique(directory, requestedName, "pdf", pdfWriter)
        try {
            if (password != null) {
                check(SecureDocumentStore.lockInPlace(output, password) == null) {
                    "The PDF could not be password-protected"
                }
            }
            output
        } catch (error: Throwable) {
            output.delete()
            throw error
        }
    }
}
