package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentShareAdapterTest {
    @Test
    fun `selects registered document mime types case insensitively`() {
        assertEquals("application/pdf", DocumentShareAdapter.mimeType("report.PDF"))
        assertEquals(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            DocumentShareAdapter.mimeType("report.DoCx"),
        )
        assertEquals(
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            DocumentShareAdapter.mimeType("slides.PPTX"),
        )
        assertEquals(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            DocumentShareAdapter.mimeType("sheet.XLSX"),
        )
        assertEquals("image/jpeg", DocumentShareAdapter.mimeType("scan.JPEG"))
    }

    @Test
    fun `uses safe fallback for missing or unsupported extensions`() {
        val fallback = "application/octet-stream"

        assertEquals(fallback, DocumentShareAdapter.mimeType("document"))
        assertEquals(fallback, DocumentShareAdapter.mimeType("document."))
        assertEquals(fallback, DocumentShareAdapter.mimeType("document.exe"))
        assertEquals(fallback, DocumentShareAdapter.mimeType(""))
        assertEquals(fallback, DocumentShareAdapter.mimeType("report.pdf.exe"))
    }
}
