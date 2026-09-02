package com.example.pdfmaker

import android.graphics.PointF

data class Quad(
    val tl: PointF = PointF(0.10f, 0.10f),
    val tr: PointF = PointF(0.90f, 0.10f),
    val br: PointF = PointF(0.90f, 0.90f),
    val bl: PointF = PointF(0.10f, 0.90f),
) {
    fun points(): List<PointF> = listOf(tl, tr, br, bl)

    fun withPoint(
        index: Int,
        point: PointF,
    ): Quad =
        when (index) {
            0 -> copy(tl = point)
            1 -> copy(tr = point)
            2 -> copy(br = point)
            else -> copy(bl = point)
        }
}
