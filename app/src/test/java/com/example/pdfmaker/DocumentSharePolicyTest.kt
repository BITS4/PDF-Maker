package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DocumentSharePolicyTest {
    @Test
    fun `maps every supported extension to an exact mime type`() {
        val expectedTypes =
            mapOf(
                "document.pdf" to "application/pdf",
                "document.doc" to "application/msword",
                "document.docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "sheet.xls" to "application/vnd.ms-excel",
                "sheet.xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "slides.ppt" to "application/vnd.ms-powerpoint",
                "slides.pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "data.csv" to "text/csv",
                "data.tsv" to "text/tab-separated-values",
                "notes.txt" to "text/plain",
                "notes.md" to "text/markdown",
                "photo.jpg" to "image/jpeg",
                "photo.jpeg" to "image/jpeg",
                "photo.png" to "image/png",
                "photo.gif" to "image/gif",
                "photo.webp" to "image/webp",
                "photo.bmp" to "image/bmp",
                "archive.zip" to "application/zip",
            )

        expectedTypes.forEach { (fileName, expectedType) ->
            assertEquals(expectedType, DocumentSharePolicy.mimeType(fileName))
        }
    }

    @Test
    fun `normalizes provider paths case query and fragment before inspecting extension`() {
        assertEquals(
            "application/pdf",
            DocumentSharePolicy.mimeType("content://provider/folder\\REPORT.PDF?download=1#page"),
        )
        assertEquals("image/jpeg", DocumentSharePolicy.mimeType("folder/photo.JpEg#preview"))
    }

    @Test
    fun `uses binary fallback instead of wildcard for unknown extensions`() {
        listOf("document", "document.", "document.unknown", "").forEach { fileName ->
            assertEquals(DocumentSharePolicy.FALLBACK_MIME, DocumentSharePolicy.mimeType(fileName))
        }
    }

    @Test
    fun `selects single and multiple send modes with exact shared mime types`() {
        assertEquals(
            DocumentSharePlan(DocumentSendMode.SINGLE, "application/pdf"),
            DocumentSharePolicy.sharePlan(listOf("document.pdf")),
        )
        assertEquals(
            DocumentSharePlan(DocumentSendMode.MULTIPLE, "image/jpeg"),
            DocumentSharePolicy.sharePlan(listOf("page-1.jpg", "page-2.jpeg")),
        )
    }

    @Test
    fun `uses binary fallback for mixed file types instead of a wildcard`() {
        assertEquals(
            DocumentSharePlan(DocumentSendMode.MULTIPLE, DocumentSharePolicy.FALLBACK_MIME),
            DocumentSharePolicy.sharePlan(listOf("document.pdf", "preview.jpg")),
        )
    }

    @Test
    fun `normalizes an explicit exact mime type`() {
        assertEquals(
            DocumentSharePlan(DocumentSendMode.MULTIPLE, "image/jpeg"),
            DocumentSharePolicy.sharePlan(
                fileNames = listOf("page-1.bin", "page-2.bin"),
                requestedMimeType = "  IMAGE/JPEG  ",
            ),
        )
    }

    @Test
    fun `rejects wildcard malformed and injectable mime types`() {
        val invalidTypes =
            listOf(
                "",
                "image",
                "image/",
                "/jpeg",
                "*/*",
                "image/*",
                "*/jpeg",
                "image/jpeg/extra",
                "image/jpeg\r\ntext/plain",
                "image/jpeg; charset=utf-8",
            )

        invalidTypes.forEach { mimeType ->
            assertThrows(IllegalArgumentException::class.java) {
                DocumentSharePolicy.requireMimeType(mimeType)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            DocumentSharePolicy.sharePlan(
                fileNames = listOf("page.jpg"),
                requestedMimeType = "image/*",
            )
        }
    }

    @Test
    fun `enforces bounded nonempty share batches`() {
        assertThrows(IllegalArgumentException::class.java) {
            DocumentSharePolicy.sharePlan(emptyList())
        }
        assertEquals(
            DocumentSendMode.MULTIPLE,
            DocumentSharePolicy.sharePlan(List(DocumentSharePolicy.MAX_SHARED_FILES) { "page-$it.jpg" }).mode,
        )
        assertThrows(IllegalArgumentException::class.java) {
            DocumentSharePolicy.sharePlan(
                List(DocumentSharePolicy.MAX_SHARED_FILES + 1) { "page-$it.jpg" },
            )
        }
    }
}
