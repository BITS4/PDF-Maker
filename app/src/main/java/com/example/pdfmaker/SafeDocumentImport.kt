package com.example.pdfmaker

import android.content.Context
import android.database.Cursor
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

data class IncomingDocumentRequest(
    val uri: Uri,
    val declaredMimeType: String?,
)

enum class IncomingDocumentKind(val extension: String) {
    PDF("pdf"),
    DOCX("docx"),
    JPEG("jpg"),
    PNG("png"),
    GIF("gif"),
    WEBP("webp"),
    BMP("bmp"),
}

internal sealed interface IncomingImportResult {
    data class Imported(val artifact: ImportedDocumentArtifact) : IncomingImportResult

    data class Rejected(val message: String) : IncomingImportResult
}

/** Copies untrusted content providers into an owned, bounded and validated file. */
internal object SafeDocumentImporter {
    const val MAX_IMPORT_BYTES = 100L * 1024L * 1024L

    fun import(
        context: Context,
        request: IncomingDocumentRequest,
        retention: IncomingImportRetention = IncomingImportRetention.USER_DOCUMENT,
        beforeChunk: () -> Unit = {},
    ): IncomingImportResult =
        try {
            requireSupportedRequest(request)
            val metadata = readMetadata(context, request, beforeChunk)
            requireReportedSize(metadata.reportedSize)
            IncomingImportResult.Imported(
                importValidatedDocument(context, request, retention, metadata, beforeChunk),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (rejection: ImportRejection) {
            IncomingImportResult.Rejected(rejection.safeMessage)
        } catch (error: Exception) {
            IncomingImportResult.Rejected(
                UserVisibleFailureReporter.message(
                    UserFailureStage.DOCUMENT_IMPORT,
                    error,
                ),
            )
        }

    private fun requireSupportedRequest(request: IncomingDocumentRequest) {
        if (request.uri.scheme != "content" || request.uri.authority.isNullOrBlank()) {
            throw ImportRejection("Only content-provider documents can be imported")
        }
    }

    private fun readMetadata(
        context: Context,
        request: IncomingDocumentRequest,
        beforeChunk: () -> Unit,
    ): ImportMetadata {
        try {
            beforeChunk()
            var displayName: String? = null
            var reportedSize = -1L
            context.contentResolver
                .query(
                    request.uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        displayName = cursor.stringValue(OpenableColumns.DISPLAY_NAME)
                        reportedSize = cursor.longValue(OpenableColumns.SIZE) ?: -1L
                    }
                }
            beforeChunk()
            return ImportMetadata(displayName, reportedSize, context.contentResolver.getType(request.uri))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw ImportRejection("The document provider could not be read", error)
        }
    }

    private fun requireReportedSize(reportedSize: Long) {
        if (reportedSize > MAX_IMPORT_BYTES) {
            throw ImportRejection("The document exceeds the 100 MB import limit")
        }
    }

    private fun importValidatedDocument(
        context: Context,
        request: IncomingDocumentRequest,
        retention: IncomingImportRetention,
        metadata: ImportMetadata,
        beforeChunk: () -> Unit,
    ): ImportedDocumentArtifact = ImportTransaction().use { transaction ->
        val stagingDirectory = requireStagingDirectory(context.cacheDir)
        val snapshot = transaction.trackSnapshot(File(stagingDirectory, ".incoming-${UUID.randomUUID()}.tmp"))
        copyProviderSnapshot(context, request.uri, snapshot, beforeChunk)
        val kind = requireSupportedContent(snapshot, metadata, request.declaredMimeType, beforeChunk)
        val outputDirectory =
            IncomingImportStoragePolicy.destination(
                kind = kind,
                retention = retention,
                documentDirectory = getPdfMakerDir(context),
                cacheDirectory = context.cacheDir,
            )
        val committed =
            transaction.trackCommitted(
                commitValidatedSnapshot(
                    snapshot = snapshot,
                    outputDirectory = outputDirectory,
                    requestedName = requestedName(metadata.displayName, request.uri),
                    kind = kind,
                    beforeChunk = beforeChunk,
                ),
            )
        beforeChunk()
        transaction.removeSnapshot()
        val artifact =
            ImportedDocumentArtifact.claim(
                file = committed,
                kind = kind,
                temporary = IncomingImportStoragePolicy.isTemporary(kind, retention),
                expectedDirectory = outputDirectory,
            )
        transaction.trackArtifact(artifact)
        beforeChunk()
        transaction.deliver()
    }

    private fun requireStagingDirectory(cacheDirectory: File): File {
        val directory = IncomingImportStoragePolicy.temporaryDirectory(cacheDirectory)
        check(directory.isDirectory || (!directory.exists() && directory.mkdirs())) {
            "Could not create the temporary import directory"
        }
        return directory
    }

    private fun copyProviderSnapshot(
        context: Context,
        uri: Uri,
        snapshot: File,
        beforeChunk: () -> Unit,
    ) {
        val stream =
            context.contentResolver.openInputStream(uri)
                ?: throw ImportRejection("The document provider returned no data")
        val copied =
            stream.use { input ->
                FileOutputStream(snapshot).use { output ->
                    BoundedIo.copy(input, output, MAX_IMPORT_BYTES, beforeChunk).also {
                        output.flush()
                        output.fd.sync()
                    }
                }
            }
        if (copied == 0L) throw ImportRejection("The document is empty")
    }

    private fun requireSupportedContent(
        snapshot: File,
        metadata: ImportMetadata,
        declaredMimeType: String?,
        beforeChunk: () -> Unit,
    ): IncomingDocumentKind {
        val kind =
            ImportedDocumentInspector.inspect(snapshot, beforeChunk)
                ?: throw ImportRejection("The document content is not a supported PDF, DOCX, or image")
        if (!ImportedDocumentInspector.mimeTypesMatch(kind, metadata.resolverMime, declaredMimeType)) {
            throw ImportRejection("The document type does not match its content")
        }
        return kind
    }

    private fun requestedName(displayName: String?, uri: Uri): String =
        displayName
            ?.substringBeforeLast('.', displayName)
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "imported_document"

    private fun Cursor.stringValue(column: String): String? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getString(index) else null
    }

