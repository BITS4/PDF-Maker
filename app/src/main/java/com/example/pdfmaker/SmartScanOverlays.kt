package com.example.pdfmaker

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Flip-card overlay ─────────────────────────────────────────────────────────

@Composable
internal fun FlipCardOverlay(onReady: () -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth(0.80f)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xF0141420))
                .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier.size(72.dp).background(Color(0xFF1E2240), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.CreditCard,
                null,
                tint = Color(0xFFFFD700),
                modifier = Modifier.size(40.dp),
            )
        }
        Text(
            "Front side captured!",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            "Now flip the card over and position the back side inside the frame.",
            color = Color(0xFFCCCCCC),
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = onReady,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFD700)),
            shape = RoundedCornerShape(25.dp),
        ) {
            Text(
                "Ready — scan back side",
                color = Color.Black,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
            )
        }
    }
}

// ── Canvas overlays ───────────────────────────────────────────────────────────

@Composable
fun GridOverlay() {
    Canvas(Modifier.fillMaxSize()) {
        val sw = 0.8.dp.toPx()
        val col = Color.White.copy(alpha = 0.38f)
        drawLine(col, Offset(size.width / 3f, 0f), Offset(size.width / 3f, size.height), sw)
        drawLine(col, Offset(size.width * 2 / 3f, 0f), Offset(size.width * 2 / 3f, size.height), sw)
        drawLine(col, Offset(0f, size.height / 3f), Offset(size.width, size.height / 3f), sw)
        drawLine(col, Offset(0f, size.height * 2 / 3f), Offset(size.width, size.height * 2 / 3f), sw)
    }
}

@Composable
fun DocsCornerOverlay(alpha: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val l = w * 0.1f
        val t = h * 0.14f
        val r = w - l
        val b = h - t
        val len = 38.dp.toPx()
        val sw = 3.dp.toPx()
        val col = Color(0xFF4F8EF7).copy(alpha = alpha)
        drawLine(col, Offset(l, t + len), Offset(l, t), sw)
        drawLine(col, Offset(l, t), Offset(l + len, t), sw)
        drawLine(col, Offset(r - len, t), Offset(r, t), sw)
        drawLine(col, Offset(r, t), Offset(r, t + len), sw)
        drawLine(col, Offset(r, b - len), Offset(r, b), sw)
        drawLine(col, Offset(r, b), Offset(r - len, b), sw)
        drawLine(col, Offset(l, b - len), Offset(l, b), sw)
        drawLine(col, Offset(l, b), Offset(l + len, b), sw)
    }
}

@Composable
fun IdCardCornerOverlay(alpha: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val cW = w * 0.84f
        val cH = cW / 1.586f
        val l = (w - cW) / 2f
        val t = h / 2f - cH / 2f - h * 0.04f
        val r = l + cW
        val b = t + cH
        val len = 30.dp.toPx()
        val sw = 3.5.dp.toPx()
        val col = Color(0xFFFFD700).copy(alpha = alpha)
        drawLine(col, Offset(l, t + len), Offset(l, t), sw)
        drawLine(col, Offset(l, t), Offset(l + len, t), sw)
        drawLine(col, Offset(r - len, t), Offset(r, t), sw)
        drawLine(col, Offset(r, t), Offset(r, t + len), sw)
        drawLine(col, Offset(r, b - len), Offset(r, b), sw)
        drawLine(col, Offset(r, b), Offset(r - len, b), sw)
        drawLine(col, Offset(l, b - len), Offset(l, b), sw)
        drawLine(col, Offset(l, b), Offset(l + len, b), sw)
        val dim = Color.Black.copy(alpha = 0.45f)
        drawRect(
            dim,
            size =
                androidx.compose.ui.geometry
                    .Size(w, t),
        )
        drawRect(
            dim,
            topLeft = Offset(0f, b),
            size =
                androidx.compose.ui.geometry
                    .Size(w, h - b),
        )
        drawRect(
            dim,
            topLeft = Offset(0f, t),
            size =
                androidx.compose.ui.geometry
                    .Size(l, cH),
        )
        drawRect(
            dim,
            topLeft = Offset(r, t),
            size =
                androidx.compose.ui.geometry
                    .Size(w - r, cH),
        )
    }
}

// ── ID card setup overlay ─────────────────────────────────────────────────────

@Composable
fun IdCardSetupOverlay(
    onSelect: (IdCardCaptureSide) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(IdCardCaptureSide.BOTH) }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.62f))
                .clickable { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth(0.88f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White)
                    .clickable {}
                    .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF2F2F2), RoundedCornerShape(10.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(58.dp)
                        .background(Color(0xFFDDE4FF), RoundedCornerShape(6.dp))
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(42.dp)
                            .background(Color(0xFFBBBBBB), RoundedCornerShape(4.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Person,
                            null,
                            tint = Color.White,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Box(
                            Modifier
                                .width(90.dp)
                                .height(7.dp)
                                .background(Color(0xFFAAAAAA), RoundedCornerShape(3.dp)),
                        )
                        Box(
                            Modifier
                                .width(120.dp)
                                .height(7.dp)
                                .background(Color(0xFFAAAAAA), RoundedCornerShape(3.dp)),
                        )
                        Box(
                            Modifier
                                .width(60.dp)
                                .height(7.dp)
                                .background(Color(0xFFAAAAAA), RoundedCornerShape(3.dp)),
                        )
                    }
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .background(Color(0xFFE8F5E9), RoundedCornerShape(6.dp))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    repeat(3) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .background(Color(0xFF9E9E9E), RoundedCornerShape(2.dp)),
                        )
                        if (it < 2) Spacer(Modifier.height(4.dp))
                    }
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier
                            .fillMaxWidth(0.65f)
                            .height(6.dp)
                            .background(Color(0xFF9E9E9E), RoundedCornerShape(2.dp)),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "Scans are placed on a single PDF page. Your data is never shared.",
                color = Color.Gray,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))

            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF0F0F0), RoundedCornerShape(12.dp)),
            ) {
                listOf(
                    IdCardCaptureSide.SINGLE to "Single side",
                    IdCardCaptureSide.BOTH to "Both sides",
                ).forEach { (side, label) ->
                    val isSel = selected == side
                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSel) AccentBlue else Color.Transparent)
                                .clickable { selected = side }
                                .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            color = if (isSel) Color.White else Color.Gray,
                            fontSize = 14.sp,
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Button(
                onClick = { onSelect(selected) },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                shape = RoundedCornerShape(28.dp),
            ) {
                Text(
                    "Scan now",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
            }
        }
    }
}
