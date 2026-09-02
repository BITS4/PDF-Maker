package com.example.pdfmaker

internal object PageEditPolicy {
    fun rotateClockwise(currentDegrees: Int): Int = normalizeRotation(currentDegrees + 90)

    fun rotateCounterClockwise(currentDegrees: Int): Int = normalizeRotation(currentDegrees - 90)

    fun normalizeRotation(degrees: Int): Int {
        require(degrees % 90 == 0) { "Page rotation must use quarter turns" }
        return ((degrees % 360) + 360) % 360
    }

    fun retainedIndexes(deleted: List<Boolean>): List<Int> = deleted.indices.filter { !deleted[it] }

    fun canSave(deleted: List<Boolean>): Boolean = retainedIndexes(deleted).isNotEmpty()
}
