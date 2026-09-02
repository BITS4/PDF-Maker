package com.example.pdfmaker

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
fun ConvertingOverlay(
    target: ConvertTarget,
    progress: Int,
    onCancel: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxWidth(0.85f)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xF0E0E0E8))
                .padding(top = 6.dp, bottom = 24.dp),
        ) {
            IconButton(onClick = onCancel, modifier = Modifier.align(Alignment.TopEnd).size(40.dp)) {
                Icon(Icons.Default.Close, null, tint = Color(0xFF666677))
            }
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                    color = AccentBlue,
                    trackColor = Color(0xFFCCCCDD),
                )
                Spacer(Modifier.height(20.dp))
                Text("Converting… ($progress%)", color = Color(0xFF222233), fontSize = 17.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "to ${if (target == ConvertTarget.WORD) "Word (.docx)" else "PowerPoint (.pptx)"}",
                    color = Color(0xFF666677),
                    fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
internal fun EditorBarItem(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = tint, fontSize = 12.sp)
    }
}

@Composable
internal fun ConvertBarItem(
    icon: ImageVector,
    label: String,
    iconBackground: Color,
    badge: Color?,
    onClick: () -> Unit,
) {
    Box {
        Column(
            modifier =
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF252535))
                    .clickable(onClick = onClick)
                    .padding(horizontal = 28.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(iconBackground),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(26.dp)) }
            Spacer(Modifier.height(6.dp))
            Text(label, color = Color.White, fontSize = 13.sp)
        }
        if (badge != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-4).dp, y = 4.dp)
                    .size(10.dp)
                    .background(badge, CircleShape),
            )
        }
    }
}

@Composable
fun LiveTextOverlay(
    item: LiveText,
    selected: Boolean,
    onSelect: () -> Unit,
    onUpdate: (newX: Float, newY: Float, newSizeSp: Float) -> Unit,
) {
    var currentX by remember(item.id) { mutableFloatStateOf(item.x) }
    var currentY by remember(item.id) { mutableFloatStateOf(item.y) }
    var currentSize by remember(item.id) { mutableFloatStateOf(item.sizeSp) }
    Box(
        Modifier
            .absoluteOffset { IntOffset(currentX.roundToInt(), currentY.roundToInt()) }
            .pointerInput(item.id) {
                detectTransformGestures { _, pan, zoom, _ ->
                    currentX += pan.x
                    currentY += pan.y
                    currentSize = (currentSize * zoom).coerceIn(8f, 120f)
                    onUpdate(currentX, currentY, currentSize)
                }
            }.clickable(onClick = onSelect)
            .then(
                if (selected) {
                    Modifier.border(1.5.dp, AccentBlue.copy(alpha = 0.8f), RoundedCornerShape(4.dp))
                } else {
                    Modifier
                },
            ).padding(6.dp),
    ) {
        Text(item.text, color = item.color, fontSize = currentSize.sp)
        if (selected) ResizeHandle()
    }
}

@Composable
fun LiveSignatureOverlay(
    item: LiveSignature,
    dispW: Float,
    dispH: Float,
    selected: Boolean,
    onSelect: () -> Unit,
    onUpdate: (newX: Float, newY: Float, newScale: Float) -> Unit,
) {
    var currentX by remember(item.id) { mutableFloatStateOf(item.x) }
    var currentY by remember(item.id) { mutableFloatStateOf(item.y) }
    var currentScale by remember(item.id) { mutableFloatStateOf(item.scaleFactor) }
    val density = LocalDensity.current
    Box(
        Modifier
            .absoluteOffset { IntOffset(currentX.roundToInt(), currentY.roundToInt()) }
            .size(
                width = with(density) { dispW.toDp() },
                height = with(density) { dispH.toDp() },
            ).pointerInput(item.id) {
                detectTransformGestures { _, pan, zoom, _ ->
                    currentX += pan.x
                    currentY += pan.y
                    currentScale = (currentScale * zoom).coerceIn(0.05f, 6f)
                    onUpdate(currentX, currentY, currentScale)
                }
            }.clickable(onClick = onSelect)
            .then(
                if (selected) {
                    Modifier.border(1.5.dp, AccentBlue.copy(alpha = 0.8f), RoundedCornerShape(4.dp))
                } else {
                    Modifier
                },
            ),
    ) {
        Image(item.bitmap.asImageBitmap(), null, modifier = Modifier.fillMaxSize())
        if (selected) ResizeHandle()
    }
}

@Composable
private fun BoxScope.ResizeHandle() {
    Box(
        Modifier
            .align(Alignment.BottomEnd)
            .offset(x = 8.dp, y = 8.dp)
            .size(18.dp)
            .background(AccentBlue, CircleShape)
            .border(2.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Default.OpenWith, null, tint = Color.White, modifier = Modifier.size(10.dp)) }
}

@Composable
fun CommittedTextOverlay(annotation: TextAnnotation) {
    Text(
        text = annotation.text,
        color = annotation.color,
        fontSize = annotation.sizeSp.sp,
        modifier =
            Modifier
                .absoluteOffset {
                    IntOffset(annotation.x.roundToInt(), annotation.y.roundToInt())
                }.padding(6.dp),
    )
}
