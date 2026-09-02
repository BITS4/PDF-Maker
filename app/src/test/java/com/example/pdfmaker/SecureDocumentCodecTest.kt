package com.example.pdfmaker

import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.security.SecureRandom
import java.util.concurrent.CancellationException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SecureDocumentCodecTest {
    private val document = "%PDF-1.7\nconfidential document".toByteArray()

    @Test
    fun authenticatedRoundTripDoesNotExposePlaintext() {
        val encrypted = SecureDocumentCodec.encrypt(document, "correct horse", iterations = 1_000)

        assertTrue(String(encrypted.copyOfRange(0, 8), Charsets.US_ASCII) == AUTHENTICATED_DOCUMENT_MAGIC)
        assertFalse(String(encrypted, Charsets.ISO_8859_1).contains(String(document, Charsets.ISO_8859_1)))
        assertArrayEquals(document, SecureDocumentCodec.decrypt(encrypted, "correct horse"))
    }

    @Test
    fun randomSaltAndNonceMakeCiphertextsUnique() {
        val first = SecureDocumentCodec.encrypt(document, "password", iterations = 1_000)
        val second = SecureDocumentCodec.encrypt(document, "password", iterations = 1_000)

        assertNotEquals(first.toList(), second.toList())
    }

    @Test
    fun wrongPasswordTruncationAndEveryRegionOfTamperingFailClosed() {
        val encrypted = SecureDocumentCodec.encrypt(document, "password", iterations = 1_000)
        assertNull(SecureDocumentCodec.decrypt(encrypted, "wrong-password"))
        assertNull(SecureDocumentCodec.decrypt(encrypted.copyOf(40), "password"))

        listOf(0, 10, 20, 35, encrypted.lastIndex).forEach { index ->
            val tampered = encrypted.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            assertNull("Tampering at index $index must fail", SecureDocumentCodec.decrypt(tampered, "password"))
        }
    }

    @Test
    fun malformedInputsAndWeakPasswordsAreRejected() {
        assertNull(SecureDocumentCodec.decrypt(byteArrayOf(), "password"))
        assertNull(SecureDocumentCodec.decrypt("not-a-document".toByteArray(), "password"))
        try {
            SecureDocumentCodec.encrypt(document, "123", iterations = 1_000)
            throw AssertionError("Weak password must fail")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun legacyCbcDocumentsRemainReadable() {
        val legacy = legacyEncrypt(document, "old-password")

        assertTrue(SecureDocumentCodec.isEncrypted(legacy))
        assertArrayEquals(document, SecureDocumentCodec.decrypt(legacy, "old-password"))
        assertNull(SecureDocumentCodec.decrypt(legacy, "bad-password"))
    }

    @Test
    fun streamingCodecRoundTripsAcrossManyPartialReads() {
        val plaintext = ByteArray(256 * 1024 + 17) { index -> (index * 31).toByte() }
        val encryptedOutput = ByteArrayOutputStream()
        val encryptedLength = SecureDocumentCodec.encrypt(
            input = PartialReadInputStream(plaintext, maximumChunk = 37),
            output = encryptedOutput,
            plaintextLength = plaintext.size.toLong(),
            password = "stream-password",
            iterations = 1_000,
        )
        val encrypted = encryptedOutput.toByteArray()

        assertEquals(plaintext.size.toLong() + SecureDocumentLimits.AUTHENTICATED_OVERHEAD_BYTES, encryptedLength)
        assertEquals(encryptedLength, encrypted.size.toLong())
        val decryptedOutput = ByteArrayOutputStream()
        assertTrue(
            SecureDocumentCodec.decrypt(
                input = PartialReadInputStream(encrypted, maximumChunk = 29),
                output = decryptedOutput,
                encryptedLength = encryptedLength,
                password = "stream-password",
            ),
        )
        assertArrayEquals(plaintext, decryptedOutput.toByteArray())
    }

    @Test
    fun streamingDecoderPreservesLegacyCompatibility() {
        val legacy = legacyEncrypt(document, "old-password")
        val output = ByteArrayOutputStream()

        assertTrue(
            SecureDocumentCodec.decrypt(
                ByteArrayInputStream(legacy),
                output,
                legacy.size.toLong(),
                "old-password",
            ),
        )
        assertArrayEquals(document, output.toByteArray())
    }

    @Test
    fun streamingDecoderFailsClosedForWrongPasswordAndTampering() {
        val encryptedOutput = ByteArrayOutputStream()
        SecureDocumentCodec.encrypt(
            ByteArrayInputStream(document),
            encryptedOutput,
            document.size.toLong(),
            "correct-password",
            iterations = 1_000,
        )
        val encrypted = encryptedOutput.toByteArray()
        val tampered = encrypted.copyOf().also { bytes ->
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        }

        assertFalse(
            SecureDocumentCodec.decrypt(
                ByteArrayInputStream(encrypted),
                ByteArrayOutputStream(),
                encrypted.size.toLong(),
                "wrong-password",
            ),
        )
        assertFalse(
            SecureDocumentCodec.decrypt(
                ByteArrayInputStream(tampered),
                ByteArrayOutputStream(),
                tampered.size.toLong(),
                "correct-password",
            ),
        )
    }

    @Test
    fun declaredStreamingLengthMustMatchTheActualInput() {
        expectIllegalArgument {
            SecureDocumentCodec.encrypt(
                ByteArrayInputStream(document),
                ByteArrayOutputStream(),
                document.size.toLong() - 1,
                "password",
                iterations = 1_000,
            )
        }
        expectIllegalArgument {
            SecureDocumentCodec.encrypt(
                ByteArrayInputStream(document.copyOf(document.size - 1)),
                ByteArrayOutputStream(),
                document.size.toLong(),
                "password",
                iterations = 1_000,
            )
        }
    }

    @Test
    fun streamingCodecChecksCancellationBeforeProcessingChunks() {
        var checks = 0

        val failure = runCatching {
            SecureDocumentCodec.encrypt(
                ByteArrayInputStream(ByteArray(64 * 1024)),
                ByteArrayOutputStream(),
                64L * 1024L,
                "password",
                iterations = 1_000,
                beforeChunk = {
                    checks += 1
                    if (checks == 2) error("cancelled")
                },
            )
        }

        assertTrue(failure.isFailure)
        assertEquals(2, checks)
    }

    @Test
    fun sizePolicyAccountsForAuthenticatedHeaderAndTagAtBothBoundaries() {
        assertEquals(
            SecureDocumentLimits.MAX_ENCRYPTED_BYTES,
            SecureDocumentLimits.authenticatedLength(SecureDocumentLimits.MAX_PLAINTEXT_BYTES),
        )
        assertEquals(
            SecureDocumentLimits.MAX_PLAINTEXT_BYTES,
            SecureDocumentLimits.requirePlaintextLength(SecureDocumentLimits.MAX_PLAINTEXT_BYTES),
        )
        assertEquals(
            SecureDocumentLimits.MAX_ENCRYPTED_BYTES,
            SecureDocumentLimits.requireEncryptedLength(SecureDocumentLimits.MAX_ENCRYPTED_BYTES),
        )
        listOf(0L, SecureDocumentLimits.MAX_PLAINTEXT_BYTES + 1).forEach { length ->
            expectIllegalArgument { SecureDocumentLimits.requirePlaintextLength(length) }
        }
        listOf(
            0L,
            SecureDocumentLimits.MIN_ENCRYPTED_BYTES - 1,
            SecureDocumentLimits.MAX_ENCRYPTED_BYTES + 1,
        ).forEach { length ->
            expectIllegalArgument { SecureDocumentLimits.requireEncryptedLength(length) }
        }
    }

    private fun legacyEncrypt(plaintext: ByteArray, password: String): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(16).also(random::nextBytes)
        val spec = PBEKeySpec(password.toCharArray(), salt, 65_536, 256)
        val key = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return ByteArrayOutputStream().apply {
            write(LEGACY_DOCUMENT_MAGIC.toByteArray())
            write(salt)
            write(iv)
            write(cipher.doFinal(plaintext))
        }.toByteArray()
    }

    private fun expectIllegalArgument(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    private class PartialReadInputStream(
        bytes: ByteArray,
        private val maximumChunk: Int,
    ) : FilterInputStream(ByteArrayInputStream(bytes)) {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, minOf(length, maximumChunk))
    }
}

class SecureDocumentStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun lockAndUnlockReplaceTheSameFileAtomically() {
        val original = "%PDF-1.7\nprivate".toByteArray()
        val file = temporaryFolder.newFile("private.pdf").apply { writeBytes(original) }

        assertNull(SecureDocumentStore.lockInPlace(file, "password"))
        assertTrue(SecureDocumentStore.isLocked(file))
        assertFalse(file.readBytes().contentEquals(original))
        assertNull(SecureDocumentStore.unlockInPlace(file, "password"))
        assertArrayEquals(original, file.readBytes())
    }

    @Test
    fun wrongPasswordPreservesEncryptedFileByteForByte() {
        val file = temporaryFolder.newFile("private.pdf").apply { writeText("secret") }
        assertNull(SecureDocumentStore.lockInPlace(file, "password"))
        val before = file.readBytes()

        assertTrue(SecureDocumentStore.unlockInPlace(file, "incorrect")!!.isNotBlank())
        assertArrayEquals(before, file.readBytes())
        assertFalse(temporarySecureFilesExist())
    }

    @Test
    fun tamperedCiphertextPreservesTheOriginalFileAndCleansTemporaryOutput() {
        val file = temporaryFolder.newFile("tampered.pdf").apply { writeText("secret") }
        assertNull(SecureDocumentStore.lockInPlace(file, "password"))
        java.io.RandomAccessFile(file, "rw").use { randomAccess ->
            randomAccess.seek(randomAccess.length() - 1)
            val original = randomAccess.readByte()
            randomAccess.seek(randomAccess.length() - 1)
            randomAccess.writeByte(original.toInt() xor 1)
        }
        val tampered = file.readBytes()

        assertTrue(SecureDocumentStore.unlockInPlace(file, "password")!!.isNotBlank())
        assertArrayEquals(tampered, file.readBytes())
        assertFalse(temporarySecureFilesExist())
    }

    @Test
    fun emptyOversizedAndAlreadyLockedFilesAreRejectedWithoutModification() {
        val empty = temporaryFolder.newFile("empty.pdf")
        assertTrue(SecureDocumentStore.lockInPlace(empty, "password")!!.contains("empty"))

        val oversized = File(temporaryFolder.root, "oversized.pdf")
        java.io.RandomAccessFile(oversized, "rw").use {
            it.setLength(SecureDocumentStore.MAX_DOCUMENT_BYTES + 1)
        }
        assertTrue(SecureDocumentStore.lockInPlace(oversized, "password")!!.contains("100 MB"))

        val locked = temporaryFolder.newFile("locked.pdf").apply { writeText("content") }
        assertNull(SecureDocumentStore.lockInPlace(locked, "password"))
        val before = locked.readBytes()
        assertTrue(SecureDocumentStore.lockInPlace(locked, "password")!!.contains("already locked"))
        assertArrayEquals(before, locked.readBytes())
    }

    @Test
    fun encryptedWireLimitIncludesV2Overhead() {
        assertEquals(
            SecureDocumentStore.MAX_DOCUMENT_BYTES + SecureDocumentLimits.AUTHENTICATED_OVERHEAD_BYTES,
            SecureDocumentStore.MAX_ENCRYPTED_DOCUMENT_BYTES,
        )
        val oversized = File(temporaryFolder.root, "oversized-locked.pdf")
        java.io.RandomAccessFile(oversized, "rw").use {
            it.setLength(SecureDocumentStore.MAX_ENCRYPTED_DOCUMENT_BYTES + 1)
        }
        val originalLength = oversized.length()

        assertTrue(SecureDocumentStore.unlockInPlace(oversized, "password")!!.contains("safety"))
        assertEquals(originalLength, oversized.length())
        assertFalse(temporarySecureFilesExist())
    }

    @Test
    fun decryptedCopyStreamsToAnAtomicOutputWithoutChangingTheSource() {
        val plaintext = "%PDF-1.7\nprivate".toByteArray()
        val encrypted = SecureDocumentCodec.encrypt(plaintext, "password", iterations = 1_000)
        val source = temporaryFolder.newFile("locked.pdf").apply { writeBytes(encrypted) }
        val outputs = temporaryFolder.newFolder("viewer-cache")

        val result = SecureDocumentStore.decryptedCopy(source, outputs, "password")

        assertArrayEquals(encrypted, source.readBytes())
        val decrypted = requireNotNull(result)
        assertArrayEquals(plaintext, decrypted.readBytes())
        assertEquals(outputs.canonicalFile, requireNotNull(decrypted.parentFile).canonicalFile)
    }

    @Test
    fun rejectedDecryptedCopyLeavesNoOutputOrTemporaryFile() {
        val encrypted = SecureDocumentCodec.encrypt(
            "%PDF-1.7\nprivate".toByteArray(),
            "password",
            iterations = 1_000,
        )
        val source = temporaryFolder.newFile("locked.pdf").apply { writeBytes(encrypted) }
        val outputs = temporaryFolder.newFolder("rejected-viewer-cache")

        assertNull(SecureDocumentStore.decryptedCopy(source, outputs, "incorrect"))
        assertTrue(outputs.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun cancellationPropagatesAndAtomicLockPreservesTheOriginal() {
        val original = ByteArray(64 * 1024) { index -> index.toByte() }
        val source = temporaryFolder.newFile("cancelled.pdf").apply { writeBytes(original) }
        var checks = 0

        val failure = runCatching {
            SecureDocumentStore.lockInPlace(source, "password") {
                checks += 1
                if (checks == 2) throw CancellationException("cancelled")
            }
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertArrayEquals(original, source.readBytes())
        assertFalse(temporarySecureFilesExist())
    }

    private fun temporarySecureFilesExist(): Boolean =
        temporaryFolder.root.listFiles().orEmpty().any { it.name.startsWith(".pdfmaker-secure-") }
}
