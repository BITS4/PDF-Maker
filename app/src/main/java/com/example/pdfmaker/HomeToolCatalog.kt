package com.example.pdfmaker

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource

// ── Tool helpers ──────────────────────────────────────────────────────────────

@Composable
fun toolName(name: String): String =
    when (name) {
        "image_to_pdf" -> stringResource(R.string.tool_image_to_pdf)
        "smart_scan" -> stringResource(R.string.tool_smart_scan)
        "import_pdf" -> stringResource(R.string.tool_import_pdf)
        "compress" -> stringResource(R.string.tool_compress)
        "pdf_to_jpg" -> stringResource(R.string.tool_pdf_to_jpg)
        "merge_pdf" -> stringResource(R.string.tool_merge_pdf)
        "docx_to_pdf" -> stringResource(R.string.tool_docx_to_pdf)
        "more" -> stringResource(R.string.tool_more)
        else -> name
    }

fun toolIcon(key: String): ImageVector =
    when (key) {
        "image_to_pdf" -> Icons.Default.Image
        "smart_scan" -> Icons.Default.DocumentScanner
        "import_pdf" -> Icons.Default.Folder
        "compress" -> Icons.Default.Compress
        "pdf_to_jpg" -> Icons.Default.Photo
        "merge_pdf" -> Icons.AutoMirrored.Filled.CallMerge
        "docx_to_pdf" -> Icons.Default.Description
        "more" -> Icons.Default.Apps
        else -> Icons.Default.PictureAsPdf
    }

fun toolIconTint(key: String): Color =
    when (key) {
        "image_to_pdf" -> Color(0xFFEF5350)
        "smart_scan" -> Color(0xFF4F8EF7)
        "import_pdf" -> Color(0xFFFFA726)
        "compress" -> Color(0xFFEF5350)
        "pdf_to_jpg" -> Color(0xFFFFA726)
        "merge_pdf" -> Color(0xFFFFA726)
        "docx_to_pdf" -> Color(0xFF4F8EF7)
        "more" -> Color(0xFF26C6A0)
        else -> Color(0xFF4F8EF7)
    }

fun toolIconBg(key: String): Color =
    when (key) {
        "image_to_pdf" -> Color(0xFF2A1010)
        "smart_scan" -> Color(0xFF1A2340)
        "import_pdf" -> Color(0xFF2A1E0A)
        "compress" -> Color(0xFF2A1010)
        "pdf_to_jpg" -> Color(0xFF2A1E0A)
        "merge_pdf" -> Color(0xFF2A1E0A)
        "docx_to_pdf" -> Color(0xFF1A2340)
        "more" -> Color(0xFF0F2420)
        else -> Color(0xFF1A2340)
    }

val toolKeys =
    listOf(
        "image_to_pdf",
        "smart_scan",
        "import_pdf",
        "compress",
        "pdf_to_jpg",
        "merge_pdf",
        "docx_to_pdf",
        "more",
    )
