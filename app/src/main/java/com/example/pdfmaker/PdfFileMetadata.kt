package com.example.pdfmaker

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File

/** Reads metadata from an app-owned PDF while keeping descriptor ownership explicit. */
internal object PdfFileMetadata {
    fun pageCount(file: File): Int {
        require(file.isFile && file.length() > 0L) { "PDF output is missing or empty" }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                require(renderer.pageCount > 0) { "PDF output has no pages" }
                renderer.pageCount
            }
        }
    }
}
