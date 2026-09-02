package com.example.pdfmaker

import android.net.Uri
import android.os.CancellationSignal
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

@Composable
internal fun IncomingDocumentEffect(
    activity: MainActivity,
    navigation: AppNavigationState,
) {
    val request = activity.incomingDocumentRequest
    LaunchedEffect(request) {
        val activeRequest = request ?: return@LaunchedEffect
        val pendingArtifact = AtomicReference<ImportedDocumentArtifact?>()
        try {
            val result =
                importIncomingDocument(activity, activeRequest, pendingArtifact)
            if (!activity.claimIncomingDocument(activeRequest.requestId)) return@LaunchedEffect
            when (result) {
                is IncomingImportResult.Imported ->
                    handleImportedDocument(
                        activity = activity,
                        navigation = navigation,
                        artifact = result.artifact,
                    )
                is IncomingImportResult.Rejected ->
                    Toast
                        .makeText(activity, result.message, Toast.LENGTH_LONG)
                        .show()
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                pendingArtifact.getAndSet(null)?.close()
            }
        }
    }
}

private suspend fun importIncomingDocument(
    activity: MainActivity,
    request: IncomingDocumentRequest,
    pendingArtifact: AtomicReference<ImportedDocumentArtifact?>,
): IncomingImportResult =
    withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val providerCancellation = CancellationSignal()
            continuation.invokeOnCancellation { providerCancellation.cancel() }
            try {
                val result =
                    SafeDocumentImporter
                        .import(
                            context = activity,
                            request = request,
                            retention = IncomingImportRetention.USER_DOCUMENT,
                            beforeChunk = { continuation.context.ensureActive() },
                            providerCancellation = providerCancellation,
                        ).also { importResult ->
                            if (importResult is IncomingImportResult.Imported) {
                                pendingArtifact.set(importResult.artifact)
                            }
                        }
                continuation.resume(result)
            } catch (cancelled: java.util.concurrent.CancellationException) {
                continuation.cancel(cancelled)
            }
        }
    }

private fun handleImportedDocument(
    activity: MainActivity,
    navigation: AppNavigationState,
    artifact: ImportedDocumentArtifact,
) {
    when (artifact.kind) {
        IncomingDocumentKind.PDF -> {
            val file = artifact.retain()
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
            val uri = activity.ownedContentUri(artifact.file)
            if (uri != null) {
                val file = artifact.retain()
                navigation.importedDocxUri = uri
                navigation.importedDocxName = file.nameWithoutExtension
                navigation.navigate(Screen.DOCX_TO_PDF)
            }
        }
        IncomingDocumentKind.JPEG,
        IncomingDocumentKind.PNG,
        IncomingDocumentKind.GIF,
        IncomingDocumentKind.WEBP,
        IncomingDocumentKind.BMP,
        -> {
            val uri = activity.ownedContentUri(artifact.file)
            if (uri != null) {
                val temporarySource = artifact.transferTemporary()
                try {
                    ImageToPdfState.replaceWithTemporaryImport(uri, temporarySource)
                } catch (expectedFailure: RuntimeException) {
                    temporarySource.close()
                    throw expectedFailure
                }
                navigation.addingMoreImages = false
                navigation.fromSmartScan = false
                navigation.navigate(Screen.IMAGE_EDIT)
            }
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
