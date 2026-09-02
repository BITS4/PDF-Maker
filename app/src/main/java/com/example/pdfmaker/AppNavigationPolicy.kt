package com.example.pdfmaker

internal enum class PendingPdfEditMode(
    val editorMode: PdfEditMode,
) {
    NONE(PdfEditMode.NONE),
    DOODLE(PdfEditMode.DOODLE),
    TEXT(PdfEditMode.TEXT),
    SIGNATURE(PdfEditMode.SIGNATURE),
}

internal data class NavigationContext(
    val fromMoreTools: Boolean = false,
    val fromFiles: Boolean = false,
    val addingMoreImages: Boolean = false,
    val fromSmartScan: Boolean = false,
)

internal data class BackDecision(
    val destination: Screen? = null,
    val clearImageState: Boolean = false,
    val consumeMoreToolsOrigin: Boolean = false,
    val consumeFilesOrigin: Boolean = false,
)

internal data class ToolLaunch(
    val destination: Screen,
    val pendingEditMode: PendingPdfEditMode = PendingPdfEditMode.NONE,
    val clearImageState: Boolean = false,
    val clearSmartScanState: Boolean = false,
)

internal enum class AppRouteGroup {
    CORE,
    IMAGE,
    DOCUMENT,
}

internal object AppNavigationPolicy {
    private val directBackDestinations =
        mapOf(
            Screen.HOME to null,
            Screen.ONBOARDING to null,
            Screen.FILES to Screen.HOME,
            Screen.SETTINGS to Screen.HOME,
            Screen.ID_CARD_RESULT to Screen.HOME,
            Screen.COMPRESS to Screen.HOME,
            Screen.MERGE_PDF to Screen.HOME,
            Screen.MORE_TOOLS to Screen.HOME,
            Screen.DOCX_TO_PDF to Screen.HOME,
            Screen.IMPORTED_PDF_VIEWER to Screen.IMPORT_PDF,
            Screen.SIGNATURE_PAD to Screen.IMPORTED_PDF_VIEWER,
            Screen.IMAGE_CROP to Screen.IMAGE_EDIT,
            Screen.IMAGE_REVIEW to Screen.IMAGE_EDIT,
        )

    private val originBackScreens =
        setOf(
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

    private val homeToolLaunches =
        mapOf(
            "image_to_pdf" to
                ToolLaunch(
                    destination = Screen.IMAGE_SELECTION,
                    clearImageState = true,
                ),
            "smart_scan" to
                ToolLaunch(
                    destination = Screen.SMART_SCAN,
                    clearSmartScanState = true,
                ),
            "import_pdf" to ToolLaunch(Screen.IMPORT_PDF),
            "compress" to ToolLaunch(Screen.COMPRESS),
            "pdf_to_jpg" to ToolLaunch(Screen.PDF_TO_JPG),
            "merge_pdf" to ToolLaunch(Screen.MERGE_PDF),
            "docx_to_pdf" to ToolLaunch(Screen.DOCX_TO_PDF),
            "more" to ToolLaunch(Screen.MORE_TOOLS),
            "split_pdf" to ToolLaunch(Screen.SPLIT_PDF),
            "page_manager" to ToolLaunch(Screen.PAGE_MANAGER),
            "lock_pdf" to ToolLaunch(Screen.LOCK_PDF),
            "unlock_pdf" to ToolLaunch(Screen.UNLOCK_PDF),
            "ocr" to ToolLaunch(Screen.OCR),
        )

    private val moreToolLaunches =
        mapOf(
            "image_to_pdf" to
                ToolLaunch(
                    destination = Screen.IMAGE_SELECTION,
                    clearImageState = true,
                ),
            "smart_scan" to ToolLaunch(Screen.SMART_SCAN),
            "scan_id" to ToolLaunch(Screen.SMART_SCAN),
            "import_pdf" to ToolLaunch(Screen.IMPORT_PDF),
            "compress" to ToolLaunch(Screen.COMPRESS),
            "pdf_to_jpg" to ToolLaunch(Screen.PDF_TO_JPG),
            "merge_pdf" to ToolLaunch(Screen.MERGE_PDF),
            "docx_to_pdf" to ToolLaunch(Screen.DOCX_TO_PDF),
            "doodle" to ToolLaunch(Screen.IMPORT_PDF, PendingPdfEditMode.DOODLE),
            "add_text" to ToolLaunch(Screen.IMPORT_PDF, PendingPdfEditMode.TEXT),
            "signature" to ToolLaunch(Screen.IMPORT_PDF, PendingPdfEditMode.SIGNATURE),
            "print_pdf" to ToolLaunch(Screen.PRINT_PDF),
            "lock_pdf" to ToolLaunch(Screen.LOCK_PDF),
            "unlock_pdf" to ToolLaunch(Screen.UNLOCK_PDF),
            "ocr" to ToolLaunch(Screen.OCR),
            "split_pdf" to ToolLaunch(Screen.SPLIT_PDF),
            "page_manager" to ToolLaunch(Screen.PAGE_MANAGER),
        )

    fun systemBack(
        screen: Screen,
        context: NavigationContext,
    ): BackDecision {
        if (directBackDestinations.containsKey(screen)) {
            return BackDecision(destination = directBackDestinations[screen])
        }
        if (screen in originBackScreens) return originDecision(context.fromMoreTools)
        return when (screen) {
            Screen.VIEWER -> {
                if (context.fromFiles) {
                    BackDecision(
                        destination = Screen.FILES,
                        consumeFilesOrigin = true,
                    )
                } else {
                    BackDecision(destination = Screen.HOME)
                }
            }

            Screen.IMAGE_SELECTION -> {
                when {
                    context.addingMoreImages -> BackDecision(destination = Screen.IMAGE_REVIEW)
                    else -> originDecision(context.fromMoreTools, clearImageState = true)
                }
            }

            Screen.IMAGE_EDIT -> {
                when {
                    context.addingMoreImages -> BackDecision(destination = Screen.IMAGE_REVIEW)
                    context.fromSmartScan -> BackDecision(destination = Screen.SMART_SCAN)
                    else -> BackDecision(destination = Screen.IMAGE_SELECTION)
                }
            }

            Screen.CONVERT_RESULT -> {
                BackDecision(
                    destination = Screen.HOME,
                    clearImageState = true,
                )
            }

            else -> {
                error("Screen $screen is missing a system-back policy")
            }
        }
    }

    fun homeTool(toolId: String): ToolLaunch? = homeToolLaunches[toolId]

    fun moreTool(toolId: String): ToolLaunch? = moreToolLaunches[toolId]

    fun routeGroup(screen: Screen): AppRouteGroup =
        when (screen) {
            Screen.HOME,
            Screen.FILES,
            Screen.VIEWER,
            Screen.SETTINGS,
            Screen.MORE_TOOLS,
            -> AppRouteGroup.CORE

            Screen.IMAGE_SELECTION,
            Screen.IMAGE_EDIT,
            Screen.IMAGE_CROP,
            Screen.IMAGE_REVIEW,
            Screen.CONVERT_RESULT,
            Screen.SMART_SCAN,
            Screen.ID_CARD_RESULT,
            -> AppRouteGroup.IMAGE

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
            -> AppRouteGroup.DOCUMENT
        }

    private fun originDecision(
        fromMoreTools: Boolean,
        clearImageState: Boolean = false,
    ): BackDecision =
        BackDecision(
            destination = if (fromMoreTools) Screen.MORE_TOOLS else Screen.HOME,
            clearImageState = clearImageState,
            consumeMoreToolsOrigin = fromMoreTools,
        )
}
