package com.example.pdfmaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

internal suspend fun compressPdf(
    context: Context,
    uri: Uri,
    level: CompressLevel,
    baseName: String,
    onProg: (Int) -> Unit,
): File {
    val operationContext = currentCoroutineContext()
    return withSafePdfRenderer(context, uri) { renderer ->
        val pageCount = CompressionPolicy.requirePageCount(renderer.pageCount)
        val outputDocument = PdfDocument()
        try {
            repeat(pageCount) { pageIndex ->
                operationContext.ensureActive()
                onProg((pageIndex * 90) / pageCount)
                renderer.openPage(pageIndex).use { page ->
                    val target = CompressionPolicy.renderSize(page.width, page.height, level.maxDimPx)
                    val sourceBitmap = Bitmap.createBitmap(
                        target.width,
                        target.height,
                        Bitmap.Config.ARGB_8888,
                    )
                    try {
                        android.graphics.Canvas(sourceBitmap).drawColor(android.graphics.Color.WHITE)
                        val transform = Matrix().apply {
                            setScale(
                                target.width.toFloat() / page.width.toFloat(),
                                target.height.toFloat() / page.height.toFloat(),
                            )
                        }
                        page.render(
                            sourceBitmap,
                            null,
                            transform,
                            PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                        )
                        operationContext.ensureActive()
                        val jpegBytes = ByteArrayOutputStream().use { encoded ->
                            check(
                                sourceBitmap.compress(
                                    Bitmap.CompressFormat.JPEG,
                                    level.jpegQuality,
                                    BoundedIo.limit(encoded, CompressionPolicy.MAX_ENCODED_PAGE_BYTES) {
                                        operationContext.ensureActive()
                                    },
                                ),
                            ) { "Could not encode compressed PDF page" }
                            encoded.toByteArray()
                        }
                        val jpegBitmap = requireNotNull(
                            android.graphics.BitmapFactory.decodeByteArray(
                                jpegBytes,
                                0,
                                jpegBytes.size,
                            ),
                        ) { "Could not decode compressed PDF page" }
                        try {
                            val pageInfo = PdfDocument.PageInfo.Builder(
                                target.width,
                                target.height,
                                pageIndex + 1,
                            ).create()
                            val outputPage = outputDocument.startPage(pageInfo)
                            outputPage.canvas.drawBitmap(jpegBitmap, 0f, 0f, null)
                            outputDocument.finishPage(outputPage)
                        } finally {
                            jpegBitmap.recycle()
                        }
                    } finally {
                        sourceBitmap.recycle()
                    }
                }
            }

            onProg(95)
            OutputStore.writeUnique(
                directory = getPdfMakerDir(context),
                requestedBaseName = "compressed_${baseName}_${level.label.lowercase()}",
                extension = "pdf",
                beforeCommit = { operationContext.ensureActive() },
            ) { output ->
                operationContext.ensureActive()
                outputDocument.writeTo(BoundedIo.limit(output, CompressionPolicy.MAX_OUTPUT_BYTES) {
                    operationContext.ensureActive()
                })
            }
        } finally {
            outputDocument.close()
        }
    }.also { onProg(100) }
}

// ── Share compressed file ─────────────────────────────────────────────────────

internal fun shareCompressedFile(context: Context, file: File): Result<Unit> =
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(intent, "Share compressed PDF"))
    }

// ── Helpers ───────────────────────────────────────────────────────────────────

internal fun formatSize(kb: Long): String = when {
    kb >= 1024 -> "%.1f MB".format(kb / 1024f)
    else       -> "$kb KB"
}
