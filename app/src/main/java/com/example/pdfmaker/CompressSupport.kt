package com.example.pdfmaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File

enum class CompressLevel(
    val label      : String,
    val sub        : String,
    val jpegQuality: Int,    // 0-100
    val maxDimPx   : Int,    // max width/height of each rendered page
    val icon       : androidx.compose.ui.graphics.vector.ImageVector,
    val color      : Color
) {
    LOW(
        label       = "Low",
        sub         = "Best quality, smaller reduction",
        jpegQuality = 82,
        maxDimPx    = 1600,
        icon        = Icons.Default.HighQuality,
        color       = Color(0xFF4CAF50)
    ),
    MEDIUM(
        label       = "Medium",
        sub         = "Balanced quality & size",
        jpegQuality = 60,
        maxDimPx    = 1200,
        icon        = Icons.Default.Tune,
        color       = Color(0xFFFFC107)
    ),
    HIGH(
        label       = "High",
        sub         = "Smallest file, reduced quality",
        jpegQuality = 35,
        maxDimPx    = 800,
        icon        = Icons.Default.Settings,
        color       = Color(0xFFF44336)
    )
}

// ── Animated compressing ring ──────────────────────────────────────────────────

@Composable
internal fun CompressingAnimation(progress: Int, accent: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "spin")
    val angle by infiniteTransition.animateFloat(
        initialValue   = 0f,
        targetValue    = 360f,
        animationSpec  = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing)
        ),
        label = "angle"
    )
    androidx.compose.foundation.Canvas(Modifier.size(120.dp)) {
        // Background ring
        drawArc(
            color       = Color(0xFF2A2A40),
            startAngle  = 0f,
            sweepAngle  = 360f,
            useCenter   = false,
            style       = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round)
        )
        // Progress arc
        drawArc(
            color       = accent,
            startAngle  = angle - 90f,
            sweepAngle  = (progress * 3.6f).coerceAtLeast(10f),
            useCenter   = false,
            style       = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

// ── Core compression logic ────────────────────────────────────────────────────

internal fun compressPdf(
    context  : Context,
    uri      : Uri,
    level    : CompressLevel,
    baseName : String,
    onProg   : (Int) -> Unit
): File? {
    return try {
        val fd  = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
        val rdr = PdfRenderer(fd)
        val count = rdr.pageCount
        if (count == 0) { rdr.close(); fd.close(); return null }

        val pdfDoc = PdfDocument()

        for (i in 0 until count) {
            onProg((i * 90) / count)

            val page = rdr.openPage(i)
            // Scale page to maxDimPx on the longest side
            val scale = level.maxDimPx.toFloat() / maxOf(page.width, page.height).coerceAtLeast(1)
            val w = (page.width  * scale).toInt().coerceAtLeast(1)
            val h = (page.height * scale).toInt().coerceAtLeast(1)

            // Render page to bitmap
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(bmp).drawColor(android.graphics.Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()

            // Re-encode as JPEG to reduce size
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, level.jpegQuality, bos)
            val jpegBytes = bos.toByteArray()
            bmp.recycle()

            // Decode JPEG back to bitmap for PdfDocument
            val jpegBmp = android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)

            val info   = PdfDocument.PageInfo.Builder(w, h, i + 1).create()
            val pdfPg  = pdfDoc.startPage(info)
            pdfPg.canvas.drawBitmap(jpegBmp, 0f, 0f, null)
            pdfDoc.finishPage(pdfPg)
            jpegBmp.recycle()
        }

        rdr.close()
        fd.close()

        onProg(95)

        val outName = "compressed_${baseName}_${level.label.lowercase()}.pdf"
        val dir     = getPdfMakerDir(context)
        val outFile = File(dir, outName)
        outFile.outputStream().use { pdfDoc.writeTo(it) }
        pdfDoc.close()

        onProg(100)
        outFile
    } catch (_: Exception) { null }
}

// ── Share compressed file ─────────────────────────────────────────────────────

internal fun shareCompressedFile(context: Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(intent, "Share compressed PDF"))
    } catch (_: Exception) {}
}

// ── Helpers ───────────────────────────────────────────────────────────────────

internal fun formatSize(kb: Long): String = when {
    kb >= 1024 -> "%.1f MB".format(kb / 1024f)
    else       -> "$kb KB"
}

