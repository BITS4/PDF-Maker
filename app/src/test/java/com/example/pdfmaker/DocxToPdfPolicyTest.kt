package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class DocxToPdfPolicyTest {
    @Test
    fun `only a content URI with a bounded nonblank authority is accepted`() {
        DocxToPdfPolicy.requireProviderUri("content", "com.example.documents")

        listOf(null, "", "file", "http", "CONTENT").forEach { scheme ->
            assertThrows(IllegalArgumentException::class.java) {
                DocxToPdfPolicy.requireProviderUri(scheme, "com.example.documents")
            }
        }
        listOf<String?>(null, "", "   ", " authority", "bad authority", "bad\nname", "a".repeat(256))
            .forEach { authority ->
                assertThrows(IllegalArgumentException::class.java) {
                    DocxToPdfPolicy.requireProviderUri("content", authority)
                }
            }
    }

    @Test
    fun `provider display name wins and is sanitized before display`() {
        val result =
            metadata(
                displayName = "../../Q3‮secret.DOCX",
                mimeType = DocxToPdfPolicy.DOCX_MIME_TYPE,
                reportedSize = 8_192L,
                descriptorSize = 4_096L,
                fallbackName = "fallback.docx",
            )

        assertEquals("Q3secret", result.displayName)
        assertEquals("Q3secret.docx", result.fileName)
        assertEquals(8_192L, result.sizeBytes)
        assertEquals("8 kB", result.formattedSize)
    }

    @Test
    fun `missing or oversized provider names safely use bounded fallbacks`() {
        val missing = metadata(displayName = null, fallbackName = "Reports/Annual.docx")
        val malicious = metadata(displayName = "x".repeat(513), fallbackName = "safe.docx")
        val unnamed = metadata(displayName = null, fallbackName = null, pathSegment = null)

        assertEquals("Annual", missing.displayName)
        assertEquals("safe", malicious.displayName)
        assertEquals("document", unnamed.displayName)
    }

    @Test
    fun `known DOCX MIME accepts case and parameters`() {
        val upper =
            metadata(
                displayName = "report.bin",
                mimeType = DocxToPdfPolicy.DOCX_MIME_TYPE.uppercase() + "; charset=binary",
            )

        assertEquals("report.bin", upper.displayName)
    }

    @Test
    fun `generic MIME requires a DOCX provider extension when one is reported`() {
        listOf<String?>(null, "", "*/*", "application/octet-stream", "application/zip")
            .forEach { mimeType ->
                assertEquals(
                    "report",
                    metadata(displayName = "report.docx", mimeType = mimeType).displayName,
                )
                assertEquals(
                    "report",
                    metadata(displayName = "report", mimeType = mimeType).displayName,
                )
            }

        listOf("report.doc", "report.pdf", "report.png").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) {
                metadata(displayName = name, mimeType = "application/octet-stream")
            }
        }
    }

    @Test
    fun `generic MIME validates fallback and path names when provider name is absent`() {
        assertEquals(
            "fallback",
            metadata(
                displayName = null,
                mimeType = null,
                fallbackName = "fallback.docx",
                pathSegment = "ignored.pdf",
            ).displayName,
        )
        assertEquals(
            "path-name",
            metadata(
                displayName = null,
                mimeType = "application/octet-stream",
                fallbackName = null,
                pathSegment = "path-name.DOCX",
            ).displayName,
        )
        listOf("fallback.pdf", "legacy.doc").forEach { fallback ->
            assertThrows(IllegalArgumentException::class.java) {
                metadata(
                    displayName = null,
                    mimeType = null,
                    fallbackName = fallback,
                    pathSegment = null,
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            metadata(
                displayName = null,
                mimeType = "*/*",
                fallbackName = null,
                pathSegment = "payload.png",
            )
        }
    }

    @Test
    fun `meaningful incompatible and malformed MIME types are rejected`() {
        listOf(
            "application/msword",
            "application/pdf",
            "image/png",
            "text/plain",
            "bad\nvalue",
            "a".repeat(256),
        ).forEach { mimeType ->
            assertThrows(IllegalArgumentException::class.java) {
                metadata(displayName = "report.docx", mimeType = mimeType)
            }
        }
    }

    @Test
    fun `largest trustworthy size is used and unknown sizes remain displayable`() {
        val queryLarger = metadata(reportedSize = 9_000L, descriptorSize = 8_000L)
        val descriptorLarger = metadata(reportedSize = 8_000L, descriptorSize = 9_000L)
        val unknown = metadata(reportedSize = -1L, descriptorSize = -1L)

        assertEquals(9_000L, queryLarger.sizeBytes)
        assertEquals(9_000L, descriptorLarger.sizeBytes)
        assertEquals(0L, unknown.sizeBytes)
    }

    @Test
    fun `size limits reject overflow and malformed negative metadata`() {
        assertEquals(
            SafeDocxInput.MAX_DOCX_BYTES,
            metadata(
                reportedSize = SafeDocxInput.MAX_DOCX_BYTES,
                descriptorSize = -1L,
            ).sizeBytes,
        )
        listOf(-2L, Long.MIN_VALUE).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                metadata(reportedSize = invalid)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            metadata(descriptorSize = SafeDocxInput.MAX_DOCX_BYTES + 1L)
        }
    }

    @Test
    fun `progress values and labels are bounded`() {
        assertEquals(0, DocxToPdfPolicy.clampProgress(Int.MIN_VALUE))
        assertEquals(43, DocxToPdfPolicy.clampProgress(43))
        assertEquals(100, DocxToPdfPolicy.clampProgress(Int.MAX_VALUE))
        assertEquals("Converting document…", DocxToPdfPolicy.progressLabel("\u0000\n"))
        assertEquals("Rendering", DocxToPdfPolicy.progressLabel("  Ren\u0000dering  "))
        assertEquals(80, DocxToPdfPolicy.progressLabel("a".repeat(120)).length)
    }

    @Test
    fun `generation and back policies cover operation boundaries`() {
        assertEquals(1L, DocxToPdfPolicy.nextGeneration(0L))
        assertEquals(1L, DocxToPdfPolicy.nextGeneration(Long.MAX_VALUE))
        assertThrows(IllegalArgumentException::class.java) {
            DocxToPdfPolicy.nextGeneration(-1L)
        }
        assertEquals(
            DocxToPdfBackAction.CANCEL_OPERATION,
            DocxToPdfPolicy.backAction(DocxToPdfPhase.PREPARING),
        )
        assertEquals(
            DocxToPdfBackAction.CANCEL_OPERATION,
            DocxToPdfPolicy.backAction(DocxToPdfPhase.CONVERTING),
        )
        listOf(DocxToPdfPhase.PICK, DocxToPdfPhase.READY, DocxToPdfPhase.DONE).forEach { phase ->
            assertEquals(DocxToPdfBackAction.NAVIGATE_BACK, DocxToPdfPolicy.backAction(phase))
        }
    }

    @Test
    fun `failure messages are fixed by stage and never expose exception content`() {
        val secret = "/private/provider/customer-name.docx"
        val failures =
            listOf(
                DocxToPdfFailureStage.SELECT to SecurityException(secret),
                DocxToPdfFailureStage.SELECT to IOException(secret),
                DocxToPdfFailureStage.SELECT to IllegalArgumentException(secret),
                DocxToPdfFailureStage.CONVERT to SecurityException(secret),
                DocxToPdfFailureStage.CONVERT to IOException(secret),
                DocxToPdfFailureStage.CONVERT to IllegalStateException(secret),
                DocxToPdfFailureStage.VERIFY to IllegalStateException(secret),
                DocxToPdfFailureStage.SHARE to IllegalStateException(secret),
            )

        failures.forEach { (stage, error) ->
            val message = DocxToPdfPolicy.failureMessage(stage, error)
            assertFalse(message.contains(secret))
            assertTrue(message.isNotBlank())
        }
        assertTrue(
            DocxToPdfPolicy
                .failureMessage(DocxToPdfFailureStage.SHARE, IllegalStateException())
                .startsWith("The converted PDF is saved"),
        )
    }

    @Test
    fun `byte formatting is deterministic and rejects impossible values`() {
        assertEquals("0 B", DocxToPdfPolicy.formatSize(0L))
        assertEquals("1023 B", DocxToPdfPolicy.formatSize(1_023L))
        assertEquals("1 kB", DocxToPdfPolicy.formatSize(1_024L))
        assertEquals("1.5 MB", DocxToPdfPolicy.formatSize(1_572_864L))
        assertThrows(IllegalArgumentException::class.java) {
            DocxToPdfPolicy.formatSize(-1L)
        }
    }

    private fun metadata(
        displayName: String? = "report.docx",
        mimeType: String? = DocxToPdfPolicy.DOCX_MIME_TYPE,
        reportedSize: Long? = 4_096L,
        descriptorSize: Long? = 4_096L,
        fallbackName: String? = "fallback.docx",
        pathSegment: String? = "provider-id",
    ): DocxInputMetadata =
        DocxToPdfPolicy.metadata(
            provider =
                DocxProviderMetadata(
                    displayName = displayName,
                    mimeType = mimeType,
                    reportedSize = reportedSize,
                    descriptorSize = descriptorSize,
                ),
            fallbackName = fallbackName,
            pathSegment = pathSegment,
        )
}
