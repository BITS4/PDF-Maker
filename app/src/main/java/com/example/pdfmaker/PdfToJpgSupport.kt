package com.example.pdfmaker

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File
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

internal fun convertPdfToJpg(
    context  : Context,
    uri      : Uri,
    baseName : String,
    quality  : JpgQuality,
    fromPage : Int,
    toPage   : Int,
    onProg   : (Int, String) -> Unit
): List<File> {
    val fd  = context.contentResolver.openFileDescriptor(uri, "r") ?: return emptyList()
    val rdr = PdfRenderer(fd)
    val outFiles = mutableListOf<File>()
    val dir = getPdfMakerDir(context)
    val total = toPage - fromPage + 1

    try {
        for (i in fromPage..toPage) {
            val pageNum = i + 1
            onProg(
                ((i - fromPage) * 95 / total.coerceAtLeast(1)),
                "Converting page $pageNum of ${rdr.pageCount}…"
            )
            val page  = rdr.openPage(i)
            val scale = quality.maxDimPx.toFloat() / maxOf(page.width, page.height).coerceAtLeast(1)
            val w     = (page.width  * scale).toInt().coerceAtLeast(1)
            val h     = (page.height * scale).toInt().coerceAtLeast(1)

            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(bmp).drawColor(android.graphics.Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()

            val fileName = "${baseName}_page${pageNum}.jpg"
            val file     = File(dir, fileName)
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, quality.jpegQuality, it) }
            bmp.recycle()
            outFiles += file
        }
    } finally {
        rdr.close()
        fd.close()
    }

    onProg(100, "Done!")
    return outFiles
}

// ── Share all JPGs as a ZIP ────────────────────────────────────────────────────

internal fun shareAllAsZip(context: Context, files: List<File>, baseName: String) {
    if (files.isEmpty()) return
    try {
        if (files.size == 1) {
            // Single image — share directly
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.provider", files.first()
            )
            context.startActivity(
                android.content.Intent.createChooser(
                    android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "image/jpeg"
                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "Share JPG"
                )
            )
        } else {
            // Multiple images — zip them
            val zipFile = File(context.cacheDir, "${baseName}_pages.zip")
            ZipOutputStream(zipFile.outputStream()).use { zos ->
                files.forEach { f ->
                    zos.putNextEntry(ZipEntry(f.name))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            val zipUri = FileProvider.getUriForFile(
                context, "${context.packageName}.provider", zipFile
            )
            context.startActivity(
                android.content.Intent.createChooser(
                    android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(android.content.Intent.EXTRA_STREAM, zipUri)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "Share JPG images"
                )
            )
        }
    } catch (_: Exception) {}
}

// ── Helpers ───────────────────────────────────────────────────────────────────

internal fun jpgFormatSize(kb: Long): String = when {
    kb >= 1024 -> "%.1f MB".format(kb / 1024f)
    else       -> "$kb KB"
}

// ── Save JPGs to device gallery (Pictures/PDFMaker) ───────────────────────────

internal fun saveJpgsToGallery(context: Context, files: List<File>) {
    val resolver = context.contentResolver
    files.forEach { file ->
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                // Android 10+ — insert via MediaStore (no WRITE_EXTERNAL_STORAGE needed)
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, file.name)
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                        "${android.os.Environment.DIRECTORY_PICTURES}/PDFMaker")
                    put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                ) ?: return@forEach
                resolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().copyTo(out)
                }
                values.clear()
                values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                // Android 9 and below — copy to Pictures directory + broadcast
                val picturesDir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_PICTURES
                )
                val dest = java.io.File(java.io.File(picturesDir, "PDFMaker").also { it.mkdirs() }, file.name)
                file.copyTo(dest, overwrite = true)
                // Notify gallery
                android.media.MediaScannerConnection.scanFile(
                    context, arrayOf(dest.absolutePath), arrayOf("image/jpeg"), null
                )
            }
        } catch (_: Exception) {}
    }
}

