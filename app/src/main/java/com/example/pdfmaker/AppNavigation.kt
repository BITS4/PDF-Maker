package com.example.pdfmaker

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.FileProvider
import com.example.pdfmaker.R
import java.io.File

@Composable
fun AppNavigation(activity: MainActivity) {

    var currentScreen  by remember { mutableStateOf(Screen.HOME) }
    var prevScreen     by remember { mutableStateOf(Screen.HOME) }
    var selectedFile   by remember { mutableStateOf<PdfFile?>(null) }
    var addingMore     by remember { mutableStateOf(false) }
    var fromSmartScan  by remember { mutableStateOf(false) }
    var resultFilePath by remember { mutableStateOf("") }
    var resultFileName by remember { mutableStateOf("") }
    var importedPdfUri  by remember { mutableStateOf<android.net.Uri?>(null) }
    var fromMoreTools  by remember { mutableStateOf(false) }
    var fromFiles      by remember { mutableStateOf(false) }
    var pendingEditMode by remember { mutableStateOf("") }  // doodle | add_text | signature
    var pinUnlocked    by remember { mutableStateOf(false) }
    var showSplash     by remember { mutableStateOf(true) }
    var showOnboarding by remember { mutableStateOf(
        !activity.getSharedPreferences("pdfmaker_prefs", 0)
             .getBoolean("onboarding_done", false)
    ) }
    var showStorageDialog by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !Environment.isExternalStorageManager()
        )
    }


    fun navigate(to: Screen) {
        if (to == Screen.HOME) { fromMoreTools = false; fromFiles = false }
        prevScreen = currentScreen; currentScreen = to
    }
    // Smart back: returns to More if launched from there, otherwise Home
    fun backToOrigin() {
        if (fromMoreTools) { fromMoreTools = false; navigate(Screen.MORE_TOOLS) }
        else navigate(Screen.HOME)
    }

    // ── PIN gate ─────────────────────────────────────────────────────────────
    val needsPin = SettingsManager.getSecurityEnabled(activity) &&
                   SettingsManager.getPin(activity).isNotEmpty() &&
                   !pinUnlocked
    if (needsPin) {
        PinScreen(onUnlocked = { pinUnlocked = true })
        return
    }

    // ── Splash screen ─────────────────────────────────────────────────────
    if (showSplash) {
        SplashScreen(onReady = { showSplash = false })
        return
    }

    // ── Onboarding (first launch only) ───────────────────────────────────
    if (showOnboarding) {
        OnboardingScreen(onDone = {
            activity.getSharedPreferences("pdfmaker_prefs", 0)
                .edit().putBoolean("onboarding_done", true).apply()
            showOnboarding = false
        })
        return
    }

    // ── All-Files-Access dialog (Android 11+, once per session) ─────────────────
    if (showStorageDialog) {
        AlertDialog(
            onDismissRequest = { showStorageDialog = false },
            containerColor   = currentCard,
            title = { Text("Allow File Access", color = currentText, fontWeight = FontWeight.Bold) },
            text  = {
                Text(
                    "Grant \"All Files Access\" so PDFMaker can find all PDFs and documents on your device.",
                    color = currentTextSecond
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showStorageDialog = false
                    activity.openManageAllFilesSettings()
                }) { Text("Grant Access", color = AccentBlue, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showStorageDialog = false }) {
                    Text("Not Now", color = currentTextSecond)
                }
            }
        )
    }

    BackHandler(enabled = currentScreen != Screen.HOME) {
        when (currentScreen) {
            Screen.FILES               -> navigate(Screen.HOME)
            Screen.SETTINGS            -> navigate(Screen.HOME)
            Screen.VIEWER              -> if (fromFiles) { fromFiles = false; navigate(Screen.FILES) } else navigate(Screen.HOME)
            Screen.SMART_SCAN          -> if (fromMoreTools) { fromMoreTools = false; navigate(Screen.MORE_TOOLS) } else navigate(Screen.HOME)
            Screen.ID_CARD_RESULT      -> navigate(Screen.HOME)
            Screen.COMPRESS            -> navigate(Screen.HOME)
            Screen.PDF_TO_JPG          -> if (fromMoreTools) { fromMoreTools = false; navigate(Screen.MORE_TOOLS) } else navigate(Screen.HOME)
            Screen.MERGE_PDF           -> navigate(Screen.HOME)
            Screen.MORE_TOOLS          -> navigate(Screen.HOME)
            Screen.DOCX_TO_PDF         -> navigate(Screen.HOME)
            Screen.IMPORT_PDF          -> if (fromMoreTools) { fromMoreTools = false; navigate(Screen.MORE_TOOLS) } else navigate(Screen.HOME)
            Screen.IMPORTED_PDF_VIEWER -> navigate(Screen.IMPORT_PDF)
            Screen.SIGNATURE_PAD       -> navigate(Screen.IMPORTED_PDF_VIEWER)
            Screen.SPLIT_PDF           -> backToOrigin()
            Screen.PAGE_MANAGER        -> backToOrigin()
            Screen.LOCK_PDF            -> backToOrigin()
            Screen.UNLOCK_PDF          -> backToOrigin()
            Screen.OCR                 -> backToOrigin()
            Screen.PRINT_PDF           -> backToOrigin()
            Screen.CAMERA_DENIED       -> backToOrigin()
            Screen.ONBOARDING          -> { /* handled by gate above, no-op */ }
            Screen.IMAGE_SELECTION  ->
                if (addingMore) navigate(Screen.IMAGE_REVIEW)
                else { ImageToPdfState.clear(); if (fromMoreTools) { fromMoreTools = false; navigate(Screen.MORE_TOOLS) } else navigate(Screen.HOME) }
            Screen.IMAGE_EDIT       -> when {
                addingMore    -> navigate(Screen.IMAGE_REVIEW)
                fromSmartScan -> navigate(Screen.SMART_SCAN)
                else          -> navigate(Screen.IMAGE_SELECTION)
            }
            Screen.IMAGE_CROP       -> navigate(Screen.IMAGE_EDIT)
            Screen.IMAGE_REVIEW     -> navigate(Screen.IMAGE_EDIT)
            Screen.CONVERT_RESULT   -> { ImageToPdfState.clear(); navigate(Screen.HOME) }
            Screen.HOME             -> { }
        }
    }

    fun sharePdfFile(file: PdfFile) {
        try {
            val uri = FileProvider.getUriForFile(
                activity, "${activity.packageName}.provider", File(file.filePath)
            )
            activity.startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, activity.getString(R.string.share_pdf_via)
            ))
        } catch (_: Exception) {
            Toast.makeText(activity, activity.getString(R.string.could_not_share), Toast.LENGTH_SHORT).show()
        }
    }

    fun openFile(file: PdfFile, fromFilesList: Boolean = false) {
        fromFiles = fromFilesList
        selectedFile = file; navigate(Screen.VIEWER)
    }

    // ── Handle "Open with" intent from outside the app ────────────────────
    LaunchedEffect(activity.incomingUri) {
        val uri = activity.incomingUri ?: return@LaunchedEffect
        activity.incomingUri = null   // consume it
        try {
            // Copy to our files dir so PdfRenderer can open it
            val fileName = uri.lastPathSegment
                ?.substringAfterLast("/")?.substringAfterLast("%2F") ?: "opened_file"
            val outFile  = java.io.File(getPdfMakerDir(activity), fileName)
            activity.contentResolver.openInputStream(uri)?.use { input ->
                outFile.outputStream().use { input.copyTo(it) }
            }
            val pf = PdfFile(
                name     = outFile.nameWithoutExtension,
                filePath = outFile.absolutePath,
                size     = "${outFile.length()/1024} KB",
                date     = "",
                lastModified = outFile.lastModified()
            )
            FileCache.prependFile(pf)
            openFile(pf)
        } catch (_: Exception) { }
    }



    val direction = navDirectionFor(prevScreen, currentScreen)

    ScreenTransition(targetState = currentScreen, direction = direction) { screen ->
    when (screen) {

        Screen.HOME -> HomeScreen(
            activity    = activity,
            onToolClick = { key ->
                when (key) {
                    "image_to_pdf" -> {
                        ImageToPdfState.clear(); addingMore = false
                        navigate(Screen.IMAGE_SELECTION)
                    }
                    "smart_scan" -> {
                        SmartScanState.clear()
                        navigate(Screen.SMART_SCAN)
                    }
                    "import_pdf" -> navigate(Screen.IMPORT_PDF)
                    "compress"   -> navigate(Screen.COMPRESS)
                    "pdf_to_jpg" -> navigate(Screen.PDF_TO_JPG)
                    "merge_pdf"   -> navigate(Screen.MERGE_PDF)
                    "docx_to_pdf" -> navigate(Screen.DOCX_TO_PDF)
                    "more"         -> navigate(Screen.MORE_TOOLS)
                    "split_pdf"    -> navigate(Screen.SPLIT_PDF)
                    "page_manager" -> navigate(Screen.PAGE_MANAGER)
                    "lock_pdf"     -> navigate(Screen.LOCK_PDF)
                    "unlock_pdf"   -> navigate(Screen.UNLOCK_PDF)
                    "ocr"          -> navigate(Screen.OCR)
                    else -> Toast.makeText(activity, key, Toast.LENGTH_SHORT).show()
                }
            },
            onFileClick  = { file -> openFile(file) },
            onShareFile  = { file -> sharePdfFile(file) },
            onFabClick   = { ImageToPdfState.clear(); addingMore = false; navigate(Screen.IMAGE_SELECTION) },
            onNavigateToFiles    = { navigate(Screen.FILES) },
            onNavigateToSettings = { navigate(Screen.SETTINGS) }
        )

        Screen.FILES -> FilesScreen(
            activity             = activity,
            onFileClick          = { file -> openFile(file, fromFilesList = true) },
            onNavigateToHome     = { navigate(Screen.HOME) },
            onNavigateToSettings = { navigate(Screen.SETTINGS) },
            onFabClick = { ImageToPdfState.clear(); addingMore = false; navigate(Screen.IMAGE_SELECTION) }
        )

        Screen.VIEWER -> {
            val file = selectedFile
            if (file != null) {
                PdfViewerScreen(file = file, onBack = { if (fromFiles) { fromFiles = false; navigate(Screen.FILES) } else navigate(Screen.HOME) }, onShare = { sharePdfFile(file) })
            } else navigate(Screen.HOME)
        }

        Screen.SETTINGS -> SettingsScreen(onBack = { navigate(Screen.HOME) })

        Screen.SMART_SCAN -> SmartScanScreen(
            onBack = { backToOrigin() },
            onDocsDone = { uris ->
                ImageToPdfState.clear()
                ImageToPdfState.isIdCardScan = false
                ImageToPdfState.addUris(uris)
                ImageToPdfState.currentEditIndex = 0
                addingMore    = false
                fromSmartScan = true
                navigate(Screen.IMAGE_CROP)  // auto-crop each page before edit
            },
            onIdCardDone = { frontUri, backUri ->
                ImageToPdfState.clear()
                ImageToPdfState.isIdCardScan = true
                ImageToPdfState.addUris(listOfNotNull(frontUri, backUri))
                ImageToPdfState.currentEditIndex = 0
                addingMore    = false
                fromSmartScan = true
                navigate(Screen.IMAGE_CROP)   // go to crop first for auto-detect
            }
        )

        Screen.IMAGE_SELECTION -> ImageSelectionScreen(
            preSelected = if (addingMore) ImageToPdfState.editStates.map { it.uri } else emptyList(),
            onImport = { uris ->
                val prevCount = ImageToPdfState.editStates.size
                ImageToPdfState.addUris(uris)
                ImageToPdfState.currentEditIndex =
                    if (addingMore) prevCount.coerceAtMost((ImageToPdfState.editStates.size - 1).coerceAtLeast(0))
                    else 0
                navigate(Screen.IMAGE_EDIT)
            },
            onBack = {
                if (addingMore) navigate(Screen.IMAGE_REVIEW)
                else { ImageToPdfState.clear(); fromSmartScan = false; backToOrigin() }
            }
        )

        Screen.IMAGE_EDIT -> {
            val states = ImageToPdfState.editStates.toList()
            if (states.isEmpty()) { navigate(Screen.HOME); return@ScreenTransition }
            ImageEditScreen(
                editStates   = states,
                initialIndex = ImageToPdfState.currentEditIndex,
                onCrop = { idx -> ImageToPdfState.currentEditIndex = idx; navigate(Screen.IMAGE_CROP) },
                onDone  = { navigate(Screen.IMAGE_REVIEW) },
                onBack  = {
                    when {
                        addingMore    -> navigate(Screen.IMAGE_REVIEW)
                        fromSmartScan -> navigate(Screen.SMART_SCAN)
                        else          -> navigate(Screen.IMAGE_SELECTION)
                    }
                },
                onDelete = { idx ->
                    ImageToPdfState.removeAt(idx)
                    if (ImageToPdfState.editStates.isEmpty()) navigate(Screen.IMAGE_SELECTION)
                }
            )
        }

        Screen.IMAGE_CROP -> {
            val states = ImageToPdfState.editStates.toList()
            val idx    = ImageToPdfState.currentEditIndex
            val es     = states.getOrNull(idx)
            if (es == null) {
                if (fromSmartScan) navigate(Screen.SMART_SCAN)
                else navigate(Screen.IMAGE_EDIT)
                return@ScreenTransition
            }
            ImageCropScreen(
                editState  = es,
                pageIndex  = idx,
                totalPages = states.size,
                isIdCard   = ImageToPdfState.isIdCardScan,
                onNext = {
                    val nextIdx = idx + 1
                    if (nextIdx < states.size) {
                        // More pages to crop — advance to next
                        ImageToPdfState.currentEditIndex = nextIdx
                        navigate(Screen.IMAGE_CROP)
                    } else {
                        // All pages cropped — go to edit
                        ImageToPdfState.currentEditIndex = 0
                        navigate(Screen.IMAGE_EDIT)
                    }
                },
                onBack = {
                    if (idx > 0) {
                        // Go back to previous page's crop
                        ImageToPdfState.currentEditIndex = idx - 1
                        navigate(Screen.IMAGE_CROP)
                    } else if (fromSmartScan) {
                        navigate(Screen.SMART_SCAN)
                    } else {
                        navigate(Screen.IMAGE_EDIT)
                    }
                },
                onRetake = if (fromSmartScan) ({
                    // Do NOT clear state here — clearing would empty editStates
                    // and trigger the empty-state guard, navigating us to HOME.
                    // Instead just go back to the camera; new capture will replace state.
                    fromSmartScan = false
                    navigate(Screen.SMART_SCAN)
                }) else null
            )
        }

        Screen.IMAGE_REVIEW -> {
            val states = ImageToPdfState.editStates.toList()
            if (states.isEmpty()) { navigate(Screen.HOME); return@ScreenTransition }
            ImageReviewScreen(
                editStates    = states,
                onAddMore     = { addingMore = true; navigate(Screen.IMAGE_SELECTION) },
                onBack        = { navigate(Screen.IMAGE_EDIT) },
                onConvertDone = { path, name ->
                    resultFilePath = path; resultFileName = name
                    navigate(Screen.CONVERT_RESULT)
                }
            )
        }

        Screen.CONVERT_RESULT -> ConvertResultScreen(
            filePath   = resultFilePath,
            fileName   = resultFileName,
            onDone     = { ImageToPdfState.clear(); navigate(Screen.HOME) },
            onOpenFile = { file -> ImageToPdfState.clear(); openFile(file) }
        )

        Screen.ID_CARD_RESULT -> navigate(Screen.HOME)

        Screen.COMPRESS   -> CompressScreen(onBack = { backToOrigin() })
        Screen.PDF_TO_JPG -> PdfToJpgScreen(onBack = { backToOrigin() })
        Screen.MERGE_PDF  -> MergePdfScreen(
            onBack      = { backToOrigin() },
            onOpenFile  = { file -> selectedFile = file; navigate(Screen.VIEWER) }
        )
        Screen.MORE_TOOLS -> MoreToolsScreen(
            onBack      = { navigate(Screen.HOME) },
            onToolClick = { key ->
                fromMoreTools = true
                when (key) {
                    "image_to_pdf" -> { ImageToPdfState.clear(); addingMore = false; navigate(Screen.IMAGE_SELECTION) }
                    "smart_scan"   -> navigate(Screen.SMART_SCAN)
                    "scan_id"      -> navigate(Screen.SMART_SCAN)
                    "import_pdf"   -> navigate(Screen.IMPORT_PDF)
                    "compress"     -> navigate(Screen.COMPRESS)
                    "pdf_to_jpg"   -> navigate(Screen.PDF_TO_JPG)
                    "merge_pdf"    -> navigate(Screen.MERGE_PDF)
                    "docx_to_pdf"  -> navigate(Screen.DOCX_TO_PDF)
                    "doodle"       -> { pendingEditMode = "doodle";    navigate(Screen.IMPORT_PDF) }
                    "add_text"     -> { pendingEditMode = "add_text";   navigate(Screen.IMPORT_PDF) }
                    "signature"    -> { pendingEditMode = "signature";  navigate(Screen.IMPORT_PDF) }
                    "print_pdf"    -> navigate(Screen.PRINT_PDF)
                    "lock_pdf"     -> navigate(Screen.LOCK_PDF)
                    "unlock_pdf"   -> navigate(Screen.UNLOCK_PDF)
                    "ocr"          -> navigate(Screen.OCR)
                    "split_pdf"    -> navigate(Screen.SPLIT_PDF)
                    "page_manager" -> navigate(Screen.PAGE_MANAGER)
                }
            }
        )
        Screen.DOCX_TO_PDF -> DocxToPdfScreen(
            onBack      = { backToOrigin() },
            onOpenFile  = { file -> selectedFile = file; navigate(Screen.VIEWER) }
        )

        Screen.IMPORT_PDF -> ImportPdfScreen(
            onBack      = { backToOrigin() },
            onPdfPicked = { uri ->
                importedPdfUri = uri
                navigate(Screen.IMPORTED_PDF_VIEWER)
            }
        )

        Screen.IMPORTED_PDF_VIEWER -> {
            val uri = importedPdfUri
            if (uri == null) { navigate(Screen.IMPORT_PDF); return@ScreenTransition }
            ImportedPdfViewerScreen(
                pdfUri          = uri,
                onBack          = { navigate(Screen.IMPORT_PDF) },
                initialEditMode = when (pendingEditMode.also { pendingEditMode = "" }) {
                    "doodle"    -> PdfEditMode.DOODLE
                    "add_text"  -> PdfEditMode.TEXT
                    "signature" -> PdfEditMode.SIGNATURE
                    else        -> PdfEditMode.NONE
                },
                onShareFile = { file ->
                    try {
                        val shareUri = androidx.core.content.FileProvider.getUriForFile(
                            activity, "${activity.packageName}.provider", file
                        )
                        activity.startActivity(android.content.Intent.createChooser(
                            android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = if (file.name.endsWith(".docx")) "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                                       else "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                                putExtra(android.content.Intent.EXTRA_STREAM, shareUri)
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }, "Share ${file.name}"
                        ))
                    } catch (_: Exception) {
                        android.widget.Toast.makeText(activity, "Could not share file", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }

        Screen.SIGNATURE_PAD -> navigate(Screen.IMPORTED_PDF_VIEWER)

        Screen.SPLIT_PDF    -> SplitPdfScreen(
            onBack     = { backToOrigin() },
            onOpenFile = { file -> openFile(file) }
        )

        Screen.PAGE_MANAGER -> PageManagerScreen(
            onBack     = { backToOrigin() },
            onOpenFile = { file -> openFile(file) }
        )

        Screen.LOCK_PDF   -> LockPdfScreen(onBack = { backToOrigin() })
        Screen.UNLOCK_PDF -> UnlockPdfScreen(onBack = { backToOrigin() })
        Screen.OCR         -> OcrScreen(onBack = { backToOrigin() })
        Screen.PRINT_PDF   -> PrintPdfScreen(onBack = { backToOrigin() })
        Screen.CAMERA_DENIED -> CameraPermissionDeniedScreen(onBack = { backToOrigin() })
        Screen.ONBOARDING    -> OnboardingScreen(onDone = { navigate(Screen.HOME) })
    }
    }
}