    private fun Cursor.longValue(column: String): Long? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getLong(index) else null
    }

    private fun commitValidatedSnapshot(
        snapshot: File,
        outputDirectory: File,
        requestedName: String,
        kind: IncomingDocumentKind,
        beforeChunk: () -> Unit,
    ): File {
        beforeChunk()
        val sourceDirectory = snapshot.canonicalFile.parentFile
        val destination = outputDirectory.canonicalFile
        if (sourceDirectory == destination) {
            return OutputStore.commitTemporaryUnique(snapshot, destination, requestedName, kind.extension)
        }
        val expectedBytes = snapshot.length()
        return OutputStore.writeUnique(
            directory = destination,
            requestedBaseName = requestedName,
            extension = kind.extension,
            beforeCommit = beforeChunk,
        ) { destinationStream ->
            snapshot.inputStream().use { source ->
                val copied = BoundedIo.copy(source, destinationStream, MAX_IMPORT_BYTES, beforeChunk)
                require(copied == expectedBytes) {
                    "The validated import snapshot changed unexpectedly"
                }
            }
        }
    }

    private data class ImportMetadata(
        val displayName: String?,
        val reportedSize: Long,
        val resolverMime: String?,
    )

    private class ImportRejection(
        val safeMessage: String,
        cause: Throwable? = null,
    ) : Exception(safeMessage, cause)

    private class ImportTransaction : Closeable {
        private var snapshot: File? = null
        private var committed: File? = null
        private var artifact: ImportedDocumentArtifact? = null

        fun trackSnapshot(file: File): File = file.also { snapshot = it }

        fun trackCommitted(file: File): File = file.also { committed = it }

        fun trackArtifact(value: ImportedDocumentArtifact) {
            artifact = value
        }

        fun removeSnapshot() {
            val tracked = snapshot ?: return
            check(OwnedImportCleanup.erase(tracked)) {
                "The temporary import snapshot could not be removed"
            }
            snapshot = null
        }

        fun deliver(): ImportedDocumentArtifact {
            val delivered = checkNotNull(artifact)
            artifact = null
            committed = null
            check(snapshot == null) { "The temporary import snapshot is still owned" }
            return delivered
        }

        override fun close() {
            artifact?.close()
            committed?.let(OwnedImportCleanup::erase)
            snapshot?.let(OwnedImportCleanup::erase)
        }
    }
}

