package com.tajapps.pdfmaker

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class EmptyKind { ALL_FILES, PDF, DOCS, IMAGES, SEARCH }

private data class EmptyConfig(
    val icon        : ImageVector,
    val iconBg      : Color,
    val iconTint    : Color,
    val title       : String,
    val subtitle    : String,
    val actionLabel : String? = null
)

private fun emptyConfig(kind: EmptyKind, query: String) = when (kind) {
    EmptyKind.ALL_FILES -> EmptyConfig(
        Icons.Default.FolderOpen, Color(0xFF1A2A3A), Color(0xFF4F8EF7),
        "No files yet",
        "Create your first PDF using the tools above.",
        "Create PDF"
    )
    EmptyKind.PDF -> EmptyConfig(
        Icons.Default.PictureAsPdf, Color(0xFF2A1A1A), Color(0xFFEF5350),
        "No PDF files",
        "PDFs you create or import will appear here."
    )
    EmptyKind.DOCS -> EmptyConfig(
        Icons.Default.Description, Color(0xFF1A1A2A), Color(0xFF4F8EF7),
        "No documents",
        "Word and text documents will appear here."
    )
    EmptyKind.IMAGES -> EmptyConfig(
        Icons.Default.Image, Color(0xFF1A2A1A), Color(0xFF26C6A0),
        "No images",
        "Images you import or scan will appear here."
    )
    EmptyKind.SEARCH -> EmptyConfig(
        Icons.Default.SearchOff, Color(0xFF2A2A1A), Color(0xFFFFA726),
        "No results for \"$query\"",
        "Try a different filename or check the filter."
    )
}

@Composable
fun EmptyState(
    kind    : EmptyKind = EmptyKind.ALL_FILES,
    query   : String    = "",
    onAction: (() -> Unit)? = null
) {
    val cfg = emptyConfig(kind, query)

    Column(
        Modifier.fillMaxWidth().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Layered circles for depth
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.size(120.dp).clip(CircleShape).background(cfg.iconBg.copy(alpha = 0.4f)))
            Box(
                Modifier.size(88.dp).clip(CircleShape).background(cfg.iconBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(cfg.icon, null, tint = cfg.iconTint, modifier = Modifier.size(44.dp))
            }
        }
        Spacer(Modifier.height(28.dp))
        Text(
            cfg.title,
            color      = currentText,
            fontSize   = 18.sp,
            fontWeight = FontWeight.Bold,
            textAlign  = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            cfg.subtitle,
            color      = currentTextSecond,
            fontSize   = 13.sp,
            textAlign  = TextAlign.Center,
            lineHeight = 20.sp
        )
        if (cfg.actionLabel != null && onAction != null) {
            Spacer(Modifier.height(24.dp))
            Button(
                onClick  = onAction,
                colors   = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                shape    = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(cfg.actionLabel, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
