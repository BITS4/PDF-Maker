package com.example.pdfmaker

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
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
    private const val STREAM_BUFFER_SIZE = 32 * 1024
    private const val MAX_EMPTY_READS = 32
    private val authenticatedMagic = AUTHENTICATED_DOCUMENT_MAGIC.toByteArray(Charsets.US_ASCII)
    private val legacyMagic = LEGACY_DOCUMENT_MAGIC.toByteArray(Charsets.US_ASCII)

    fun encrypt(
        plaintext: ByteArray,
        password: String,
        random: SecureRandom = SecureRandom(),
        iterations: Int = ITERATIONS,
    ): ByteArray {
        DocumentInputValidator.requirePlaintextLength(plaintext.size.toLong())
        validatePasswordAndIterations(password, iterations)

        val header = newAuthenticatedHeader(random, iterations)
        val cipher = authenticatedCipher(Cipher.ENCRYPT_MODE, password, header, iterations)
        val payloadSize = cipher.getOutputSize(plaintext.size)
        val encrypted = ByteArray(Math.addExact(header.size, payloadSize))
        header.copyInto(encrypted)
        val written = cipher.doFinal(plaintext, 0, plaintext.size, encrypted, header.size)
        val exactSize = header.size + written
        return if (exactSize == encrypted.size) {
            encrypted
        } else {
            encrypted.copyOf(exactSize)
        }
    }

    /** Encrypts without materializing either the source or ciphertext as a whole byte array. */
    internal fun encrypt(
        input: InputStream,
        output: OutputStream,
        plaintextLength: Long,
        password: String,
        random: SecureRandom = SecureRandom(),
        iterations: Int = ITERATIONS,
        beforeChunk: () -> Unit = {},
    ): Long {
        DocumentInputValidator.requirePlaintextLength(plaintextLength)
        validatePasswordAndIterations(password, iterations)

        val header = newAuthenticatedHeader(random, iterations)
        val cipher = authenticatedCipher(Cipher.ENCRYPT_MODE, password, header, iterations)
        output.write(header)
        val payloadBytes =
            transformExactly(
                input = input,
                expectedInputBytes = plaintextLength,
                output = output,
                cipher = cipher,
                maximumOutputBytes = plaintextLength + TAG_BITS / Byte.SIZE_BITS,
                clearTransformedBytes = false,
                beforeChunk = beforeChunk,
            )
        val written = header.size.toLong() + payloadBytes
        check(written == DocumentInputValidator.authenticatedLength(plaintextLength)) {
            "Encrypted document length is inconsistent"
        }
        return written
    }

    fun decrypt(
        encrypted: ByteArray,
        password: String,
    ): ByteArray? {
        if (!DocumentInputValidator.isPasswordAccepted(password)) return null
        if (!DocumentInputValidator.isSupportedEncryptedDocument(encrypted)) return null
        return when {
            encrypted.hasMagic(AUTHENTICATED_DOCUMENT_MAGIC) -> decryptAuthenticated(encrypted, password)
            encrypted.hasMagic(LEGACY_DOCUMENT_MAGIC) -> decryptLegacy(encrypted, password)
            else -> null
        }
    }

    fun isEncrypted(bytes: ByteArray): Boolean = DocumentInputValidator.hasSupportedMagic(bytes)

    /** Decrypts V2 and legacy files while keeping any unauthenticated output in caller-owned temporary storage. */
    internal fun decrypt(
        input: InputStream,
        output: OutputStream,
        encryptedLength: Long,
        password: String,
        beforeChunk: () -> Unit = {},
    ): Boolean {
        if (!DocumentInputValidator.isPasswordAccepted(password) ||
            !DocumentInputValidator.isEncryptedLengthAccepted(encryptedLength)
        ) {
            return false
        }
        val magic = readExactly(input, authenticatedMagic.size, beforeChunk) ?: return false
        return try {
            when {
                magic.contentEquals(authenticatedMagic) -> {
                    decryptAuthenticated(
                        input,
                        output,
                        encryptedLength,
                        password,
                        magic,
                        beforeChunk,
                    )
                }

                magic.contentEquals(legacyMagic) -> {
                    decryptLegacy(
                        input,
                        output,
                        encryptedLength,
                        password,
                        beforeChunk,
                    )
                }

                else -> {
                    false
                }
            }
        } catch (_: AEADBadTagException) {
            false
        } catch (_: BadPaddingException) {
            false
        } catch (_: IllegalBlockSizeException) {
            false
        }
    }

    private fun decryptAuthenticated(
        encrypted: ByteArray,
        password: String,
    ): ByteArray? {
        if (encrypted.size < MIN_V2_SIZE) return null
        return try {
            val buffer = ByteBuffer.wrap(encrypted)
            val magic = ByteArray(authenticatedMagic.size).also { buffer.get(it) }
            val iterations = buffer.int
            if (!MessageDigest.isEqual(magic, authenticatedMagic) ||
                iterations !in MIN_ITERATIONS..MAX_ITERATIONS
            ) {
                return null
            }
            val salt = ByteArray(SALT_SIZE).also { buffer.get(it) }
            val nonce = ByteArray(NONCE_SIZE).also { buffer.get(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            initializeCipher(cipher, Cipher.DECRYPT_MODE, password, salt, iterations, GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(encrypted, 0, V2_HEADER_SIZE)
            decryptArray(cipher, encrypted, V2_HEADER_SIZE)
        } catch (_: AEADBadTagException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun decryptLegacy(
        encrypted: ByteArray,
        password: String,
    ): ByteArray? {
        if (encrypted.size <= LEGACY_HEADER_SIZE) return null
        return try {
            val salt = encrypted.copyOfRange(8, 24)
            val iv = encrypted.copyOfRange(24, 40)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            initializeCipher(cipher, Cipher.DECRYPT_MODE, password, salt, 65_536, IvParameterSpec(iv))
            decryptArray(cipher, encrypted, LEGACY_HEADER_SIZE)
        } catch (_: Exception) {
            null
        }
    }

    private fun decryptAuthenticated(
        input: InputStream,
        output: OutputStream,
        encryptedLength: Long,
        password: String,
        magic: ByteArray,
        beforeChunk: () -> Unit,
    ): Boolean {
        val remainder = readExactly(input, V2_HEADER_SIZE - magic.size, beforeChunk) ?: return false
        val header = magic + remainder
        val iterations = ByteBuffer.wrap(header, magic.size, Int.SIZE_BYTES).int
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) return false
        val saltOffset = magic.size + Int.SIZE_BYTES
        val salt = header.copyOfRange(saltOffset, saltOffset + SALT_SIZE)
        val nonce = header.copyOfRange(saltOffset + SALT_SIZE, V2_HEADER_SIZE)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        initializeCipher(cipher, Cipher.DECRYPT_MODE, password, salt, iterations, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(header)
        val ciphertextLength = encryptedLength - V2_HEADER_SIZE
        if (ciphertextLength < TAG_BITS / Byte.SIZE_BITS) return false
        transformExactly(
            input = input,
            expectedInputBytes = ciphertextLength,
            output = output,
            cipher = cipher,
            maximumOutputBytes = DocumentInputValidator.MAX_PLAINTEXT_BYTES,
            clearTransformedBytes = true,
            beforeChunk = beforeChunk,
        )
        return true
    }

    private fun decryptLegacy(
        input: InputStream,
        output: OutputStream,
        encryptedLength: Long,
        password: String,
        beforeChunk: () -> Unit,
    ): Boolean {
        val header = readExactly(input, LEGACY_HEADER_SIZE - legacyMagic.size, beforeChunk) ?: return false
        val salt = header.copyOfRange(0, SALT_SIZE)
        val iv = header.copyOfRange(SALT_SIZE, SALT_SIZE + 16)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        initializeCipher(cipher, Cipher.DECRYPT_MODE, password, salt, 65_536, IvParameterSpec(iv))
        val ciphertextLength = encryptedLength - LEGACY_HEADER_SIZE
        if (ciphertextLength <= 0 || ciphertextLength % 16L != 0L) return false
        transformExactly(
            input = input,
            expectedInputBytes = ciphertextLength,
            output = output,
            cipher = cipher,
            maximumOutputBytes = DocumentInputValidator.MAX_PLAINTEXT_BYTES,
            clearTransformedBytes = true,
            beforeChunk = beforeChunk,
        )
        return true
    }

    private fun newAuthenticatedHeader(
        random: SecureRandom,
        iterations: Int,
    ): ByteArray {
        val salt = ByteArray(SALT_SIZE).also(random::nextBytes)
        val nonce = ByteArray(NONCE_SIZE).also(random::nextBytes)
        return ByteBuffer
            .allocate(V2_HEADER_SIZE)
            .put(authenticatedMagic)
            .putInt(iterations)
            .put(salt)
            .put(nonce)
            .array()
    }

    private fun authenticatedCipher(
        mode: Int,
        password: String,
        header: ByteArray,
        iterations: Int,
    ): Cipher {
        val saltOffset = authenticatedMagic.size + Int.SIZE_BYTES
        val salt = header.copyOfRange(saltOffset, saltOffset + SALT_SIZE)
        val nonce = header.copyOfRange(saltOffset + SALT_SIZE, V2_HEADER_SIZE)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        initializeCipher(cipher, mode, password, salt, iterations, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(header)
        return cipher
    }

    private fun initializeCipher(
        cipher: Cipher,
        mode: Int,
        password: String,
        salt: ByteArray,
        iterations: Int,
        parameters: java.security.spec.AlgorithmParameterSpec,
    ) {
        val keyBytes = deriveKey(password, salt, iterations)
        try {
            cipher.init(mode, SecretKeySpec(keyBytes, "AES"), parameters)
        } finally {
            keyBytes.fill(0)
        }
    }

    private fun decryptArray(
        cipher: Cipher,
        encrypted: ByteArray,
        inputOffset: Int,
    ): ByteArray {
        val inputLength = encrypted.size - inputOffset
        val candidate = ByteArray(cipher.getOutputSize(inputLength))
        var keepCandidate = false
        try {
            val written = cipher.doFinal(encrypted, inputOffset, inputLength, candidate, 0)
            if (written == candidate.size) {
                keepCandidate = true
                return candidate
            }
            return candidate.copyOf(written)
        } finally {
            if (!keepCandidate) candidate.fill(0)
        }
    }

    private fun transformExactly(
        input: InputStream,
        expectedInputBytes: Long,
        output: OutputStream,
        cipher: Cipher,
        maximumOutputBytes: Long,
        clearTransformedBytes: Boolean,
        beforeChunk: () -> Unit,
    ): Long {
        require(expectedInputBytes >= 0) { "Encrypted document length is invalid" }
        val inputBuffer = ByteArray(STREAM_BUFFER_SIZE)
        try {
            var inputBytes = 0L
            var outputBytes = 0L
            var emptyReads = 0
            var finished = false
            while (!finished) {
                beforeChunk()
                val read = input.read(inputBuffer)
                when {
                    read < 0 -> {
                        finished = true
                    }

                    read == 0 -> {
                        emptyReads += 1
                        check(emptyReads <= MAX_EMPTY_READS) { "Document input made no progress" }
                    }

                    else -> {
                        emptyReads = 0
                        inputBytes += read
                        require(inputBytes <= expectedInputBytes) {
                            "Document changed while it was being processed"
                        }
                        outputBytes =
                            writeTransformed(
                                output,
                                cipher.update(inputBuffer, 0, read),
                                outputBytes,
                                maximumOutputBytes,
                                clearTransformedBytes,
                            )
                    }
                }
            }
            require(inputBytes == expectedInputBytes) { "Document changed while it was being processed" }
            beforeChunk()
            outputBytes =
                writeTransformed(
                    output,
                    cipher.doFinal(),
                    outputBytes,
                    maximumOutputBytes,
                    clearTransformedBytes,
                )
            return outputBytes
        } finally {
            inputBuffer.fill(0)
        }
    }

    private fun writeTransformed(
        output: OutputStream,
        transformed: ByteArray?,
        alreadyWritten: Long,
        maximumOutputBytes: Long,
        clearAfterWrite: Boolean,
    ): Long {
        if (transformed == null || transformed.isEmpty()) return alreadyWritten
        val total = Math.addExact(alreadyWritten, transformed.size.toLong())
        require(total <= maximumOutputBytes) { "Decrypted document exceeds the 100 MB safety limit" }
        try {
            output.write(transformed)
        } finally {
            if (clearAfterWrite) transformed.fill(0)
        }
        return total
    }

    private fun readExactly(
        input: InputStream,
        count: Int,
        beforeChunk: () -> Unit,
    ): ByteArray? {
        val bytes = ByteArray(count)
        var offset = 0
        var emptyReads = 0
        while (offset < count) {
            beforeChunk()
            val read = input.read(bytes, offset, count - offset)
            if (read < 0) return null
            if (read == 0) {
                emptyReads += 1
                if (emptyReads > MAX_EMPTY_READS) return null
            } else {
                emptyReads = 0
                offset += read
            }
        }
        return bytes
    }

    private fun validatePasswordAndIterations(
        password: String,
        iterations: Int,
    ) {
        DocumentInputValidator.requirePassword(password)
        require(iterations in MIN_ITERATIONS..MAX_ITERATIONS) { "Invalid key derivation work factor" }
    }

    private fun deriveKey(
        password: String,
        salt: ByteArray,
        iterations: Int,
    ): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun ByteArray.hasMagic(value: String): Boolean {
        val expected =
            when (value) {
                AUTHENTICATED_DOCUMENT_MAGIC -> authenticatedMagic
                LEGACY_DOCUMENT_MAGIC -> legacyMagic
                else -> value.toByteArray(Charsets.US_ASCII)
            }
        if (size < expected.size) return false
        return expected.indices.all { index -> this[index] == expected[index] }
    }
}
