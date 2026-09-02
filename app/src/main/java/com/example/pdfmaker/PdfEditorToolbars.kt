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
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
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
internal fun PdfEditorBottomBar(
    editMode: PdfEditMode,
    showConvert: Boolean,
    doodleSize: Float,
    doodleColor: Color,
    canUndo: Boolean,
    canRedo: Boolean,
    onDoodleSize: (Float) -> Unit,
    onDoodleColor: (Color) -> Unit,
    onCancelDoodle: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onCommitDoodle: () -> Unit,
    onCancelText: () -> Unit,
    onAddText: () -> Unit,
    onCommitText: () -> Unit,
    onMode: (PdfEditMode) -> Unit,
    onShowConvert: (Boolean) -> Unit,
    onConvertWord: () -> Unit,
    onConvertPpt: () -> Unit,
    onShare: () -> Unit,
) {
    val background = Color(0xFF1A1A2A)
    val secondary = Color(0xFF9999BB)
    when {
        editMode == PdfEditMode.DOODLE -> DoodleToolbar(
            background, doodleSize, doodleColor, canUndo, canRedo,
            onDoodleSize, onDoodleColor, onCancelDoodle, onUndo, onRedo, onCommitDoodle,
        )
        editMode == PdfEditMode.TEXT -> TextToolbar(background, onCancelText, onAddText, onCommitText)
        editMode == PdfEditMode.EDIT_PICKER -> Row(
            Modifier.fillMaxWidth().background(background).navigationBarsPadding().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CloseButton { onMode(PdfEditMode.NONE) }
            EditorBarItem(Icons.Default.Brush, "Doodle", secondary) { onMode(PdfEditMode.DOODLE) }
            EditorBarItem(Icons.Default.TextFields, "Text", secondary) { onMode(PdfEditMode.TEXT) }
            EditorBarItem(Icons.Default.Draw, "Signature", secondary) { onMode(PdfEditMode.SIGNATURE) }
        }
        showConvert -> Row(
            Modifier.fillMaxWidth().background(background).navigationBarsPadding()
                .padding(vertical = 10.dp, horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CloseButton { onShowConvert(false) }
            ConvertBarItem(Icons.Default.Description, "To Word", Color(0xFF1565C0), null, onConvertWord)
            ConvertBarItem(Icons.Default.Slideshow, "To PPT", Color(0xFFB71C1C), Color.Red, onConvertPpt)
        }
        else -> Row(
            Modifier.fillMaxWidth().background(background).navigationBarsPadding().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            EditorBarItem(Icons.Default.Edit, "Edit", secondary) { onMode(PdfEditMode.EDIT_PICKER) }
            EditorBarItem(Icons.Default.SwapHoriz, "Convert", secondary) { onShowConvert(true) }
            EditorBarItem(Icons.Default.Share, "Share", secondary, onShare)
        }
    }
}

@Composable
private fun DoodleToolbar(
    background: Color,
    size: Float,
    color: Color,
    canUndo: Boolean,
    canRedo: Boolean,
    onSize: (Float) -> Unit,
    onColor: (Color) -> Unit,
    onCancel: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onCommit: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().background(background).navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Size", color = Color.White, fontSize = 13.sp, modifier = Modifier.width(42.dp))
            Slider(
                value = size,
                onValueChange = onSize,
                valueRange = 2f..40f,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(thumbColor = AccentBlue, activeTrackColor = AccentBlue),
            )
            Text(
                "${size.toInt()}",
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier.width(28.dp),
                textAlign = TextAlign.End,
            )
        }
        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(penPalette) { _, option ->
                val selected = option == color
                Box(
                    Modifier.size(36.dp).clip(CircleShape)
                        .background(if (selected) Color(0xFFFFD700) else Color.Transparent)
                        .padding(if (selected) 3.dp else 0.dp).clip(CircleShape).background(option)
                        .border(1.dp, Color(0xFF333344), CircleShape).clickable { onColor(option) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCancel) { Icon(Icons.Default.Close, null, tint = Color.White) }
            Row {
                IconButton(onClick = onUndo, enabled = canUndo) {
                    Icon(Icons.AutoMirrored.Filled.Undo, null, tint = enabledTint(canUndo))
                }
                IconButton(onClick = onRedo, enabled = canRedo) {
                    Icon(Icons.AutoMirrored.Filled.Redo, null, tint = enabledTint(canRedo))
                }
            }
            IconButton(onClick = onCommit) { Icon(Icons.Default.Check, null, tint = AccentBlue) }
        }
    }
}

@Composable
private fun TextToolbar(
    background: Color,
    onCancel: () -> Unit,
    onAdd: () -> Unit,
    onCommit: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(background).navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCancel) { Icon(Icons.Default.Close, null, tint = Color.White) }
        Box(
            Modifier.clip(RoundedCornerShape(24.dp)).background(Color(0xFF2A2A3A))
                .border(1.dp, Color(0xFF444455), RoundedCornerShape(24.dp)).clickable(onClick = onAdd)
                .padding(horizontal = 28.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Add, null, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text("ADD", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
        IconButton(onClick = onCommit) { Icon(Icons.Default.Check, null, tint = AccentBlue) }
    }
}

@Composable
private fun CloseButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(Color(0xFF2A2A3A)),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.Close, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
    }
}

private fun enabledTint(enabled: Boolean) = if (enabled) Color.White else Color(0xFF555566)
