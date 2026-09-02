package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.util.concurrent.CancellationException

class UserVisibleFailurePolicyTest {
    private val secret = "token=private-secret content://accounts/private /storage/emulated/0/private.pdf"

    @Test
    fun `every stage and failure kind returns fixed privacy safe copy`() {
        val failures =
            mapOf(
                UserFailureKind.ACCESS_DENIED to SecurityException(secret),
                UserFailureKind.INPUT_OUTPUT to IOException(secret),
                UserFailureKind.INVALID_INPUT to IllegalArgumentException(secret),
                UserFailureKind.UNAVAILABLE to IllegalStateException(secret),
                UserFailureKind.SECURE_PROCESSING to GeneralSecurityException(secret),
                UserFailureKind.UNSUPPORTED to UnsupportedOperationException(secret),
                UserFailureKind.UNEXPECTED to RuntimeException(secret),
            )

        assertEquals(UserFailureKind.entries.toSet(), failures.keys)
        UserFailureStage.entries.forEach { stage ->
            val messages =
                failures.map { (expectedKind, failure) ->
                    assertEquals(expectedKind, UserVisibleFailurePolicy.classify(failure))
                    UserVisibleFailurePolicy.message(stage, failure).also(::assertPrivacySafe)
                }
            assertEquals(UserFailureKind.entries.size, messages.toSet().size)
        }
    }

    @Test
    fun `copy remains stage specific for every failure kind`() {
        val failures =
            listOf(
                SecurityException(secret),
                IOException(secret),
                IllegalArgumentException(secret),
                IllegalStateException(secret),
                GeneralSecurityException(secret),
                UnsupportedOperationException(secret),
                RuntimeException(secret),
            )

        failures.forEach { failure ->
            val messages =
                UserFailureStage.entries.map { stage ->
                    UserVisibleFailurePolicy.message(stage, failure)
                }
            assertEquals(UserFailureStage.entries.size, messages.toSet().size)
        }
    }

    @Test
    fun `provider failures are classified as secure processing without leaking details`() {
        val failure = ProviderException(secret)

        assertEquals(
            UserFailureKind.SECURE_PROCESSING,
            UserVisibleFailurePolicy.classify(failure),
        )
        assertPrivacySafe(
            UserVisibleFailurePolicy.message(UserFailureStage.DOCUMENT_LOCK, failure),
        )
    }

    @Test
    fun `cancellation is rethrown unchanged for every stage`() {
        UserFailureStage.entries.forEach { stage ->
            val cancellation = CancellationException(secret)

            val thrown =
                assertThrows(CancellationException::class.java) {
                    UserVisibleFailurePolicy.message(stage, cancellation)
                }

            assertSame(cancellation, thrown)
        }
    }

    @Test
    fun `event codes are fixed safe and unique`() {
        val safeCode = Regex("[a-z_]+")

        assertEquals(
            UserFailureStage.entries.size,
            UserFailureStage.entries
                .map(UserFailureStage::eventCode)
                .toSet()
                .size,
        )
        assertTrue(UserFailureStage.entries.all { it.eventCode.matches(safeCode) })
        assertTrue(UserFailureKind.entries.all { it.eventCode.matches(safeCode) })
    }

    private fun assertPrivacySafe(message: String) {
        assertTrue(message.isNotBlank())
        assertFalse(message.contains(secret))
        assertFalse(message.contains("private-secret"))
        assertFalse(message.contains("content://"))
        assertFalse(message.contains("/storage/"))
    }
}
