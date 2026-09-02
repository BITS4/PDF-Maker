package com.example.pdfmaker

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
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
internal fun JpgTopBar(
    background: Color,
    primaryText: Color,
    secondaryText: Color,
    showChange: Boolean,
    onBack: () -> Unit,
    onChange: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(background).padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = primaryText)
        }
        Text(
            "PDF to JPG",
            color = primaryText,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        if (showChange) {
            TextButton(onClick = onChange) { Text("Change", color = secondaryText) }
        }
    }
}

@Composable
internal fun JpgPickerPanel(
    primaryText: Color,
    secondaryText: Color,
    onPick: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(100.dp)
                .clip(CircleShape)
                .background(Color(0xFF1E1E30))
                .border(2.dp, Color(0xFFFF7043).copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Image,
                contentDescription = null,
                tint = Color(0xFFFF7043),
                modifier = Modifier.size(44.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "Select a PDF to convert",
            color = primaryText,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Each page will be saved as a JPG image",
            color = secondaryText,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(36.dp))
        Button(
            onClick = onPick,
            modifier = Modifier.fillMaxWidth(0.7f).height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF7043)),
        ) {
            Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Choose PDF", fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}

@Composable
internal fun JpgConvertingPanel(
    progress: Int,
    progressText: String,
    primaryText: Color,
    secondaryText: Color,
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        JpgSpinner(progress = progress, color = Color(0xFFFF7043))
        Spacer(Modifier.height(28.dp))
        Text(
            "Converting…",
            color = primaryText,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(progressText, color = secondaryText, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            "$progress%",
            color = Color(0xFFFF7043),
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(20.dp))
        LinearProgressIndicator(
            progress = { progress.coerceIn(0, 100) / 100f },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = Color(0xFFFF7043),
            trackColor = Color(0xFF2A2A40),
        )
    }
}

@Composable
internal fun JpgErrorPanel(
    message: String,
    primaryText: Color,
    secondaryText: Color,
    onRetry: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(88.dp).clip(CircleShape).background(Color(0xFF2A1010)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = Color(0xFFF44336),
                modifier = Modifier.size(44.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            "Conversion Failed",
            color = primaryText,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            color = secondaryText,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(0.6f).height(50.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
        ) {
            Text("Try Again", fontWeight = FontWeight.Bold)
        }
    }
}
