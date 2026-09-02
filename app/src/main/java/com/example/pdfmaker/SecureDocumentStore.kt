package com.example.pdfmaker

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.util.UUID
import java.util.concurrent.CancellationException
import timber.log.Timber

/** Applies the secure-document codec to application-owned files using atomic replacement. */
object SecureDocumentStore {
    const val MAX_DOCUMENT_BYTES = SecureDocumentLimits.MAX_PLAINTEXT_BYTES
    const val MAX_ENCRYPTED_DOCUMENT_BYTES = SecureDocumentLimits.MAX_ENCRYPTED_BYTES

    @Synchronized
    fun lockInPlace(
        file: File,
        password: String,
        beforeChunk: () -> Unit = {},
    ): String? =
        safely(
            onFailure = { error ->
                UserVisibleFailurePolicy.message(UserFailureStage.DOCUMENT_LOCK, error)
            },
        ) {
            val target = validateWritableFile(file)
            if (password.length !in 4..128) return@safely "Password must contain 4 to 128 characters"
            if (isLocked(target)) return@safely "File is already locked"
            val plaintextLength = SecureDocumentLimits.requirePlaintextLength(target.length())
            transformAtomically(target) { input, output ->
                beforeChunk()
                SecureDocumentCodec.encrypt(
                    SecureDocumentTypePolicy.verifiedInput(input),
                    output,
                    plaintextLength,
                    password,
                    beforeChunk = beforeChunk,
                )
                true
            }
            null
        }

    @Synchronized
    fun unlockInPlace(
        file: File,
        password: String,
        beforeChunk: () -> Unit = {},
    ): String? =
        safely(
            onFailure = { error ->
                UserVisibleFailurePolicy.message(UserFailureStage.DOCUMENT_UNLOCK, error)
            },
        ) {
            val target = validateWritableFile(file)
            val encryptedLength = SecureDocumentLimits.requireEncryptedLength(target.length())
            val decrypted = transformAtomically(
                target = target,
                validateOutput = SecureDocumentTypePolicy::requirePlainPdfFile,
            ) { input, output ->
                SecureDocumentCodec.decrypt(
                    input,
                    output,
                    encryptedLength,
                    password,
                    beforeChunk,
                )
            }
            if (!decrypted) return@safely "Wrong password or damaged file"
            null
        }

    fun isLocked(file: File): Boolean = safely(onFailure = { false }) {
        if (!file.isFile || file.length() < DOCUMENT_MAGIC_BYTES) return@safely false
        file.inputStream().use { input ->
            val header = ByteArray(DOCUMENT_MAGIC_BYTES)
            var offset = 0
            while (offset < header.size) {
                val read = input.read(header, offset, header.size - offset)
                if (read <= 0) return@safely false
                offset += read
            }
            SecureDocumentCodec.isEncrypted(header)
        }
    }

    fun encryptedCopy(
        context: Context,
        bytes: ByteArray,
        password: String,
        baseName: String,
    ): Pair<String, String>? = encryptedCopy(getPdfMakerDir(context), bytes, password, baseName)

    internal fun encryptedCopy(
        destinationDirectory: File,
        bytes: ByteArray,
        password: String,
        baseName: String,
    ): Pair<String, String>? = safely(onFailure = { null }) {
        SecureDocumentLimits.requirePlaintextLength(bytes.size.toLong())
        SecureDocumentTypePolicy.requirePlainPdf(
            bytes.copyOfRange(0, minOf(bytes.size, SecureDocumentTypePolicy.SIGNATURE_BYTES)),
        )
        val output = OutputStore.writeUnique(
            destinationDirectory,
            "${SafeFileName.baseName(baseName)}_locked",
            "pdf",
        ) { destination ->
            ByteArrayInputStream(bytes).use { input ->
                SecureDocumentCodec.encrypt(input, destination, bytes.size.toLong(), password)
            }
        }
        output.absolutePath to output.name
    }

    fun decryptedCopy(
        source: File,
        destinationDirectory: File,
        password: String,
        beforeChunk: () -> Unit = {},
    ): File? = safely(onFailure = { null }) {
        val inputFile = source.canonicalFile
        require(inputFile.isFile && inputFile.canRead()) { "Locked file is unavailable" }
        val encryptedLength = SecureDocumentLimits.requireEncryptedLength(inputFile.length())
        OutputStore.writeUnique(
            destinationDirectory,
            "pdfmaker-unlocked",
            "pdf",
            beforeCommit = beforeChunk,
        ) { destination ->
            val validatingOutput = PlainPdfValidatingOutputStream(destination)
            val decrypted = FileInputStream(inputFile).use { input ->
                SecureDocumentCodec.decrypt(
                    input,
                    validatingOutput,
                    encryptedLength,
                    password,
                    beforeChunk,
                )
            }
            if (!decrypted) throw SecureDocumentRejectedException()
            validatingOutput.requirePlainPdf()
        }
    }

    private fun validateWritableFile(file: File): File {
        val target = file.canonicalFile
        require(target.isFile) { "File not found" }
        require(target.canWrite()) { "File is not writable" }
        return target
    }

    private fun transformAtomically(
        target: File,
        validateOutput: (File) -> Unit = {},
        transform: (InputStream, OutputStream) -> Boolean,
    ): Boolean {
        val parent = requireNotNull(target.parentFile?.canonicalFile) { "File has no parent directory" }
        val temporary = File(parent, ".pdfmaker-secure-${UUID.randomUUID()}.tmp").canonicalFile
        require(temporary.parentFile == parent) { "Temporary file escaped its directory" }
        var committed = false
        try {
            val transformed = FileInputStream(target).use { input ->
                FileOutputStream(temporary).use { output ->
                    val success = transform(input, output)
                    if (success) {
                        output.flush()
                        output.fd.sync()
                    }
                    success
                }
            }
            if (!transformed) return false
            validateOutput(temporary)
            moveReplacing(temporary, target)
            committed = true
            return true
        } finally {
            if (!committed) temporary.delete()
        }
    }

    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private inline fun <T> safely(
        onFailure: (Exception) -> T,
        operation: () -> T,
    ): T = try {
        operation()
    } catch (error: SecureDocumentRejectedException) {
        onFailure(error)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: java.io.IOException) {
        reportFailure(error)
        onFailure(error)
    } catch (error: GeneralSecurityException) {
        reportFailure(error)
        onFailure(error)
    } catch (error: SecurityException) {
        reportFailure(error)
        onFailure(error)
    } catch (error: ProviderException) {
        reportFailure(error)
        onFailure(error)
    } catch (error: IllegalArgumentException) {
        reportFailure(error)
        onFailure(error)
    } catch (error: IllegalStateException) {
        reportFailure(error)
        onFailure(error)
    } catch (error: ArithmeticException) {
        reportFailure(error)
        onFailure(error)
    } catch (error: UnsupportedOperationException) {
        reportFailure(error)
        onFailure(error)
    }

    private fun reportFailure(error: Exception) {
        Timber.tag("SecureDocumentStore").w(
            ObservabilityPolicy.sanitizedThrowable(error),
            "event=secure_document_failure",
        )
    }

    private const val DOCUMENT_MAGIC_BYTES = 8

    private class SecureDocumentRejectedException : IllegalStateException("Secure document was rejected")
}
