package com.example.pdfmaker

import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

@Composable
internal fun IncomingDocumentEffect(
    activity: MainActivity,
    navigation: AppNavigationState,
) {
    val request = activity.incomingDocumentRequest
    LaunchedEffect(request) {
        val activeRequest = request ?: return@LaunchedEffect
        val result =
            withContext(Dispatchers.IO) {
                SafeDocumentImporter.import(activity, activeRequest)
            }
        activity.consumeIncomingDocument(activeRequest)
        when (result) {
            is IncomingImportResult.Imported ->
                handleImportedDocument(
                    activity = activity,
                    navigation = navigation,
                    imported = result,
                )
            is IncomingImportResult.Rejected ->
                Toast
                    .makeText(activity, result.message, Toast.LENGTH_LONG)
                    .show()
        }
    }
}

private fun handleImportedDocument(
    activity: MainActivity,
    navigation: AppNavigationState,
    imported: IncomingImportResult.Imported,
) {
    when (imported.kind) {
        IncomingDocumentKind.PDF -> {
            val file = imported.file
            val pdfFile =
                PdfFile(
                    name = file.nameWithoutExtension,
                    filePath = file.absolutePath,
                    size = FileRepository.formatSize(file.length()),
                    date = FileRepository.formatDate(file.lastModified()),
                    lastModified = file.lastModified(),
                )
            FileCache.prependFile(pdfFile)
            navigation.openFile(pdfFile)
        }
        IncomingDocumentKind.DOCX -> {
            val uri = activity.ownedContentUri(imported.file) ?: return
            navigation.importedDocxUri = uri
            navigation.importedDocxName = imported.file.nameWithoutExtension
            navigation.navigate(Screen.DOCX_TO_PDF)
        }
        IncomingDocumentKind.JPEG,
        IncomingDocumentKind.PNG,
        IncomingDocumentKind.GIF,
        IncomingDocumentKind.WEBP,
        IncomingDocumentKind.BMP,
        -> {
            val uri = activity.ownedContentUri(imported.file) ?: return
            ImageToPdfState.clear()
            ImageToPdfState.addUris(listOf(uri))
            ImageToPdfState.currentEditIndex = 0
            navigation.addingMoreImages = false
            navigation.fromSmartScan = false
            navigation.navigate(Screen.IMAGE_EDIT)
        }
    }
}

private fun MainActivity.ownedContentUri(file: File): Uri? =
    try {
        FileProvider.getUriForFile(
            this,
            "$packageName.provider",
            file,
        )
    } catch (error: IllegalArgumentException) {
        reportOwnedUriFailure(error)
        null
    } catch (error: SecurityException) {
        reportOwnedUriFailure(error)
        null
    }

private fun MainActivity.reportOwnedUriFailure(error: RuntimeException) {
    Timber
        .tag("IncomingDocument")
        .w("Imported document URI unavailable (%s)", error.javaClass.simpleName)
    Toast
        .makeText(this, "The imported document could not be opened safely", Toast.LENGTH_LONG)
        .show()
}
