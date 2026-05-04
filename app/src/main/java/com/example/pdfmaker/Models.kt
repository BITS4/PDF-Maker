package com.tajapps.pdfmaker

import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

// ── Navigation ────────────────────────────────────────────────────────────────
enum class Screen {
    HOME, FILES, VIEWER, SETTINGS,
    IMAGE_SELECTION, IMAGE_EDIT, IMAGE_CROP, IMAGE_REVIEW, CONVERT_RESULT,
    SMART_SCAN,
    ID_CARD_RESULT,
    COMPRESS,            // PDF compressor
    PDF_TO_JPG,          // PDF → JPG images
    MERGE_PDF,           // Merge multiple PDFs into one
    MORE_TOOLS,          // Tools grid screen
    DOCX_TO_PDF,         // Word (.docx) → PDF
    IMPORT_PDF,          // file picker + device PDF list
    IMPORTED_PDF_VIEWER, // viewer with Edit / Convert / Share
    SIGNATURE_PAD,       // full-screen signature drawing (launched from viewer)
    SPLIT_PDF,           // extract page range into new PDF
    PAGE_MANAGER,        // rotate / delete pages
    LOCK_PDF,            // AES-256 password-protect PDF
    UNLOCK_PDF,          // remove password from locked PDF
    OCR,                 // extract text from PDF/image via ML Kit
    PRINT_PDF,           // send to Android PrintManager
    ONBOARDING,          // first-launch onboarding flow
    CAMERA_DENIED        // camera permission denied screen
}

// ── Core app models ───────────────────────────────────────────────────────────
data class ToolItem(val id: Int, val name: String)

data class PdfFile(
    val name        : String,
    val filePath    : String,
    val size        : String,
    val date        : String,
    val pageCount   : Int  = 1,
    val lastModified: Long = 0L
)

// ── File type filter ──────────────────────────────────────────────────────────

enum class FileTypeFilter(val label: String, val extensions: Set<String>) {
    ALL    ("All",   emptySet()),
    PDF    ("PDF",   setOf("pdf")),
    DOCS   ("Docs",  setOf("doc", "docx")),
    SHEETS ("Excel", setOf("xls", "xlsx")),
    SLIDES ("PPT",   setOf("ppt", "pptx")),
    TEXT   ("Text",  setOf("txt", "csv", "tsv", "md")),
    IMAGES ("Image", setOf("jpg", "jpeg", "png", "webp", "bmp", "gif"))
}

fun List<PdfFile>.filteredBy(filter: FileTypeFilter): List<PdfFile> {
    if (filter == FileTypeFilter.ALL) return this
    return filter { file ->
        val ext = file.filePath.substringAfterLast('.', "").lowercase()
        ext in filter.extensions
    }
}

// ── Sort options ──────────────────────────────────────────────────────────────
enum class SortOrder(val label: String) {
    DATE_DESC  ("Newest first"),
    DATE_ASC   ("Oldest first"),
    NAME_ASC   ("Name A→Z"),
    NAME_DESC  ("Name Z→A"),
    SIZE_DESC  ("Largest first"),
    SIZE_ASC   ("Smallest first")
}

fun List<PdfFile>.sorted(order: SortOrder): List<PdfFile> = when (order) {
    SortOrder.DATE_DESC  -> sortedByDescending { it.lastModified }
    SortOrder.DATE_ASC   -> sortedBy          { it.lastModified }
    SortOrder.NAME_ASC   -> sortedBy          { it.name.lowercase() }
    SortOrder.NAME_DESC  -> sortedByDescending { it.name.lowercase() }
    SortOrder.SIZE_DESC  -> sortedByDescending { it.size }
    SortOrder.SIZE_ASC   -> sortedBy          { it.size }
}

// ── Image filter enum ─────────────────────────────────────────────────────────
enum class ImageFilter(val label: String) {
    ORIGINAL  ("Original"),
    AI_ENHANCE("AI"),
    DOCS      ("Docs"),
    IMAGE     ("Image"),
    SUPER     ("Super"),
    ENHANCE   ("Enhance"),
    ENHANCE2  ("Enhance2"),
    BW        ("B&W"),
    BW2       ("B&W2"),
    GRAY      ("Gray"),
    INVERT    ("Invert")
}

// ── Per-image edit state ──────────────────────────────────────────────────────
class ImageEditState(val uri: Uri) {

    // *** originalBitmap is now observable so LaunchedEffect fires when it loads ***
    var originalBitmap by mutableStateOf<Bitmap?>(null)
    var displayBitmap  by mutableStateOf<Bitmap?>(null)
    var finalBitmap    by mutableStateOf<Bitmap?>(null)

    var filter        by mutableStateOf(ImageFilter.ORIGINAL)
    var brightness    by mutableStateOf(0f)
    var contrast      by mutableStateOf(0f)
    var details       by mutableStateOf(0f)
    var totalRotation by mutableStateOf(0f)

    var cropRect    by mutableStateOf(RectF(0f, 0f, 1f, 1f))
    var cropApplied by mutableStateOf(false)

    fun rebuildFinal() {
        val base = originalBitmap ?: return
        var bm: Bitmap = ImageProcessing.applyFilter(base, filter)
        bm = ImageProcessing.applyAdjustments(bm, brightness, contrast, details)
        val rotDeg = ((totalRotation % 360) + 360) % 360
        if (rotDeg != 0f) bm = ImageProcessing.rotateBitmap(bm, rotDeg)
        displayBitmap = bm
        val r = cropRect
        if (cropApplied &&
            (r.left > 0.001f || r.top > 0.001f || r.right < 0.999f || r.bottom < 0.999f)) {
            bm = ImageProcessing.cropBitmap(bm, r)
        }
        finalBitmap = bm
    }
}

// ── PDF conversion options ────────────────────────────────────────────────────
data class ConvertOptions(
    val fileName    : String  = "",
    val usePassword : Boolean = false,
    val password    : String  = "",
    val whiteMargins: Boolean = false,
    val compression : String  = "Low Compression",
    val pageSize    : String  = "A4",
    val orientation : String  = "Auto"
)

// ── Docx / viewer content model ───────────────────────────────────────────────
// Shared between DocxToPdfScreen and PdfViewerScreen

data class DocRun(
    val text    : String,
    val bold    : Boolean = false,
    val italic  : Boolean = false,
    val fontSize: Float   = 11f   // points
)

sealed class DocBlock {
    data class Paragraph(val runs: List<DocRun>, val headingLevel: Int = 0) : DocBlock()
    data class ImageBlock(val name: String) : DocBlock()
    object PageBreak : DocBlock()
}

