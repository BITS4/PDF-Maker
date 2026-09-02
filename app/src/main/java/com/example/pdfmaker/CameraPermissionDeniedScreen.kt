package com.example.pdfmaker

import androidx.activity.compose.LocalActivity
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.NoPhotography
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
internal fun CameraPermissionDeniedScreen(
    onBack: () -> Unit,
    status: SmartScanPermissionStatus = SmartScanPermissionStatus.SETTINGS_REQUIRED,
    onRequestPermission: () -> Unit = {},
    onOpenSettings: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val content = cameraPermissionContent(status)
    Box(Modifier.fillMaxSize().background(currentBg)) {
        Column(Modifier.fillMaxSize()) {
            CameraPermissionHeader(onBack)
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CameraPermissionIcon(status)
                Spacer(Modifier.height(28.dp))
                Text(
                    text = content.title,
                    color = currentText,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = content.description,
                    color = currentTextSecond,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 22.sp,
                )
                Spacer(Modifier.height(28.dp))
                CameraPermissionAction(
                    status = status,
                    onRequestPermission = onRequestPermission,
                    onOpenSettings =
                        onOpenSettings ?: {
                            openApplicationDetailsSettings(context, activity)
                        },
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onBack,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Text("Go Back")
                }
            }
        }
    }
}

@Composable
private fun CameraPermissionHeader(onBack: () -> Unit) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(currentCard)
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Go back", tint = currentText)
        }
        Text(
            text = "Camera Access",
            color = currentText,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun CameraPermissionIcon(status: SmartScanPermissionStatus) {
    Box(
        modifier = Modifier.size(100.dp).clip(CircleShape).background(Color(0xFF2A1A1A)),
        contentAlignment = Alignment.Center,
    ) {
        if (status == SmartScanPermissionStatus.REQUESTING) {
            CircularProgressIndicator(color = AccentBlue, modifier = Modifier.size(48.dp))
        } else {
            Icon(
                imageVector = Icons.Default.NoPhotography,
                contentDescription = null,
                tint = BadgeRed,
                modifier = Modifier.size(52.dp),
            )
        }
    }
}

@Composable
private fun CameraPermissionAction(
    status: SmartScanPermissionStatus,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    when (status) {
        SmartScanPermissionStatus.RATIONALE_REQUIRED -> {
            PermissionButton(
                label = "Allow Camera",
                onClick = onRequestPermission,
                includeSettingsIcon = false,
            )
        }

        SmartScanPermissionStatus.SETTINGS_REQUIRED -> {
            PermissionButton(
                label = "Open App Settings",
                onClick = onOpenSettings,
                includeSettingsIcon = true,
            )
        }

        SmartScanPermissionStatus.REQUESTING,
        SmartScanPermissionStatus.GRANTED,
        -> {
            Unit
        }
    }
}

@Composable
private fun PermissionButton(
    label: String,
    onClick: () -> Unit,
    includeSettingsIcon: Boolean,
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
        if (includeSettingsIcon) {
            Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

private data class CameraPermissionContent(
    val title: String,
    val description: String,
)

private fun cameraPermissionContent(status: SmartScanPermissionStatus): CameraPermissionContent =
    when (status) {
        SmartScanPermissionStatus.REQUESTING -> {
            CameraPermissionContent(
                title = "Requesting Camera Access",
                description = "Confirm the Android permission prompt to scan documents and ID cards.",
            )
        }

        SmartScanPermissionStatus.RATIONALE_REQUIRED -> {
            CameraPermissionContent(
                title = "Camera Permission Needed",
                description =
                    "Smart Scan only uses the camera while this screen is open. " +
                        "Allow access to scan documents and ID cards.",
            )
        }

        SmartScanPermissionStatus.SETTINGS_REQUIRED -> {
            CameraPermissionContent(
                title = "Enable Camera in Settings",
                description =
                    "Camera access is blocked for PDF Maker. Open App Settings, choose Permissions, " +
                        "and allow Camera to use Smart Scan.",
            )
        }

        SmartScanPermissionStatus.GRANTED -> {
            CameraPermissionContent(
                title = "Camera Ready",
                description = "Camera access has been granted.",
            )
        }
    }
