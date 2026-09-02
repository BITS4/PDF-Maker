package com.example.pdfmaker

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CancellationException
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafeDocumentImporterInstrumentedTest {
    private lateinit var context: Context
    private val createdFiles = mutableListOf<File>()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @After
    fun tearDown() {
        createdFiles.forEach(File::delete)
    }

    @Test
    fun userPdfIsCopiedIntoThePersistentDestinationBeforeRetention() {
        val bytes = "%PDF-1.7\nimported\n%%EOF".toByteArray()
        val source = sourceFile("persistent", bytes)

        val result = import(source, IncomingImportRetention.USER_DOCUMENT)
        val artifact = (result as IncomingImportResult.Imported).artifact
        createdFiles += artifact.file

        assertEquals(IncomingDocumentKind.PDF, artifact.kind)
        assertNotEquals(source.canonicalFile, artifact.file.canonicalFile)
        assertEquals(getPdfMakerDir(context).canonicalFile, artifact.file.parentFile?.canonicalFile)
        assertArrayEquals(bytes, artifact.file.readBytes())

        val retained = artifact.retain()
        artifact.close()
        assertTrue(retained.isFile)
    }

    @Test
    fun operationPdfRemainsPrivateAndIsDeletedWhenItsArtifactCloses() {
        val source = sourceFile("temporary", "%PDF-1.7\n%%EOF".toByteArray())

        val result = import(source, IncomingImportRetention.OPERATION_TEMPORARY)
        val artifact = (result as IncomingImportResult.Imported).artifact
        val imported = artifact.file

        assertEquals(
            IncomingImportStoragePolicy.temporaryDirectory(context.cacheDir).canonicalFile,
            imported.parentFile?.canonicalFile,
        )
        artifact.close()
        assertFalse(imported.exists())
    }

    @Test
    fun cancellationDuringProviderCopyRemovesThePartialSnapshot() {
        val source = sourceFile(
            "cancel-copy",
            "%PDF-1.7\n".toByteArray() + ByteArray(DEFAULT_BUFFER_SIZE * 3),
        )
        val staging = IncomingImportStoragePolicy.temporaryDirectory(context.cacheDir).apply { mkdirs() }
        val before = staging.listFiles().orEmpty().map(File::getName).toSet()
        var checks = 0

        assertThrows(CancellationException::class.java) {
            SafeDocumentImporter.import(
                context = context,
                request = request(source),
                retention = IncomingImportRetention.USER_DOCUMENT,
                beforeChunk = {
                    checks += 1
                    if (checks == 4) throw CancellationException("cancelled")
                },
            )
        }

        assertEquals(before, staging.listFiles().orEmpty().map(File::getName).toSet())
    }

    @Test
    fun cancellationAfterCommitRemovesTheUnacceptedPersistentFile() {
        val uniqueName = "cancel-commit-${System.nanoTime()}"
        val source = sourceFile(uniqueName, "%PDF-1.7\n%%EOF".toByteArray())
        val outputDirectory = getPdfMakerDir(context)

        assertThrows(CancellationException::class.java) {
            SafeDocumentImporter.import(
                context = context,
                request = request(source),
                retention = IncomingImportRetention.USER_DOCUMENT,
                beforeChunk = {
                    if (matchingOutputs(outputDirectory, uniqueName).isNotEmpty()) {
                        throw CancellationException("cancelled after commit")
                    }
                },
            )
        }

        assertTrue(matchingOutputs(outputDirectory, uniqueName).isEmpty())
    }

    private fun import(source: File, retention: IncomingImportRetention): IncomingImportResult =
        SafeDocumentImporter.import(
            context = context,
            request = request(source),
            retention = retention,
        )

    private fun request(source: File): IncomingDocumentRequest =
        IncomingDocumentRequest(ownedUri(source), "application/pdf")

    private fun sourceFile(label: String, bytes: ByteArray): File {
        val directory = File(context.cacheDir, "pdfmaker").apply { mkdirs() }
        return File(directory, "$label-${System.nanoTime()}.pdf").apply {
            writeBytes(bytes)
            createdFiles += this
        }
    }

    private fun ownedUri(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.provider", file)

    private fun matchingOutputs(directory: File, baseName: String): List<File> =
        directory.listFiles().orEmpty().filter { file -> file.name.startsWith(baseName) }
}
