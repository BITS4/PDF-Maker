package com.example.pdfmaker

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocxConversionArchiveTest {
    @Test
    fun `extracts required parts and cleans staged media`() {
        val root = Files.createTempDirectory("docx-conversion-test").toFile()
        try {
            val archiveFile =
                File(root, "source.docx").apply {
                    writeBytes(
                        zipBytes(
                            "word/document.xml" to "<document/>".toByteArray(),
                            "word/_rels/document.xml.rels" to "<Relationships/>".toByteArray(),
                            "word/media/image.png" to byteArrayOf(1, 2, 3),
                            "custom/ignored.bin" to byteArrayOf(4, 5),
                        ),
                    )
                }

            val extracted = extractDocxConversionArchive(archiveFile, root)
            val mediaFile = requireNotNull(extracted.mediaFiles["image.png"])

            assertEquals("<document/>", extracted.documentXml)
            assertEquals("<Relationships/>", extracted.relationshipsXml)
            assertArrayEquals(byteArrayOf(1, 2, 3), mediaFile.readBytes())
            val extractionDirectory = requireNotNull(mediaFile.parentFile)
            extracted.close()
            assertFalse(mediaFile.exists())
            assertFalse(extractionDirectory.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `rejects a missing core part and removes partial extraction`() {
        val root = Files.createTempDirectory("docx-conversion-duplicate-test").toFile()
        try {
            val archiveFile =
                File(root, "source.docx").apply {
                    writeBytes(
                        zipBytes(
                            "word/_rels/document.xml.rels" to "<Relationships/>".toByteArray(),
                            "word/media/image.png" to byteArrayOf(1, 2, 3),
                        ),
                    )
                }

            assertThrows(IllegalArgumentException::class.java) {
                extractDocxConversionArchive(archiveFile, root)
            }
            assertTrue(root.listFiles().orEmpty().none(File::isDirectory))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `cancellation during media copy erases partial extraction`() {
        val root = Files.createTempDirectory("docx-conversion-media-cancel").toFile()
        try {
            val archiveFile =
                File(root, "source.docx").apply {
                    writeBytes(
                        zipBytes(
                            "word/document.xml" to "<document/>".toByteArray(),
                            "word/media/large.bin" to ByteArray(32_000) { index -> index.toByte() },
                        ),
                    )
                }
            var observedPartialMedia = false

            assertThrows(CancellationException::class.java) {
                extractDocxConversionArchive(archiveFile, root) {
                    val partialMedia =
                        root
                            .walkTopDown()
                            .firstOrNull { file -> file.isFile && file.extension == "media" && file.length() > 0L }
                    if (partialMedia != null) {
                        observedPartialMedia = true
                        throw CancellationException("cancel media")
                    }
                }
            }

            assertTrue(observedPartialMedia)
            assertTrue(root.listFiles().orEmpty().none(File::isDirectory))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `cancellation during ignored entry copy erases extraction directory`() {
        val root = Files.createTempDirectory("docx-conversion-skip-cancel").toFile()
        try {
            val archiveFile =
                File(root, "source.docx").apply {
                    writeBytes(
                        zipBytes(
                            "custom/large.bin" to ByteArray(32_000) { index -> index.toByte() },
                            "word/document.xml" to "<document/>".toByteArray(),
                        ),
                    )
                }
            var checks = 0

            assertThrows(CancellationException::class.java) {
                extractDocxConversionArchive(archiveFile, root) {
                    checks += 1
                    if (checks == 4) throw CancellationException("cancel ignored entry")
                }
            }

            assertEquals(4, checks)
            assertTrue(root.listFiles().orEmpty().none(File::isDirectory))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun zipBytes(vararg entries: Pair<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            ZipOutputStream(bytes).use { zip ->
                entries.forEach { (name, contents) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(contents)
                    zip.closeEntry()
                }
            }
            bytes.toByteArray()
        }
}
