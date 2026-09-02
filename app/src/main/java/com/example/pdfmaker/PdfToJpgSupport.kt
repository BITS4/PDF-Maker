package com.example.pdfmaker

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class JpgQuality(
    val label      : String,
    val sub        : String,
    val jpegQuality: Int,
    val maxDimPx   : Int,
    val color      : Color
) {
    LOW(
        label       = "Low",
        sub         = "72 DPI · small file",
        jpegQuality = 60,
        maxDimPx    = 800,
        color       = Color(0xFF9E9E9E)
    ),
    MEDIUM(
        label       = "Medium",
        sub         = "150 DPI · balanced",
        jpegQuality = 82,
        maxDimPx    = 1600,
        color       = Color(0xFF2196F3)
    ),
    HIGH(
        label       = "High",
        sub         = "300 DPI · best quality",
        jpegQuality = 95,
        maxDimPx    = 3000,
        color       = Color(0xFF4CAF50)
    )
}
// ── Small helper composables ──────────────────────────────────────────────────

@Composable
internal fun PageRangeTab(
    label    : String,
    selected : Boolean,
    accent   : Color,
    modifier : Modifier = Modifier,
    onClick  : () -> Unit
) {
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) accent else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (selected) Color.White else Color(0xFF9999BB),
            fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
internal fun PageNumberField(
    label   : String,
    value   : Int,
    range   : IntRange,
    cardBg  : Color,
    textPri : Color,
    textSec : Color,
    accent  : Color,
    onValue : (Int) -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = textSec, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.clip(RoundedCornerShape(10.dp)).background(cardBg)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick  = { if (value > range.first) onValue(value - 1) },
                modifier = Modifier.size(32.dp)
            ) { Icon(Icons.Default.Remove, null, tint = if (value > range.first) accent else textSec,
                    modifier = Modifier.size(16.dp)) }
            Text("$value", color = textPri,
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.widthIn(min = 28.dp), textAlign = TextAlign.Center)
            IconButton(
                onClick  = { if (value < range.last) onValue(value + 1) },
                modifier = Modifier.size(32.dp)
            ) { Icon(Icons.Default.Add, null, tint = if (value < range.last) accent else textSec,
                    modifier = Modifier.size(16.dp)) }
        }
    }
}

@Composable
internal fun JpgSpinner(progress: Int, color: Color) {
    val inf = rememberInfiniteTransition(label = "spin")
    val angle by inf.animateFloat(
        initialValue  = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label         = "angle"
    )
    Canvas(Modifier.size(110.dp)) {
        drawArc(Color(0xFF2A2A40), 0f, 360f, false,
            style = Stroke(10.dp.toPx(), cap = StrokeCap.Round))
        drawArc(color, angle - 90f, (progress * 3.6f).coerceAtLeast(10f), false,
            style = Stroke(10.dp.toPx(), cap = StrokeCap.Round))
    }
}

// ── Core conversion logic ──────────────────────────────────────────────────────

internal data class PdfToJpgPreview(
    val pageCount: Int,
    val bitmaps: List<Bitmap>,
)

