package com.example.pdfmaker

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ConvertResultScreen(
    filePath: String,
    fileName: String,
    onDone: () -> Unit,
    onOpenFile: (PdfFile) -> Unit,
) {
    val file = File(filePath)
    val sizeKb = file.length() / 1024

    Column(
        Modifier
            .fillMaxSize()
            .background(currentBg)
            .statusBarsPadding()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Success circle
        Box(
            Modifier.size(100.dp).clip(CircleShape).background(Color(0xFF1A3020)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.CheckCircle,
                null,
                tint = Color(0xFF4CAF50),
                modifier = Modifier.size(52.dp),
            )
        }

        Spacer(Modifier.height(24.dp))

        Text(
            "PDF Created!",
            color = currentText,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )

        Spacer(Modifier.height(12.dp))

        Text(
            fileName,
            color = currentTextSecond,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            if (sizeKb >= 1024) "%.1f MB".format(sizeKb / 1024f) else "$sizeKb KB",
            color = currentTextSecond,
            fontSize = 13.sp,
        )

        Spacer(Modifier.height(36.dp))

        // Open button
        Button(
            onClick = {
                val pf =
                    PdfFile(
                        name = fileName.removeSuffix(".pdf"),
                        filePath = filePath,
                        size = if (sizeKb >= 1024) "%.1f MB".format(sizeKb / 1024f) else "$sizeKb KB",
                        date = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date()),
                        pageCount = 1,
                        lastModified = file.lastModified(),
                    )
                onOpenFile(pf)
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(Icons.Default.OpenInBrowser, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Open PDF", fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }

        Spacer(Modifier.height(12.dp))

        OutlinedButton(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, currentTextSecond.copy(alpha = 0.4f)),
        ) {
            Icon(Icons.Default.Home, null, tint = currentTextSecond, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Back to Home", color = currentTextSecond, fontWeight = FontWeight.Medium, fontSize = 15.sp)
        }
    }
}
