package com.example.pdfmaker

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

@Composable
@Suppress("TooGenericExceptionCaught")
fun ImportedPdfViewerScreen(
    pdfUri: Uri,
    onBack: () -> Unit,
    onShareFile: (File) -> Unit,
    initialEditMode: PdfEditMode = PdfEditMode.NONE,
) {
    val context = LocalContext.current
    val operations = rememberPdfEditorOperationController()
    val localDensity = LocalDensity.current
    val density = localDensity.density
    val scaledDensity = density * localDensity.fontScale
    val displayWidth = context.resources.displayMetrics.widthPixels

    var pageCount by remember { mutableIntStateOf(0) }
    var currentPage by remember { mutableIntStateOf(0) }
    var pageBitmaps by remember { mutableStateOf<Map<Int, Bitmap>>(emptyMap()) }
    var pdfTitle by remember { mutableStateOf("Document") }
    val annotations = remember { mutableStateListOf<PageAnnotations>() }
    var editMode by remember { mutableStateOf(initialEditMode) }
    var showConvert by remember { mutableStateOf(false) }
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
    var stagedSource by remember(pdfUri) { mutableStateOf<StagedPdfSource?>(null) }
    var loadError by remember(pdfUri) { mutableStateOf<String?>(null) }

    val workingUri = stagedSource?.file?.let(Uri::fromFile)
    val latestPageBitmaps by rememberUpdatedState(pageBitmaps)
    val latestStagedSource by rememberUpdatedState(stagedSource)
    val latestLiveSignatures by rememberUpdatedState(liveSignatures)

    DisposableEffect(pdfUri) {
        onDispose {
            val ownedBitmaps =
                latestPageBitmaps.values +
                    annotationBitmaps(annotations, latestLiveSignatures)
            val ownedSource = latestStagedSource
            val releaseResources = {
                recycleDistinctBitmaps(ownedBitmaps)
                ownedSource?.close()
                Unit
            }
            operations.cancelAndRelease(releaseResources)
        }
    }

    BackHandler(enabled = editMode != PdfEditMode.NONE || showConvert || operations.target != ConvertTarget.NONE) {
        when {
            operations.target != ConvertTarget.NONE -> operations.cancel()
            editMode == PdfEditMode.DOODLE -> {
                doodleStrokes = emptyList()
                doodleRedo = emptyList()
                editMode = PdfEditMode.EDIT_PICKER
            }
            editMode == PdfEditMode.TEXT -> {
                liveTexts = emptyList()
                recycleDistinctBitmaps(liveSignatures.map(LiveSignature::bitmap))
                liveSignatures = emptyList()
                selectedItemId = null
                editMode = PdfEditMode.EDIT_PICKER
            }
            editMode == PdfEditMode.EDIT_PICKER -> editMode = PdfEditMode.NONE
            showConvert -> showConvert = false
        }
    }

    LaunchedEffect(pdfUri) {
        operations.cancelAndJoin()
        operations.dismissError()
        val obsoleteBitmaps = pageBitmaps.values + annotationBitmaps(annotations, liveSignatures)
        val obsoleteSource = stagedSource
        pageBitmaps = emptyMap()
        stagedSource = null
        loadError = null
        pageCount = 0
        currentPage = 0
        annotations.clear()
        liveSignatures = emptyList()
        liveTexts = emptyList()
        try {
            withFrameNanos { }
        } finally {
            recycleDistinctBitmaps(obsoleteBitmaps)
            obsoleteSource?.close()
        }
        var pendingSource: StagedPdfSource? = null
        try {
            val count = withContext(Dispatchers.IO) {
                SafePdfInput.fromUri(context, pdfUri).also { pendingSource = it }.let { source ->
                    PageEditPolicy.requireSupportedPageCount(PdfFileMetadata.pageCount(source.file))
                }
            }
            coroutineContext.ensureActive()
            stagedSource = checkNotNull(pendingSource)
            pendingSource = null
            pageCount = count
            pdfTitle =
                pdfUri.lastPathSegment
                    ?.removeSuffix(".pdf")
                    ?.substringAfterLast("/")
                    ?.substringAfterLast("%2F") ?: "Document"
            repeat(count) { annotations.add(PageAnnotations()) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            loadError = error.message ?: "The PDF could not be opened safely."
        } finally {
            pendingSource?.close()
        }
    }

    LaunchedEffect(currentPage, pageCount, workingUri) {
        val sourceUri = workingUri ?: return@LaunchedEffect
        val retainedIndexes = PageBitmapCachePolicy.retainedIndexes(currentPage, pageCount)
        val retained = pageBitmaps.filterKeys { it in retainedIndexes }
        val obsoleteBitmaps =
            pageBitmaps
            .filterKeys { it !in retainedIndexes }
            .values
        pageBitmaps = retained
        if (obsoleteBitmaps.isNotEmpty()) {
            try {
                withFrameNanos { }
            } finally {
                recycleDistinctBitmaps(obsoleteBitmaps)
            }
        }

        val rendered = mutableMapOf<Int, Bitmap>()
        try {
            withContext(Dispatchers.IO) {
                retainedIndexes
                    .filter { it !in retained }
                    .sortedBy { kotlin.math.abs(it - currentPage) }
                    .forEach { index ->
                        coroutineContext.ensureActive()
                        renderPage(context, sourceUri, index, displayWidth)?.let { rendered[index] = it }
                    }
            }
            coroutineContext.ensureActive()
            pageBitmaps = retained + rendered
            if (currentPage !in pageBitmaps) {
                loadError = "This PDF page could not be rendered safely."
            }
            rendered.clear()
        } finally {
            recycleDistinctBitmaps(rendered.values)
        }
    }

    if (loadError != null) {
        Box(Modifier.fillMaxSize().background(Color(0xFF1A1A1A))) {
            ViewerErrorView(
                message = loadError.orEmpty(),
                onBack = onBack,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        return
    }

    if (workingUri == null) {
        Box(
            Modifier.fillMaxSize().background(Color(0xFF1A1A1A)),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = AccentBlue)
        }
        return
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
        pageAnnotations.signatures +=
            liveSignatures.mapNotNull { signature ->
                normalizeSignaturePlacement(
                    signature.x,
                    signature.y,
                    signature.scaleFactor,
                    pageBoxWidth,
                    pageBoxHeight,
                )?.let { SignatureOverlay(it.x, it.y, it.width, signature.bitmap) }
            }
        liveTexts = emptyList()
        liveSignatures = emptyList()
        selectedItemId = null
    }

    fun startOfficeConversion(target: ConvertTarget) {
        val timestamp = System.currentTimeMillis()
        showConvert = false
        operations.launch(
            target = target,
            producer = { reportProgress ->
                when (target) {
                    ConvertTarget.WORD -> pdfToDocx(context, workingUri, "doc_$timestamp.docx", reportProgress)
                    ConvertTarget.PPT -> pdfToPptx(context, workingUri, "ppt_$timestamp.pptx", reportProgress)
                    ConvertTarget.NONE,
                    ConvertTarget.PDF,
                    -> error("Unsupported office conversion target")
                }
            },
            consumer = onShareFile,
        )
    }

    fun shareAnnotatedPdf() {
        val annotationSnapshot = snapshotAnnotations(annotations)
        operations.launch(
            target = ConvertTarget.PDF,
            producer = { reportProgress ->
                buildAnnotatedPdf(
                    context,
                    workingUri,
                    annotationSnapshot,
                    density,
                    scaledDensity,
                    pageBoxWidth,
                    pageBoxHeight,
                    "shared_${System.currentTimeMillis()}.pdf",
                    reportProgress,
                )
            },
            consumer = { file ->
                val shareUri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                val intent =
                    Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, shareUri)
                        clipData = ClipData.newRawUri("Annotated PDF", shareUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                context.startActivity(Intent.createChooser(intent, "Share PDF"))
            },
        )
    }

    if (editMode == PdfEditMode.SIGNATURE) {
        SignaturePadScreen(
            onConfirm = { bitmap ->
                if (bitmap == null) {
                    editMode = PdfEditMode.EDIT_PICKER
                } else {
                    liveSignatures +=
                        LiveSignature(
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
            onPageSize = { width, height ->
                pageBoxWidth = width
                pageBoxHeight = height
            },
            onDoodleStart = {
                activePath = listOf(it)
                doodleRedo = emptyList()
            },
            onDoodlePoint = { activePath = activePath + it },
            onDoodleEnd = {
                if (activePath.size >= 2) doodleStrokes += DrawStroke(activePath, doodleColor, doodleSize)
                activePath = emptyList()
            },
            onTextTap = {
                tapPosition = it
                textInput = ""
                showTextDialog = true
            },
            onSelect = { selectedItemId = it },
            onTextUpdate = { id, x, y, size ->
                liveTexts = liveTexts.map { if (it.id == id) it.copy(x = x, y = y, sizeSp = size) else it }
            },
            onSignatureUpdate = { id, x, y, scale ->
                liveSignatures =
                    liveSignatures.map {
                        if (it.id == id) it.copy(x = x, y = y, scaleFactor = scale) else it
                    }
            },
        )
        PdfEditorTopBar(
            title = pdfTitle,
            editMode = editMode,
            onBack = onBack,
            onResetDoodle = {
                doodleStrokes = emptyList()
                doodleRedo = emptyList()
            },
            onResetText = {
                liveTexts = emptyList()
                recycleDistinctBitmaps(liveSignatures.map(LiveSignature::bitmap))
                liveSignatures = emptyList()
                selectedItemId = null
            },
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
                    doodleStrokes = emptyList()
                    doodleRedo = emptyList()
                    editMode = PdfEditMode.EDIT_PICKER
                },
                onUndo = {
                    if (doodleStrokes.isNotEmpty()) {
                        doodleRedo += doodleStrokes.last()
                        doodleStrokes = doodleStrokes.dropLast(1)
                    }
                },
                onRedo = {
                    if (doodleRedo.isNotEmpty()) {
                        doodleStrokes += doodleRedo.last()
                        doodleRedo = doodleRedo.dropLast(1)
                    }
                },
                onCommitDoodle = {
                    commitDoodle()
                    editMode = PdfEditMode.NONE
                },
                onCancelText = {
                    liveTexts = emptyList()
                    recycleDistinctBitmaps(liveSignatures.map(LiveSignature::bitmap))
                    liveSignatures = emptyList()
                    selectedItemId = null
                    editMode = PdfEditMode.EDIT_PICKER
                },
                onAddText = {
                    tapPosition = Offset(100f, 200f)
                    textInput = ""
                    showTextDialog = true
                },
                onCommitText = {
                    commitTextAndSignatures()
                    editMode = PdfEditMode.NONE
                },
                onMode = { editMode = it },
                onShowConvert = { showConvert = it },
                onConvertWord = { startOfficeConversion(ConvertTarget.WORD) },
                onConvertPpt = { startOfficeConversion(ConvertTarget.PPT) },
                onShare = ::shareAnnotatedPdf,
            )
        }
        if (operations.target != ConvertTarget.NONE) {
            ConvertingOverlay(operations.target, operations.progress, operations::cancel)
        }
    }

    if (showTextDialog) {
        AddTextDialog(
            textInput,
            textColor,
            textSize,
            tapPosition,
            onText = { textInput = it },
            onColor = { textColor = it },
            onSize = { textSize = it },
            onDismiss = { showTextDialog = false },
            onAdd = { liveTexts += it },
        )
    }

    operations.errorMessage?.let { message ->
        PdfEditorOperationErrorDialog(message = message, onDismiss = operations::dismissError)
    }
}
