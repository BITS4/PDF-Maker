package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNavigationPolicyTest {
    @Test
    fun `every screen has an explicit route group`() {
        val expected =
            mapOf(
                AppRouteGroup.CORE to
                    setOf(
                        Screen.HOME,
                        Screen.FILES,
                        Screen.VIEWER,
                        Screen.SETTINGS,
                        Screen.MORE_TOOLS,
                    ),
                AppRouteGroup.IMAGE to
                    setOf(
                        Screen.IMAGE_SELECTION,
                        Screen.IMAGE_EDIT,
                        Screen.IMAGE_CROP,
                        Screen.IMAGE_REVIEW,
                        Screen.CONVERT_RESULT,
                        Screen.SMART_SCAN,
                        Screen.ID_CARD_RESULT,
                    ),
                AppRouteGroup.DOCUMENT to
                    setOf(
                        Screen.COMPRESS,
                        Screen.PDF_TO_JPG,
                        Screen.MERGE_PDF,
                        Screen.DOCX_TO_PDF,
                        Screen.IMPORT_PDF,
                        Screen.IMPORTED_PDF_VIEWER,
                        Screen.SIGNATURE_PAD,
                        Screen.SPLIT_PDF,
                        Screen.PAGE_MANAGER,
                        Screen.LOCK_PDF,
                        Screen.UNLOCK_PDF,
                        Screen.OCR,
                        Screen.PRINT_PDF,
                        Screen.ONBOARDING,
                        Screen.CAMERA_DENIED,
                    ),
            )

        assertEquals(Screen.entries.toSet(), expected.values.flatten().toSet())
        Screen.entries.forEach { screen ->
            val expectedGroup = expected.entries.single { screen in it.value }.key
            assertEquals(expectedGroup, AppNavigationPolicy.routeGroup(screen))
        }
    }

    @Test
    fun `every screen has an explicit default system back destination`() {
        val expected =
            mapOf(
                Screen.HOME to null,
                Screen.FILES to Screen.HOME,
                Screen.VIEWER to Screen.HOME,
                Screen.SETTINGS to Screen.HOME,
                Screen.IMAGE_SELECTION to Screen.HOME,
                Screen.IMAGE_EDIT to Screen.IMAGE_SELECTION,
                Screen.IMAGE_CROP to Screen.IMAGE_EDIT,
                Screen.IMAGE_REVIEW to Screen.IMAGE_EDIT,
                Screen.CONVERT_RESULT to Screen.HOME,
                Screen.SMART_SCAN to Screen.HOME,
                Screen.ID_CARD_RESULT to Screen.HOME,
                Screen.COMPRESS to Screen.HOME,
                Screen.PDF_TO_JPG to Screen.HOME,
                Screen.MERGE_PDF to Screen.HOME,
                Screen.MORE_TOOLS to Screen.HOME,
                Screen.DOCX_TO_PDF to Screen.HOME,
                Screen.IMPORT_PDF to Screen.HOME,
                Screen.IMPORTED_PDF_VIEWER to Screen.IMPORT_PDF,
                Screen.SIGNATURE_PAD to Screen.IMPORTED_PDF_VIEWER,
                Screen.SPLIT_PDF to Screen.HOME,
                Screen.PAGE_MANAGER to Screen.HOME,
                Screen.LOCK_PDF to Screen.HOME,
                Screen.UNLOCK_PDF to Screen.HOME,
                Screen.OCR to Screen.HOME,
                Screen.PRINT_PDF to Screen.HOME,
                Screen.ONBOARDING to null,
                Screen.CAMERA_DENIED to Screen.HOME,
            )

        assertEquals(Screen.entries.toSet(), expected.keys)
        expected.forEach { (screen, destination) ->
            assertEquals(
                destination,
                AppNavigationPolicy.systemBack(screen, NavigationContext()).destination,
            )
        }
    }

    @Test
    fun `origin aware screens return to more tools exactly once`() {
        val screens =
            listOf(
                Screen.SMART_SCAN,
                Screen.PDF_TO_JPG,
                Screen.IMPORT_PDF,
                Screen.SPLIT_PDF,
                Screen.PAGE_MANAGER,
                Screen.LOCK_PDF,
                Screen.UNLOCK_PDF,
                Screen.OCR,
                Screen.PRINT_PDF,
                Screen.CAMERA_DENIED,
            )

        screens.forEach { screen ->
            val decision =
                AppNavigationPolicy.systemBack(
                    screen,
                    NavigationContext(fromMoreTools = true),
                )
            assertEquals(Screen.MORE_TOOLS, decision.destination)
            assertTrue(decision.consumeMoreToolsOrigin)
            assertFalse(decision.consumeFilesOrigin)
        }
    }

    @Test
    fun `viewer back respects files origin boundary`() {
        val fromFiles =
            AppNavigationPolicy.systemBack(
                Screen.VIEWER,
                NavigationContext(fromFiles = true),
            )
        val direct = AppNavigationPolicy.systemBack(Screen.VIEWER, NavigationContext())

        assertEquals(Screen.FILES, fromFiles.destination)
        assertTrue(fromFiles.consumeFilesOrigin)
        assertEquals(Screen.HOME, direct.destination)
        assertFalse(direct.consumeFilesOrigin)
    }

    @Test
    fun `image back policy honors add more then scan then selection priority`() {
        assertEquals(
            Screen.IMAGE_REVIEW,
            AppNavigationPolicy
                .systemBack(
                    Screen.IMAGE_EDIT,
                    NavigationContext(addingMoreImages = true, fromSmartScan = true),
                ).destination,
        )
        assertEquals(
            Screen.SMART_SCAN,
            AppNavigationPolicy
                .systemBack(
                    Screen.IMAGE_EDIT,
                    NavigationContext(fromSmartScan = true),
                ).destination,
        )
        assertEquals(
            Screen.IMAGE_SELECTION,
            AppNavigationPolicy.systemBack(Screen.IMAGE_EDIT, NavigationContext()).destination,
        )
    }

    @Test
    fun `image selection clears only when leaving the flow`() {
        val addingMore =
            AppNavigationPolicy.systemBack(
                Screen.IMAGE_SELECTION,
                NavigationContext(addingMoreImages = true, fromMoreTools = true),
            )
        val leaving =
            AppNavigationPolicy.systemBack(
                Screen.IMAGE_SELECTION,
                NavigationContext(fromMoreTools = true),
            )

        assertEquals(Screen.IMAGE_REVIEW, addingMore.destination)
        assertFalse(addingMore.clearImageState)
        assertFalse(addingMore.consumeMoreToolsOrigin)
        assertEquals(Screen.MORE_TOOLS, leaving.destination)
        assertTrue(leaving.clearImageState)
        assertTrue(leaving.consumeMoreToolsOrigin)
    }

    @Test
    fun `conversion result clears image state while inert screens do not`() {
        val result =
            AppNavigationPolicy.systemBack(
                Screen.CONVERT_RESULT,
                NavigationContext(),
            )

        assertEquals(Screen.HOME, result.destination)
        assertTrue(result.clearImageState)
        assertNull(AppNavigationPolicy.systemBack(Screen.HOME, NavigationContext()).destination)
        assertNull(AppNavigationPolicy.systemBack(Screen.ONBOARDING, NavigationContext()).destination)
    }

    @Test
    fun `home tools map to bounded launches and reject unknown keys`() {
        val expected =
            mapOf(
                "image_to_pdf" to Screen.IMAGE_SELECTION,
                "smart_scan" to Screen.SMART_SCAN,
                "import_pdf" to Screen.IMPORT_PDF,
                "compress" to Screen.COMPRESS,
                "pdf_to_jpg" to Screen.PDF_TO_JPG,
                "merge_pdf" to Screen.MERGE_PDF,
                "docx_to_pdf" to Screen.DOCX_TO_PDF,
                "more" to Screen.MORE_TOOLS,
                "split_pdf" to Screen.SPLIT_PDF,
                "page_manager" to Screen.PAGE_MANAGER,
                "lock_pdf" to Screen.LOCK_PDF,
                "unlock_pdf" to Screen.UNLOCK_PDF,
                "ocr" to Screen.OCR,
            )

        expected.forEach { (toolId, destination) ->
            assertEquals(destination, AppNavigationPolicy.homeTool(toolId)?.destination)
        }
        assertTrue(AppNavigationPolicy.homeTool("image_to_pdf")?.clearImageState == true)
        assertTrue(AppNavigationPolicy.homeTool("smart_scan")?.clearSmartScanState == true)
        assertNull(AppNavigationPolicy.homeTool(""))
        assertNull(AppNavigationPolicy.homeTool("unknown"))
    }

    @Test
    fun `more tools use typed pending editor modes`() {
        val expectations =
            mapOf(
                "doodle" to PendingPdfEditMode.DOODLE,
                "add_text" to PendingPdfEditMode.TEXT,
                "signature" to PendingPdfEditMode.SIGNATURE,
            )

        expectations.forEach { (toolId, mode) ->
            val launch = AppNavigationPolicy.moreTool(toolId)
            assertEquals(Screen.IMPORT_PDF, launch?.destination)
            assertEquals(mode, launch?.pendingEditMode)
            assertEquals(mode.editorMode, launch?.pendingEditMode?.editorMode)
        }
        assertEquals(
            setOf(PdfEditMode.NONE, PdfEditMode.DOODLE, PdfEditMode.TEXT, PdfEditMode.SIGNATURE),
            PendingPdfEditMode.entries.map { it.editorMode }.toSet(),
        )
    }

    @Test
    fun `more tool aliases and unknown boundaries are explicit`() {
        assertEquals(
            AppNavigationPolicy.moreTool("smart_scan"),
            AppNavigationPolicy.moreTool("scan_id"),
        )
        assertTrue(AppNavigationPolicy.moreTool("image_to_pdf")?.clearImageState == true)
        assertNull(AppNavigationPolicy.moreTool(""))
        assertNull(AppNavigationPolicy.moreTool("SMART_SCAN"))
        assertNull(AppNavigationPolicy.moreTool("unknown"))
    }
}
