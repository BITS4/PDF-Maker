package com.example.pdfmaker

import android.graphics.PointF
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private const val HOUGH_STEP_DEGREES = 2
private const val MAX_HOUGH_PEAKS = 40

private data class HoughLine(
    val rho: Int,
    val theta: Int,
    val votes: Int,
) {
    val radians: Double get() = theta * PI / 180.0
}

private data class BoundaryLines(
    val top: HoughLine,
    val bottom: HoughLine,
    val left: HoughLine,
    val right: HoughLine,
)

internal fun detectDocumentQuad(edgeMap: DocumentEdgeMap): Quad? {
    val diagonal = sqrt((edgeMap.width * edgeMap.width + edgeMap.height * edgeMap.height).toDouble()).toInt() + 1
    val rhoCount = 2 * diagonal + 1
    val thetaCount = 180 / HOUGH_STEP_DEGREES
    val accumulator = buildHoughAccumulator(edgeMap, diagonal, rhoCount, thetaCount)
    val peaks = extractHoughPeaks(accumulator, edgeMap, diagonal, rhoCount, thetaCount)
    val boundaries = selectBoundaryLines(peaks, edgeMap.height) ?: return null
    return intersectBoundaryLines(boundaries, edgeMap.width, edgeMap.height)
}

private fun buildHoughAccumulator(
    edgeMap: DocumentEdgeMap,
    diagonal: Int,
    rhoCount: Int,
    thetaCount: Int,
): IntArray {
    val accumulator = IntArray(rhoCount * thetaCount)
    val cosine = DoubleArray(thetaCount) { theta -> cos(theta * HOUGH_STEP_DEGREES * PI / 180.0) }
    val sine = DoubleArray(thetaCount) { theta -> sin(theta * HOUGH_STEP_DEGREES * PI / 180.0) }
    for (y in 0 until edgeMap.height) {
        for (x in 0 until edgeMap.width) {
            if (edgeMap.edges[y * edgeMap.width + x]) {
                voteForEdge(x, y, diagonal, rhoCount, thetaCount, cosine, sine, accumulator)
            }
        }
    }
    return accumulator
}

private fun voteForEdge(
    x: Int,
    y: Int,
    diagonal: Int,
    rhoCount: Int,
    thetaCount: Int,
    cosine: DoubleArray,
    sine: DoubleArray,
    accumulator: IntArray,
) {
    for (theta in 0 until thetaCount) {
        val rho = (x * cosine[theta] + y * sine[theta]).roundToInt() + diagonal
        if (rho in 0 until rhoCount) accumulator[rho * thetaCount + theta] += 1
    }
}

private fun extractHoughPeaks(
    accumulator: IntArray,
    edgeMap: DocumentEdgeMap,
    diagonal: Int,
    rhoCount: Int,
    thetaCount: Int,
): List<HoughLine> {
    val minimumVotes = (min(edgeMap.width, edgeMap.height) / 8).coerceAtLeast(10)
    val candidates =
        accumulator.indices
            .filter { accumulator[it] >= minimumVotes }
            .sortedByDescending(accumulator::get)
    val suppressed = BooleanArray(accumulator.size)
    val peaks = ArrayList<HoughLine>(MAX_HOUGH_PEAKS)
    var candidateIndex = 0
    while (candidateIndex < candidates.size && peaks.size < MAX_HOUGH_PEAKS) {
        val candidate = candidates[candidateIndex]
        if (!suppressed[candidate]) {
            peaks += candidate.toHoughLine(accumulator, diagonal, thetaCount)
            suppressHoughNeighborhood(suppressed, candidate, diagonal, rhoCount, thetaCount)
        }
        candidateIndex += 1
    }
    return peaks
}

private fun Int.toHoughLine(accumulator: IntArray, diagonal: Int, thetaCount: Int): HoughLine =
    HoughLine(
        rho = this / thetaCount - diagonal,
        theta = this % thetaCount * HOUGH_STEP_DEGREES,
        votes = accumulator[this],
    )

private fun suppressHoughNeighborhood(
    suppressed: BooleanArray,
    candidate: Int,
    diagonal: Int,
    rhoCount: Int,
    thetaCount: Int,
) {
    val rho = candidate / thetaCount
    val theta = candidate % thetaCount
    val rhoRadius = diagonal / 8
    val thetaRadius = 9
    for (rhoOffset in -rhoRadius..rhoRadius) {
        val nearbyRho = rho + rhoOffset
        if (nearbyRho in 0 until rhoCount) {
            for (thetaOffset in -thetaRadius..thetaRadius) {
                val nearbyTheta = (theta + thetaOffset + thetaCount) % thetaCount
                suppressed[nearbyRho * thetaCount + nearbyTheta] = true
            }
        }
    }
}

private fun selectBoundaryLines(peaks: List<HoughLine>, height: Int): BoundaryLines? {
    val horizontal = peaks.filter { it.theta in 60..120 }.sortedBy(HoughLine::rho)
    val vertical =
        peaks
            .filter { it.theta < 30 || it.theta > 150 }
            .sortedBy { line -> verticalPosition(line, height) }
    return if (horizontal.size >= 2 && vertical.size >= 2) {
        BoundaryLines(horizontal.first(), horizontal.last(), vertical.first(), vertical.last())
    } else {
        null
    }
}

private fun verticalPosition(line: HoughLine, height: Int): Double {
    val cosine = cos(line.radians)
    val sine = sin(line.radians)
    return if (abs(cosine) > 0.05) (line.rho - height / 2.0 * sine) / cosine else line.rho.toDouble()
}

private fun intersectBoundaryLines(lines: BoundaryLines, width: Int, height: Int): Quad? {
    val corners =
        listOf(
            intersect(lines.top, lines.left),
            intersect(lines.top, lines.right),
            intersect(lines.bottom, lines.right),
            intersect(lines.bottom, lines.left),
        )
    if (corners.any { it == null }) return null
    val normalized = corners.filterNotNull().map { point -> point.normalized(width, height) }
    val quad = Quad(normalized[0], normalized[1], normalized[2], normalized[3])
    return quad.takeIf(::isPlausibleDocumentQuad)
}

private fun intersect(first: HoughLine, second: HoughLine): PointF? {
    val firstCosine = cos(first.radians)
    val firstSine = sin(first.radians)
    val secondCosine = cos(second.radians)
    val secondSine = sin(second.radians)
    val determinant = firstCosine * secondSine - firstSine * secondCosine
    return if (abs(determinant) < 1e-9) {
        null
    } else {
        PointF(
            ((first.rho * secondSine - second.rho * firstSine) / determinant).toFloat(),
            ((second.rho * firstCosine - first.rho * secondCosine) / determinant).toFloat(),
        )
    }
}

private fun PointF.normalized(width: Int, height: Int): PointF =
    PointF((x / width).coerceIn(0f, 1f), (y / height).coerceIn(0f, 1f))

private fun isPlausibleDocumentQuad(quad: Quad): Boolean {
    val horizontalSpan = (quad.tr.x - quad.tl.x + quad.br.x - quad.bl.x) / 2f
    val verticalSpan = (quad.bl.y - quad.tl.y + quad.br.y - quad.tr.y) / 2f
    return horizontalSpan > 0.20f && verticalSpan > 0.20f
}