/** Signature and container checks performed after the provider stream is fully bounded. */
object ImportedDocumentInspector {
    private const val MAX_IMAGE_PIXELS = 16_000_000L
    private const val MAX_DOCX_ENTRIES = 2_000
    private const val MAX_DOCX_ENTRY_BYTES = 50L * 1024L * 1024L
    private const val MAX_DOCX_EXPANDED_BYTES = 200L * 1024L * 1024L
    private const val MAX_COMPRESSION_RATIO = 250L

    fun inspect(file: File, beforeChunk: () -> Unit = {}): IncomingDocumentKind? {
        if (!file.isFile || file.length() <= 0) return null
        beforeChunk()
        val prefix = file.inputStream().use { BoundedIo.readPrefix(it, 64) }
        beforeChunk()
        return when (signature(prefix)) {
            IncomingDocumentKind.PDF -> IncomingDocumentKind.PDF
            IncomingDocumentKind.DOCX -> if (isSafeDocx(file, beforeChunk)) IncomingDocumentKind.DOCX else null
            IncomingDocumentKind.JPEG,
            IncomingDocumentKind.PNG,
            IncomingDocumentKind.GIF,
            IncomingDocumentKind.WEBP,
            IncomingDocumentKind.BMP -> validateImage(file, signature(prefix), beforeChunk)
            null -> null
        }
    }

