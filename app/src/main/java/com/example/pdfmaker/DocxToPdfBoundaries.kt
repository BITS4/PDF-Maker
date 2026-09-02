package com.example.pdfmaker

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.File

/** Opaque source handle keeps action-state tests independent from Android's URI implementation. */
internal class DocxInputSource private constructor(
    private val providerUri: Uri?,
    val testId: String?,
) {
    fun requireProviderUri(): Uri = requireNotNull(providerUri) { "A provider URI is required" }

    companion object {
        fun provider(uri: Uri): DocxInputSource = DocxInputSource(uri, null)

        fun test(id: String): DocxInputSource {
            require(id.isNotBlank()) { "Test source identifier cannot be blank" }
            return DocxInputSource(null, id)
        }
    }
}

internal interface DocxToPdfBoundaries {
    suspend fun inspect(
        source: DocxInputSource,
        fallbackName: String?,
    ): DocxInputMetadata

    suspend fun convert(
        source: DocxInputSource,
        baseName: String,
        onProgress: (Int, String) -> Unit,
    ): DocxPdfResult

    suspend fun verify(converted: DocxPdfResult): DocxSavedResult

    fun share(file: File): Boolean

    fun cache(catalogEntry: PdfFile)

    fun deleteUnclaimed(file: File)
}

internal data class DocxActionDispatchers(
    val io: CoroutineDispatcher,
    val main: CoroutineDispatcher,
) {
    companion object {
        fun production(): DocxActionDispatchers =
            DocxActionDispatchers(
                io = Dispatchers.IO,
                main = Dispatchers.Main.immediate,
            )
    }
}

internal class AndroidDocxToPdfBoundaries(
    private val context: Context,
) : DocxToPdfBoundaries {
    override suspend fun inspect(
        source: DocxInputSource,
        fallbackName: String?,
    ): DocxInputMetadata =
        readDocxInputMetadata(context, source.requireProviderUri(), fallbackName)

    override suspend fun convert(
        source: DocxInputSource,
        baseName: String,
        onProgress: (Int, String) -> Unit,
    ): DocxPdfResult =
        docxToPdf(
            context = context,
            uri = source.requireProviderUri(),
            baseName = baseName,
            onProgress = onProgress,
        )

    override suspend fun verify(converted: DocxPdfResult): DocxSavedResult =
        verifyConvertedDocxOutput(converted)

    override fun share(file: File): Boolean = shareDocxPdf(context, file)

    override fun cache(catalogEntry: PdfFile) = FileCache.prependFile(catalogEntry)

    override fun deleteUnclaimed(file: File) {
        OwnedImportCleanup.erase(file)
    }
}

private fun verifyConvertedDocxOutput(converted: DocxPdfResult): DocxSavedResult {
    val file = converted.file
    require(file.isFile && file.length() in 1..DocxConversionPolicy.MAX_OUTPUT_BYTES) {
        "Converted PDF output is missing or exceeds its limit"
    }
    SecureDocumentTypePolicy.requirePlainPdfFile(file)
    val verifiedPageCount = PdfFileMetadata.pageCount(file)
    require(verifiedPageCount == converted.pageCount && verifiedPageCount in 1..DocxConversionPolicy.MAX_PAGES) {
        "Converted PDF page metadata is inconsistent"
    }
    val sizeBytes = file.length()
    val lastModified = file.lastModified()
    val catalogEntry =
        PdfFile(
            name = file.name,
            filePath = file.absolutePath,
            size = FileRepository.formatSize(sizeBytes),
            date = FileRepository.formatDate(lastModified),
            pageCount = verifiedPageCount,
            lastModified = lastModified,
        )
    return DocxSavedResult(file = file, catalogEntry = catalogEntry, sizeBytes = sizeBytes)
}
