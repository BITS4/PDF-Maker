package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ImportedDocumentInspectorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun recognizesPdfAndEverySupportedImageSignature() {
        assertEquals(IncomingDocumentKind.PDF, ImportedDocumentInspector.signature("%PDF-1.7".toByteArray()))
        assertEquals(
            IncomingDocumentKind.JPEG,
            ImportedDocumentInspector.signature(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00)),
        )
        assertEquals(
            IncomingDocumentKind.PNG,
            ImportedDocumentInspector.signature(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)),
        )
        assertEquals(IncomingDocumentKind.GIF, ImportedDocumentInspector.signature("GIF89a".toByteArray()))
        assertEquals(IncomingDocumentKind.WEBP, ImportedDocumentInspector.signature("RIFF0000WEBP".toByteArray()))
        assertEquals(IncomingDocumentKind.BMP, ImportedDocumentInspector.signature("BMpayload".toByteArray()))
        assertNull(ImportedDocumentInspector.signature("<html>not a pdf".toByteArray()))
    }

    @Test
    fun pdfInspectionDoesNotTrustTheFileExtension() {
        val valid = temporaryFolder.newFile("payload.bin").apply { writeText("%PDF-1.7\ncontent") }
        val fake = temporaryFolder.newFile("fake.pdf").apply { writeText("not a PDF") }

        assertEquals(IncomingDocumentKind.PDF, ImportedDocumentInspector.inspect(valid))
        assertNull(ImportedDocumentInspector.inspect(fake))
    }

    @Test
    fun acceptsOnlyDocxContainersWithRequiredParts() {
        val valid =
            createZip(
                "valid.docx",
                mapOf("[Content_Types].xml" to "types", "word/document.xml" to "document"),
            )
        val genericZip = createZip("generic.zip", mapOf("notes.txt" to "hello"))

        assertEquals(IncomingDocumentKind.DOCX, ImportedDocumentInspector.inspect(valid))
        assertNull(ImportedDocumentInspector.inspect(genericZip))
    }

    @Test
    fun rejectsDocxTraversalEntriesAndCompressionBombs() {
        val traversal =
            createZip(
                "traversal.docx",
                mapOf("[Content_Types].xml" to "types", "word/document.xml" to "doc", "../escape" to "bad"),
            )
        val bomb = temporaryFolder.newFile("bomb.docx")
        ZipOutputStream(FileOutputStream(bomb)).use { output ->
            output.putNextEntry(ZipEntry("[Content_Types].xml"))
            output.write("types".toByteArray())
            output.closeEntry()
            output.putNextEntry(ZipEntry("word/document.xml"))
            output.write(ByteArray(2 * 1024 * 1024))
            output.closeEntry()
        }

        assertNull(ImportedDocumentInspector.inspect(traversal))
        assertNull(ImportedDocumentInspector.inspect(bomb))
    }

    @Test
    fun docxInspectionPropagatesCancellationInsteadOfDowngradingItToRejection() {
        val valid =
            createZip(
                "cancelled.docx",
                mapOf(
                    "[Content_Types].xml" to "types",
                    "word/document.xml" to "document",
                ),
            )
        var checks = 0

        assertThrows(CancellationException::class.java) {
            ImportedDocumentInspector.inspect(valid) {
                checks += 1
                if (checks == 3) throw CancellationException("cancelled")
            }
        }
        assertEquals(3, checks)
    }

    @Test
    fun mimeValidationRejectsContentTypeConfusion() {
        assertTrue(ImportedDocumentInspector.mimeTypesMatch(IncomingDocumentKind.PDF, "application/pdf"))
        assertTrue(ImportedDocumentInspector.mimeTypesMatch(IncomingDocumentKind.PDF, "application/octet-stream", null))
        assertTrue(ImportedDocumentInspector.mimeTypesMatch(IncomingDocumentKind.PNG, "image/*", "image/png"))
        assertTrue(ImportedDocumentInspector.mimeTypesMatch(IncomingDocumentKind.JPEG, "image/jpg"))
        assertTrue(ImportedDocumentInspector.mimeTypesMatch(IncomingDocumentKind.BMP, "image/x-ms-bmp"))
        assertFalse(ImportedDocumentInspector.mimeTypesMatch(IncomingDocumentKind.PDF, "image/png"))
        assertFalse(ImportedDocumentInspector.mimeTypesMatch(IncomingDocumentKind.DOCX, "application/pdf"))
        assertFalse(ImportedDocumentInspector.mimeTypesMatch(IncomingDocumentKind.JPEG, "image/png"))
    }

    private fun createZip(
        name: String,
        entries: Map<String, String>,
    ): File {
        val file = temporaryFolder.newFile(name)
        ZipOutputStream(FileOutputStream(file)).use { output ->
            entries.forEach { (entryName, contents) ->
                output.putNextEntry(ZipEntry(entryName))
                output.write(contents.toByteArray())
                output.closeEntry()
            }
        }
        return file
    }
}
