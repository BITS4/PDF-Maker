package com.example.pdfmaker

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File

@Composable
internal fun ViewerUnsupportedView(
    file: PdfFile,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val extension = file.name.substringAfterLast('.', "?").uppercase()
    ViewerStatusView(
        modifier = modifier,
        icon = {
            Icon(
                Icons.AutoMirrored.Filled.InsertDriveFile,
                null,
                tint = TextSecond,
                modifier = Modifier.size(46.dp),
            )
        },
        title = ".$extension files can't be previewed",
        description = "Open with another app to view this file.",
        actionLabel = "Open with…",
        actionColor = AccentBlue,
        onAction = { openWithExternalApp(context, file) },
    )
}

@Composable
internal fun ViewerExternalOpenView(
    file: PdfFile,
    kind: ViewerFileKind,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val (appHint, tint) =
        when (kind) {
            ViewerFileKind.DOCX -> "Word or Google Docs" to Color(0xFF1565C0)
            ViewerFileKind.XLSX -> "Excel or Google Sheets" to Color(0xFF2E7D32)
            ViewerFileKind.PPTX -> "PowerPoint or Google Slides" to Color(0xFFE65100)
            else -> "a compatible app" to Color(0xFF555566)
        }
    ViewerStatusView(
        modifier = modifier,
        icon = {
            Icon(
                Icons.AutoMirrored.Filled.OpenInNew,
                null,
                tint = tint,
                modifier = Modifier.size(46.dp),
            )
        },
        title = "Opening in $appHint…",
        description = "This file type is opened in a dedicated app\nfor accurate formatting and layout.",
        actionLabel = "Open with…",
        actionColor = tint,
        onAction = { openWithExternalApp(context, file) },
    )
}

@Composable
internal fun ViewerErrorView(
    message: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.ErrorOutline, null, tint = BadgeRed, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text(message, color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onBack,
            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
        ) {
            Text("Go Back")
        }
    }
}

@Composable
private fun ViewerStatusView(
    modifier: Modifier,
    icon: @Composable () -> Unit,
    title: String,
    description: String,
    actionLabel: String,
    actionColor: Color,
    onAction: () -> Unit,
) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(90.dp).clip(CircleShape).background(Color(0xFF252535)),
            contentAlignment = Alignment.Center,
        ) {
            icon()
        }
        Spacer(Modifier.height(16.dp))
        Text(
            title,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(description, color = TextSecond, fontSize = 13.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onAction,
            colors = ButtonDefaults.buttonColors(containerColor = actionColor),
            shape = RoundedCornerShape(12.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(actionLabel, fontWeight = FontWeight.Bold)
        }
    }
}

internal fun openWithExternalApp(
    context: Context,
    file: PdfFile,
): Boolean =
    try {
        val source = File(file.filePath)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", source)
        val kind = detectViewerFileKind(file.filePath, file.name)
        val intent =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, viewerMimeType(kind))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        context.startActivity(Intent.createChooser(intent, "Open with"))
        true
    } catch (ignoredError: Exception) {
        Log.w("PdfViewer", "No application could open ${file.name}", ignoredError)
        false
    }

internal fun viewerErrorMessage(error: Exception): String =
    when (error) {
        is SecurityException -> "File access was denied. Choose the document again."
        is java.io.FileNotFoundException -> "The document is no longer available."
        else -> "The document could not be rendered."
    }
