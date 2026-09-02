package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MergePdfUiStateTest {
    @Test
    fun `share launch failure preserves completed result for retry`() {
        val completed = completedState("customer-private-record.pdf")
        val state = completed.state

        assertTrue(state.reportShareResult(launched = false))

        assertEquals(MergeState.DONE, state.phase)
        assertSame(completed.result.file, state.resultFile)
        assertSame(completed.result.catalogEntry, state.resultPdfFile)
        assertTrue(state.shareMessage.orEmpty().contains("Tap Share to try again"))
        assertFalse(state.shareMessage.orEmpty().contains("customer-private-record"))
        assertTrue(state.errorMessage.isEmpty())
    }

    @Test
    fun `successful share retry clears failure without discarding result`() {
        val completed = completedState()
        val state = completed.state
        assertTrue(state.reportShareResult(launched = false))

        assertTrue(state.reportShareResult(launched = true))

        assertEquals(MergeState.DONE, state.phase)
        assertNull(state.shareMessage)
        assertSame(completed.result.file, state.resultFile)
        assertSame(completed.result.catalogEntry, state.resultPdfFile)
    }

    @Test
    fun `share callbacks are ignored without an owned completed result`() {
        val state = MergePdfUiState()

        assertFalse(state.reportShareResult(launched = false))
        assertEquals(MergeState.EMPTY, state.phase)
        assertNull(state.shareMessage)

        val completed = completedState()
        completed.state.reset()
        assertFalse(completed.state.reportShareResult(launched = false))
        assertEquals(MergeState.EMPTY, completed.state.phase)
        assertNull(completed.state.shareMessage)
    }

    @Test
    fun `only current generation can report progress or complete`() {
        val running = runningState()
        val state = running.state
        val result = result("accepted.pdf")

        assertFalse(state.reportProgress(running.generation - 1L, 73, "Stale"))
        assertEquals(0, state.progress)
        assertTrue(state.reportProgress(running.generation, Int.MAX_VALUE, "Saving"))
        assertEquals(100, state.progress)
        assertEquals("Saving", state.progressText)
        assertFalse(
            state.mergeSucceeded(
                running.generation - 1L,
                result.file,
                result.catalogEntry,
            ),
        )
        assertTrue(
            state.mergeSucceeded(
                running.generation,
                result.file,
                result.catalogEntry,
            ),
        )

        assertEquals(MergeState.DONE, state.phase)
        assertSame(result.file, state.resultFile)
        assertSame(result.catalogEntry, state.resultPdfFile)
        assertFalse(state.mergeFailed(running.generation, "late failure"))
        assertEquals(MergeState.DONE, state.phase)
        assertTrue(state.errorMessage.isEmpty())
    }

    @Test
    fun `current failure is redacted by caller and stale failure is ignored`() {
        val running = runningState()
        val state = running.state

        assertFalse(state.mergeFailed(running.generation + 1L, "stale"))
        assertEquals(MergeState.MERGING, state.phase)
        assertTrue(state.mergeFailed(running.generation, "merge failed"))

        assertEquals(MergeState.ERROR, state.phase)
        assertEquals("merge failed", state.errorMessage)
        assertEquals(0, state.progress)
        assertNull(state.resultFile)
        assertNull(state.resultPdfFile)
        assertFalse(state.mergeFailed(running.generation, "late"))
        assertEquals("merge failed", state.errorMessage)
    }

    @Test
    fun `cancellation invalidates all late callbacks and clears transient state`() {
        val running = runningState()
        val state = running.state
        val result = result("late.pdf")
        assertTrue(state.reportProgress(running.generation, 64, "Rendering"))

        assertTrue(state.cancelActiveMerge())

        assertEquals(MergeState.EMPTY, state.phase)
        assertEquals(0, state.progress)
        assertTrue(state.progressText.isEmpty())
        assertFalse(state.reportProgress(running.generation, 90, "Late"))
        assertFalse(
            state.mergeSucceeded(
                running.generation,
                result.file,
                result.catalogEntry,
            ),
        )
        assertFalse(state.mergeFailed(running.generation, "Late failure"))
        assertNull(state.resultFile)
        assertNull(state.resultPdfFile)
        assertTrue(state.errorMessage.isEmpty())
    }

    @Test
    fun `reset releases accepted result and ignores later share callbacks`() {
        val completed = completedState()
        val state = completed.state
        assertTrue(state.reportShareResult(launched = false))

        state.reset()

        assertEquals(MergeState.EMPTY, state.phase)
        assertNull(state.resultFile)
        assertNull(state.resultPdfFile)
        assertNull(state.shareMessage)
        assertFalse(state.reportShareResult(launched = true))
    }

    private fun runningState(): RunningState {
        val operation = MergeOperationState()
        val generation = requireNotNull(operation.begin())
        return RunningState(MergePdfUiState(operation), generation)
    }

    private fun completedState(name: String = "merged.pdf"): CompletedState {
        val operation = MergeOperationState()
        val generation = requireNotNull(operation.begin())
        val result = result(name)
        check(operation.complete(generation, result))
        return CompletedState(MergePdfUiState(operation), result)
    }

    private fun result(name: String): MergeOwnedResult {
        val file = File(name)
        return MergeOwnedResult(
            file = file,
            catalogEntry =
                PdfFile(
                    name = name,
                    filePath = file.path,
                    size = "1 KB",
                    date = "2026-09-02",
                    pageCount = 2,
                    lastModified = 1L,
                ),
        )
    }

    private data class RunningState(
        val state: MergePdfUiState,
        val generation: Long,
    )

    private data class CompletedState(
        val state: MergePdfUiState,
        val result: MergeOwnedResult,
    )
}
