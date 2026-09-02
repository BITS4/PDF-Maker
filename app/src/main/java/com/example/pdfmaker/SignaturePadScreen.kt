package com.example.pdfmaker

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.createBitmap

@Composable
fun SignaturePadScreen(
    onConfirm: (Bitmap?) -> Unit,
    onCancel: () -> Unit,
) {
    var strokes by remember { mutableStateOf<List<DrawStroke>>(emptyList()) }
    var redoStack by remember { mutableStateOf<List<DrawStroke>>(emptyList()) }
    var activePath by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var penColor by remember { mutableStateOf(Color.Black) }
    var penSize by remember { mutableFloatStateOf(5f) }
    var canvasW by remember { mutableIntStateOf(1) }
    var canvasH by remember { mutableIntStateOf(1) }
    val hasContent = strokes.isNotEmpty()

    Column(Modifier.fillMaxSize().background(Color(0xFF0D0D16)).statusBarsPadding()) {
        SignatureHeader(onCancel = onCancel, hasContent = hasContent) {
            strokes = emptyList()
            redoStack = emptyList()
        }

        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.White)
                .onGloballyPositioned {
                    canvasW = it.size.width
                    canvasH = it.size.height
                }.pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = {
                            activePath = listOf(it)
                            redoStack = emptyList()
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            activePath = activePath + change.position
                        },
                        onDragEnd = {
                            if (activePath.size >= 2) {
                                strokes = strokes + DrawStroke(activePath, penColor, penSize)
                            }
                            activePath = emptyList()
                        },
                        onDragCancel = { activePath = emptyList() },
                    )
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                strokes.forEach { drawEditorStroke(it) }
                if (activePath.size >= 2) drawEditorStroke(DrawStroke(activePath, penColor, penSize))
            }
            if (!hasContent && activePath.isEmpty()) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Sign here", color = Color(0xFFBBBBCC), fontSize = 24.sp, fontWeight = FontWeight.Light)
                    Spacer(Modifier.height(6.dp))
                    Text("Add a signature to your document.", color = Color(0xFFCCCCDD), fontSize = 14.sp)
                }
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A1A2A))
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Size", color = Color.White, fontSize = 13.sp, modifier = Modifier.width(42.dp))
                Slider(
                    value = penSize,
                    onValueChange = { penSize = it },
                    valueRange = 2f..40f,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = AccentBlue, activeTrackColor = AccentBlue),
                )
                Text(
                    "${penSize.toInt()}",
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier.width(28.dp),
                    textAlign = TextAlign.End,
                )
            }
            Spacer(Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(penPalette) { _, color ->
                    val selected = color == penColor
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(if (selected) Color(0xFFFFD700) else Color.Transparent)
                            .padding(if (selected) 3.dp else 0.dp)
                            .clip(CircleShape)
                            .background(color)
                            .border(1.dp, Color(0xFF333344), CircleShape)
                            .clickable { penColor = color },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onCancel) { Icon(Icons.Default.Close, null, tint = Color.White) }
                Row {
                    IconButton(
                        onClick = {
                            if (strokes.isNotEmpty()) {
                                redoStack += strokes.last()
                                strokes = strokes.dropLast(1)
                            }
                        },
                        enabled = hasContent,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Undo,
                            null,
                            tint = if (hasContent) Color.White else Color(0xFF555566),
                        )
                    }
                    IconButton(
                        onClick = {
                            if (redoStack.isNotEmpty()) {
                                strokes += redoStack.last()
                                redoStack = redoStack.dropLast(1)
                            }
                        },
                        enabled = redoStack.isNotEmpty(),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Redo,
                            null,
                            tint = if (redoStack.isNotEmpty()) Color.White else Color(0xFF555566),
                        )
                    }
                }
                IconButton(onClick = {
                    if (strokes.isEmpty()) {
                        onConfirm(null)
                        return@IconButton
                    }
                    onConfirm(renderSignatureBitmap(strokes, canvasW, canvasH))
                }) { Icon(Icons.Default.Check, null, tint = AccentBlue) }
            }
        }
    }
}

@Composable
private fun SignatureHeader(
    onCancel: () -> Unit,
    hasContent: Boolean,
    onReset: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCancel) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
        }
        Text(
            "Add signature",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        TextButton(onClick = onReset, enabled = hasContent) {
            Text("Reset", color = if (hasContent) Color.White else Color(0xFF555566))
        }
    }
}

private fun renderSignatureBitmap(
    strokes: List<DrawStroke>,
    width: Int,
    height: Int,
): Bitmap {
    val bitmap = createBitmap(width, height)
    val canvas = android.graphics.Canvas(bitmap)
    canvas.drawColor(android.graphics.Color.WHITE)
    val paint =
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
    strokes.forEach { stroke ->
        paint.color = stroke.color.toArgb()
        paint.strokeWidth = stroke.strokeWidth
        val path = android.graphics.Path()
        stroke.points.firstOrNull()?.let { path.moveTo(it.x, it.y) }
        stroke.points.drop(1).forEach { path.lineTo(it.x, it.y) }
        canvas.drawPath(path, paint)
    }
    return bitmap
}
