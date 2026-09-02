package com.example.pdfmaker

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.util.concurrent.CancellationException

class SafePdfInputCancellationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun cancellationDuringStagingPropagatesAndDeletesTheSnapshot() {
        val bytes = "%PDF-1.7\n".toByteArray() + ByteArray(32 * 1024) { it.toByte() }
        val input = TrackingInputStream(bytes)
        var checks = 0

        val failure =
            try {
                SafePdfInput.stage(input, temporaryFolder.root) {
                    checks += 1
                    throw CancellationException("cancel staging")
                }
                null
            } catch (error: Throwable) {
                error
            }

        assertTrue(failure is CancellationException)
        assertEqualsOne(checks)
        assertTrue(input.closed)
        assertFalse(
            temporaryFolder.root
                .listFiles()
                .orEmpty()
                .any { it.name.startsWith(".source-") },
        )
        assertArrayEquals(bytes, input.original)
    }

    private fun assertEqualsOne(value: Int) {
        assertTrue("Expected exactly one cancellation check but saw $value", value == 1)
    }

    private class TrackingInputStream(
        val original: ByteArray,
    ) : ByteArrayInputStream(original) {
        var closed = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }
}