    fun signature(prefix: ByteArray): IncomingDocumentKind? = when {
        prefix.startsWithAscii("%PDF-") || SecureDocumentCodec.isEncrypted(prefix) -> IncomingDocumentKind.PDF
        prefix.startsWith(byteArrayOf(0x50, 0x4B, 0x03, 0x04)) -> IncomingDocumentKind.DOCX
        prefix.startsWith(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())) -> IncomingDocumentKind.JPEG
        prefix.startsWith(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) -> IncomingDocumentKind.PNG
        prefix.startsWithAscii("GIF87a") || prefix.startsWithAscii("GIF89a") -> IncomingDocumentKind.GIF
        prefix.size >= 12 && prefix.startsWithAscii("RIFF") &&
            prefix.copyOfRange(8, 12).contentEquals("WEBP".toByteArray(Charsets.US_ASCII)) -> IncomingDocumentKind.WEBP
        prefix.startsWithAscii("BM") -> IncomingDocumentKind.BMP
        else -> null
    }

    fun mimeTypesMatch(kind: IncomingDocumentKind, vararg rawMimeTypes: String?): Boolean =
        rawMimeTypes.asSequence()
            .mapNotNull { it?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) }
            .filterNot { it.isBlank() || it == "*/*" || it == "application/octet-stream" }
            .all { mime ->
                when (kind) {
                    IncomingDocumentKind.PDF -> mime == "application/pdf"
                    IncomingDocumentKind.DOCX -> mime in setOf(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "application/zip",
                    )
                    else -> mime == "image/*" || mime == "image/${kind.extension}" ||
                        (kind == IncomingDocumentKind.JPEG && mime == "image/jpeg")
                }
            }

    private fun isSafeDocx(file: File, beforeChunk: () -> Unit): Boolean =
        try {
            inspectDocxEntries(file, beforeChunk)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            false
        } catch (_: RuntimeException) {
            false
        }

    private fun inspectDocxEntries(file: File, beforeChunk: () -> Unit): Boolean {
        val state = DocxScanState()
        ZipFile(file).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                beforeChunk()
                val entry = entries.nextElement()
                state.validateEntry(entry)
                if (!entry.isDirectory) {
                    state.recordExpanded(entry.name, expandedSize(zip, entry, beforeChunk))
                }
            }
        }
        return state.hasRequiredParts
    }

    private fun expandedSize(
        zip: ZipFile,
        entry: ZipEntry,
        beforeChunk: () -> Unit,
    ): Long =
        zip.getInputStream(entry).use { input ->
            BoundedIo.copy(input, DISCARDING_OUTPUT, MAX_DOCX_ENTRY_BYTES, beforeChunk)
        }

    private class DocxScanState {
        private val entryNames = mutableSetOf<String>()
        private var entryCount = 0
        private var expandedTotal = 0L
        private var hasContentTypes = false
        private var hasDocument = false

        val hasRequiredParts: Boolean
            get() = hasContentTypes && hasDocument

        fun validateEntry(entry: ZipEntry) {
            entryCount += 1
            require(entryCount <= MAX_DOCX_ENTRIES) { "DOCX contains too many entries" }
            require(isSafeZipName(entry.name)) { "DOCX contains an unsafe entry name" }
            require(entryNames.add(entry.name)) { "DOCX contains duplicate entries" }
            if (entry.isDirectory || entry.size < 0) return
            require(entry.size <= MAX_DOCX_ENTRY_BYTES) { "DOCX entry is too large" }
            require(expandedTotal <= MAX_DOCX_EXPANDED_BYTES - entry.size) {
                "DOCX expands beyond its limit"
            }
            requireSafeCompressionRatio(entry)
        }

        fun recordExpanded(name: String, expandedBytes: Long) {
            require(expandedTotal <= MAX_DOCX_EXPANDED_BYTES - expandedBytes) {
                "DOCX expands beyond its limit"
            }
            expandedTotal += expandedBytes
            if (name == "[Content_Types].xml") hasContentTypes = true
            if (name == "word/document.xml") hasDocument = true
        }

        private fun requireSafeCompressionRatio(entry: ZipEntry) {
            if (entry.size < 1024L * 1024L || entry.compressedSize <= 0) return
            val roundedRatio = (entry.size + entry.compressedSize - 1L) / entry.compressedSize
            require(roundedRatio <= MAX_COMPRESSION_RATIO) { "DOCX compression ratio is unsafe" }
        }
    }

    private val DISCARDING_OUTPUT =
        object : OutputStream() {
            override fun write(value: Int) = Unit

            override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
        }

    private fun validateImage(
        file: File,
        detected: IncomingDocumentKind?,
        beforeChunk: () -> Unit,
    ): IncomingDocumentKind? {
        val kind = detected ?: return null
        beforeChunk()
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        beforeChunk()
        val width = options.outWidth
        val height = options.outHeight
        if (width <= 0 || height <= 0 || width.toLong() * height.toLong() > MAX_IMAGE_PIXELS) return null
        return kind
    }

    private fun isSafeZipName(raw: String): Boolean {
        if (raw.isBlank() || raw.length > 240 || raw.startsWith('/') || raw.startsWith('\\')) return false
        val normalized = raw.replace('\\', '/')
        return normalized.split('/').none { it == "." || it == ".." } && ':' !in normalized
    }

    private fun ByteArray.startsWith(expected: ByteArray): Boolean =
        size >= expected.size && copyOfRange(0, expected.size).contentEquals(expected)

    private fun ByteArray.startsWithAscii(expected: String): Boolean =
        startsWith(expected.toByteArray(Charsets.US_ASCII))
}
