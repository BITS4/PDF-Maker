package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfToJpgPolicyTest {
    @Test
    fun `accepts supported page counts and rejects resource exhaustion`() {
        assertEquals(1, PdfToJpgPolicy.requirePageCount(1))
        assertEquals(
            PdfToJpgPolicy.MAX_EXPORT_PAGES,
            PdfToJpgPolicy.requirePageCount(PdfToJpgPolicy.MAX_EXPORT_PAGES),
        )
        listOf(0, PdfToJpgPolicy.MAX_EXPORT_PAGES + 1).forEach { count ->
            assertTrue(runCatching { PdfToJpgPolicy.requirePageCount(count) }.isFailure)
        }
    }

    @Test
    fun `bounds export rendering by edge and pixel count`() {
        val square = PdfToJpgPolicy.renderSize(20_000, 20_000, 10_000)!!
        assertTrue(square.width <= PdfToJpgPolicy.MAX_RENDER_EDGE)
        assertTrue(square.height <= PdfToJpgPolicy.MAX_RENDER_EDGE)
        assertTrue(square.pixelCount <= PdfToJpgPolicy.MAX_RENDER_PIXELS)

        val portrait = PdfToJpgPolicy.renderSize(1_000, 100_000, 3_000)!!
        assertTrue(portrait.pixelCount <= PdfToJpgPolicy.MAX_RENDER_PIXELS)
        assertNull(PdfToJpgPolicy.renderSize(0, 100, 3_000))
        assertNull(PdfToJpgPolicy.renderSize(100, 100, 0))
    }

    @Test
    fun `plans bounded result thumbnails`() {
        val plan = PdfToJpgPolicy.resultThumbnailPlan(8_000, 6_000)!!
        assertTrue(plan.estimatedDimensions.width <= PdfToJpgPolicy.RESULT_THUMBNAIL_EDGE)
        assertTrue(plan.estimatedDimensions.height <= PdfToJpgPolicy.RESULT_THUMBNAIL_EDGE)
        assertTrue(plan.estimatedDimensions.pixels <= PdfToJpgPolicy.RESULT_THUMBNAIL_PIXELS)
        assertNull(PdfToJpgPolicy.resultThumbnailPlan(-1, 10))
    }

    @Test
    fun `enforces individual and cumulative output budgets`() {
        val first = PdfToJpgPolicy.recordExportedFile(0, 1_024)
        assertEquals(1_024, first)
        assertEquals(3_072, PdfToJpgPolicy.requireShareBatch(listOf(1_024, 2_048)))

        assertTrue(
            runCatching {
                PdfToJpgPolicy.recordExportedFile(0, PdfToJpgPolicy.MAX_JPEG_BYTES + 1)
            }.isFailure,
        )
        assertTrue(
            runCatching {
                PdfToJpgPolicy.recordExportedFile(
                    PdfToJpgPolicy.MAX_EXPORT_BYTES,
                    1,
                )
            }.isFailure,
        )
        assertTrue(runCatching { PdfToJpgPolicy.requireShareBatch(emptyList()) }.isFailure)
    }

    @Test
    fun `sanitizes provider paths into bounded display names`() {
        assertEquals("report", PdfToJpgPolicy.displayBaseName("folder/report.PDF"))
        assertEquals("invoice", PdfToJpgPolicy.displayBaseName("folder%2Finvoice.pdf"))
        assertEquals("document", PdfToJpgPolicy.displayBaseName("\u0000\u202E.pdf"))
        assertEquals(
            PdfToJpgPolicy.MAX_DISPLAY_NAME_LENGTH,
            PdfToJpgPolicy.displayBaseName("x".repeat(200) + ".pdf").length,
        )
    }

    @Test
    fun `maps every failure stage without leaking exception details`() {
        val privateDetail = "/storage/private/customer-report.pdf"
        PdfToJpgFailureStage.entries.forEach { stage ->
            listOf(
                SecurityException(privateDetail),
                java.io.IOException(privateDetail),
                IllegalStateException(privateDetail),
            ).forEach { error ->
                val message = PdfToJpgPolicy.failureMessage(stage, error)
                assertFalse(message.contains(privateDetail))
                assertFalse(message.contains("customer-report"))
            }
        }
    }
}
