package com.example.pdfmaker

import java.io.File
import java.io.FileOutputStream
import timber.log.Timber

internal enum class IncomingImportRetention {
    USER_DOCUMENT,
    OPERATION_TEMPORARY,
}

internal object IncomingImportStoragePolicy {
    private const val TEMPORARY_IMPORT_PATH = "pdfmaker/incoming"

    fun destination(
        kind: IncomingDocumentKind,
        retention: IncomingImportRetention,
        documentDirectory: File,
        cacheDirectory: File,
    ): File =
        if (isTemporary(kind, retention)) {
            temporaryDirectory(cacheDirectory)
        } else {
            documentDirectory
        }

    fun isTemporary(kind: IncomingDocumentKind, retention: IncomingImportRetention): Boolean =
        retention == IncomingImportRetention.OPERATION_TEMPORARY || kind.isImage

    fun temporaryDirectory(cacheDirectory: File): File =
        File(cacheDirectory, TEMPORARY_IMPORT_PATH)
}

internal class ImportedDocumentArtifact private constructor(
    val file: File,
    val kind: IncomingDocumentKind,
    private val temporary: Boolean,
    private val directory: File,
) : AutoCloseable {
    private var state = ArtifactState.PENDING

    @Synchronized
    fun retain(): File {
        check(state == ArtifactState.PENDING && !temporary) {
            "Only pending persistent imports can be retained"
        }
        state = ArtifactState.RETAINED
        return file
    }

    @Synchronized
    fun transferTemporary(): TemporaryImportLease {
        check(state == ArtifactState.PENDING && temporary) {
            "Only pending temporary imports can transfer ownership"
        }
        val lease = TemporaryImportLease.claim(file, directory)
        state = ArtifactState.TRANSFERRED
        return lease
    }

    @Synchronized
    override fun close() {
        if (state != ArtifactState.PENDING) return
        if (OwnedImportCleanup.erase(file)) state = ArtifactState.CLOSED
    }

    companion object {
        fun claim(
            file: File,
            kind: IncomingDocumentKind,
            temporary: Boolean,
            expectedDirectory: File,
        ): ImportedDocumentArtifact {
            val source = requireOwnedImport(file, expectedDirectory)
            return ImportedDocumentArtifact(source, kind, temporary, expectedDirectory.canonicalFile)
        }
    }

    private enum class ArtifactState {
        PENDING,
        RETAINED,
        TRANSFERRED,
        CLOSED,
    }
}

internal class TemporaryImportLease private constructor(
    val file: File,
    private var released: Boolean = false,
) : AutoCloseable {
    @Synchronized
    override fun close() {
        if (released) return
        released = OwnedImportCleanup.erase(file)
    }

    companion object {
        fun claim(file: File, expectedDirectory: File): TemporaryImportLease =
            TemporaryImportLease(requireOwnedImport(file, expectedDirectory))
    }
}

internal object OwnedImportCleanup {
    fun erase(file: File): Boolean {
        if (!existsSafely(file)) return true
        if (deleteSafely(file)) return true

        val truncated =
            try {
                FileOutputStream(file, false).use { output ->
                    output.flush()
                    output.fd.sync()
                }
                true
            } catch (error: java.io.IOException) {
                warn(error)
                false
            } catch (error: SecurityException) {
                warn(error)
                false
            }
        val deleted = truncated && deleteSafely(file)
        if (!deleted) Timber.tag("ImportCleanup").w("Owned import cleanup was deferred")
        return deleted || !existsSafely(file)
    }

    private fun deleteSafely(file: File): Boolean =
        try {
            file.delete()
        } catch (error: SecurityException) {
            warn(error)
            false
        }

    private fun existsSafely(file: File): Boolean =
        try {
            file.exists()
        } catch (error: SecurityException) {
            warn(error)
            true
        }

    private fun warn(error: Exception) {
        Timber.tag("ImportCleanup").w("Owned import cleanup failed (%s)", error.javaClass.simpleName)
    }
}

private fun requireOwnedImport(file: File, expectedDirectory: File): File {
    val source = file.canonicalFile
    val directory = expectedDirectory.canonicalFile
    require(source.isFile && source.parentFile == directory) {
        "Imported file is outside its owned directory"
    }
    return source
}

private val IncomingDocumentKind.isImage: Boolean
    get() =
        when (this) {
            IncomingDocumentKind.JPEG,
            IncomingDocumentKind.PNG,
            IncomingDocumentKind.GIF,
            IncomingDocumentKind.WEBP,
            IncomingDocumentKind.BMP,
            -> true

            IncomingDocumentKind.PDF,
            IncomingDocumentKind.DOCX,
            -> false
        }
