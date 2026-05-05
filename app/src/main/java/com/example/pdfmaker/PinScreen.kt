package com.example.pdfmaker

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ── Pin screen: shown at app launch when PIN is set ───────────────────────────

@Composable
fun PinScreen(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    var entered  by remember { mutableStateOf("") }
    var shaking  by remember { mutableStateOf(false) }
    var attempts by remember { mutableIntStateOf(0) }
    var locked   by remember { mutableStateOf(false) }   // too many wrong attempts
    var lockSecs by remember { mutableIntStateOf(0) }

    val savedPin = SettingsManager.getPin(context)

    // Countdown when locked out
    LaunchedEffect(locked) {
        if (locked) {
            lockSecs = 30
            while (lockSecs > 0) { delay(1000); lockSecs-- }
            locked = false; attempts = 0
        }
    }

    val shakeOffset by animateFloatAsState(
        targetValue    = 0f,
        animationSpec  = if (shaking) keyframes {
            durationMillis = 400
            0f  at 0
            -18f at 50
            18f  at 100
            -14f at 150
            14f  at 200
            -8f  at 280
            8f   at 320
            0f   at 400
        } else keyframes { durationMillis = 1; 0f at 0 },
        label = "shake"
    )

    Box(
        Modifier.fillMaxSize().background(Color(0xFF0D0D16)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.fillMaxWidth().padding(32.dp).offset(x = shakeOffset.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Lock icon
            Box(Modifier.size(80.dp).clip(CircleShape).background(Color(0xFF1A2340)),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Lock, null, tint = AccentBlue, modifier = Modifier.size(42.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text("Enter PIN", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                if (locked) "Too many attempts. Wait ${lockSecs}s"
                else if (attempts > 0) "Wrong PIN (${attempts}/5)"
                else "Enter your PIN to continue",
                color = if (locked || attempts > 0) BadgeRed else Color(0xFF8888AA),
                fontSize = 13.sp
            )
            Spacer(Modifier.height(32.dp))

            // PIN dots
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(4) { i ->
                    val filled = i < entered.length
                    val col by animateColorAsState(
                        if (filled) AccentBlue else Color(0xFF252535), label = "dot")
                    Box(Modifier.size(16.dp).clip(CircleShape).background(col))
                }
            }

            Spacer(Modifier.height(40.dp))

            // Number pad
            val keys = listOf("1","2","3","4","5","6","7","8","9","","0","⌫")
            keys.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp),
                    modifier = Modifier.padding(vertical = 8.dp)) {
                    row.forEach { key ->
                        when (key) {
                            ""  -> Spacer(Modifier.size(72.dp))
                            "⌫" -> PinKey(content = {
                                Icon(Icons.AutoMirrored.Filled.Backspace, null,
                                    tint = Color(0xFF8888AA), modifier = Modifier.size(24.dp))
                            }, enabled = !locked) {
                                if (entered.isNotEmpty()) entered = entered.dropLast(1)
                            }
                            else -> PinKey(content = {
                                Text(key, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                            }, enabled = !locked && entered.length < 4) {
                                entered += key
                                if (entered.length == 4) {
                                    if (entered == savedPin) {
                                        onUnlocked()
                                    } else {
                                        shaking = true
                                        scope.launch {
                                            delay(420); shaking = false
                                        }
                                        attempts++
                                        if (attempts >= 5) locked = true
                                        entered = ""
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PinKey(
    content : @Composable () -> Unit,
    enabled : Boolean = true,
    onClick : () -> Unit
) {
    Box(
        Modifier.size(72.dp)
            .clip(CircleShape)
            .background(if (enabled) Color(0xFF1E1E30) else Color(0xFF14141F))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}
