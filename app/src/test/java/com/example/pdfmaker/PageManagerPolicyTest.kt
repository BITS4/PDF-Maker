package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class PageManagerPolicyTest {
    @Test
    fun activeOperationsOwnBackUntilCancellationCompletes() {
        PageManagerPhase.entries.forEach { phase ->
            val expected =
                if (phase == PageManagerPhase.PREPARING || phase == PageManagerPhase.SAVING) {
                    PageManagerBackAction.CANCEL_OPERATION
                } else {
                    PageManagerBackAction.NAVIGATE_BACK
                }
            assertEquals(expected, PageManagerPolicy.backAction(phase))
        }
    }

    @Test
    fun displayNamesAreBoundedSanitizedAndProviderAware() {
        assertEquals("report", PageManagerPolicy.displayBaseName("primary%2Freport.PDF"))
        assertEquals("report", PageManagerPolicy.displayBaseName("/folder/report.pdf"))
        assertEquals("document", PageManagerPolicy.displayBaseName(null))
        assertEquals("document", PageManagerPolicy.displayBaseName("\u202E\u0000.pdf"))
        assertEquals(
            PageManagerPolicy.MAX_DISPLAY_NAME_LENGTH,
            PageManagerPolicy.displayBaseName("a".repeat(200) + ".pdf").length,
        )
    }

    @Test
    fun generationWrapsWithoutReusingTheInvalidZeroToken() {
        assertEquals(1L, PageManagerPolicy.nextGeneration(0L))
        assertEquals(1L, PageManagerPolicy.nextGeneration(Long.MAX_VALUE))
    }

    @Test
    fun supersededSelectionCannotReplaceTheCurrentRequest() {
        val state = PageManagerOperationState()
        val first = state.beginSelection()
        val second = state.beginSelection()

        assertFalse(state.selectionSucceeded(first))
        assertTrue(state.selectionSucceeded(second))
        assertEquals(PageManagerPhase.EDIT, state.phase)
        assertTrue(state.hasSelection)
    }

    @Test
    fun selectionCancellationReturnsToTheAtomicFallback() {
        val state = PageManagerOperationState()
        state.beginSelection()

        assertTrue(state.cancelActive())
        assertEquals(PageManagerPhase.PICK, state.phase)
        assertFalse(state.hasSelection)

        val accepted = state.beginSelection()
        assertTrue(state.selectionSucceeded(accepted))
        state.beginSelection()

        assertTrue(state.cancelActive())
        assertEquals(PageManagerPhase.EDIT, state.phase)
        assertTrue(state.hasSelection)
    }

    @Test
    fun failedReplacementRetainsAnAcceptedSelection() {
        val state = PageManagerOperationState()
        val first = state.beginSelection()
        assertTrue(state.selectionSucceeded(first))
        val replacement = state.beginSelection()

        assertTrue(state.selectionFailed(replacement))
        assertEquals(PageManagerPhase.EDIT, state.phase)
        assertTrue(state.hasSelection)
    }

    @Test
    fun saveRequiresSelectionEditablePhaseAndRetainedPages() {
        val state = PageManagerOperationState()
        assertEquals(null, state.beginSave(canSave = true))
        val selection = state.beginSelection()
        assertEquals(null, state.beginSave(canSave = true))
        assertTrue(state.selectionSucceeded(selection))
        assertEquals(null, state.beginSave(canSave = false))

        val save = requireNotNull(state.beginSave(canSave = true))
        assertEquals(PageManagerPhase.SAVING, state.phase)
        assertFalse(state.saveSucceeded(save - 1L))
        assertTrue(state.saveSucceeded(save))
        assertEquals(PageManagerPhase.DONE, state.phase)
    }

    @Test
    fun canceledAndFailedSavesReturnToTheEditorAndRejectStaleCompletion() {
        val state = acceptedState()
        val canceledSave = requireNotNull(state.beginSave(canSave = true))

        assertTrue(state.cancelActive())
        assertEquals(PageManagerPhase.EDIT, state.phase)
        assertFalse(state.saveSucceeded(canceledSave))

        val failedSave = requireNotNull(state.beginSave(canSave = true))
        assertTrue(state.saveFailed(failedSave))
        assertEquals(PageManagerPhase.EDIT, state.phase)
        assertFalse(state.saveFailed(failedSave))
    }

    @Test
    fun editAgainAndInvalidationResetLifecycleWithoutForgedTransitions() {
        val state = acceptedState()
        val save = requireNotNull(state.beginSave(canSave = true))
        assertTrue(state.saveSucceeded(save))
        assertTrue(state.editAgain())
        assertFalse(state.editAgain())

        state.invalidate()

        assertEquals(PageManagerPhase.PICK, state.phase)
        assertFalse(state.hasSelection)
        assertFalse(state.cancelActive())
    }

    @Test
    fun failureMessagesAreStageSpecificAndDoNotLeakDetails() {
        val secret = "content://provider/private/customer.pdf"
        PageManagerFailureStage.entries.forEach { stage ->
            val message = PageManagerPolicy.failureMessage(stage, IOException(secret))

            assertFalse(message.contains(secret))
            assertTrue(message.endsWith('.'))
        }
    }

    private fun acceptedState(): PageManagerOperationState =
        PageManagerOperationState().also { state ->
            assertTrue(state.selectionSucceeded(state.beginSelection()))
        }
}
