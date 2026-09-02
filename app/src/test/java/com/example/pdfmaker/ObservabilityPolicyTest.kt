package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservabilityPolicyTest {
    private val validDsn = "https://public-key@errors.example.com/42"

    @Test
    fun `telemetry requires an explicit release opt in and valid dsn`() {
        assertTrue(ObservabilityPolicy.shouldEnable(true, validDsn, isDebug = false))
        assertFalse(ObservabilityPolicy.shouldEnable(false, validDsn, isDebug = false))
        assertFalse(ObservabilityPolicy.shouldEnable(true, validDsn, isDebug = true))
        assertFalse(ObservabilityPolicy.shouldEnable(true, "", isDebug = false))
    }

    @Test
    fun `dsn validation requires encrypted transport and a complete endpoint`() {
        val rejected =
            listOf(
                "http://public-key@errors.example.com/42",
                "https://errors.example.com/42",
                "https://public-key@errors.example.com",
                "https://public-key@errors.example.com/42?token=secret",
                "https://public-key@errors.example.com/42#fragment",
                "not a uri",
            )

        assertTrue(ObservabilityPolicy.isValidDsn(validDsn))
        rejected.forEach { assertFalse(it, ObservabilityPolicy.isValidDsn(it)) }
    }

    @Test
    fun `log metadata never includes caller messages or unsafe tags`() {
        val secret = "document=/storage/private/report.pdf user=person@example.com"
        val message = ObservabilityPolicy.safeLogMessage(6, IllegalStateException(secret))

        assertFalse(message.contains(secret))
        assertFalse(message.contains("report.pdf"))
        assertEquals("PdfMaker", ObservabilityPolicy.safeLogTag("user/email@example.com"))
        assertEquals("Pdf.Export", ObservabilityPolicy.safeLogTag("Pdf.Export"))
    }

    @Test
    fun `sanitized throwable preserves stack location but removes the original message`() {
        val source = IllegalArgumentException("private-file-name.pdf")
        source.stackTrace = arrayOf(StackTraceElement("PdfEngine", "render", "PdfEngine.kt", 25))

        val sanitized = ObservabilityPolicy.sanitizedThrowable(source)

        assertEquals(ObservabilityPolicy.REDACTED_EVENT_MESSAGE, sanitized.message)
        assertFalse(sanitized.toString().contains("private-file-name.pdf"))
        assertEquals(source.stackTrace.toList(), sanitized.stackTrace.toList())
        assertEquals(null, sanitized.cause)
    }
}
