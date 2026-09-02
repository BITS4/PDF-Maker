package com.example.pdfmaker

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SafeDocxInputTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun stagesAValidatedDocxSnapshot() {
        val bytes = docxBytes()

        val staged = SafeDocxInput.stage(ByteArrayInputStream(bytes), temporaryFolder.root)

        assertArrayEquals(bytes, staged.readBytes())
    }

    @Test
    fun stagingRejectsSpoofedAndOversizedProviderContentWithoutArtifacts() {
        assertTrue(
            runCatching {
                SafeDocxInput.stage(ByteArrayInputStream("PK fake".toByteArray()), temporaryFolder.root)
            }.isFailure,
        )
        assertTrue(
            runCatching {
                SafeDocxInput.stage(ByteArrayInputStream(docxBytes()), temporaryFolder.root, maximumBytes = 8)
            }.isFailure,
        )
        assertTrue(temporaryFolder.root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun entryReaderStopsAtItsIndependentLimit() {
        assertArrayEquals(
            "1234".toByteArray(),
            SafeDocxInput.readEntry(ByteArrayInputStream("1234".toByteArray()), 4),
        )
        assertTrue(
            runCatching {
                SafeDocxInput.readEntry(ByteArrayInputStream("12345".toByteArray()), 4)
            }.isFailure,
        )
    }

    @Test
    fun xmlDecoderRejectsEntityAndDoctypePayloads() {
        assertEquals("<document>safe</document>", SafeDocxInput.decodeXml("<document>safe</document>".toByteArray()))
        assertTrue(
            runCatching { SafeDocxInput.decodeXml("<!DOCTYPE x><document/>".toByteArray()) }.isFailure,
        )
        assertTrue(
            runCatching { SafeDocxInput.decodeXml("<!ENTITY x SYSTEM 'file:///secret'>".toByteArray()) }.isFailure,
        )
    }

    private fun docxBytes(): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write("<Types/>".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write("<document>safe</document>".toByteArray())
            zip.closeEntry()
        }
    }.toByteArray()
}
