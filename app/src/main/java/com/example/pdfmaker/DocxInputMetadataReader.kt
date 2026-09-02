package com.example.pdfmaker

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Reads untrusted provider metadata on the I/O dispatcher with cancellation support. */
internal suspend fun readDocxInputMetadata(
    context: Context,
    uri: Uri,
    fallbackName: String?,
): DocxInputMetadata =
    withContext(Dispatchers.IO) {
        val operationContext = currentCoroutineContext()
        operationContext.ensureActive()
        DocxToPdfPolicy.requireProviderUri(uri.scheme, uri.authority)
        val cancellationSignal = CancellationSignal()
        val cancellationHandle =
            operationContext[Job]?.invokeOnCompletion { cause ->
                if (cause is CancellationException) cancellationSignal.cancel()
            }
        try {
            val resolver = context.contentResolver
            val queried = readProviderMetadata(resolver = resolver, uri = uri, signal = cancellationSignal)
            operationContext.ensureActive()
            val mimeType = readProviderMimeType(context, uri, operationContext)
            operationContext.ensureActive()
            val descriptorSize =
                resolver.openFileDescriptor(uri, "r", cancellationSignal)?.use { descriptor ->
                    descriptor.statSize
                } ?: error("The DOCX provider returned no file descriptor")
            operationContext.ensureActive()
            DocxToPdfPolicy.metadata(
                provider =
                    DocxProviderMetadata(
                        displayName = queried.displayName,
                        mimeType = mimeType,
                        reportedSize = queried.reportedSize,
                        descriptorSize = descriptorSize,
                    ),
                fallbackName = fallbackName,
                pathSegment = uri.lastPathSegment,
            )
        } catch (cancelled: OperationCanceledException) {
            operationContext.ensureActive()
            throw cancelled
        } finally {
            cancellationHandle?.dispose()
            cancellationSignal.cancel()
        }
    }

private data class QueriedDocxMetadata(
    val displayName: String? = null,
    val reportedSize: Long? = null,
)

private fun readProviderMetadata(
    resolver: android.content.ContentResolver,
    uri: Uri,
    signal: CancellationSignal,
): QueriedDocxMetadata =
    try {
        resolver
            .query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
                signal,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    QueriedDocxMetadata()
                } else {
                    QueriedDocxMetadata(
                        displayName = cursor.optionalString(OpenableColumns.DISPLAY_NAME),
                        reportedSize = cursor.optionalLong(OpenableColumns.SIZE),
                    )
                }
            } ?: QueriedDocxMetadata()
    } catch (denied: SecurityException) {
        throw denied
    } catch (cancelled: OperationCanceledException) {
        throw cancelled
    } catch (_: RuntimeException) {
        Timber.tag("DocxMetadata").w("event=provider_metadata_unavailable")
        QueriedDocxMetadata()
    }

private fun readProviderMimeType(
    context: Context,
    uri: Uri,
    operationContext: kotlin.coroutines.CoroutineContext,
): String? =
    try {
        context.contentResolver.getType(uri)
    } catch (denied: SecurityException) {
        throw denied
    } catch (_: RuntimeException) {
        operationContext.ensureActive()
        Timber.tag("DocxMetadata").w("event=provider_mime_unavailable")
        null
    }

private fun Cursor.optionalString(columnName: String): String? {
    val index = getColumnIndex(columnName)
    return if (index >= 0 && !isNull(index)) getString(index) else null
}

private fun Cursor.optionalLong(columnName: String): Long? {
    val index = getColumnIndex(columnName)
    return if (index >= 0 && !isNull(index)) getLong(index) else null
}
