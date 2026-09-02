package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CompressUiStateTest {
    @Test
    fun `selection moves from pick through preparing to ready`() {
        val state = CompressUiState()

        assertEquals(CompressionPhase.PICK, state.phase)
        assertEquals(CompressionBackAction.NAVIGATE_BACK, state.backAction)
        val generation = state.beginSelection()
        assertEquals(CompressionPhase.PREPARING, state.phase)
        assertEquals(CompressionBackAction.CANCEL_OPERATION, state.backAction)

        assertTrue(state.selectionSucceeded(generation, input("report", 4_096L)))
        assertEquals(CompressionPhase.READY, state.phase)
        assertEquals("report", state.input?.displayName)
        assertNull(state.errorMessage)
    }

    @Test
    fun `newer selections reject stale success and failure callbacks`() {
        val state = CompressUiState()
        val staleGeneration = state.beginSelection()
        val currentGeneration = state.beginSelection()

        assertFalse(state.selectionSucceeded(staleGeneration, input("stale", 1L)))
        assertFalse(state.selectionFailed(staleGeneration, "stale failure"))
        assertTrue(state.selectionSucceeded(currentGeneration, input("current", 2L)))
        assertEquals("current", state.input?.displayName)
    }

    @Test
    fun `failed replacement restores the prior ready selection`() {
        val state = readyState()
        val previousInput = state.input
        val generation = state.beginSelection()

        assertTrue(state.selectionFailed(generation, "provider unavailable"))
        assertEquals(CompressionPhase.READY, state.phase)
        assertSame(previousInput, state.input)
        assertEquals("provider unavailable", state.errorMessage)
    }

    @Test
    fun `failed initial selection returns to pick with a safe fallback message`() {
        val state = CompressUiState()
        val generation = state.beginSelection()

        assertTrue(state.selectionFailed(generation, ""))
        assertEquals(CompressionPhase.PICK, state.phase)
        assertTrue(state.errorMessage.orEmpty().isNotBlank())
    }

    @Test
    fun `compression level changes only while ready`() {
        val state = CompressUiState()
        state.selectLevel(CompressLevel.HIGH)
        assertEquals(CompressLevel.MEDIUM, state.level)

        prepare(state)
        state.selectLevel(CompressLevel.HIGH)
        assertEquals(CompressLevel.HIGH, state.level)

        state.beginCompression()
        state.selectLevel(CompressLevel.LOW)
        assertEquals(CompressLevel.HIGH, state.level)
    }

    @Test
    fun `compression request is single start and snapshots input and level`() {
        val state = readyState()
        state.selectLevel(CompressLevel.LOW)

        val request = state.beginCompression()

        assertEquals(CompressionPhase.COMPRESSING, state.phase)
        assertEquals(CompressionBackAction.CANCEL_OPERATION, state.backAction)
        assertEquals(CompressLevel.LOW, request?.level)
        assertSame(state.input, request?.input)
        assertNull(state.beginCompression())
    }

    @Test
    fun `progress accepts only the current compression generation and clamps values`() {
        val state = readyState()
        val request = requireNotNull(state.beginCompression())

        assertFalse(state.reportProgress(request.generation - 1L, 50))
        assertEquals(0, state.progress)
        assertTrue(state.reportProgress(request.generation, Int.MIN_VALUE))
        assertEquals(0, state.progress)
        assertTrue(state.reportProgress(request.generation, 61))
        assertEquals(61, state.progress)
        assertTrue(state.reportProgress(request.generation, Int.MAX_VALUE))
        assertEquals(100, state.progress)
    }

    @Test
    fun `cancel returns preparation to its fallback and compression to ready`() {
        val empty = CompressUiState()
        empty.beginSelection()
        assertTrue(empty.cancelActive())
        assertEquals(CompressionPhase.PICK, empty.phase)
        assertFalse(empty.cancelActive())

        val ready = readyState()
        ready.beginSelection()
        assertTrue(ready.cancelActive())
        assertEquals(CompressionPhase.READY, ready.phase)
        ready.beginCompression()
        assertTrue(ready.cancelActive())
        assertEquals(CompressionPhase.READY, ready.phase)
        assertEquals(0, ready.progress)
    }

    @Test
    fun `compression failure preserves input and rejects later stale callbacks`() {
        val state = readyState()
        val request = requireNotNull(state.beginCompression())

        assertTrue(state.compressionFailed(request.generation, "storage unavailable"))
        assertEquals(CompressionPhase.READY, state.phase)
        assertEquals("report", state.input?.displayName)
        assertEquals("storage unavailable", state.errorMessage)
        assertFalse(state.reportProgress(request.generation, 90))
        assertFalse(state.compressionSucceeded(request.generation, result(500L)))
    }

    @Test
    fun `successful result reaches done and share failure keeps it available`() {
        val state = readyState(sizeBytes = 1_000L)
        val request = requireNotNull(state.beginCompression())
        val result = result(600L)

        assertTrue(state.compressionSucceeded(request.generation, result))
        assertEquals(CompressionPhase.DONE, state.phase)
        assertEquals(100, state.progress)
        assertSame(result, state.result)
        assertEquals(40, state.savedPercent)

        assertTrue(state.shareFailed("No share target"))
        assertEquals(CompressionPhase.DONE, state.phase)
        assertSame(result, state.result)
        assertEquals("No share target", state.errorMessage)
        state.dismissError()
        assertNull(state.errorMessage)
    }

    @Test
    fun `share failure is ignored until a completed result exists`() {
        val state = readyState()

        assertFalse(state.shareFailed("ignored"))
        assertNull(state.errorMessage)
    }

    @Test
    fun `reset invalidates in-flight completion while preserving preference`() {
        val state = readyState()
        state.selectLevel(CompressLevel.HIGH)
        val request = requireNotNull(state.beginCompression())

        state.reset()

        assertEquals(CompressionPhase.PICK, state.phase)
        assertEquals(CompressLevel.HIGH, state.level)
        assertNull(state.input)
        assertNull(state.result)
        assertFalse(state.compressionSucceeded(request.generation, result(10L)))
    }

    private fun readyState(sizeBytes: Long = 4_096L): CompressUiState = CompressUiState().also { state -> prepare(state, sizeBytes) }

    private fun prepare(
        state: CompressUiState,
        sizeBytes: Long = 4_096L,
    ) {
        val generation = state.beginSelection()
        check(state.selectionSucceeded(generation, input("report", sizeBytes)))
    }

    private fun input(
        name: String,
        sizeBytes: Long,
    ): CompressionInput = CompressionInput(name, sizeBytes)

    private fun result(sizeBytes: Long): CompressionResult {
        val file = File("compressed_report.pdf")
        return CompressionResult(
            file = file,
            catalogEntry =
                PdfFile(
                    name = file.name,
                    filePath = file.path,
                    size = "$sizeBytes B",
                    date = "01/01 00:00",
                    pageCount = 1,
                    lastModified = 1L,
                ),
            sizeBytes = sizeBytes,
        )
    }
}
