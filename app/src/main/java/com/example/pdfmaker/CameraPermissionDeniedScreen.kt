package com.tajapps.pdfmaker

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CameraPermissionDeniedScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    Box(Modifier.fillMaxSize().background(currentBg)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().background(currentCard).statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = currentText)
                }
                Text("Camera Access", color = currentText, fontSize = 18.sp,
                    fontWeight = FontWeight.Bold)
            }

            Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(Modifier.size(100.dp).clip(CircleShape).background(Color(0xFF2A1A1A)),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.NoPhotography, null, tint = BadgeRed,
                        modifier = Modifier.size(52.dp))
                }
                Spacer(Modifier.height(28.dp))
                Text("Camera Permission Needed", color = currentText, fontSize = 20.sp,
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Smart Scan needs camera access to scan documents and ID cards.\n\n" +
                    "Please grant the Camera permission in your phone's Settings.",
                    color = currentTextSecond, fontSize = 14.sp,
                    textAlign = TextAlign.Center, lineHeight = 22.sp
                )
                Spacer(Modifier.height(32.dp))

                // Steps card
                Surface(color = currentCard, shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf(
                            "1" to "Open your phone Settings",
                            "2" to "Go to Apps → PDF Maker",
                            "3" to "Tap Permissions → Camera",
                            "4" to "Select \"Allow only while using the app\""
                        ).forEach { (step, text) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(28.dp).clip(CircleShape)
                                        .background(AccentBlue),
                                    contentAlignment = Alignment.Center) {
                                    Text(step, color = Color.White, fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.width(12.dp))
                                Text(text, color = currentText, fontSize = 13.sp)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(28.dp))

                Button(
                    onClick = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        context.startActivity(intent)
                    },
                    colors   = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                    shape    = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Icon(Icons.Default.Settings, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Open App Settings", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick  = onBack,
                    shape    = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text("Go Back") }
            }
        }
    }
}
