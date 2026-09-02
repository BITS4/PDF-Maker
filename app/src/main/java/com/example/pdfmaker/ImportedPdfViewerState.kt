package com.example.pdfmaker

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color

internal class ImportedViewerResources(
    private val bitmaps: List<Bitmap>,
    private val source: StagedPdfSource?,
) {
    fun release() {
        BitmapOwnership.retire(bitmaps)
        source?.close()
    }
}

internal data class ImportedPageCacheRetention(
    val retained: Map<Int, Bitmap>,
    val obsolete: Collection<Bitmap>,
)

@Stable
internal class ImportedPdfViewerState(
    initialEditMode: PdfEditMode,
) {
    var pageCount by mutableIntStateOf(0)
        private set
    var currentPage by mutableIntStateOf(0)
        private set
    var pageBitmaps by mutableStateOf<Map<Int, Bitmap>>(emptyMap())
        private set
    var pdfTitle by mutableStateOf("Document")
        private set
    val annotations = mutableStateListOf<PageAnnotations>()

    var editMode by mutableStateOf(initialEditMode)
        private set
    var showConvert by mutableStateOf(false)
        private set
    var stagedSource by mutableStateOf<StagedPdfSource?>(null)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set

    val doodle = ImportedPdfDoodleState()
    val overlays = ImportedPdfOverlayState()

    val workingUri: Uri?
        get() = stagedSource?.file?.let(Uri::fromFile)

    fun backAction(operationTarget: ConvertTarget): ImportedViewerBackAction = ImportedPdfViewerPolicy.backAction(editMode, showConvert, operationTarget)

    fun handleBack(
        action: ImportedViewerBackAction,
        operations: PdfEditorOperationController,
    ) {
        when (action) {
            ImportedViewerBackAction.CANCEL_OPERATION -> operations.cancel()
            ImportedViewerBackAction.DISCARD_DOODLE -> cancelDoodle()
            ImportedViewerBackAction.DISCARD_OVERLAYS -> cancelOverlayEdits()
            ImportedViewerBackAction.CLOSE_SIGNATURE_PAD -> cancelSignature()
            ImportedViewerBackAction.CLOSE_EDITOR -> editMode = PdfEditMode.NONE
            ImportedViewerBackAction.CLOSE_CONVERT -> showConvert = false
            ImportedViewerBackAction.NAVIGATE_BACK -> Unit
        }
    }

    fun prepareForLoad(): ImportedViewerResources {
        val resources = ownedResources()
        pageBitmaps = emptyMap()
        stagedSource = null
        loadError = null
        pageCount = 0
        currentPage = 0
        annotations.clear()
        doodle.reset()
        overlays.clearWithoutRecycling()
        return resources
    }

    fun acceptSource(
        source: StagedPdfSource,
        count: Int,
        title: String,
    ) {
        require(count in 1..PageEditPolicy.MAX_EDITABLE_PAGES) { "Imported PDF page count is invalid" }
        check(stagedSource == null) { "An imported PDF source is already active" }
        stagedSource = source
        pageCount = count
        currentPage = 0
        pdfTitle = title
        repeat(count) { annotations.add(PageAnnotations()) }
    }

    fun reportLoadFailure(error: Exception) {
        loadError = ImportedPdfViewerPolicy.loadFailureMessage(error)
    }

    fun prunePageCache(retainedIndexes: Set<Int>): ImportedPageCacheRetention {
        val retained = pageBitmaps.filterKeys { it in retainedIndexes }
        val obsolete = pageBitmaps.filterKeys { it !in retainedIndexes }.values
        pageBitmaps = retained
        return ImportedPageCacheRetention(retained, obsolete)
    }

    fun installRenderedPages(
        retained: Map<Int, Bitmap>,
        rendered: Map<Int, Bitmap>,
    ) {
        pageBitmaps = retained + rendered
        if (currentPage !in pageBitmaps) {
            loadError = "This PDF page could not be rendered safely."
        }
    }

    fun selectEditMode(mode: PdfEditMode) {
        editMode = mode
    }

    fun setConvertVisible(visible: Boolean) {
        showConvert = visible
    }

    fun cancelDoodle() {
        doodle.reset()
        editMode = PdfEditMode.EDIT_PICKER
    }

    fun commitDoodle() {
        doodle.commitTo(annotations.getOrNull(currentPage))
        editMode = PdfEditMode.NONE
    }

    fun resetOverlayEdits() {
        overlays.discardPending()
    }

    fun cancelOverlayEdits() {
        overlays.discardPending()
        editMode = PdfEditMode.EDIT_PICKER
    }

    fun commitOverlayEdits() {
        if (overlays.commitTo(annotations.getOrNull(currentPage))) {
            editMode = PdfEditMode.NONE
        } else {
            loadError = "The current PDF page is unavailable. Reopen the document and try again."
        }
    }

    fun acceptSignature(bitmap: Bitmap?) {
        editMode = if (overlays.acceptSignature(bitmap)) PdfEditMode.TEXT else PdfEditMode.EDIT_PICKER
    }

    fun cancelSignature() {
        editMode = PdfEditMode.EDIT_PICKER
    }

    fun release(operations: PdfEditorOperationController) {
        val resources = ownedResources()
        operations.cancelAndRelease(resources::release)
    }

    private fun ownedResources(): ImportedViewerResources =
        ImportedViewerResources(
            bitmaps = pageBitmaps.values + annotationBitmaps(annotations, overlays.liveSignatures),
            source = stagedSource,
        )
}

