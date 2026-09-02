package com.example.pdfmaker

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
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
}
