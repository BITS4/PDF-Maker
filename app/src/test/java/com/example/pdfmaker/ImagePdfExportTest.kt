package com.example.pdfmaker

import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImagePdfExportTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val pdf = "%PDF-1.7\nimage pages".toByteArray()

    @Test
    fun unprotectedExportWritesAUniquePdf() {
        temporaryFolder.newFile("scan.pdf").writeText("existing")

        val result = ImagePdfExport.write(temporaryFolder.root, "scan", null) { it.write(pdf) }

        assertTrue(result.isSuccess)
        assertEquals("scan (1).pdf", result.getOrThrow().name)
        assertArrayEquals(pdf, result.getOrThrow().readBytes())
    }

    @Test
    fun protectedExportIsAuthenticatedAndDecryptable() {
        val result = ImagePdfExport.write(temporaryFolder.root, "private", "password") { it.write(pdf) }
        val output = result.getOrThrow()

        assertTrue(SecureDocumentStore.isLocked(output))
        assertArrayEquals(pdf, SecureDocumentCodec.decrypt(output.readBytes(), "password"))
    }

    @Test
    fun invalidPasswordNeverLeavesAPlaintextPdf() {
        val result = ImagePdfExport.write(temporaryFolder.root, "private", "123") { it.write(pdf) }

        assertTrue(result.isFailure)
        assertFalse(temporaryFolder.root.listFiles().orEmpty().any { it.extension == "pdf" })
    }

    @Test
    fun writerFailureLeavesNoTargetOrTemporaryFile() {
        val result = ImagePdfExport.write(temporaryFolder.root, "broken", null) {
            it.write(pdf)
            throw IOException("simulated storage failure")
        }

        assertTrue(result.isFailure)
        assertTrue(temporaryFolder.root.listFiles().orEmpty().isEmpty())
    }
}