internal fun loadPdfToJpgPreview(source: StagedPdfSource): PdfToJpgPreview =
    withStagedPdfRenderer(source) { renderer ->
        val pageCount = PdfToJpgPolicy.requirePageCount(renderer.pageCount)
        val previews = mutableListOf<Bitmap>()
        try {
            repeat(minOf(pageCount, PdfToJpgPolicy.PREVIEW_COUNT)) { pageIndex ->
                previews += renderRendererPage(renderer, pageIndex, PdfToJpgPolicy.PREVIEW_EDGE)
            }
            PdfToJpgPreview(pageCount, previews)
        } catch (error: Throwable) {
            previews.forEach { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
            throw error
        }
    }

internal suspend fun convertPdfToJpg(
    context: Context,
    source: StagedPdfSource,
    baseName: String,
    quality: JpgQuality,
    fromPage: Int,
    toPage: Int,
    onProg: (Int, String) -> Unit,
): List<File> {
    val callingContext = currentCoroutineContext()
    return withStagedPdfRenderer(source) { renderer ->
        PdfToJpgPolicy.requirePageCount(renderer.pageCount)
        require(fromPage in 0 until renderer.pageCount) { "First page is outside the PDF" }
        require(toPage in fromPage until renderer.pageCount) { "Last page is outside the PDF" }
        val outputFiles = mutableListOf<File>()
        val outputDirectory = getPdfMakerDir(context)
        val total = toPage - fromPage + 1
        var totalBytes = 0L

        try {
            for (pageIndex in fromPage..toPage) {
                callingContext.ensureActive()
                val pageNumber = pageIndex + 1
                onProg(
                    ((pageIndex - fromPage) * 95 / total),
                    "Converting page $pageNumber of ${renderer.pageCount}…",
                )
                renderer.openPage(pageIndex).use { page ->
                    val target = PdfToJpgPolicy.renderSize(page.width, page.height, quality.maxDimPx)
                        ?: error("PDF page has invalid dimensions")
                    val bitmap = Bitmap.createBitmap(
                        target.width,
                        target.height,
                        Bitmap.Config.ARGB_8888,
                    )
                    try {
                        android.graphics.Canvas(bitmap).drawColor(android.graphics.Color.WHITE)
                        val scale =
                            RenderSizing.scaleTo(page.width, page.height, target)
                                ?: error("PDF page has invalid dimensions")
                        val transform = android.graphics.Matrix()
                        transform.setScale(scale.scaleX, scale.scaleY)
                        page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val outputFile = OutputStore.writeUnique(
                            directory = outputDirectory,
                            requestedBaseName = "${baseName}_page$pageNumber",
                            extension = "jpg",
                        ) { output ->
                            check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality.jpegQuality, output)) {
                                "Could not encode page as JPEG"
                            }
                        }
                        outputFiles += outputFile
                        totalBytes = PdfToJpgPolicy.recordExportedFile(totalBytes, outputFile.length())
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        } catch (error: Throwable) {
            outputFiles.forEach(File::delete)
            throw error
        }

        onProg(100, "Done!")
        outputFiles
    }
}

private inline fun <T> withStagedPdfRenderer(
    source: StagedPdfSource,
    block: (PdfRenderer) -> T,
): T {
    val descriptor = source.openDescriptor()
    val renderer = try {
        PdfRenderer(descriptor)
    } catch (error: Throwable) {
        descriptor.close()
        throw error
    }
    return renderer.use(block)
}

private fun renderRendererPage(
    renderer: PdfRenderer,
    pageIndex: Int,
    maximumEdge: Int,
): Bitmap = renderer.openPage(pageIndex).use { page ->
    val target = PdfToJpgPolicy.renderSize(page.width, page.height, maximumEdge)
        ?: error("PDF page has invalid dimensions")
    val bitmap = Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
    try {
        android.graphics.Canvas(bitmap).drawColor(android.graphics.Color.WHITE)
        val scale =
            RenderSizing.scaleTo(page.width, page.height, target)
                ?: error("PDF page has invalid dimensions")
        val transform = android.graphics.Matrix()
        transform.setScale(scale.scaleX, scale.scaleY)
        page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        bitmap
    } catch (error: Throwable) {
        bitmap.recycle()
        throw error
    }
}

internal fun decodeJpgResultThumbnail(file: File): Result<Bitmap> = runCatching {
    require(file.isFile && file.length() in 1..PdfToJpgPolicy.MAX_JPEG_BYTES) {
        "Converted image is missing or exceeds its size limit"
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    require(bounds.outMimeType == "image/jpeg") { "Converted image is not a JPEG" }
    val plan = requireNotNull(PdfToJpgPolicy.resultThumbnailPlan(bounds.outWidth, bounds.outHeight)) {
        "Converted image has invalid dimensions"
    }
    var owned: Bitmap? = null
    try {
        owned = requireNotNull(
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = plan.sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inScaled = false
                },
            ),
        ) { "Converted image could not be decoded" }
        val fitted = requireNotNull(
            ImageInputPolicy.fitWithinLimits(
                owned.width,
                owned.height,
                PdfToJpgPolicy.RESULT_THUMBNAIL_EDGE,
                PdfToJpgPolicy.RESULT_THUMBNAIL_PIXELS,
            ),
        ) { "Converted image has invalid dimensions" }
        if (fitted.width != owned.width || fitted.height != owned.height) {
            val scaled = Bitmap.createScaledBitmap(owned, fitted.width, fitted.height, true)
            if (scaled !== owned) owned.recycle()
            owned = scaled
        }
        checkNotNull(owned).also { owned = null }
    } finally {
        owned?.takeUnless(Bitmap::isRecycled)?.recycle()
    }
}

// ── Share all JPGs as a ZIP ────────────────────────────────────────────────────

internal fun prepareJpgShareIntent(
    context: Context,
    files: List<File>,
    baseName: String,
): Result<Intent> = runCatching {
    val exportDirectory = getPdfMakerDir(context).canonicalFile
    val safeFiles = files.map { file ->
        file.canonicalFile.also { canonical ->
            require(canonical.isFile && canonical.parentFile == exportDirectory) {
                "Only converted JPG files can be shared"
            }
        }
    }
    PdfToJpgPolicy.requireShareBatch(safeFiles.map(File::length))

    val sendIntent =
        if (files.size == 1) {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                safeFiles.first(),
            )
            Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            val shareDirectory = File(context.cacheDir, "pdfmaker")
            val zipFile = OutputStore.writeUnique(
                directory = shareDirectory,
                requestedBaseName = "${baseName}_pages",
                extension = "zip",
            ) { output ->
                ZipOutputStream(NonClosingOutputStream(output)).use { zip ->
                    safeFiles.forEach { file ->
                        zip.putNextEntry(ZipEntry("${SafeFileName.baseName(file.nameWithoutExtension)}.jpg"))
                        try {
                            file.inputStream().use { source ->
                                BoundedIo.copy(source, zip, PdfToJpgPolicy.MAX_JPEG_BYTES)
                            }
                        } finally {
                            zip.closeEntry()
                        }
                    }
                }
            }
            val zipUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                zipFile,
            )
            Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, zipUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    Intent.createChooser(sendIntent, if (safeFiles.size == 1) "Share JPG" else "Share JPG images")
}

