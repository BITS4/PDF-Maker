package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerFilePolicyTest {
    @Test
    fun `detects supported extensions case insensitively`() {
        assertEquals(ViewerFileKind.PDF, detectViewerFileKind("/docs/report.PDF"))
        assertEquals(ViewerFileKind.DOCX, detectViewerFileKind("/docs/report.doc"))
        assertEquals(ViewerFileKind.XLSX, detectViewerFileKind("/docs/report.xlsx"))
        assertEquals(ViewerFileKind.PPTX, detectViewerFileKind("/docs/slides.pptx"))
        assertEquals(ViewerFileKind.CSV, detectViewerFileKind("/docs/data.tsv"))
        assertEquals(ViewerFileKind.TXT, detectViewerFileKind("/docs/notes.md"))
        assertEquals(ViewerFileKind.IMAGE, detectViewerFileKind("/docs/photo.webp"))
    }

    @Test
    fun `falls back to the display name and ignores url suffixes`() {
        assertEquals(ViewerFileKind.PDF, detectViewerFileKind("/cache/document", "invoice.pdf"))
        assertEquals(ViewerFileKind.IMAGE, detectViewerFileKind("photo.PNG?download=1"))
        assertEquals(ViewerFileKind.UNSUPPORTED, detectViewerFileKind("archive.zip"))
    }

    @Test
    fun `routes office files externally and previewable files internally`() {
        assertTrue(shouldOpenExternally(ViewerFileKind.DOCX))
        assertTrue(shouldOpenExternally(ViewerFileKind.XLSX))
        assertTrue(shouldOpenExternally(ViewerFileKind.PPTX))
        assertFalse(shouldOpenExternally(ViewerFileKind.PDF))
        assertTrue(canRenderInApp(ViewerFileKind.PDF))
        assertFalse(canRenderInApp(ViewerFileKind.DOCX))
        assertFalse(canRenderInApp(ViewerFileKind.UNSUPPORTED))
        val mimeTypes =
            mapOf(
                ViewerFileKind.PDF to "application/pdf",
                ViewerFileKind.DOCX to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                ViewerFileKind.XLSX to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                ViewerFileKind.PPTX to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                ViewerFileKind.CSV to "text/csv",
                ViewerFileKind.TXT to "text/plain",
                ViewerFileKind.IMAGE to "image/*",
                ViewerFileKind.UNSUPPORTED to "*/*",
            )
        mimeTypes.forEach { (kind, expected) -> assertEquals(expected, viewerMimeType(kind)) }
    }

    @Test
    fun `calculates a safe high density render target`() {
        assertEquals(1_080, viewerTargetWidth(320, 1f))
        assertEquals(2_160, viewerTargetWidth(360, 3f))
        assertEquals(1_080, viewerTargetWidth(0, Float.NaN))
    }
}
