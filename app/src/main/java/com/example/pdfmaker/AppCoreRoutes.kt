package com.example.pdfmaker

import android.widget.Toast
import androidx.compose.runtime.Composable
import java.io.File

@Composable
internal fun AppCoreRoute(
    activity: MainActivity,
    navigation: AppNavigationState,
    screen: Screen,
) {
    when (screen) {
        Screen.HOME ->
            HomeScreen(
                activity = activity,
                onToolClick = { toolId ->
                    val launch = AppNavigationPolicy.homeTool(toolId)
                    if (launch == null) {
                        Toast.makeText(activity, toolId, Toast.LENGTH_SHORT).show()
                    } else {
                        navigation.launchTool(launch)
                    }
                },
                onFileClick = navigation::openFile,
                onShareFile = { file -> sharePdf(activity, file) },
                onFabClick = {
                    navigation.launchTool(
                        ToolLaunch(
                            destination = Screen.IMAGE_SELECTION,
                            clearImageState = true,
                        ),
                    )
                },
                onNavigateToFiles = { navigation.navigate(Screen.FILES) },
                onNavigateToSettings = { navigation.navigate(Screen.SETTINGS) },
            )
        Screen.FILES ->
            FilesScreen(
                activity = activity,
                onFileClick = { file -> navigation.openFile(file, fromFilesList = true) },
                onNavigateToHome = { navigation.navigate(Screen.HOME) },
                onNavigateToSettings = { navigation.navigate(Screen.SETTINGS) },
                onFabClick = {
                    navigation.launchTool(
                        ToolLaunch(
                            destination = Screen.IMAGE_SELECTION,
                            clearImageState = true,
                        ),
                    )
                },
            )
        Screen.VIEWER -> {
            val file = navigation.selectedFile
            if (file == null) {
                navigation.navigate(Screen.HOME)
                return
            }
            PdfViewerScreen(
                file = file,
                onBack = navigation::handleSystemBack,
                onShare = { sharePdf(activity, file) },
            )
        }
        Screen.SETTINGS ->
            SettingsScreen(
                onBack = { navigation.navigate(Screen.HOME) },
            )
        Screen.MORE_TOOLS ->
            MoreToolsScreen(
                onBack = { navigation.navigate(Screen.HOME) },
                onToolClick = { toolId ->
                    AppNavigationPolicy.moreTool(toolId)?.let { launch ->
                        navigation.launchTool(launch, fromMoreToolsGrid = true)
                    }
                },
            )
        else -> error("Screen $screen is not a core route")
    }
}

private fun sharePdf(
    activity: MainActivity,
    file: PdfFile,
) {
    val shared =
        DocumentShareAdapter.share(
            activity = activity,
            file = File(file.filePath),
            chooserTitle = activity.getString(R.string.share_pdf_via),
        )
    if (!shared) {
        Toast
            .makeText(activity, activity.getString(R.string.could_not_share), Toast.LENGTH_SHORT)
            .show()
    }
}
