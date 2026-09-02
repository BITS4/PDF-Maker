package com.example.pdfmaker

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri

internal fun <T> withSafePdfRenderer(
    context: Context,
    uri: Uri,
    block: (PdfRenderer) -> T,
): T = SafePdfInput.fromUri(context, uri).use { source ->
    val descriptor = source.openDescriptor()
    val renderer = withFailureCleanup(cleanup = descriptor::close) {
        PdfRenderer(descriptor)
    }
    renderer.use(block)
}
