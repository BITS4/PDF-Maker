package com.example.pdfmaker

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
// This function is the state-hoisted rendering boundary for the editor. Keeping the
// callbacks explicit makes ownership visible to callers and prevents hidden mutable state.
@Suppress("LongParameterList")
internal fun PdfEditorCanvas(
    bitmap: Bitmap?,
    annotations: PageAnnotations?,
    page: Int,
    pageCount: Int,
    editMode: PdfEditMode,
    doodleStrokes: List<DrawStroke>,
    activePath: List<Offset>,
    doodleColor: Color,
    doodleSize: Float,
    liveTexts: List<LiveText>,
    liveSignatures: List<LiveSignature>,
    selectedItemId: String?,
    pageBoxWidth: Int,
    onPageSize: (Int, Int) -> Unit,
    onDoodleStart: (Offset) -> Unit,
    onDoodlePoint: (Offset) -> Unit,
    onDoodleEnd: () -> Unit,
    onTextTap: (Offset) -> Unit,
    onSelect: (String) -> Unit,
    onTextUpdate: (String, Float, Float, Float) -> Unit,
    onSignatureUpdate: (String, Float, Float, Float) -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFCCCCCC))
            .editorGestures(editMode, onDoodleStart, onDoodlePoint, onDoodleEnd, onTextTap),
    ) {
        if (bitmap == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentBlue)
            }
        } else {
            Image(
                bitmap.asImageBitmap(),
                null,
                modifier =
                    Modifier.fillMaxSize().onGloballyPositioned {
                        onPageSize(it.size.width, it.size.height)
                    },
            )
            Canvas(Modifier.fillMaxSize()) {
                annotations?.strokes?.forEach { drawEditorStroke(it) }
                annotations?.signatures?.forEach { signature ->
                    val width = (signature.width * size.width).toInt()
                    val height = (width.toFloat() / signature.bitmap.width * signature.bitmap.height).toInt()
                    drawImage(
                        image = signature.bitmap.asImageBitmap(),
                        dstOffset = IntOffset((signature.x * size.width).toInt(), (signature.y * size.height).toInt()),
                        dstSize =
                            androidx.compose.ui.unit
                                .IntSize(width, height),
                    )
                }
                if (editMode == PdfEditMode.DOODLE) {
                    doodleStrokes.forEach { drawEditorStroke(it) }
                    if (activePath.size >= 2) drawEditorStroke(DrawStroke(activePath, doodleColor, doodleSize))
                }
            }
            annotations?.texts?.forEach { CommittedTextOverlay(it) }
            liveTexts.forEach { item ->
                LiveTextOverlay(
                    item = item,
                    selected = selectedItemId == item.id,
                    onSelect = { onSelect(item.id) },
                    onUpdate = { x, y, size -> onTextUpdate(item.id, x, y, size) },
                )
            }
            liveSignatures.forEach { item ->
                val initialWidth = (pageBoxWidth * 0.4f).coerceAtLeast(80f)
                val width = (initialWidth * item.scaleFactor).coerceAtLeast(40f)
                val height = if (item.bitmap.width > 0) width / item.bitmap.width * item.bitmap.height else width
                LiveSignatureOverlay(
                    item = item,
                    dispW = width,
                    dispH = height,
                    selected = selectedItemId == item.id,
                    onSelect = { onSelect(item.id) },
                    onUpdate = { x, y, scale -> onSignatureUpdate(item.id, x, y, scale) },
                )
            }
        }
        if (pageCount > 1) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 5.dp),
            ) {
                Text("${page + 1}/$pageCount", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private fun Modifier.editorGestures(
    editMode: PdfEditMode,
    onDoodleStart: (Offset) -> Unit,
    onDoodlePoint: (Offset) -> Unit,
    onDoodleEnd: () -> Unit,
    onTextTap: (Offset) -> Unit,
): Modifier =
    when (editMode) {
        PdfEditMode.DOODLE ->
            pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = onDoodleStart,
                    onDrag = { change, _ ->
                        change.consume()
                        onDoodlePoint(change.position)
                    },
                    onDragEnd = onDoodleEnd,
                    onDragCancel = onDoodleEnd,
                )
            }
        PdfEditMode.TEXT -> pointerInput(Unit) { detectTapGestures(onTap = onTextTap) }
        else -> this
    }

internal fun DrawScope.drawEditorStroke(stroke: DrawStroke) {
    if (stroke.points.size < 2) return
    val path =
        Path().apply {
            moveTo(stroke.points.first().x, stroke.points.first().y)
            stroke.points.drop(1).forEach { lineTo(it.x, it.y) }
        }
    drawPath(
        path,
        stroke.color,
        style = Stroke(width = stroke.strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}
