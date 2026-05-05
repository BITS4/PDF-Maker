package com.example.pdfmaker

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(onReady: () -> Unit) {
    val scale   = remember { Animatable(0.6f) }
    val alpha   = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        // Animate in
        scale.animateTo(1f,   tween(400))
        alpha.animateTo(1f,   tween(300))
        delay(800)
        onReady()
    }

    Box(
        Modifier.fillMaxSize().background(Color(0xFFC62828)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.scale(scale.value)
        ) {
            // App icon circle
            Box(
                Modifier.size(100.dp).clip(RoundedCornerShape(24.dp))
                    .background(Color.White.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.PictureAsPdf, null,
                    tint = Color.White, modifier = Modifier.size(60.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "PDF Maker",
                color      = Color.White,
                fontSize   = 28.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Create · Edit · Secure",
                color    = Color.White.copy(alpha = 0.75f),
                fontSize = 14.sp
            )
        }
    }
}
