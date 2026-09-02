package com.example.pdfmaker

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal const val LEGACY_DOCUMENT_MAGIC = "PDFLOCK1"
internal const val AUTHENTICATED_DOCUMENT_MAGIC = "PDFLOCK2"

/** Versioned authenticated encryption with read compatibility for the original CBC format. */
object SecureDocumentCodec {
    private const val ITERATIONS = 210_000
    private const val MIN_ITERATIONS = 1_000
    private const val MAX_ITERATIONS = 2_000_000
    private const val SALT_SIZE = 16
    private const val NONCE_SIZE = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256
    private const val V2_HEADER_SIZE = 8 + Int.SIZE_BYTES + SALT_SIZE + NONCE_SIZE
    private const val MIN_V2_SIZE = V2_HEADER_SIZE + TAG_BITS / 8
    private const val LEGACY_HEADER_SIZE = 8 + SALT_SIZE + 16

    fun encrypt(
        plaintext: ByteArray,
        password: String,
        random: SecureRandom = SecureRandom(),
        iterations: Int = ITERATIONS,
    ): ByteArray {
        require(plaintext.isNotEmpty()) { "Cannot encrypt an empty document" }
        require(password.length in 4..128) { "Password must contain 4 to 128 characters" }
        require(iterations in MIN_ITERATIONS..MAX_ITERATIONS) { "Invalid key derivation work factor" }

        val salt = ByteArray(SALT_SIZE).also(random::nextBytes)
        val nonce = ByteArray(NONCE_SIZE).also(random::nextBytes)
        val header = ByteBuffer.allocate(V2_HEADER_SIZE)
            .put(AUTHENTICATED_DOCUMENT_MAGIC.toByteArray(Charsets.US_ASCII))
            .putInt(iterations)
            .put(salt)
            .put(nonce)
            .array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(deriveKey(password, salt, iterations), "AES"),
            GCMParameterSpec(TAG_BITS, nonce),
        )
        cipher.updateAAD(header)
        return header + cipher.doFinal(plaintext)
    }

    fun decrypt(encrypted: ByteArray, password: String): ByteArray? {
        if (password.length !in 4..128) return null
        return when {
            encrypted.hasMagic(AUTHENTICATED_DOCUMENT_MAGIC) -> decryptAuthenticated(encrypted, password)
            encrypted.hasMagic(LEGACY_DOCUMENT_MAGIC) -> decryptLegacy(encrypted, password)
            else -> null
        }
    }

    fun isEncrypted(bytes: ByteArray): Boolean =
        bytes.hasMagic(AUTHENTICATED_DOCUMENT_MAGIC) || bytes.hasMagic(LEGACY_DOCUMENT_MAGIC)

    private fun decryptAuthenticated(encrypted: ByteArray, password: String): ByteArray? {
        if (encrypted.size < MIN_V2_SIZE) return null
        return try {
            val buffer = ByteBuffer.wrap(encrypted)
            val magic = ByteArray(8).also { buffer.get(it) }
            val iterations = buffer.int
            if (!MessageDigest.isEqual(magic, AUTHENTICATED_DOCUMENT_MAGIC.toByteArray(Charsets.US_ASCII)) ||
                iterations !in MIN_ITERATIONS..MAX_ITERATIONS
            ) {
                return null
            }
            val salt = ByteArray(SALT_SIZE).also { buffer.get(it) }
            val nonce = ByteArray(NONCE_SIZE).also { buffer.get(it) }
            val ciphertext = ByteArray(buffer.remaining()).also { buffer.get(it) }
            val header = encrypted.copyOfRange(0, V2_HEADER_SIZE)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(deriveKey(password, salt, iterations), "AES"),
                GCMParameterSpec(TAG_BITS, nonce),
            )
            cipher.updateAAD(header)
            cipher.doFinal(ciphertext)
        } catch (_: AEADBadTagException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun decryptLegacy(encrypted: ByteArray, password: String): ByteArray? {
        if (encrypted.size <= LEGACY_HEADER_SIZE) return null
        return try {
            val salt = encrypted.copyOfRange(8, 24)
            val iv = encrypted.copyOfRange(24, 40)
            val ciphertext = encrypted.copyOfRange(40, encrypted.size)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(deriveKey(password, salt, 65_536), "AES"),
                IvParameterSpec(iv),
            )
            cipher.doFinal(ciphertext)
        } catch (_: Exception) {
            null
        }
    }

    private fun deriveKey(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun ByteArray.hasMagic(value: String): Boolean {
        val expected = value.toByteArray(Charsets.US_ASCII)
        return size >= expected.size && MessageDigest.isEqual(copyOfRange(0, expected.size), expected)
    }
}

object SecureDocumentStore {
    const val MAX_DOCUMENT_BYTES = 100L * 1024L * 1024L

    fun lockInPlace(file: File, password: String): String? {
        return try {
            validateWritableDocument(file)
            if (password.length !in 4..128) return "Password must contain 4 to 128 characters"
            val bytes = readBounded(file)
            if (SecureDocumentCodec.isEncrypted(bytes)) return "File is already locked"
            OutputStore.replaceAtomically(file, SecureDocumentCodec.encrypt(bytes, password))
            null
        } catch (error: Exception) {
            safeError("Could not lock file", error)
        }
    }

    fun unlockInPlace(file: File, password: String): String? {
        return try {
            validateWritableDocument(file)
            val encrypted = readBounded(file)
            val plaintext = SecureDocumentCodec.decrypt(encrypted, password)
                ?: return "Wrong password or damaged file"
            OutputStore.replaceAtomically(file, plaintext)
            null
        } catch (error: Exception) {
            safeError("Could not unlock file", error)
        }
    }

    fun isLocked(file: File): Boolean {
        if (!file.isFile || file.length() < 8) return false
        return try {
            file.inputStream().use { input ->
                val header = ByteArray(8)
                input.read(header) == header.size && SecureDocumentCodec.isEncrypted(header)
            }
        } catch (_: Exception) {
            false
        }
    }

    fun encryptedCopy(context: Context, bytes: ByteArray, password: String, baseName: String): Pair<String, String>? =
        try {
            val encrypted = SecureDocumentCodec.encrypt(bytes, password)
            val output = OutputStore.writeUnique(
                getPdfMakerDir(context),
                "${SafeFileName.baseName(baseName)}_locked",
                "pdf",
            ) { it.write(encrypted) }
            output.absolutePath to output.name
        } catch (_: Exception) {
            null
        }

    private fun validateWritableDocument(file: File) {
        require(file.isFile) { "File not found" }
        require(file.canWrite()) { "File is not writable" }
        require(file.length() in 1..MAX_DOCUMENT_BYTES) { "File is empty or exceeds the 100 MB safety limit" }
    }

    private fun readBounded(file: File): ByteArray {
        require(file.length() <= MAX_DOCUMENT_BYTES) { "File exceeds the 100 MB safety limit" }
        return file.inputStream().use { input ->
            val output = ByteArrayOutputStream(file.length().coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= MAX_DOCUMENT_BYTES) { "File exceeds the 100 MB safety limit" }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
    }

    private fun safeError(prefix: String, error: Exception): String = when (error) {
        is IllegalArgumentException, is IllegalStateException -> error.message ?: prefix
        else -> prefix
    }
}
