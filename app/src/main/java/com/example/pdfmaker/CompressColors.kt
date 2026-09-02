package com.example.pdfmaker

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

internal data class CompressColors(
    val background: Color = Color(0xFF0D0D16),
    val bar: Color = Color(0xFF1A1A2A),
    val card: Color = Color(0xFF14141F),
    val primaryText: Color = Color.White,
    val secondaryText: Color = Color(0xFF9999BB),
    val accent: Color = AccentBlue,
)

internal val CompressLevel.title: String
    get() =
        when (this) {
            CompressLevel.LOW -> "Low"
            CompressLevel.MEDIUM -> "Medium"
            CompressLevel.HIGH -> "High"
        }

internal val CompressLevel.description: String
    get() =
        when (this) {
            CompressLevel.LOW -> "Best quality, smaller reduction"
            CompressLevel.MEDIUM -> "Balanced quality and size"
            CompressLevel.HIGH -> "Smallest file, reduced quality"
        }

internal val CompressLevel.color: Color
    get() =
        when (this) {
            CompressLevel.LOW -> Color(0xFF4CAF50)
            CompressLevel.MEDIUM -> Color(0xFFFFC107)
            CompressLevel.HIGH -> Color(0xFFF44336)
        }

internal val CompressLevel.icon: ImageVector
    get() =
        when (this) {
            CompressLevel.LOW -> Icons.Default.HighQuality
            CompressLevel.MEDIUM -> Icons.Default.Tune
            CompressLevel.HIGH -> Icons.Default.Settings
        }

@Composable
internal fun CompressingAnimation(
    progress: Int,
    accent: Color,
) {
    val transition = rememberInfiniteTransition(label = "compression-spin")
    val angle by
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(animation = tween(1_400, easing = LinearEasing)),
            label = "compression-angle",
        )
    Canvas(Modifier.size(120.dp)) {
        drawArc(
            color = Color(0xFF2A2A40),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round),
        )
        drawArc(
            color = accent,
            startAngle = angle - 90f,
            sweepAngle = (CompressionPolicy.clampProgress(progress) * 3.6f).coerceAtLeast(10f),
            useCenter = false,
            style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}
