package com.example.pdfmaker

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext

class DocxToPdfActionsTest {
    @Test
    fun `selection success records source and selection failure is fixed and retryable`() {
        Harness().use { harness ->
            harness.actions.select(source("valid"), "fallback.docx")

            assertEquals(DocxToPdfPhase.READY, harness.state.phase)
            assertEquals("report", harness.state.input?.displayName)
            assertEquals("valid", harness.boundaries.inspectedSources.single().testId)

            val secret = "content://private/customer-name.docx"
            harness.boundaries.inspectBlock = { _, _ -> throw IOException(secret) }
            harness.actions.select(source("unreadable"))

            assertEquals(DocxToPdfPhase.READY, harness.state.phase)
            assertEquals("report", harness.state.input?.displayName)
            assertFalse(requireNotNull(harness.state.errorMessage).contains(secret))
        }
    }

    @Test
    fun `new selection cancels its predecessor and stale completion cannot win`() {
        Harness().use { harness ->
            var firstCancelled = false
            harness.boundaries.inspectBlock = { source, _ ->
                if (source.testId == "first") {
                    try {
                        awaitCancellation()
                    } finally {
                        firstCancelled = true
                    }
                }
                input(source.testId ?: "unknown")
            }

            harness.actions.select(source("first"))
            assertEquals(DocxToPdfPhase.PREPARING, harness.state.phase)
            harness.actions.select(source("second"))

            assertTrue(firstCancelled)
            assertEquals(DocxToPdfPhase.READY, harness.state.phase)
            assertEquals("second", harness.state.input?.displayName)
        }
    }

    @Test
    fun `explicit selection cancellation returns to fallback without accepting late data`() {
        Harness().use { harness ->
            var cancelled = false
            harness.boundaries.inspectBlock = { _, _ ->
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }

            harness.actions.select(source("pending"))
            assertTrue(harness.actions.cancelActive())

            assertTrue(cancelled)
            assertEquals(DocxToPdfPhase.PICK, harness.state.phase)
            assertNull(harness.state.input)
        }
    }

    @Test
    fun `verified output reaches done and is cached exactly once`() {
        Harness().use { harness ->
            harness.actions.select(source("valid"))
            harness.actions.startConversion()

            assertEquals(DocxToPdfPhase.DONE, harness.state.phase)
            assertSame(harness.boundaries.verifiedResult, harness.state.result)
            assertEquals(listOf(harness.boundaries.verifiedResult.catalogEntry), harness.boundaries.cached)
            assertTrue(harness.boundaries.deleted.isEmpty())
            assertEquals("report", harness.boundaries.convertedBaseNames.single())
        }
    }

    @Test
    fun `cancellation during verification deletes the unclaimed output`() {
        Harness().use { harness ->
            var verificationCancelled = false
            harness.boundaries.verifyBlock = {
                try {
                    awaitCancellation()
                } finally {
                    verificationCancelled = true
                }
            }
            harness.actions.select(source("valid"))
            harness.actions.startConversion()

            assertEquals(DocxToPdfPhase.CONVERTING, harness.state.phase)
            assertTrue(harness.actions.cancelActive())

            assertTrue(verificationCancelled)
            assertEquals(DocxToPdfPhase.READY, harness.state.phase)
            assertEquals(listOf(harness.boundaries.convertedFile), harness.boundaries.deleted)
            assertTrue(harness.boundaries.cached.isEmpty())
        }
    }

    @Test
    fun `verification failure deletes output and reports a fixed retryable error`() {
        Harness().use { harness ->
            val secret = "/private/customer.pdf"
            harness.boundaries.verifyBlock = { throw IllegalArgumentException(secret) }
            harness.actions.select(source("valid"))
            harness.actions.startConversion()

            assertEquals(DocxToPdfPhase.READY, harness.state.phase)
            assertEquals(listOf(harness.boundaries.convertedFile), harness.boundaries.deleted)
            assertFalse(requireNotNull(harness.state.errorMessage).contains(secret))
            assertTrue(requireNotNull(harness.state.errorMessage).contains("incomplete output"))
        }
    }

    @Test
    fun `share runs on main and failure retains done while later success clears the error`() {
        val mainDispatcher = InlineRecordingDispatcher()
        Harness(mainDispatcher).use { harness ->
            harness.actions.select(source("valid"))
            harness.actions.startConversion()
            val completed = requireNotNull(harness.state.result)
            harness.boundaries.shareBlock = {
                assertTrue(mainDispatcher.isDispatching)
                false
            }

            harness.actions.shareResult()

            assertEquals(DocxToPdfPhase.DONE, harness.state.phase)
            assertSame(completed, harness.state.result)
            assertTrue(requireNotNull(harness.state.errorMessage).startsWith("The converted PDF is saved"))

            harness.boundaries.shareBlock = {
                assertTrue(mainDispatcher.isDispatching)
                true
            }
            harness.actions.shareResult()

            assertEquals(DocxToPdfPhase.DONE, harness.state.phase)
            assertSame(completed, harness.state.result)
            assertNull(harness.state.errorMessage)
        }
    }

