package com.example.pdfmaker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MergeOperationStateTest {
    @Test
    fun `begin is single flight until the active generation terminates`() {
        val state = MergeOperationState()

        val first = requireNotNull(state.begin())

        assertTrue(state.isActive)
        assertTrue(state.accepts(first))
        assertNull(state.begin())
        assertFalse(state.accepts(first + 1L))
        assertTrue(state.fail(first))
        assertFalse(state.isActive)
        assertTrue(requireNotNull(state.begin()) > first)
    }

    @Test
    fun `stale terminal callbacks cannot claim or replace a result`() {
        val state = MergeOperationState()
        val generation = requireNotNull(state.begin())
        val staleResult = result("stale.pdf")
        val acceptedResult = result("accepted.pdf")
        val replacement = result("replacement.pdf")

        assertFalse(state.complete(generation + 1L, staleResult))
        assertNull(state.result)
        assertTrue(state.complete(generation, acceptedResult))
        assertSame(acceptedResult, state.result)
        assertFalse(state.complete(generation, replacement))
        assertFalse(state.fail(generation))
        assertSame(acceptedResult, state.result)
        assertNull(state.begin())
    }

    @Test
    fun `cancellation invalidates callbacks and advances the next generation`() {
        val state = MergeOperationState()
        val cancelledGeneration = requireNotNull(state.begin())

        assertTrue(state.cancel())
        assertFalse(state.cancel())
        assertFalse(state.accepts(cancelledGeneration))
        assertFalse(state.fail(cancelledGeneration))
        assertFalse(state.complete(cancelledGeneration, result("late.pdf")))

        val nextGeneration = requireNotNull(state.begin())
        assertTrue(nextGeneration > cancelledGeneration)
        assertTrue(state.accepts(nextGeneration))
    }

    @Test
    fun `clear invalidates work and releases completed result ownership`() {
        val active = MergeOperationState()
        val activeGeneration = requireNotNull(active.begin())
        active.clear()
        assertFalse(active.accepts(activeGeneration))
        assertNull(active.result)

        val completed = MergeOperationState()
        val completedGeneration = requireNotNull(completed.begin())
        assertTrue(completed.complete(completedGeneration, result("owned.pdf")))

        completed.clear()

        assertNull(completed.result)
        assertFalse(completed.isActive)
        assertTrue(requireNotNull(completed.begin()) > completedGeneration)
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
}
