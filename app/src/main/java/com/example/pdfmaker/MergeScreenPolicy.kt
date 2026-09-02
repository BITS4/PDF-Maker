package com.example.pdfmaker

internal enum class MergeItemMove(
    val offset: Int,
) {
    UP(-1),
    DOWN(1),
}

internal data class MergeSummary(
    val fileCount: Int,
    val pageCount: Int,
    val sizeKb: Long,
)

internal object MergeScreenPolicy {
    private const val MinimumMergeFiles = 2

    fun canMerge(fileCount: Int): Boolean =
        fileCount in MinimumMergeFiles..MergePdfPolicy.MAX_SOURCE_FILES

    fun summary(
        pageCounts: List<Int>,
        sizesKb: List<Long>,
    ): MergeSummary {
        require(pageCounts.size == sizesKb.size) { "Merge item totals must have equal lengths" }
        val pageCount =
            pageCounts.fold(0) { total, count ->
                require(count >= 0) { "Page counts cannot be negative" }
                Math.addExact(total, count)
            }
        val sizeKb =
            sizesKb.fold(0L) { total, size ->
                require(size >= 0L) { "File sizes cannot be negative" }
                Math.addExact(total, size)
            }
        return MergeSummary(pageCounts.size, pageCount, sizeKb)
    }

    fun targetIndex(
        itemCount: Int,
        currentIndex: Int,
        move: MergeItemMove,
    ): Int? {
        if (currentIndex !in 0 until itemCount) return null
        val target = currentIndex + move.offset
        return target.takeIf { it in 0 until itemCount }
    }

    fun <T> moved(
        items: List<T>,
        currentIndex: Int,
        move: MergeItemMove,
    ): List<T> {
        val target = targetIndex(items.size, currentIndex, move) ?: return items
        return items.toMutableList().apply {
            this[currentIndex] = items[target]
            this[target] = items[currentIndex]
        }
    }

    fun progress(percent: Int): Int = percent.coerceIn(0, 100)

    fun defaultOutputName(completedMerges: Int): String {
        require(completedMerges >= 0) { "Completed merge count cannot be negative" }
        return "merged_document_${Math.addExact(completedMerges, 1)}"
    }
}
