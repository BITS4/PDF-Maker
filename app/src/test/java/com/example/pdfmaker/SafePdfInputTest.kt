package com.example.pdfmaker

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SafePdfInputTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun stagesAValidPdfAsAnOwnedSnapshot() {
        val bytes = "%PDF-1.7\nprovider content".toByteArray()

        val file = SafePdfInput.stage(ByteArrayInputStream(bytes), temporaryFolder.root)

        assertArrayEquals(bytes, file.readBytes())
        assertTrue(file.canonicalFile.parentFile == temporaryFolder.root.canonicalFile)
    }

    @Test
    fun rejectsSpoofedAndEmptyDocumentsWithoutArtifacts() {
        assertTrue(
            runCatching {
                SafePdfInput.stage(ByteArrayInputStream("not a pdf".toByteArray()), temporaryFolder.root)
            }.isFailure,
        )
        assertTrue(
            runCatching { SafePdfInput.stage(ByteArrayInputStream(byteArrayOf()), temporaryFolder.root) }.isFailure,
        )

        assertTrue(temporaryFolder.root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun rejectsProviderStreamsAboveTheConfiguredLimitAndCleansUp() {
        val oversized = "%PDF-".toByteArray() + ByteArray(20)

        val result = runCatching {
            SafePdfInput.stage(ByteArrayInputStream(oversized), temporaryFolder.root, maximumBytes = 16)
        }

        assertTrue(result.isFailure)
        assertTrue(temporaryFolder.root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun rejectsLockedInputUntilItIsExplicitlyUnlocked() {
        val locked = SecureDocumentCodec.encrypt("%PDF-1.7".toByteArray(), "password", iterations = 1_000)

        val result = runCatching {
            SafePdfInput.stage(ByteArrayInputStream(locked), temporaryFolder.root)
        }

        assertTrue(result.isFailure)
        assertFalse(temporaryFolder.root.listFiles().orEmpty().any())
    }

    @Test
    fun closesAndDeletesOwnedSnapshotsAfterSpooling() {
        val staged = temporaryFolder.newFile("print-source.pdf").apply {
            writeText("%PDF-1.7\nprint content")
        }

        StagedPdfSource(staged).use { source ->
            assertTrue(source.file.isFile)
            assertTrue(source.file.inputStream().use { it.read() } >= 0)
        }

        assertFalse(staged.exists())
    }
}
