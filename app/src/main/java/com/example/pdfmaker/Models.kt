package com.example.pdfmaker

import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
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
    return FileCatalog.filter(this, filter)
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

fun List<PdfFile>.sorted(order: SortOrder): List<PdfFile> = FileCatalog.sort(this, order)

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
data class ImageRenderRequest(
    val source: Bitmap,
    val filter: ImageFilter,
    val brightness: Float,
    val contrast: Float,
    val details: Float,
    val rotationDegrees: Float,
    val cropRect: RectF,
    val cropApplied: Boolean,
)

data class ImageRenderResult(
    val display: Bitmap,
    val final: Bitmap,
) {
    fun generatedBitmaps(source: Bitmap): List<Bitmap> =
        uniqueBitmaps(display, final).filter { it !== source }
}

class ImageEditState(val uri: Uri) {
    var originalBitmap by mutableStateOf<Bitmap?>(null)
    var displayBitmap  by mutableStateOf<Bitmap?>(null)
    var finalBitmap    by mutableStateOf<Bitmap?>(null)
    var loadError      by mutableStateOf<String?>(null)
    var isRendering    by mutableStateOf(false)

    var filter        by mutableStateOf(ImageFilter.ORIGINAL)
    var brightness    by mutableStateOf(0f)
    var contrast      by mutableStateOf(0f)
    var details       by mutableStateOf(0f)
    var totalRotation by mutableStateOf(0f)

    var cropRect    by mutableStateOf(RectF(0f, 0f, 1f, 1f))
    var cropApplied by mutableStateOf(false)

    fun renderRequest(): ImageRenderRequest? {
        val source = originalBitmap?.takeUnless { it.isRecycled } ?: return null
        return ImageRenderRequest(
            source = source,
            filter = filter,
            brightness = brightness,
            contrast = contrast,
            details = details,
            rotationDegrees = ((totalRotation % 360f) + 360f) % 360f,
            cropRect = RectF(cropRect),
            cropApplied = cropApplied,
        )
    }

    fun installSource(bitmap: Bitmap): List<Bitmap> {
        require(!bitmap.isRecycled) { "Cannot install a recycled image" }
        val retired = ownedBitmaps().filterNotSameAs(bitmap)
        originalBitmap = bitmap
        displayBitmap = bitmap
        finalBitmap = bitmap
        loadError = null
        return retired
    }

    fun installRender(result: ImageRenderResult): List<Bitmap> {
        require(!result.display.isRecycled && !result.final.isRecycled) {
            "Cannot install a recycled render result"
        }
        val source = originalBitmap
        val retired = ownedBitmaps().filterNotSameAs(source, result.display, result.final)
        displayBitmap = result.display
        finalBitmap = result.final
        loadError = null
        return retired
    }

    fun installCropPreview(bitmap: Bitmap): List<Bitmap> {
        require(!bitmap.isRecycled) { "Cannot install a recycled crop preview" }
        val retired = uniqueBitmaps(displayBitmap).filterNotSameAs(
            originalBitmap,
            finalBitmap,
            bitmap,
        )
        displayBitmap = bitmap
        return retired
    }

    /** Commits already-rendered crop pixels as the new baseline, avoiding double filters/rotation. */
    fun commitCroppedSource(bitmap: Bitmap): List<Bitmap> {
        val retired = ownedBitmaps().filterNotSameAs(bitmap)
        originalBitmap = bitmap
        displayBitmap = bitmap
        finalBitmap = bitmap
        filter = ImageFilter.ORIGINAL
        brightness = 0f
        contrast = 0f
        details = 0f
        totalRotation = 0f
        cropRect = RectF(0f, 0f, 1f, 1f)
        cropApplied = false
        loadError = null
        return retired
    }

    fun releaseBitmaps(): List<Bitmap> {
        val sourceInUse = originalBitmap.takeIf { isRendering }
        val retired = ownedBitmaps().filterNotSameAs(sourceInUse)
        originalBitmap = null
        displayBitmap = null
        finalBitmap = null
        isRendering = false
        return retired
    }

    private fun ownedBitmaps(): List<Bitmap> =
        uniqueBitmaps(originalBitmap, displayBitmap, finalBitmap)
}

/** Delays recycling until Compose has had time to replace an image in the display list. */
object BitmapOwnership {
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun retire(bitmaps: Iterable<Bitmap>) {
        val unique = uniqueBitmaps(*bitmaps.toList().toTypedArray())
        if (unique.isEmpty()) return
        mainHandler.post {
            val choreographer = Choreographer.getInstance()
            fun recycleAfterFrame(framesRemaining: Int) {
                if (framesRemaining == 0) {
                    unique.forEach { bitmap ->
                        if (!bitmap.isRecycled) bitmap.recycle()
                    }
                } else {
                    choreographer.postFrameCallback {
                        recycleAfterFrame(framesRemaining - 1)
                    }
                }
            }
            recycleAfterFrame(framesRemaining = 2)
        }
    }
}

private fun uniqueBitmaps(vararg candidates: Bitmap?): List<Bitmap> {
    val unique = mutableListOf<Bitmap>()
    candidates.filterNotNull().forEach { candidate ->
        if (unique.none { it === candidate }) unique += candidate
    }
    return unique
}

private fun List<Bitmap>.filterNotSameAs(vararg retained: Bitmap?): List<Bitmap> =
    filter { candidate -> retained.none { it === candidate } }

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