@Stable
internal class ImportedPdfDoodleState {
    var strokes by mutableStateOf<List<DrawStroke>>(emptyList())
        private set
    var redo by mutableStateOf<List<DrawStroke>>(emptyList())
        private set
    var activePath by mutableStateOf<List<Offset>>(emptyList())
        private set
    var color by mutableStateOf(Color.Black)
    var size by mutableFloatStateOf(5f)

    fun start(point: Offset) {
        activePath = listOf(point)
        redo = emptyList()
    }

    fun append(point: Offset) {
        activePath = activePath + point
    }

    fun finish() {
        if (activePath.size >= 2) strokes = strokes + DrawStroke(activePath, color, size)
        activePath = emptyList()
    }

    fun undo() {
        if (strokes.isEmpty()) return
        redo = redo + strokes.last()
        strokes = strokes.dropLast(1)
    }

    fun redo() {
        if (redo.isEmpty()) return
        strokes = strokes + redo.last()
        redo = redo.dropLast(1)
    }

    fun reset() {
        strokes = emptyList()
        redo = emptyList()
        activePath = emptyList()
    }

    fun commitTo(page: PageAnnotations?) {
        if (page != null) page.strokes = page.strokes + strokes
        reset()
    }
}

@Stable
internal class ImportedPdfOverlayState {
    var liveTexts by mutableStateOf<List<LiveText>>(emptyList())
        private set
    var liveSignatures by mutableStateOf<List<LiveSignature>>(emptyList())
        private set
    var selectedItemId by mutableStateOf<String?>(null)
        private set
    var pageBoxWidth by mutableIntStateOf(1)
        private set
    var pageBoxHeight by mutableIntStateOf(1)
        private set
    var showTextDialog by mutableStateOf(false)
        private set
    var tapPosition by mutableStateOf(Offset.Zero)
        private set
    var textInput by mutableStateOf("")
    var textColor by mutableStateOf(Color.Black)
    var textSize by mutableFloatStateOf(18f)

    fun updatePageSize(
        width: Int,
        height: Int,
    ) {
        if (width <= 0 || height <= 0) return
        pageBoxWidth = width
        pageBoxHeight = height
    }

    fun beginTextAt(position: Offset) {
        tapPosition = position
        textInput = ""
        showTextDialog = true
    }

    fun dismissTextDialog() {
        showTextDialog = false
    }

    fun addText(text: LiveText) {
        liveTexts = liveTexts + text
    }

    fun select(id: String) {
        selectedItemId = id
    }

    fun updateText(
        id: String,
        x: Float,
        y: Float,
        size: Float,
    ) {
        liveTexts = liveTexts.map { if (it.id == id) it.copy(x = x, y = y, sizeSp = size) else it }
    }

    fun updateSignature(
        id: String,
        x: Float,
        y: Float,
        scale: Float,
    ) {
        liveSignatures =
            liveSignatures.map { if (it.id == id) it.copy(x = x, y = y, scaleFactor = scale) else it }
    }

    fun acceptSignature(bitmap: Bitmap?): Boolean {
        if (bitmap == null) return false
        liveSignatures =
            liveSignatures +
            LiveSignature(
                bitmap = bitmap,
                x = (pageBoxWidth * 0.3f).coerceAtLeast(40f),
                y = (pageBoxHeight * 0.5f).coerceAtLeast(40f),
            )
        return true
    }

    fun discardPending() {
        val retired = liveSignatures.map(LiveSignature::bitmap)
        clearWithoutRecycling()
        BitmapOwnership.retire(retired)
    }

    fun commitTo(page: PageAnnotations?): Boolean {
        if (page == null) return false
        page.texts += liveTexts.map { TextAnnotation(it.x, it.y, it.text, it.color, it.sizeSp) }
        page.signatures += liveSignatures.mapNotNull(::normalizedSignature)
        clearWithoutRecycling()
        return true
    }

    fun clearWithoutRecycling() {
        liveTexts = emptyList()
        liveSignatures = emptyList()
        selectedItemId = null
        showTextDialog = false
    }

    private fun normalizedSignature(signature: LiveSignature): SignatureOverlay? =
        normalizeSignaturePlacement(
            signature.x,
            signature.y,
            signature.scaleFactor,
            pageBoxWidth,
            pageBoxHeight,
        )?.let { placement ->
            SignatureOverlay(placement.x, placement.y, placement.width, signature.bitmap)
        }
}
