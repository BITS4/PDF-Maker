package com.example.pdfmaker

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
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

@Composable
internal fun MergePdfProgressContent(
    progress: Int,
    progressText: String,
    colors: MergePdfColors,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MergeSpinner(progress = progress, color = colors.action)
        Spacer(Modifier.height(28.dp))
        Text(
            text = "Merging…",
            color = colors.primaryText,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(progressText, color = colors.secondaryText, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "$progress%",
            color = colors.action,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(20.dp))
        LinearProgressIndicator(
            progress = { progress / 100f },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
            color = colors.action,
            trackColor = Color(0xFF2A2A40),
        )
    }
}

@Composable
internal fun MergePdfDoneContent(
    file: File?,
    shareMessage: String?,
    summary: MergeSummary,
    colors: MergePdfColors,
    callbacks: MergePdfCallbacks,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1A3020)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = Color(0xFF4CAF50),
                modifier = Modifier.size(52.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = "Merged Successfully!",
            color = colors.primaryText,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = file?.name ?: "Merged PDF",
            color = colors.secondaryText,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(24.dp))
        MergeResultStats(file, summary, colors)
        Spacer(Modifier.height(32.dp))
        MergeResultActions(
            enabled = file != null,
            colors = colors,
            onOpen = callbacks.onOpen,
            onShare = callbacks.onShare,
        )
        if (shareMessage != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = shareMessage,
                color = Color(0xFFEF5350),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = callbacks.onReset,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, colors.secondaryText.copy(alpha = 0.4f)),
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = colors.secondaryText,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Merge Another",
                color = colors.secondaryText,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
            )
        }
    }
}

@Composable
private fun MergeResultStats(
    file: File?,
    summary: MergeSummary,
    colors: MergePdfColors,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(colors.card)
                .padding(20.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        StatColumn("Files merged", "${summary.fileCount}", colors.secondaryText, colors.primaryText)
        ResultDivider()
        StatColumn("Total pages", "${summary.pageCount}", colors.secondaryText, colors.primaryText)
        ResultDivider()
        StatColumn(
            "File size",
            mergeFormatSize((file?.length() ?: 0L) / 1024L),
            colors.secondaryText,
            colors.primaryText,
        )
    }
}

@Composable
private fun ResultDivider() {
    VerticalDivider(
        modifier = Modifier.height(40.dp),
        color = Color(0xFF2A2A3A),
    )
}

@Composable
private fun MergeResultActions(
    enabled: Boolean,
    colors: MergePdfColors,
    onOpen: () -> Unit,
    onShare: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Button(
            onClick = onOpen,
            enabled = enabled,
            modifier = Modifier.weight(1f).height(54.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text("Open", fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
        Button(
            onClick = onShare,
            enabled = enabled,
            modifier = Modifier.weight(1f).height(54.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = colors.action),
        ) {
            Icon(
                imageVector = Icons.Default.Share,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text("Share", fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}

@Composable
internal fun MergePdfErrorContent(
    message: String,
    colors: MergePdfColors,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(90.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF2A1010)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = Color(0xFFF44336),
                modifier = Modifier.size(46.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            text = "Merge Failed",
            color = colors.primaryText,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            color = colors.secondaryText,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(0.6f).height(50.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
        ) {
            Text("Try Again", fontWeight = FontWeight.Bold)
        }
    }
}
