package com.example.pdfmaker

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun CropLoadingSkeleton() {
    val shimmerTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerX by shimmerTransition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerX",
    )
    val scanY by shimmerTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "scanY",
    )

    Box(Modifier.fillMaxSize().background(Color(0xFF0D0D14))) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val cardWidth = width * 0.84f
            val cardHeight = cardWidth / 1.586f
            val cardLeft = (width - cardWidth) / 2f
            val cardTop = height / 2f - cardHeight / 2f - height * 0.04f
            val cardRight = cardLeft + cardWidth
            val cardBottom = cardTop + cardHeight

            drawRoundRect(
                color = Color(0xFF1A1A2E),
                topLeft = Offset(cardLeft, cardTop),
                size = Size(cardWidth, cardHeight),
                cornerRadius = CornerRadius(12f),
            )
            drawRoundRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = 0.08f),
                        Color.White.copy(alpha = 0.15f),
                        Color.White.copy(alpha = 0.08f),
                        Color.Transparent,
                    ),
                    start = Offset(shimmerX * width, cardTop),
                    end = Offset(shimmerX * width + width * 0.5f, cardBottom),
                ),
                topLeft = Offset(cardLeft, cardTop),
                size = Size(cardWidth, cardHeight),
                cornerRadius = CornerRadius(12f),
            )

            val photoSize = cardHeight * 0.55f
            val photoLeft = cardLeft + cardWidth * 0.04f
            val photoTop = cardTop + (cardHeight - photoSize) / 2f
            drawRoundRect(
                color = Color(0xFF252540),
                topLeft = Offset(photoLeft, photoTop),
                size = Size(photoSize * 0.75f, photoSize),
                cornerRadius = CornerRadius(6f),
            )

            val textLeft = photoLeft + photoSize * 0.75f + cardWidth * 0.04f
            val lineHeight = cardHeight * 0.07f
            val lineGap = lineHeight * 1.8f
            listOf(0.38f, 0.30f, 0.24f, 0.36f, 0.20f).forEachIndexed { index, fraction ->
                drawRoundRect(
                    color = Color(0xFF252540),
                    topLeft = Offset(textLeft, photoTop + index * lineGap),
                    size = Size(cardWidth * fraction, lineHeight),
                    cornerRadius = CornerRadius(3f),
                )
            }

            val bracketLength = 22f
            val bracketColor = Color(0xFFFFD700).copy(alpha = 0.6f)
            listOf(
                Triple(cardLeft, cardTop, 1f),
                Triple(cardRight, cardTop, -1f),
                Triple(cardRight, cardBottom, -1f),
                Triple(cardLeft, cardBottom, 1f),
            ).forEachIndexed { index, (x, y, xDirection) ->
                val yDirection = if (index < 2) 1f else -1f
                drawLine(
                    bracketColor,
                    Offset(x, y + yDirection * bracketLength),
                    Offset(x, y),
                    3.5f,
                )
                drawLine(
                    bracketColor,
                    Offset(x, y),
                    Offset(x + xDirection * bracketLength, y),
                    3.5f,
                )
            }

            val scanLineY = cardTop + cardHeight * scanY
            drawLine(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color(0xFFFFD700).copy(alpha = 0.7f),
                        Color(0xFFFFD700).copy(alpha = 0.9f),
                        Color(0xFFFFD700).copy(alpha = 0.7f),
                        Color.Transparent,
                    ),
                    startX = cardLeft,
                    endX = cardRight,
                ),
                start = Offset(cardLeft, scanLineY),
                end = Offset(cardRight, scanLineY),
                strokeWidth = 2f,
            )
        }

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 120.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Preparing image…",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 13.sp,
            )
        }
    }
}
