package com.example.pdfmaker

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class SecureDocumentTypeSafetyTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun nonPdfLockIsRejectedWithoutChangingTheSource() {
        val original = "plain text that is not a PDF".toByteArray()
        val source = temporaryFolder.newFile("mislabelled.pdf").apply { writeBytes(original) }

        val message = SecureDocumentStore.lockInPlace(source, "password")

        assertTrue(requireNotNull(message).contains("invalid or unsupported"))
        assertArrayEquals(original, source.readBytes())
        assertNoSecureTemporaries(temporaryFolder.root)
    }

    @Test
    fun nonPdfEncryptedCopyIsRejectedWithoutCreatingAnOutput() {
        val outputs = temporaryFolder.newFolder("encrypted-copies")

        val result =
            SecureDocumentStore.encryptedCopy(
                outputs,
                "not a PDF".toByteArray(),
                "password",
                "mislabelled.pdf",
            )

        assertNull(result)
        assertTrue(outputs.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun validPdfCopyStillRoundTrips() {
        val original = "%PDF-1.7\nvalid document".toByteArray()
        val lockedOutputs = temporaryFolder.newFolder("locked-copies")
        val unlockedOutputs = temporaryFolder.newFolder("unlocked-copies")

        val locked =
            requireNotNull(
                SecureDocumentStore.encryptedCopy(
                    lockedOutputs,
                    original,
                    "password",
                    "valid.pdf",
                ),
            )
        val lockedFile = File(locked.first)
        val encrypted = lockedFile.readBytes()
        val unlocked =
            requireNotNull(
                SecureDocumentStore.decryptedCopy(lockedFile, unlockedOutputs, "password"),
            )

        assertTrue(SecureDocumentCodec.isEncrypted(encrypted))
        assertArrayEquals(encrypted, lockedFile.readBytes())
        assertArrayEquals(original, unlocked.readBytes())
    }

    @Test
    fun authenticatedNonPdfCannotOverwriteOrEscape() {
        val encrypted =
            SecureDocumentCodec.encrypt(
                "authenticated but not PDF".toByteArray(),
                "password",
                iterations = 1_000,
            )

        assertRejectedEncryptedPayload(encrypted)
    }

    @Test
    fun legacyNonPdfCannotOverwriteOrEscape() {
        val encrypted = legacyEncrypt("legacy but not PDF".toByteArray(), "password")

        assertRejectedEncryptedPayload(encrypted)
    }

    @Test
    fun legacyPdfRemainsCompatibleAfterTypeValidation() {
        val original = "%PDF-1.4\nlegacy document".toByteArray()
        val encrypted = legacyEncrypt(original, "password")
        val source = temporaryFolder.newFile("legacy-valid.pdf").apply { writeBytes(encrypted) }

        assertNull(SecureDocumentStore.unlockInPlace(source, "password"))
        assertArrayEquals(original, source.readBytes())
    }

    @Test
    fun signaturePolicyRejectsEncryptedAndNonPdfPrefixes() {
        assertTrue(SecureDocumentTypePolicy.isPlainPdf("%PDF-1.7".toByteArray()))
        assertFalse(SecureDocumentTypePolicy.isPlainPdf("not a pdf".toByteArray()))
        assertFalse(
            SecureDocumentTypePolicy.isPlainPdf(
                SecureDocumentCodec.encrypt(
                    "%PDF-1.7\nlocked".toByteArray(),
                    "password",
                    iterations = 1_000,
                ),
            ),
        )
    }

    private fun assertRejectedEncryptedPayload(encrypted: ByteArray) {
        val inPlace =
            temporaryFolder.newFile("crafted-${System.nanoTime()}.pdf").apply {
                writeBytes(encrypted)
            }
        val outputs = temporaryFolder.newFolder("outputs-${System.nanoTime()}")

        val message = SecureDocumentStore.unlockInPlace(inPlace, "password")
        val copied = SecureDocumentStore.decryptedCopy(inPlace, outputs, "password")

        assertTrue(requireNotNull(message).contains("invalid or unsupported"))
        assertArrayEquals(encrypted, inPlace.readBytes())
        assertNull(copied)
        assertTrue(outputs.listFiles().orEmpty().isEmpty())
        assertNoSecureTemporaries(requireNotNull(inPlace.parentFile))
    }

    private fun legacyEncrypt(
        plaintext: ByteArray,
        password: String,
    ): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(16).also(random::nextBytes)
        val spec = PBEKeySpec(password.toCharArray(), salt, 65_536, 256)
        val key =
            try {
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return ByteArrayOutputStream()
            .apply {
                write(LEGACY_DOCUMENT_MAGIC.toByteArray())
                write(salt)
                write(iv)
                write(cipher.doFinal(plaintext))
            }.toByteArray()
    }

    private fun assertNoSecureTemporaries(directory: File) {
        assertFalse(
            directory.listFiles().orEmpty().any {
                it.name.startsWith(".pdfmaker-secure-") || it.name.startsWith(".pdfmaker-")
            },
        )
    }
}
