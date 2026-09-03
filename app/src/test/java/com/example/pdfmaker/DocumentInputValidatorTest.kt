package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentInputValidatorTest {
    @Test
    fun `plaintext validation accepts both boundary sizes`() {
        assertEquals(1L, DocumentInputValidator.requirePlaintextLength(1L))
        assertEquals(
            DocumentInputValidator.MAX_PLAINTEXT_BYTES,
            DocumentInputValidator.requirePlaintextLength(DocumentInputValidator.MAX_PLAINTEXT_BYTES),
        )
    }

    @Test
    fun `plaintext validation rejects empty negative and oversized input`() {
        listOf(-1L, 0L, DocumentInputValidator.MAX_PLAINTEXT_BYTES + 1L).forEach { length ->
            expectIllegalArgument { DocumentInputValidator.requirePlaintextLength(length) }
        }
    }

    @Test
    fun `encrypted validation accepts both complete envelope boundaries`() {
        assertEquals(
            DocumentInputValidator.MIN_ENCRYPTED_BYTES,
            DocumentInputValidator.requireEncryptedLength(DocumentInputValidator.MIN_ENCRYPTED_BYTES),
        )
        assertEquals(
            DocumentInputValidator.MAX_ENCRYPTED_BYTES,
            DocumentInputValidator.requireEncryptedLength(DocumentInputValidator.MAX_ENCRYPTED_BYTES),
        )
    }

    @Test
    fun `encrypted validation rejects truncated and oversized envelopes`() {
        listOf(
            0L,
            DocumentInputValidator.MIN_ENCRYPTED_BYTES - 1L,
            DocumentInputValidator.MAX_ENCRYPTED_BYTES + 1L,
        ).forEach { length ->
            assertFalse(DocumentInputValidator.isEncryptedLengthAccepted(length))
            expectIllegalArgument { DocumentInputValidator.requireEncryptedLength(length) }
        }
    }

    @Test
    fun `password validation rejects empty short and excessively long input`() {
        listOf("", "123", "x".repeat(129)).forEach { password ->
            assertFalse(DocumentInputValidator.isPasswordAccepted(password))
            expectIllegalArgument { DocumentInputValidator.requirePassword(password) }
        }
        assertTrue(DocumentInputValidator.isPasswordAccepted("1234"))
        assertTrue(DocumentInputValidator.isPasswordAccepted("x".repeat(128)))
    }

    @Test
    fun `wire marker validation recognizes only supported envelope versions`() {
        val authenticated =
            AUTHENTICATED_DOCUMENT_MAGIC.toByteArray(Charsets.US_ASCII) +
                ByteArray(DocumentInputValidator.MIN_ENCRYPTED_BYTES.toInt())
        val legacy =
            LEGACY_DOCUMENT_MAGIC.toByteArray(Charsets.US_ASCII) +
                ByteArray(DocumentInputValidator.MIN_ENCRYPTED_BYTES.toInt())
        val malformed =
            "NOTLOCKED".toByteArray(Charsets.US_ASCII) +
                ByteArray(DocumentInputValidator.MIN_ENCRYPTED_BYTES.toInt())

        assertTrue(DocumentInputValidator.hasSupportedMagic(authenticated))
        assertTrue(DocumentInputValidator.hasSupportedMagic(legacy))
        assertTrue(DocumentInputValidator.isSupportedEncryptedDocument(authenticated))
        assertFalse(DocumentInputValidator.isSupportedEncryptedDocument(ByteArray(0)))
        assertFalse(DocumentInputValidator.isSupportedEncryptedDocument(malformed))
        assertFalse(DocumentInputValidator.hasSupportedMagic("PDFLOCK".toByteArray(Charsets.US_ASCII)))
    }

    private fun expectIllegalArgument(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected boundary rejection.
        }
    }
}
