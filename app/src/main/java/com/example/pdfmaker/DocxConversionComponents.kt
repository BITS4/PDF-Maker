package com.example.pdfmaker

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Helper composables ────────────────────────────────────────────────────────

@Composable
internal fun FeatureRow(
    icon: ImageVector,
    text: String,
    tint: Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, color = Color(0xFF9999BB), fontSize = 13.sp)
    }
}

@Composable
internal fun DocxSpinner(
    progress: Int,
    color: Color,
) {
    val inf = rememberInfiniteTransition(label = "spin")
    val angle by inf.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "angle",
    )
    Canvas(Modifier.size(110.dp)) {
        drawArc(
            Color(0xFF2A2A40),
            0f,
            360f,
            false,
            style = Stroke(10.dp.toPx(), cap = StrokeCap.Round),
        )
        drawArc(
            color,
            angle - 90f,
            (progress * 3.6f).coerceAtLeast(10f),
            false,
            style = Stroke(10.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}