private class NonClosingOutputStream(output: OutputStream) : FilterOutputStream(output) {
    override fun close() = flush()
}

// ── Helpers ───────────────────────────────────────────────────────────────────

internal fun jpgFormatSize(kb: Long): String = when {
    kb >= 1024 -> "%.1f MB".format(kb / 1024f)
    else       -> "$kb KB"
}

// ── Save JPGs to device gallery (Pictures/PDFMaker) ───────────────────────────

@Suppress("TooGenericExceptionCaught") // Each provider failure is recorded so remaining images can still be saved.
internal fun saveJpgsToGallery(context: Context, files: List<File>): GallerySaveReport {
    if (files.isEmpty()) return GallerySavePolicy.report(0, 0, emptyList())
    if (!GallerySavePolicy.supportsGalleryWrite(Build.VERSION.SDK_INT)) {
        return GallerySavePolicy.report(
            requestedCount = files.size,
            savedCount = 0,
            errors = listOf("Saving directly to Gallery requires Android 10 or newer. Use Share All instead."),
        )
    }

    val resolver = context.contentResolver
    val errors = mutableListOf<String>()
    var savedCount = 0
    files.forEach { file ->
        var insertedUri: Uri? = null
        try {
            require(file.isFile && file.length() in 1..PdfToJpgPolicy.MAX_JPEG_BYTES) {
                "${file.name} is missing or exceeds the 50 MB image limit"
            }
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "${SafeFileName.baseName(file.nameWithoutExtension)}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_PICTURES}/PDFMaker")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val galleryUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Gallery storage rejected ${file.name}")
            insertedUri = galleryUri
            val output = resolver.openOutputStream(galleryUri)
                ?: error("Gallery storage could not open ${file.name}")
            output.use { destination ->
                file.inputStream().use { source ->
                    BoundedIo.copy(source, destination, PdfToJpgPolicy.MAX_JPEG_BYTES)
                    destination.flush()
                }
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            check(resolver.update(galleryUri, values, null, null) > 0) {
                "Gallery storage could not publish ${file.name}"
            }
            savedCount += 1
        } catch (error: Exception) {
            insertedUri?.let { uri -> runCatching { resolver.delete(uri, null, null) } }
            errors += error.message ?: "${file.name} could not be saved"
        }
    }
    return GallerySavePolicy.report(files.size, savedCount, errors)
}
