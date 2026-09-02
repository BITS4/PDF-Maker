package com.example.pdfmaker

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ImportedPdfViewerScreen(
    pdfUri: Uri,
    onBack: () -> Unit,
    onShareFile: (File) -> Unit,
    initialEditMode: PdfEditMode = PdfEditMode.NONE,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val displayWidth = context.resources.displayMetrics.widthPixels

    var pageCount by remember { mutableIntStateOf(0) }
    var currentPage by remember { mutableIntStateOf(0) }
    var pageBitmaps by remember { mutableStateOf<Map<Int, Bitmap>>(emptyMap()) }
    var pdfTitle by remember { mutableStateOf("Document") }
    val annotations = remember { mutableStateListOf<PageAnnotations>() }
    var editMode by remember { mutableStateOf(initialEditMode) }
    var showConvert by remember { mutableStateOf(false) }
    var convertTarget by remember { mutableStateOf(ConvertTarget.NONE) }
    var convertProgress by remember { mutableIntStateOf(0) }
    var doodleStrokes by remember { mutableStateOf<List<DrawStroke>>(emptyList()) }
    var doodleRedo by remember { mutableStateOf<List<DrawStroke>>(emptyList()) }
    var activePath by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var doodleColor by remember { mutableStateOf(Color.Black) }
    var doodleSize by remember { mutableFloatStateOf(5f) }
    var liveTexts by remember { mutableStateOf<List<LiveText>>(emptyList()) }
    var liveSignatures by remember { mutableStateOf<List<LiveSignature>>(emptyList()) }
    var selectedItemId by remember { mutableStateOf<String?>(null) }
    var pageBoxWidth by remember { mutableIntStateOf(1) }
    var pageBoxHeight by remember { mutableIntStateOf(1) }
    var showTextDialog by remember { mutableStateOf(false) }
    var tapPosition by remember { mutableStateOf(Offset.Zero) }
    var textInput by remember { mutableStateOf("") }
    var textColor by remember { mutableStateOf(Color.Black) }
    var textSize by remember { mutableFloatStateOf(18f) }

    BackHandler(enabled = editMode != PdfEditMode.NONE || showConvert) {
        when {
            editMode == PdfEditMode.DOODLE -> {
                doodleStrokes = emptyList()
                doodleRedo = emptyList()
                editMode = PdfEditMode.EDIT_PICKER
            }
            editMode == PdfEditMode.TEXT -> {
                liveTexts = emptyList()
                selectedItemId = null
                editMode = PdfEditMode.EDIT_PICKER
            }
            editMode == PdfEditMode.EDIT_PICKER -> editMode = PdfEditMode.NONE
            showConvert -> showConvert = false
        }
    }

    LaunchedEffect(pdfUri) {
        val (count, title) = withContext(Dispatchers.IO) {
            pdfPageCount(context, pdfUri) to (
                pdfUri.lastPathSegment?.removeSuffix(".pdf")
                    ?.substringAfterLast("/")?.substringAfterLast("%2F") ?: "Document"
                )
        }
        pageCount = count
        pdfTitle = title
        annotations.clear()
        repeat(count) { annotations.add(PageAnnotations()) }
    }

    LaunchedEffect(currentPage, pageCount) {
        if (pageCount == 0) return@LaunchedEffect
        listOf(currentPage, currentPage + 1, currentPage - 1)
            .filter { it in 0 until pageCount && !pageBitmaps.containsKey(it) }
            .forEach { index ->
                renderPage(context, pdfUri, index, displayWidth)?.let {
                    pageBitmaps = pageBitmaps + (index to it)
                }
            }
    }

    fun commitDoodle() {
        annotations.getOrNull(currentPage)?.let { it.strokes = it.strokes + doodleStrokes }
        doodleStrokes = emptyList()
        doodleRedo = emptyList()
        activePath = emptyList()
    }

    fun commitTextAndSignatures() {
        val pageAnnotations = annotations.getOrNull(currentPage) ?: return
        pageAnnotations.texts += liveTexts.map { TextAnnotation(it.x, it.y, it.text, it.color, it.sizeSp) }
        pageAnnotations.signatures += liveSignatures.mapNotNull { signature ->
            normalizeSignaturePlacement(
                signature.x, signature.y, signature.scaleFactor, pageBoxWidth, pageBoxHeight,
            )?.let { SignatureOverlay(it.x, it.y, it.width, signature.bitmap) }
        }
        liveTexts = emptyList()
        liveSignatures = emptyList()
        selectedItemId = null
    }

    fun startOfficeConversion(target: ConvertTarget) {
        showConvert = false
        convertTarget = target
        convertProgress = 0
        val timestamp = System.currentTimeMillis()
        scope.launch(Dispatchers.IO) {
            val file = when (target) {
                ConvertTarget.WORD -> pdfToDocx(context, pdfUri, "doc_$timestamp.docx") { progress ->
                    scope.launch(Dispatchers.Main) { convertProgress = progress }
                }
                ConvertTarget.PPT -> pdfToPptx(context, pdfUri, "ppt_$timestamp.pptx") { progress ->
                    scope.launch(Dispatchers.Main) { convertProgress = progress }
                }
                ConvertTarget.NONE -> null
            }
            withContext(Dispatchers.Main) {
                convertTarget = ConvertTarget.NONE
                file?.let(onShareFile)
            }
        }
    }

    fun shareAnnotatedPdf() {
        scope.launch(Dispatchers.IO) {
            val file = buildAnnotatedPdf(
                context, pdfUri, annotations, density,
                context.resources.displayMetrics.scaledDensity,
                pageBoxWidth, pageBoxHeight, "shared_${System.currentTimeMillis()}.pdf",
            ) ?: return@launch
            val shareUri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, shareUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            withContext(Dispatchers.Main) {
                context.startActivity(Intent.createChooser(intent, "Share PDF"))
            }
        }
    }

    if (editMode == PdfEditMode.SIGNATURE) {
        SignaturePadScreen(
            onConfirm = { bitmap ->
                if (bitmap == null) {
                    editMode = PdfEditMode.EDIT_PICKER
                } else {
                    liveSignatures += LiveSignature(
                        bitmap = bitmap,
                        x = (pageBoxWidth * 0.3f).coerceAtLeast(40f),
                        y = (pageBoxHeight * 0.5f).coerceAtLeast(40f),
                    )
                    editMode = PdfEditMode.TEXT
                }
            },
            onCancel = { editMode = PdfEditMode.EDIT_PICKER },
        )
        return
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF0D0D16)).statusBarsPadding()) {
        PdfEditorCanvas(
            bitmap = pageBitmaps[currentPage],
            annotations = annotations.getOrNull(currentPage),
            page = currentPage,
            pageCount = pageCount,
            editMode = editMode,
            doodleStrokes = doodleStrokes,
            activePath = activePath,
            doodleColor = doodleColor,
            doodleSize = doodleSize,
            liveTexts = liveTexts,
            liveSignatures = liveSignatures,
            selectedItemId = selectedItemId,
            pageBoxWidth = pageBoxWidth,
            onPageSize = { width, height -> pageBoxWidth = width; pageBoxHeight = height },
            onDoodleStart = { activePath = listOf(it); doodleRedo = emptyList() },
            onDoodlePoint = { activePath = activePath + it },
            onDoodleEnd = {
                if (activePath.size >= 2) doodleStrokes += DrawStroke(activePath, doodleColor, doodleSize)
                activePath = emptyList()
            },
            onTextTap = { tapPosition = it; textInput = ""; showTextDialog = true },
            onSelect = { selectedItemId = it },
            onTextUpdate = { id, x, y, size ->
                liveTexts = liveTexts.map { if (it.id == id) it.copy(x = x, y = y, sizeSp = size) else it }
            },
            onSignatureUpdate = { id, x, y, scale ->
                liveSignatures = liveSignatures.map {
                    if (it.id == id) it.copy(x = x, y = y, scaleFactor = scale) else it
                }
            },
        )
        PdfEditorTopBar(
            title = pdfTitle,
            editMode = editMode,
            onBack = onBack,
            onResetDoodle = { doodleStrokes = emptyList(); doodleRedo = emptyList() },
            onResetText = { liveTexts = emptyList(); liveSignatures = emptyList(); selectedItemId = null },
        )
        if (editMode == PdfEditMode.TEXT) Box(Modifier.align(Alignment.TopStart)) { TextEditHint() }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
            PdfEditorBottomBar(
                editMode = editMode,
                showConvert = showConvert,
                doodleSize = doodleSize,
                doodleColor = doodleColor,
                canUndo = doodleStrokes.isNotEmpty(),
                canRedo = doodleRedo.isNotEmpty(),
                onDoodleSize = { doodleSize = it },
                onDoodleColor = { doodleColor = it },
                onCancelDoodle = {
                    doodleStrokes = emptyList(); doodleRedo = emptyList(); editMode = PdfEditMode.EDIT_PICKER
                },
                onUndo = {
                    if (doodleStrokes.isNotEmpty()) {
                        doodleRedo += doodleStrokes.last(); doodleStrokes = doodleStrokes.dropLast(1)
                    }
                },
                onRedo = {
                    if (doodleRedo.isNotEmpty()) {
                        doodleStrokes += doodleRedo.last(); doodleRedo = doodleRedo.dropLast(1)
                    }
                },
                onCommitDoodle = { commitDoodle(); editMode = PdfEditMode.NONE },
                onCancelText = {
                    liveTexts = emptyList(); liveSignatures = emptyList(); selectedItemId = null
                    editMode = PdfEditMode.EDIT_PICKER
                },
                onAddText = { tapPosition = Offset(100f, 200f); textInput = ""; showTextDialog = true },
                onCommitText = { commitTextAndSignatures(); editMode = PdfEditMode.NONE },
                onMode = { editMode = it },
                onShowConvert = { showConvert = it },
                onConvertWord = { startOfficeConversion(ConvertTarget.WORD) },
                onConvertPpt = { startOfficeConversion(ConvertTarget.PPT) },
                onShare = ::shareAnnotatedPdf,
            )
        }
        if (convertTarget != ConvertTarget.NONE) {
            ConvertingOverlay(convertTarget, convertProgress) { convertTarget = ConvertTarget.NONE }
        }
    }

    if (showTextDialog) {
        AddTextDialog(
            textInput, textColor, textSize, tapPosition,
            onText = { textInput = it },
            onColor = { textColor = it },
            onSize = { textSize = it },
            onDismiss = { showTextDialog = false },
            onAdd = { liveTexts += it },
        )
    }
}
