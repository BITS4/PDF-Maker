package com.example.pdfmaker

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
internal fun AppDocumentRoute(
    activity: MainActivity,
    navigation: AppNavigationState,
    screen: Screen,
) {
    when (screen) {
        Screen.COMPRESS -> {
            CompressScreen(onBack = navigation::navigateBackToOrigin)
        }

        Screen.PDF_TO_JPG -> {
            PdfToJpgScreen(onBack = navigation::navigateBackToOrigin)
        }

        Screen.MERGE_PDF -> {
            MergePdfScreen(
                onBack = navigation::navigateBackToOrigin,
                onOpenFile = navigation::openFile,
            )
        }

        Screen.DOCX_TO_PDF -> {
            DocxToPdfRoute(navigation)
        }

        Screen.IMPORT_PDF -> {
            ImportPdfRoute(navigation)
        }

        Screen.IMPORTED_PDF_VIEWER -> {
            ImportedPdfViewerRoute(activity, navigation)
        }

        Screen.SIGNATURE_PAD -> {
            navigation.navigate(Screen.IMPORTED_PDF_VIEWER)
        }

        else -> {
            SupportingDocumentRoute(navigation, screen)
        }
    }
}

@Composable
private fun SupportingDocumentRoute(
    navigation: AppNavigationState,
    screen: Screen,
) {
    when (screen) {
        Screen.SPLIT_PDF -> {
            SplitPdfScreen(
                onBack = navigation::navigateBackToOrigin,
                onOpenFile = navigation::openFile,
            )
        }

        Screen.PAGE_MANAGER -> {
            PageManagerScreen(
                onBack = navigation::navigateBackToOrigin,
                onOpenFile = navigation::openFile,
            )
        }

        Screen.LOCK_PDF -> {
            LockPdfScreen(onBack = navigation::navigateBackToOrigin)
        }

        Screen.UNLOCK_PDF -> {
            UnlockPdfScreen(onBack = navigation::navigateBackToOrigin)
        }

        Screen.OCR -> {
            OcrScreen(onBack = navigation::navigateBackToOrigin)
        }

        Screen.PRINT_PDF -> {
            PrintPdfScreen(onBack = navigation::navigateBackToOrigin)
        }

        Screen.CAMERA_DENIED -> {
            CameraPermissionDeniedScreen(onBack = navigation::navigateBackToOrigin)
        }

        Screen.ONBOARDING -> {
            OnboardingScreen(
                onDone = { navigation.navigate(Screen.HOME) },
            )
        }

        else -> {
            error("Screen $screen is not a document route")
        }
    }
}

@Composable
private fun DocxToPdfRoute(navigation: AppNavigationState) {
    DocxToPdfScreen(
        onBack = {
            navigation.clearImportedDocx()
            navigation.navigateBackToOrigin()
        },
        onOpenFile = { file ->
            navigation.clearImportedDocx()
            navigation.openFile(file)
        },
        initialUri = navigation.importedDocxUri,
        initialName = navigation.importedDocxName,
    )
}

@Composable
private fun ImportPdfRoute(navigation: AppNavigationState) {
    ImportPdfScreen(
        onBack = navigation::navigateBackToOrigin,
        onPdfPicked = { uri ->
            navigation.importedPdfUri = uri
            navigation.navigate(Screen.IMPORTED_PDF_VIEWER)
        },
    )
}

@Composable
private fun ImportedPdfViewerRoute(
    activity: MainActivity,
    navigation: AppNavigationState,
) {
    val uri = navigation.importedPdfUri
    if (uri == null) {
        navigation.navigate(Screen.IMPORT_PDF)
        return
    }
    val initialEditMode = remember(uri) { navigation.consumePendingEditMode() }
    ImportedPdfViewerScreen(
        pdfUri = uri,
        onBack = { navigation.navigate(Screen.IMPORT_PDF) },
        initialEditMode = initialEditMode,
        onShareFile = { file ->
            val shared =
                DocumentShareAdapter.share(
                    context = activity,
                    file = file,
                    chooserTitle = "Share document",
                )
            if (!shared) {
                Toast
                    .makeText(
                        activity,
                        activity.getString(R.string.could_not_share),
                        Toast.LENGTH_SHORT,
                    ).show()
            }
        },
    )
}