    @Test
    fun `unexpected share failure is redacted and keeps the completed result`() {
        Harness().use { harness ->
            harness.actions.select(source("valid"))
            harness.actions.startConversion()
            val completed = requireNotNull(harness.state.result)
            harness.boundaries.shareBlock = { throw UnsupportedOperationException("private target") }

            harness.actions.shareResult()

            assertEquals(DocxToPdfPhase.DONE, harness.state.phase)
            assertSame(completed, harness.state.result)
            assertFalse(requireNotNull(harness.state.errorMessage).contains("private target"))
        }
    }

    @Test
    fun `cache failure cannot invalidate a safely saved result`() {
        Harness().use { harness ->
            harness.boundaries.cacheBlock = { throw IllegalStateException("cache unavailable") }
            harness.actions.select(source("valid"))
            harness.actions.startConversion()

            assertEquals(DocxToPdfPhase.DONE, harness.state.phase)
            assertSame(harness.boundaries.verifiedResult, harness.state.result)
            assertTrue(harness.boundaries.deleted.isEmpty())
        }
    }

    @Test
    fun `progress flood schedules one conflated main delivery with the newest value`() {
        val queuedMain = QueuedDispatcher()
        Harness(queuedMain).use { harness ->
            harness.boundaries.convertBlock = { _, _, onProgress ->
                repeat(10_000) { index -> onProgress(index, "step-$index") }
                awaitCancellation()
            }
            harness.actions.select(source("valid"))
            harness.actions.startConversion()

            assertEquals(DocxToPdfPhase.CONVERTING, harness.state.phase)
            assertEquals(1, queuedMain.pendingCount)
            queuedMain.runAll()
            assertEquals(100, harness.state.progress)
            assertEquals("step-9999", harness.state.progressText)
            assertEquals(0, queuedMain.pendingCount)
            assertTrue(harness.actions.cancelActive())
        }
    }

    private class Harness(
        mainDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    ) : AutoCloseable {
        private val job = SupervisorJob()
        val state = DocxToPdfUiState()
        val boundaries = FakeBoundaries()
        val actions =
            DocxToPdfActions(
                scope = CoroutineScope(job + Dispatchers.Unconfined),
                state = state,
                boundaries = boundaries,
                dispatchers =
                    DocxActionDispatchers(
                        io = Dispatchers.Unconfined,
                        main = mainDispatcher,
                    ),
            )

        override fun close() {
            actions.release()
            job.cancel()
        }
    }

    private class FakeBoundaries : DocxToPdfBoundaries {
        val convertedFile = File("converted.pdf")
        val verifiedResult = result(convertedFile)
        val inspectedSources = mutableListOf<DocxInputSource>()
        val convertedBaseNames = mutableListOf<String>()
        val cached = mutableListOf<PdfFile>()
        val deleted = mutableListOf<File>()
        var inspectBlock: suspend (DocxInputSource, String?) -> DocxInputMetadata = { _, _ -> input("report") }
        var convertBlock: suspend (DocxInputSource, String, (Int, String) -> Unit) -> DocxPdfResult =
            { _, _, progress ->
                progress(50, "Rendering")
                DocxPdfResult(convertedFile, 2)
            }
        var verifyBlock: suspend (DocxPdfResult) -> DocxSavedResult = { verifiedResult }
        var shareBlock: (File) -> Boolean = { true }
        var cacheBlock: (PdfFile) -> Unit = { cached += it }

        override suspend fun inspect(
            source: DocxInputSource,
            fallbackName: String?,
        ): DocxInputMetadata {
            inspectedSources += source
            return inspectBlock(source, fallbackName)
        }

        override suspend fun convert(
            source: DocxInputSource,
            baseName: String,
            onProgress: (Int, String) -> Unit,
        ): DocxPdfResult {
            convertedBaseNames += baseName
            return convertBlock(source, baseName, onProgress)
        }

        override suspend fun verify(converted: DocxPdfResult): DocxSavedResult = verifyBlock(converted)

        override fun share(file: File): Boolean = shareBlock(file)

        override fun cache(catalogEntry: PdfFile) = cacheBlock(catalogEntry)

        override fun deleteUnclaimed(file: File) {
            deleted += file
        }
    }

    private class InlineRecordingDispatcher : CoroutineDispatcher() {
        var isDispatching = false
            private set

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            isDispatching = true
            try {
                block.run()
            } finally {
                isDispatching = false
            }
        }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        val pendingCount: Int
            get() = tasks.size

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            tasks.addLast(block)
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    private companion object {
        fun source(id: String): DocxInputSource = DocxInputSource.test(id)

        fun input(name: String): DocxInputMetadata = DocxInputMetadata(name, 4_096L)

        fun result(file: File): DocxSavedResult =
            DocxSavedResult(
                file = file,
                catalogEntry =
                    PdfFile(
                        name = file.name,
                        filePath = file.path,
                        size = "4 kB",
                        date = "01/01 00:00",
                        pageCount = 2,
                        lastModified = 1L,
                    ),
                sizeBytes = 4_096L,
            )
    }
}
