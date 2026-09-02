package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockPdfSelectionPolicyTest {
    @Test
    fun extensionPolicyAcceptsOnlyPdfLeafNamesCaseInsensitively() {
        listOf(
            "/documents/report.pdf",
            "/documents/report.PDF",
            "C:\\documents\\report.PdF",
        ).forEach { path ->
            assertTrue(path, LockPdfSelectionPolicy.hasPdfExtension(path))
        }

        listOf(
            "",
            ".pdf",
            "/documents/report",
            "/documents/report.pdf.tmp",
            "/documents/archive.pdf/report.txt",
            "/documents/report.docx",
            "/documents/report.pdf ",
        ).forEach { path ->
            assertFalse(path, LockPdfSelectionPolicy.hasPdfExtension(path))
        }
    }

    @Test
    fun candidatesPreserveOrderAndCheckLockStateOnlyForPdfs() {
        val files =
            listOf(
                pdfFile("first.pdf"),
                pdfFile("notes.txt"),
                pdfFile("LOCKED.PDF"),
                pdfFile("last.PdF"),
            )
        val inspected = mutableListOf<String>()

        val candidates =
            LockPdfSelectionPolicy.unlockedPdfCandidates(files) { path ->
                inspected += path
                path.endsWith("LOCKED.PDF")
            }

        assertEquals(listOf("first.pdf", "last.PdF"), candidates.map(PdfFile::filePath))
        assertEquals(listOf("first.pdf", "LOCKED.PDF", "last.PdF"), inspected)
    }

    private fun pdfFile(path: String) =
        PdfFile(
            name = path,
            filePath = path,
            size = "1 KB",
            date = "today",
        )
}
