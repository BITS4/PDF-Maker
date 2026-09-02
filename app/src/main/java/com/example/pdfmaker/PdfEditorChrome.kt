package com.example.pdfmaker

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

@Composable
internal fun PdfEditorTopBar(
    title: String,
    editMode: PdfEditMode,
    onBack: () -> Unit,
    onResetDoodle: () -> Unit,
    onResetText: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF0D0D16).copy(alpha = 0.92f))
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
        }
        Text(
            title,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.weight(1f).padding(start = 2.dp),
        )
        when (editMode) {
            PdfEditMode.DOODLE -> TextButton(onClick = onResetDoodle) { Text("Reset", color = Color.White) }
            PdfEditMode.TEXT -> TextButton(onClick = onResetText) { Text("Reset", color = Color.White) }
            else -> {
                IconButton(onClick = {}) { Icon(Icons.Default.Edit, null, tint = Color.White) }
                IconButton(onClick = {}) { Icon(Icons.Default.Search, null, tint = Color.White) }
                IconButton(onClick = {}) { Icon(Icons.Default.MoreVert, null, tint = Color.White) }
            }
        }
    }
}

@Composable
internal fun TextEditHint() {
    Box(Modifier.fillMaxWidth().padding(top = 60.dp, start = 16.dp, end = 16.dp)) {
        Box(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xCC333344))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) { Text("Tap anywhere to add text", color = Color.White, fontSize = 14.sp) }
    }
}

@Composable
internal fun AddTextDialog(
    text: String,
    color: Color,
    size: Float,
    position: Offset,
    onText: (String) -> Unit,
    onColor: (Color) -> Unit,
    onSize: (Float) -> Unit,
    onDismiss: () -> Unit,
    onAdd: (LiveText) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF1E1E2E))
                .padding(20.dp),
        ) {
            Text("Add Text", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = text,
                onValueChange = onText,
                label = { Text("Type here…", color = Color(0xFF9999BB)) },
                textStyle =
                    androidx.compose.ui.text
                        .TextStyle(color = Color.White),
                colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentBlue,
                        unfocusedBorderColor = Color(0xFF444455),
                        cursorColor = AccentBlue,
                    ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Size:", color = Color(0xFF9999BB), fontSize = 13.sp)
                Slider(
                    value = size,
                    onValueChange = onSize,
                    valueRange = 8f..60f,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    colors = SliderDefaults.colors(thumbColor = AccentBlue, activeTrackColor = AccentBlue),
                )
                Text("${size.toInt()}", color = Color.White, fontSize = 13.sp)
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(penPalette) { _, option ->
                    val selected = option == color
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(if (selected) Color(0xFFFFD700) else Color.Transparent)
                            .padding(if (selected) 3.dp else 0.dp)
                            .clip(CircleShape)
                            .background(option)
                            .border(1.dp, Color(0xFF333344), CircleShape)
                            .clickable { onColor(option) },
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel", color = Color(0xFF9999BB)) }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (text.isNotBlank()) {
                            onAdd(LiveText(text = text, color = color, sizeSp = size, x = position.x, y = position.y))
                        }
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                ) { Text("Add", color = Color.White) }
            }
        }
    }
}
