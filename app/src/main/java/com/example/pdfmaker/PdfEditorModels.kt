package com.example.pdfmaker

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color

data class DrawStroke(
    val points: List<Offset>,
    val color: Color,
    val strokeWidth: Float,
)

data class TextAnnotation(
    val x: Float,
    val y: Float,
    val text: String,
    val color: Color,
    val sizeSp: Float,
)

data class SignatureOverlay(
    val x: Float,
    val y: Float,
    val width: Float,
    val bitmap: Bitmap,
)

data class LiveText(
    val id: String =
        java.util.UUID
            .randomUUID()
            .toString(),
    val text: String,
    val color: Color,
    val sizeSp: Float,
    val x: Float,
    val y: Float,
)

data class LiveSignature(
    val id: String =
        java.util.UUID
            .randomUUID()
            .toString(),
    val bitmap: Bitmap,
    val x: Float,
    val y: Float,
    val scaleFactor: Float = 1f,
)

class PageAnnotations {
    var strokes by mutableStateOf<List<DrawStroke>>(emptyList())
    var texts by mutableStateOf<List<TextAnnotation>>(emptyList())
    var signatures by mutableStateOf<List<SignatureOverlay>>(emptyList())
}

enum class PdfEditMode { NONE, EDIT_PICKER, DOODLE, TEXT, SIGNATURE }

enum class ConvertTarget { NONE, WORD, PPT }

internal val penPalette =
    listOf(
        Color.Black,
        Color(0xFF555555),
        Color(0xFFAAAAAA),
        Color(0xFFDDDDDD),
        Color.Red,
        Color(0xFF00C853),
        Color(0xFF2196F3),
        Color(0xFFFF00FF),
    )
