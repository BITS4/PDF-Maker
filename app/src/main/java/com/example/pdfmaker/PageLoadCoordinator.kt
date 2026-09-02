package com.example.pdfmaker

internal data class PageLoadRequest(
    val sourceId: String,
    val generation: Long,
)

/** Issues source-bound request tokens so stale asynchronous loads cannot replace newer state. */
internal class PageLoadCoordinator {
    private var generation = 0L
    private var current: PageLoadRequest? = null

    fun begin(sourceId: String): PageLoadRequest {
        generation += 1
        return PageLoadRequest(sourceId, generation).also { current = it }
    }

    fun isCurrent(request: PageLoadRequest): Boolean = current == request

    fun invalidate() {
        current = null
    }
}
