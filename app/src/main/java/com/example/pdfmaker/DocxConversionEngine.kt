package com.example.pdfmaker

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/** Converts a provider-backed DOCX snapshot without retaining all embedded media in memory. */
internal suspend fun docxToPdf(
    context: Context,
    uri: Uri,
    baseName: String,
    onProgress: (Int, String) -> Unit,
): DocxPdfResult {
    val operationContext = currentCoroutineContext()
    onProgress(5, "Reading document…")
    val providerInput =
        context.contentResolver.openInputStream(uri)
            ?: error("The DOCX provider returned no data")
    val workingDirectory = File(context.cacheDir, "pdfmaker")
    val stagedDocx = SafeDocxInput.stage(providerInput, workingDirectory) {
        operationContext.ensureActive()
    }
    try {
        operationContext.ensureActive()
        extractDocxConversionArchive(stagedDocx, workingDirectory) {
            operationContext.ensureActive()
        }.use { archive ->
            operationContext.ensureActive()
            onProgress(30, "Parsing content…")
            val relationships =
                archive.relationshipsXml
                    ?.let { xml ->
                        parseConversionRelationships(xml) { operationContext.ensureActive() }
                    }.orEmpty()
            val blocks =
                parseConversionDocument(archive.documentXml, relationships) {
                    operationContext.ensureActive()
                }
            operationContext.ensureActive()
            onProgress(50, "Rendering pages…")
            return renderDocxPdf(
                context = context,
                blocks = blocks,
                mediaFiles = archive.mediaFiles,
                baseName = baseName,
                onProgress = onProgress,
            )
        }
    } finally {
        OwnedImportCleanup.erase(stagedDocx)
    }
}

internal fun shareDocxPdf(
    context: Context,
    file: File,
): Boolean =
    DocumentShareAdapter.share(
        context = context,
        file = file,
        chooserTitle = "Share PDF",
        requestedMimeType = "application/pdf",
    )

internal fun docxFormatSize(kb: Long): String = if (kb >= 1024) "%.1f MB".format(kb / 1024f) else "$kb KB"
