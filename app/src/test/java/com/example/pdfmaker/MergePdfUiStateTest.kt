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
        val state = MergePdfUiState()
        val result = result("customer-private-record.pdf")
        state.mergeSucceeded(result.first, result.second)

        assertTrue(state.reportShareResult(launched = false))

        assertEquals(MergeState.DONE, state.phase)
        assertSame(result.first, state.resultFile)
        assertSame(result.second, state.resultPdfFile)
        assertTrue(state.shareMessage.orEmpty().contains("Tap Share to try again"))
        assertFalse(state.shareMessage.orEmpty().contains("customer-private-record"))
        assertTrue(state.errorMessage.isEmpty())
    }

    @Test
    fun `successful retry clears share failure without discarding result`() {
        val state = completedState()
        assertTrue(state.reportShareResult(launched = false))

        assertTrue(state.reportShareResult(launched = true))

        assertEquals(MergeState.DONE, state.phase)
        assertNull(state.shareMessage)
        assertTrue(state.resultFile != null)
        assertTrue(state.resultPdfFile != null)
    }

    @Test
    fun `share callbacks are ignored without a completed result`() {
        val state = MergePdfUiState()

        assertFalse(state.reportShareResult(launched = false))
        assertEquals(MergeState.EMPTY, state.phase)
        assertNull(state.shareMessage)

        val (file, pdfFile) = result("merged.pdf")
        state.mergeSucceeded(file, pdfFile)
        state.reset()
        assertFalse(state.reportShareResult(launched = false))
        assertEquals(MergeState.EMPTY, state.phase)
        assertNull(state.shareMessage)
    }

    @Test
    fun `merge and terminal error transitions clear stale share messages`() {
        val state = completedState()
        state.reportShareResult(launched = false)

        val replacement = result("replacement.pdf")
        state.mergeSucceeded(replacement.first, replacement.second)
        assertNull(state.shareMessage)

        state.reportShareResult(launched = false)
        state.reject("merge failed")
        assertEquals(MergeState.ERROR, state.phase)
        assertNull(state.shareMessage)
        assertEquals("merge failed", state.errorMessage)
    }

    private fun completedState(): MergePdfUiState =
        MergePdfUiState().also { state ->
            val result = result("merged.pdf")
            state.mergeSucceeded(result.first, result.second)
        }

    private fun result(name: String): Pair<File, PdfFile> {
        val file = File(name)
        return file to
            PdfFile(
                name = name,
                filePath = file.path,
                size = "1 KB",
                date = "2026-09-02",
                pageCount = 2,
                lastModified = 1L,
            )
    }
}
