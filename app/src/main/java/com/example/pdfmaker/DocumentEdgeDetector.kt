package com.example.pdfmaker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

fun perspectiveWarp(source: Bitmap, quad: Quad): Bitmap {
    val sourceWidth = source.width.toFloat()
    val sourceHeight = source.height.toFloat()
    val topLeft = quad.tl.scaled(sourceWidth, sourceHeight)
    val topRight = quad.tr.scaled(sourceWidth, sourceHeight)
    val bottomRight = quad.br.scaled(sourceWidth, sourceHeight)
    val bottomLeft = quad.bl.scaled(sourceWidth, sourceHeight)
    val measuredWidth =
        ((distance(topLeft, topRight) + distance(bottomLeft, bottomRight)) / 2f)
            .coerceAtLeast(10f)
            .toInt()
    val measuredHeight =
        ((distance(topLeft, bottomLeft) + distance(topRight, bottomRight)) / 2f)
            .coerceAtLeast(10f)
            .toInt()
    val output =
        requireNotNull(ImageInputPolicy.fitWithinLimits(measuredWidth, measuredHeight)) {
            "Crop output dimensions are invalid"
        }
    val result = Bitmap.createBitmap(output.width, output.height, Bitmap.Config.ARGB_8888)
    Canvas(result).apply {
        drawColor(android.graphics.Color.WHITE)
        drawBitmap(source, perspectiveMatrix(topLeft, topRight, bottomRight, bottomLeft, output), warpPaint())
    }
    return result
}

/** Detects a likely document boundary with a bounded Canny/Hough pipeline. */
fun autoDetectQuad(bitmap: Bitmap): Quad {
    val edgeMap = createDocumentEdgeMap(bitmap)
    return detectDocumentQuad(edgeMap) ?: edgeScanFallback(edgeMap.edges, edgeMap.width, edgeMap.height)
}

private fun perspectiveMatrix(
    topLeft: PointF,
    topRight: PointF,
    bottomRight: PointF,
    bottomLeft: PointF,
    output: ImageDimensions,
): Matrix =
    Matrix().apply {
        setPolyToPoly(
            floatArrayOf(
                topLeft.x,
                topLeft.y,
                topRight.x,
                topRight.y,
                bottomRight.x,
                bottomRight.y,
                bottomLeft.x,
                bottomLeft.y,
            ),
            0,
            floatArrayOf(
                0f,
                0f,
                output.width.toFloat(),
                0f,
                output.width.toFloat(),
                output.height.toFloat(),
                0f,
                output.height.toFloat(),
            ),
            0,
            4,
        )
    }

private fun warpPaint(): Paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

private fun PointF.scaled(width: Float, height: Float): PointF = PointF(x * width, y * height)

private fun distance(first: PointF, second: PointF): Float =
    sqrt((second.x - first.x).pow(2) + (second.y - first.y).pow(2))

/** Fallback: scans the edge map to find conservative document boundaries. */
internal fun edgeScanFallback(edges: BooleanArray, width: Int, height: Int): Quad {
    val bounds = findEdgeBounds(edges, width, height)
    val left = (bounds.left.toFloat() / width).coerceIn(0.03f, 0.45f)
    val right = (bounds.right.toFloat() / width).coerceIn(0.55f, 0.97f)
    val top = (bounds.top.toFloat() / height).coerceIn(0.03f, 0.45f)
    val bottom = (bounds.bottom.toFloat() / height).coerceIn(0.55f, 0.97f)
    return Quad(PointF(left, top), PointF(right, top), PointF(right, bottom), PointF(left, bottom))
}

internal fun findEdgeBounds(edges: BooleanArray, width: Int, height: Int): EdgeBounds {
    require(width > 0 && height > 0 && edges.size == width * height) { "Edge map dimensions are invalid" }
    val margin = (min(width, height) * 0.04f).toInt()
    val top =
        findHorizontalBoundary(edges, width, margin until height / 2, margin, width, width * 0.07f)
            ?: (height * 0.05f).toInt()
    val bottom =
        findHorizontalBoundary(
            edges,
            width,
            (height / 2 until height - margin).reversed(),
            margin,
            width,
            width * 0.07f,
        ) ?: (height * 0.95f).toInt()
    val left =
        findVerticalBoundary(edges, width, margin until width / 2, margin, height, height * 0.07f)
            ?: (width * 0.05f).toInt()
    val right =
        findVerticalBoundary(
            edges,
            width,
            (width / 2 until width - margin).reversed(),
            margin,
            height,
            height * 0.07f,
        ) ?: (width * 0.95f).toInt()
    return EdgeBounds(left, top, right, bottom)
}

private fun findHorizontalBoundary(
    edges: BooleanArray,
    width: Int,
    rows: IntProgression,
    margin: Int,
    end: Int,
    threshold: Float,
): Int? = rows.firstOrNull { y -> (margin until end - margin).count { x -> edges[y * width + x] } > threshold }

private fun findVerticalBoundary(
    edges: BooleanArray,
    width: Int,
    columns: IntProgression,
    margin: Int,
    end: Int,
    threshold: Float,
): Int? = columns.firstOrNull { x -> (margin until end - margin).count { y -> edges[y * width + x] } > threshold }
