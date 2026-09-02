package com.example.pdfmaker

import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

@Stable
internal class AppNavigationState(
    showOnboardingInitially: Boolean,
) {
    var currentScreen by mutableStateOf(Screen.HOME)
        private set
    var previousScreen by mutableStateOf(Screen.HOME)
        private set
    var selectedFile by mutableStateOf<PdfFile?>(null)
    var addingMoreImages by mutableStateOf(false)
    var fromSmartScan by mutableStateOf(false)
    var resultFilePath by mutableStateOf("")
    var resultFileName by mutableStateOf("")
    var importedPdfUri by mutableStateOf<Uri?>(null)
    var importedDocxUri by mutableStateOf<Uri?>(null)
    var importedDocxName by mutableStateOf<String?>(null)
    var fromMoreTools by mutableStateOf(false)
    var fromFiles by mutableStateOf(false)
    var pinUnlocked by mutableStateOf(false)
    var showSplash by mutableStateOf(true)
    var showOnboarding by mutableStateOf(showOnboardingInitially)

    private var pendingEditMode = PendingPdfEditMode.NONE

    fun navigate(destination: Screen) {
        if (destination == Screen.HOME) {
            fromMoreTools = false
            fromFiles = false
        }
        previousScreen = currentScreen
        currentScreen = destination
    }

    fun navigateBackToOrigin() {
        if (fromMoreTools) {
            fromMoreTools = false
            navigate(Screen.MORE_TOOLS)
        } else {
            navigate(Screen.HOME)
        }
    }

    fun openFile(
        file: PdfFile,
        fromFilesList: Boolean = false,
    ) {
        fromFiles = fromFilesList
        selectedFile = file
        navigate(Screen.VIEWER)
    }

    fun launchTool(
        launch: ToolLaunch,
        fromMoreToolsGrid: Boolean = false,
    ) {
        if (launch.clearImageState) {
            ImageToPdfState.clear()
            addingMoreImages = false
        }
        if (launch.clearSmartScanState) SmartScanState.clear()
        if (fromMoreToolsGrid) fromMoreTools = true
        pendingEditMode = launch.pendingEditMode
        navigate(launch.destination)
    }

    fun handleSystemBack() {
        val decision =
            AppNavigationPolicy.systemBack(
                screen = currentScreen,
                context =
                    NavigationContext(
                        fromMoreTools = fromMoreTools,
                        fromFiles = fromFiles,
                        addingMoreImages = addingMoreImages,
                        fromSmartScan = fromSmartScan,
                    ),
            )
        if (decision.clearImageState) ImageToPdfState.clear()
        if (decision.consumeMoreToolsOrigin) fromMoreTools = false
        if (decision.consumeFilesOrigin) fromFiles = false
        decision.destination?.let(::navigate)
    }

    fun consumePendingEditMode(): PdfEditMode {
        val editorMode = pendingEditMode.editorMode
        pendingEditMode = PendingPdfEditMode.NONE
        return editorMode
    }

    fun clearImportedDocx() {
        importedDocxUri = null
        importedDocxName = null
    }
}
