package com.example.pdfmaker

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun CompressPreparingContent(
    colors: CompressColors,
    onCancel: () -> Unit,
) {
    CompressionWorkLayout(
        title = "Preparing PDF…",
        detail = "Checking the selected document and available storage.",
        colors = colors,
        onCancel = onCancel,
    ) {
        CircularProgressIndicator(color = colors.accent, modifier = Modifier.size(72.dp))
    }
}

@Composable
internal fun CompressProgressContent(
    progress: Int,
    colors: CompressColors,
    onCancel: () -> Unit,
) {
    CompressionWorkLayout(
        title = "Compressing…",
        detail = "$progress%",
        colors = colors,
        onCancel = onCancel,
    ) {
        CompressingAnimation(progress = progress, accent = colors.accent)
        Spacer(Modifier.height(20.dp))
        LinearProgressIndicator(
            progress = { progress / 100f },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = colors.accent,
            trackColor = Color(0xFF2A2A40),
        )
    }
}

@Composable
private fun CompressionWorkLayout(
    title: String,
    detail: String,
    colors: CompressColors,
    onCancel: () -> Unit,
    indicator: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        indicator()
        Spacer(Modifier.height(28.dp))
        Text(title, color = colors.primaryText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            text = detail,
            color = colors.accent,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        OutlinedButton(onClick = onCancel) {
            Text("Cancel", color = colors.secondaryText)
        }
    }
}

@Composable
internal fun CompressDoneContent(
    state: CompressUiState,
    actions: CompressActions,
    colors: CompressColors,
) {
    val input = state.input ?: return
    val result = state.result ?: return
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.size(100.dp).clip(CircleShape).background(Color(0xFF1A3020)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = Color(0xFF4CAF50),
                modifier = Modifier.size(52.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = "Compression Complete!",
            color = colors.primaryText,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(24.dp))
        CompressionSizeComparison(input.sizeBytes, result.sizeBytes, colors)
        state.savedPercent?.let { saved ->
            Spacer(Modifier.height(12.dp))
            Box(
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF1A3020))
                        .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Text(
                    text = "Saved $saved%",
                    color = Color(0xFF4CAF50),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Spacer(Modifier.height(36.dp))
        Button(
            onClick = actions::shareResult,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
        ) {
            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(8.dp))
            Text("Share", fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = actions::reset,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, colors.secondaryText.copy(alpha = 0.4f)),
        ) {
            Icon(Icons.Default.Add, contentDescription = null, tint = colors.secondaryText)
            Spacer(Modifier.size(8.dp))
            Text("Compress Another", color = colors.secondaryText, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun CompressionSizeComparison(
    originalBytes: Long,
    compressedBytes: Long,
    colors: CompressColors,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(colors.card)
                .padding(20.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SizeColumn("Original", originalBytes, colors.secondaryText, colors.primaryText)
        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = colors.accent)
        SizeColumn("Compressed", compressedBytes, colors.secondaryText, Color(0xFF4CAF50))
    }
}

@Composable
private fun SizeColumn(
    label: String,
    sizeBytes: Long,
    labelColor: Color,
    valueColor: Color,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = labelColor, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (sizeBytes > 0L) FileRepository.formatSize(sizeBytes) else "Unknown",
            color = valueColor,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
