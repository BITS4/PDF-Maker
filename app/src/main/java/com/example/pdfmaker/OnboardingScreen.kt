package com.tajapps.pdfmaker

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class OnboardPage(
    val icon    : ImageVector,
    val iconBg  : Color,
    val iconTint: Color,
    val title   : String,
    val subtitle: String
)

private val pages = listOf(
    OnboardPage(
        Icons.Default.PictureAsPdf, Color(0xFFB71C1C), Color.White,
        "All Your PDFs in One Place",
        "Create, edit, compress, merge and organise your PDFs — all offline, no cloud required."
    ),
    OnboardPage(
        Icons.Default.DocumentScanner, Color(0xFF1565C0), Color.White,
        "Smart Scan & OCR",
        "Scan documents with your camera and extract text instantly using on-device AI — no internet needed."
    ),
    OnboardPage(
        Icons.Default.Lock, Color(0xFF2E7D32), Color.White,
        "Secure Your Documents",
        "Password-protect any PDF with AES-256 encryption. Only you can open it."
    ),
    OnboardPage(
        Icons.Default.Edit, Color(0xFF6A1B9A), Color.White,
        "Annotate & Sign",
        "Draw on pages, add text stamps, and sign documents directly on your phone."
    ),
    OnboardPage(
        Icons.Default.FolderOpen, Color(0xFFE65100), Color.White,
        "Open From Anywhere",
        "Tap any PDF in your file manager or email and it opens straight in PDF Maker."
    ),
)

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    var current by remember { mutableIntStateOf(0) }

    Box(Modifier.fillMaxSize().background(currentBg)) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Skip button
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                if (current < pages.lastIndex) {
                    TextButton(
                        onClick  = onDone,
                        modifier = Modifier.align(Alignment.CenterEnd)
                    ) {
                        Text("Skip", color = currentTextSecond, fontSize = 14.sp)
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // Page content — animated slide
            AnimatedVisibility(
                visible = true,
                enter   = fadeIn() + slideInHorizontally { it / 3 },
                exit    = fadeOut() + slideOutHorizontally { -it / 3 }
            ) {
                val page = pages[current]
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier            = Modifier.padding(horizontal = 32.dp)
                ) {
                    // Icon circle
                    Box(
                        Modifier.size(120.dp).clip(CircleShape).background(page.iconBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(page.icon, null, tint = page.iconTint,
                            modifier = Modifier.size(60.dp))
                    }
                    Spacer(Modifier.height(36.dp))
                    Text(
                        page.title,
                        color      = currentText,
                        fontSize   = 24.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign  = TextAlign.Center,
                        lineHeight = 30.sp
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        page.subtitle,
                        color     = currentTextSecond,
                        fontSize  = 15.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 22.sp
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // Dot indicators
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 32.dp)
            ) {
                pages.indices.forEach { idx ->
                    val w by animateDpAsState(if (idx == current) 24.dp else 8.dp, label = "dot")
                    Box(
                        Modifier.height(8.dp).width(w).clip(CircleShape)
                            .background(if (idx == current) AccentBlue else Color(0xFF3A3A4A))
                    )
                }
            }

            // Buttons
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (current > 0) {
                    OutlinedButton(
                        onClick  = { current-- },
                        shape    = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f).height(52.dp)
                    ) {
                        Text("Back", fontSize = 15.sp)
                    }
                }
                Button(
                    onClick = { if (current < pages.lastIndex) current++ else onDone() },
                    colors  = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                    shape   = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(if (current > 0) 1f else Float.MAX_VALUE)
                        .height(52.dp)
                ) {
                    Text(
                        if (current < pages.lastIndex) "Next" else "Get Started",
                        fontSize   = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
