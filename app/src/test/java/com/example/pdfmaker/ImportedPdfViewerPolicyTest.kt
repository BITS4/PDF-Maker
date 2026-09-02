package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.IOException

class ImportedPdfViewerPolicyTest {
    @Test
    fun activeOperationAlwaysOwnsBackNavigation() {
        PdfEditMode.entries.forEach { mode ->
            assertEquals(
                ImportedViewerBackAction.CANCEL_OPERATION,
                ImportedPdfViewerPolicy.backAction(mode, showConvert = true, ConvertTarget.WORD),
            )
        }
    }

    @Test
    fun eachEditorModeMapsToItsSafeExitAction() {
        val expectations =
            mapOf(
                PdfEditMode.DOODLE to ImportedViewerBackAction.DISCARD_DOODLE,
                PdfEditMode.TEXT to ImportedViewerBackAction.DISCARD_OVERLAYS,
                PdfEditMode.SIGNATURE to ImportedViewerBackAction.CLOSE_SIGNATURE_PAD,
                PdfEditMode.EDIT_PICKER to ImportedViewerBackAction.CLOSE_EDITOR,
            )

        expectations.forEach { (mode, action) ->
            assertEquals(action, ImportedPdfViewerPolicy.backAction(mode, false, ConvertTarget.NONE))
        }
    }

    @Test
    fun editorExitPrecedesClosingTheConvertTray() {
        assertEquals(
            ImportedViewerBackAction.DISCARD_DOODLE,
            ImportedPdfViewerPolicy.backAction(PdfEditMode.DOODLE, true, ConvertTarget.NONE),
        )
    }

    @Test
    fun convertTrayClosesBeforeLeavingTheViewer() {
        assertEquals(
            ImportedViewerBackAction.CLOSE_CONVERT,
            ImportedPdfViewerPolicy.backAction(PdfEditMode.NONE, true, ConvertTarget.NONE),
        )
        assertEquals(
            ImportedViewerBackAction.NAVIGATE_BACK,
            ImportedPdfViewerPolicy.backAction(PdfEditMode.NONE, false, ConvertTarget.NONE),
        )
    }

    @Test
    fun titleUsesPlainAndEncodedLeafNamesCaseInsensitively() {
        assertEquals("report", ImportedPdfViewerPolicy.documentTitle("folder/report.PDF"))
        assertEquals("invoice", ImportedPdfViewerPolicy.documentTitle("folder%2Finvoice.pdf"))
        assertEquals("scan", ImportedPdfViewerPolicy.documentTitle("folder%2fscan.PdF"))
    }

    @Test
    fun titleFallsBackForMissingOrUnsafeNames() {
        assertEquals("Document", ImportedPdfViewerPolicy.documentTitle(null))
        assertEquals("Document", ImportedPdfViewerPolicy.documentTitle("\u0000\n.pdf"))
    }

    @Test
    fun titleIsBoundedAndControlCharactersAreRemoved() {
        val title = ImportedPdfViewerPolicy.documentTitle("ab\u0000${"c".repeat(200)}.pdf")

        assertFalse(title.any(Char::isISOControl))
        assertEquals(ImportedPdfViewerPolicy.MAX_TITLE_LENGTH, title.length)
    }

    @Test
    fun loadFailuresAreActionableWithoutLeakingExceptionDetails() {
        val secret = "private-provider-path"
        val failures =
            listOf(
                SecurityException(secret),
                IOException(secret),
                IllegalArgumentException(secret),
                IllegalStateException(secret),
                RuntimeException(secret),
            )

        failures.forEach { error ->
            assertFalse(ImportedPdfViewerPolicy.loadFailureMessage(error).contains(secret))
        }
    }
}
