package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DocxToPdfUiStateTest {
    @Test
    fun `initial state is ready for an explicit provider selection`() {
        val state = DocxToPdfUiState()

        assertEquals(DocxToPdfPhase.PICK, state.phase)
        assertEquals(DocxToPdfBackAction.NAVIGATE_BACK, state.backAction)
        assertNull(state.input)
        assertNull(state.result)
        assertNull(state.errorMessage)
        assertEquals(0, state.progress)
        assertEquals("", state.progressText)
        assertNull(state.beginConversion())
    }

    @Test
    fun `selection transitions through preparation and replaces the input`() {
        val state = DocxToPdfUiState()
        val generation = state.beginSelection()

        assertEquals(DocxToPdfPhase.PREPARING, state.phase)
        assertEquals(DocxToPdfBackAction.CANCEL_OPERATION, state.backAction)
        assertTrue(state.selectionSucceeded(generation, input("quarterly", 2_048L)))
        assertEquals(DocxToPdfPhase.READY, state.phase)
        assertEquals("quarterly", state.input?.displayName)
        assertEquals(2_048L, state.input?.sizeBytes)
    }

    @Test
    fun `stale selection callbacks cannot replace newer state`() {
        val state = DocxToPdfUiState()
        val oldGeneration = state.beginSelection()
        val currentGeneration = state.beginSelection()

        assertFalse(state.selectionSucceeded(oldGeneration, input("old")))
        assertFalse(state.selectionFailed(oldGeneration, "old error"))
        assertTrue(state.selectionSucceeded(currentGeneration, input("new")))
        assertEquals("new", state.input?.displayName)
        assertNull(state.errorMessage)
    }

    @Test
    fun `selection failure uses safe default and returns to pick`() {
        val state = DocxToPdfUiState()
        val generation = state.beginSelection()

        assertTrue(state.selectionFailed(generation, ""))
        assertEquals(DocxToPdfPhase.PICK, state.phase)
        assertEquals("The Word document could not be selected safely.", state.errorMessage)
        state.dismissError()
        assertNull(state.errorMessage)
    }

    @Test
    fun `failed replacement preserves a ready input`() {
        val state = readyState("original")
        val generation = state.beginSelection()

        assertTrue(state.selectionFailed(generation, "Provider unavailable"))
        assertEquals(DocxToPdfPhase.READY, state.phase)
        assertEquals("original", state.input?.displayName)
        assertEquals("Provider unavailable", state.errorMessage)
    }

    @Test
    fun `failed replacement preserves a completed result`() {
        val state = completedState()
        val completed = state.result
        val generation = state.beginSelection()

        assertTrue(state.selectionFailed(generation, "Provider unavailable"))
        assertEquals(DocxToPdfPhase.DONE, state.phase)
        assertSame(completed, state.result)
    }

    @Test
    fun `conversion request is single start and snapshots selected metadata`() {
        val state = readyState("contract")

        val request = state.beginConversion()

        assertEquals(DocxToPdfPhase.CONVERTING, state.phase)
        assertEquals(DocxToPdfBackAction.CANCEL_OPERATION, state.backAction)
        assertEquals("contract", request?.input?.displayName)
        assertNull(state.beginConversion())
    }

    @Test
    fun `progress is clamped sanitized and limited to the active generation`() {
        val state = readyState()
        val request = requireNotNull(state.beginConversion())

        assertFalse(state.reportProgress(request.generation - 1L, 60, "stale"))
        assertEquals(0, state.progress)
        assertTrue(state.reportProgress(request.generation, Int.MIN_VALUE, "\u0000"))
        assertEquals(0, state.progress)
        assertEquals("Converting document…", state.progressText)
        assertTrue(state.reportProgress(request.generation, Int.MAX_VALUE, "Rendering"))
        assertEquals(100, state.progress)
        assertEquals("Rendering", state.progressText)
    }

    @Test
    fun `cancelling preparation restores its fallback and rejects late callbacks`() {
        val empty = DocxToPdfUiState()
        val emptyGeneration = empty.beginSelection()
        assertTrue(empty.cancelActive())
        assertEquals(DocxToPdfPhase.PICK, empty.phase)
        assertFalse(empty.selectionSucceeded(emptyGeneration, input("late")))

        val ready = readyState("retained")
        val replacementGeneration = ready.beginSelection()
        assertTrue(ready.cancelActive())
        assertEquals(DocxToPdfPhase.READY, ready.phase)
        assertEquals("retained", ready.input?.displayName)
        assertFalse(ready.selectionFailed(replacementGeneration, "late"))
        assertFalse(ready.cancelActive())
    }

    @Test
    fun `cancelling conversion restores ready and invalidates progress and result`() {
        val state = readyState()
        val request = requireNotNull(state.beginConversion())
        state.reportProgress(request.generation, 55, "Rendering")

        assertTrue(state.cancelActive())
        assertEquals(DocxToPdfPhase.READY, state.phase)
        assertEquals(0, state.progress)
        assertEquals("", state.progressText)
        assertFalse(state.reportProgress(request.generation, 90, "Late"))
        assertFalse(state.conversionSucceeded(request.generation, result("late")))
    }

    @Test
    fun `conversion failure remains retryable and rejects stale completion`() {
        val state = readyState()
        val request = requireNotNull(state.beginConversion())

        assertTrue(state.conversionFailed(request.generation, "Storage unavailable"))
        assertEquals(DocxToPdfPhase.READY, state.phase)
        assertEquals("Storage unavailable", state.errorMessage)
        assertFalse(state.conversionSucceeded(request.generation, result("late")))
        assertFalse(state.conversionFailed(request.generation, "late"))
    }

    @Test
    fun `successful conversion reaches done with immutable verified metadata`() {
        val state = readyState()
        val request = requireNotNull(state.beginConversion())
        val output = result("converted", 5_000L)

        assertTrue(state.conversionSucceeded(request.generation, output))
        assertEquals(DocxToPdfPhase.DONE, state.phase)
        assertEquals(DocxToPdfBackAction.NAVIGATE_BACK, state.backAction)
        assertEquals(100, state.progress)
        assertEquals("Done", state.progressText)
        assertSame(output, state.result)
    }

    @Test
    fun `share failure keeps the completed PDF and is retryable`() {
        val state = completedState()
        val completed = requireNotNull(state.result)

        assertTrue(state.shareFailed(completed, ""))
        assertEquals(DocxToPdfPhase.DONE, state.phase)
        assertSame(completed, state.result)
        assertEquals("The saved PDF could not be shared.", state.errorMessage)
        state.dismissError()
        assertNull(state.errorMessage)
        assertTrue(state.shareFailed(completed, "Try another app"))
        assertEquals("Try another app", state.errorMessage)
        assertTrue(state.shareSucceeded(completed))
        assertNull(state.errorMessage)
    }

    @Test
    fun `share failure from an obsolete result cannot affect a replacement`() {
        val state = completedState("first")
        val obsolete = requireNotNull(state.result)
        val generation = state.beginSelection()
        state.selectionSucceeded(generation, input("second"))
        val request = requireNotNull(state.beginConversion())
        val current = result("second")
        state.conversionSucceeded(request.generation, current)

        assertFalse(state.shareFailed(obsolete, "stale"))
        assertFalse(state.shareSucceeded(obsolete))
        assertSame(current, state.result)
        assertNull(state.errorMessage)
    }

    @Test
    fun `reset invalidates in-flight work and clears all presentation state`() {
        val state = readyState()
        val request = requireNotNull(state.beginConversion())
        state.reportProgress(request.generation, 60, "Rendering")

        state.reset()

        assertEquals(DocxToPdfPhase.PICK, state.phase)
        assertNull(state.input)
        assertNull(state.result)
        assertEquals(0, state.progress)
        assertEquals("", state.progressText)
        assertFalse(state.conversionSucceeded(request.generation, result("late")))
    }

    @Test
    fun `state rejects impossible input and output metadata`() {
        val state = DocxToPdfUiState()
        val generation = state.beginSelection()
        assertThrows(IllegalArgumentException::class.java) {
            state.selectionSucceeded(generation, input("", 10L))
        }

        val validState = readyState()
        val request = requireNotNull(validState.beginConversion())
        assertThrows(IllegalArgumentException::class.java) {
            validState.conversionSucceeded(request.generation, result("empty", 0L))
        }
    }

    private fun readyState(name: String = "report"): DocxToPdfUiState =
        DocxToPdfUiState().also { state ->
            val generation = state.beginSelection()
            check(state.selectionSucceeded(generation, input(name)))
        }

    private fun completedState(name: String = "report"): DocxToPdfUiState =
        readyState(name).also { state ->
            val request = requireNotNull(state.beginConversion())
            check(state.conversionSucceeded(request.generation, result(name)))
        }

    private fun input(
        name: String,
        sizeBytes: Long = 4_096L,
    ): DocxInputMetadata = DocxInputMetadata(name, sizeBytes)

    private fun result(
        name: String,
        sizeBytes: Long = 4_096L,
    ): DocxSavedResult {
        val file = File("$name.pdf")
        return DocxSavedResult(
            file = file,
            catalogEntry =
                PdfFile(
                    name = file.name,
                    filePath = file.path,
                    size = "$sizeBytes B",
                    date = "01/01 00:00",
                    pageCount = 2,
                    lastModified = 1L,
                ),
            sizeBytes = sizeBytes,
        )
    }
}
