package com.example.pdfmaker

/**
 * Tracks delivery and one-time claiming of incoming intents without retaining Android objects.
 *
 * The three exposed primitive values are suitable for instance-state persistence. Calls are
 * expected to be confined to the activity's main thread.
 */
internal class IncomingIntentLifecycleState private constructor(
    lastIssuedRequestId: Long,
    pendingRequestId: Long,
    currentIntentConsumed: Boolean,
) {
    var lastIssuedRequestId: Long = lastIssuedRequestId
        private set

    var pendingRequestId: Long = pendingRequestId
        private set

    var currentIntentConsumed: Boolean = currentIntentConsumed
        private set

    val hasPendingRequest: Boolean
        get() = pendingRequestId != NO_REQUEST_ID

    /** Records every real delivery, even when its URI and other intent fields are unchanged. */
    fun recordDelivery(): Long {
        val requestId = nextAvailableRequestId(lastIssuedRequestId, pendingRequestId)
        lastIssuedRequestId = requestId
        pendingRequestId = requestId
        currentIntentConsumed = false
        return requestId
    }

    /**
     * Claims exactly the currently pending delivery.
     *
     * A stale or invalid request ID cannot clear a newer pending delivery.
     */
    fun claim(requestId: Long): Boolean {
        if (requestId <= NO_REQUEST_ID || requestId != pendingRequestId) return false
        pendingRequestId = NO_REQUEST_ID
        currentIntentConsumed = true
        return true
    }

    /** Marks a malformed incoming launch handled without allocating a request. */
    fun discardCurrentIntent() {
        pendingRequestId = NO_REQUEST_ID
        currentIntentConsumed = true
    }

    companion object {
        const val NO_REQUEST_ID = 0L

        fun create(): IncomingIntentLifecycleState =
            IncomingIntentLifecycleState(
                lastIssuedRequestId = NO_REQUEST_ID,
                pendingRequestId = NO_REQUEST_ID,
                currentIntentConsumed = false,
            )

        /** Restores state from values that can be stored directly in an Android state bundle. */
        fun restore(
            lastIssuedRequestId: Long,
            pendingRequestId: Long,
            hasPendingRequest: Boolean,
            currentIntentConsumed: Boolean,
        ): IncomingIntentLifecycleState {
            val restoredPendingId =
                pendingRequestId.takeIf {
                    hasPendingRequest && !currentIntentConsumed && it > NO_REQUEST_ID
                }
                    ?: NO_REQUEST_ID
            val restoredLastIssuedId =
                lastIssuedRequestId
                    .takeIf { it > NO_REQUEST_ID }
                    ?.let { maxOf(it, restoredPendingId) }
                    ?: restoredPendingId
            return IncomingIntentLifecycleState(
                lastIssuedRequestId = restoredLastIssuedId,
                pendingRequestId = restoredPendingId,
                currentIntentConsumed =
                    currentIntentConsumed ||
                        (hasPendingRequest && restoredPendingId == NO_REQUEST_ID),
            )
        }

        private fun nextAvailableRequestId(
            current: Long,
            activePendingId: Long,
        ): Long {
            var candidate =
                if (current in 1L until Long.MAX_VALUE) {
                    current + 1L
                } else {
                    1L
                }
            if (candidate == activePendingId) {
                candidate = if (candidate == Long.MAX_VALUE) 1L else candidate + 1L
            }
            return candidate
        }
    }
}
