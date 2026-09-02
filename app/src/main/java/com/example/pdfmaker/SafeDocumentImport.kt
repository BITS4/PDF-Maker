package com.example.pdfmaker

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
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

sealed interface IncomingImportResult {
    data class Imported(val file: File, val kind: IncomingDocumentKind) : IncomingImportResult
    data class Rejected(val message: String) : IncomingImportResult
}

/** Copies untrusted content providers into an owned, bounded and validated file. */
object SafeDocumentImporter {
    const val MAX_IMPORT_BYTES = 100L * 1024L * 1024L

    fun import(context: Context, request: IncomingDocumentRequest): IncomingImportResult {
        if (request.uri.scheme != "content" || request.uri.authority.isNullOrBlank()) {
            return IncomingImportResult.Rejected("Only content-provider documents can be imported")
        }

        val resolver = context.contentResolver
        val metadata = runCatching {
            var displayName: String? = null
            var reportedSize = -1L
            resolver.query(
                request.uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        .takeIf { it >= 0 }
                        ?.let { displayName = cursor.getString(it) }
                    cursor.getColumnIndex(OpenableColumns.SIZE)
                        .takeIf { it >= 0 && !cursor.isNull(it) }
                        ?.let { reportedSize = cursor.getLong(it) }
                }
            }
            ImportMetadata(displayName, reportedSize, resolver.getType(request.uri))
        }.getOrElse {
            return IncomingImportResult.Rejected("The document provider could not be read")
        }
        if (metadata.reportedSize > MAX_IMPORT_BYTES) {
            return IncomingImportResult.Rejected("The document exceeds the 100 MB import limit")
        }

        val outputDirectory = getPdfMakerDir(context)
        val temporary = File(outputDirectory, ".incoming-${UUID.randomUUID()}.tmp")
        return try {
            val stream = resolver.openInputStream(request.uri)
                ?: return IncomingImportResult.Rejected("The document provider returned no data")
            val copied = stream.use { input ->
                FileOutputStream(temporary).use { output ->
                    val count = BoundedIo.copy(input, output, MAX_IMPORT_BYTES)
                    output.flush()
                    output.fd.sync()
                    count
                }
            }
            require(copied > 0) { "The document is empty" }

            val kind = ImportedDocumentInspector.inspect(temporary)
                ?: error("The document content is not a supported PDF, DOCX, or image")
            require(ImportedDocumentInspector.mimeTypesMatch(kind, metadata.resolverMime, request.declaredMimeType)) {
                "The document type does not match its content"
            }
            val requestedName = metadata.displayName
                ?.let { it.substringBeforeLast('.', it) }
                ?: request.uri.lastPathSegment?.substringAfterLast('/')
                ?: "imported_document"
            val imported = OutputStore.commitTemporaryUnique(
                temporary,
                outputDirectory,
                requestedName,
                kind.extension,
            )
            IncomingImportResult.Imported(imported, kind)
        } catch (error: Exception) {
            temporary.delete()
            IncomingImportResult.Rejected(
                when (error) {
                    is IllegalArgumentException, is IllegalStateException ->
                        error.message ?: "The document could not be imported"
                    else -> "The document could not be imported safely"
                },
            )
        }
    }

    private data class ImportMetadata(
        val displayName: String?,
        val reportedSize: Long,
        val resolverMime: String?,
    )
}

/** Signature and container checks performed after the provider stream is fully bounded. */
object ImportedDocumentInspector {
    private const val MAX_IMAGE_PIXELS = 16_000_000L
    private const val MAX_DOCX_ENTRIES = 2_000
    private const val MAX_DOCX_ENTRY_BYTES = 50L * 1024L * 1024L
    private const val MAX_DOCX_EXPANDED_BYTES = 200L * 1024L * 1024L
    private const val MAX_COMPRESSION_RATIO = 250L

    fun inspect(file: File): IncomingDocumentKind? {
        if (!file.isFile || file.length() <= 0) return null
        val prefix = file.inputStream().use { BoundedIo.readPrefix(it, 64) }
        return when (signature(prefix)) {
            IncomingDocumentKind.PDF -> IncomingDocumentKind.PDF
            IncomingDocumentKind.DOCX -> if (isSafeDocx(file)) IncomingDocumentKind.DOCX else null
            IncomingDocumentKind.JPEG,
            IncomingDocumentKind.PNG,
            IncomingDocumentKind.GIF,
            IncomingDocumentKind.WEBP,
            IncomingDocumentKind.BMP -> validateImage(file, signature(prefix))
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

    private fun isSafeDocx(file: File): Boolean = runCatching {
        var entryCount = 0
        var expandedTotal = 0L
        var hasContentTypes = false
        var hasDocument = false
        ZipFile(file).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                entryCount += 1
                require(entryCount <= MAX_DOCX_ENTRIES) { "DOCX contains too many entries" }
                require(isSafeZipName(entry.name)) { "DOCX contains an unsafe entry name" }
                if (entry.name == "[Content_Types].xml") hasContentTypes = true
                if (entry.name == "word/document.xml") hasDocument = true
                if (entry.isDirectory) continue

                if (entry.size >= 0) {
                    require(entry.size <= MAX_DOCX_ENTRY_BYTES) { "DOCX entry is too large" }
                    require(expandedTotal <= MAX_DOCX_EXPANDED_BYTES - entry.size) { "DOCX expands beyond its limit" }
                    if (entry.size >= 1024L * 1024L && entry.compressedSize > 0) {
                        require(entry.size / entry.compressedSize <= MAX_COMPRESSION_RATIO) {
                            "DOCX compression ratio is unsafe"
                        }
                    }
                }
                var expandedEntry = 0L
                zip.getInputStream(entry).use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        expandedEntry += read
                        require(expandedEntry <= MAX_DOCX_ENTRY_BYTES) { "DOCX entry is too large" }
                    }
                }
                require(expandedTotal <= MAX_DOCX_EXPANDED_BYTES - expandedEntry) { "DOCX expands beyond its limit" }
                expandedTotal += expandedEntry
            }
        }
        hasContentTypes && hasDocument
    }.getOrDefault(false)

    private fun validateImage(file: File, detected: IncomingDocumentKind?): IncomingDocumentKind? {
        val kind = detected ?: return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
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
