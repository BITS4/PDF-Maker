package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingIntentLifecycleStateTest {
    @Test
    fun `deliveries receive monotonically increasing request IDs`() {
        val state = IncomingIntentLifecycleState.create()

        assertEquals(1L, state.recordDelivery())
        assertEquals(2L, state.recordDelivery())
        assertEquals(3L, state.recordDelivery())
        assertEquals(3L, state.lastIssuedRequestId)
        assertEquals(3L, state.pendingRequestId)
        assertTrue(state.hasPendingRequest)
        assertFalse(state.currentIntentConsumed)
    }

    @Test
    fun `identical new delivery receives a fresh ID and makes the old claim stale`() {
        val state = IncomingIntentLifecycleState.create()
        val firstDelivery = state.recordDelivery()

        // Payload identity is intentionally irrelevant: an actual callback means a new delivery.
        val identicalSecondDelivery = state.recordDelivery()

        assertEquals(firstDelivery + 1L, identicalSecondDelivery)
        assertFalse(state.claim(firstDelivery))
        assertTrue(state.hasPendingRequest)
        assertEquals(identicalSecondDelivery, state.pendingRequestId)
    }

    @Test
    fun `matching claim consumes pending request exactly once`() {
        val state = IncomingIntentLifecycleState.create()
        val requestId = state.recordDelivery()

        assertTrue(state.claim(requestId))
        assertFalse(state.hasPendingRequest)
        assertTrue(state.currentIntentConsumed)
        assertEquals(IncomingIntentLifecycleState.NO_REQUEST_ID, state.pendingRequestId)
        assertFalse(state.claim(requestId))
        assertEquals(requestId, state.lastIssuedRequestId)
    }

    @Test
    fun `primitive pending state survives restoration`() {
        val original = IncomingIntentLifecycleState.create()
        repeat(4) { original.recordDelivery() }

        val restored =
            IncomingIntentLifecycleState.restore(
                lastIssuedRequestId = original.lastIssuedRequestId,
                pendingRequestId = original.pendingRequestId,
                hasPendingRequest = original.hasPendingRequest,
                currentIntentConsumed = original.currentIntentConsumed,
            )

        assertEquals(4L, restored.lastIssuedRequestId)
        assertEquals(4L, restored.pendingRequestId)
        assertTrue(restored.hasPendingRequest)
        assertTrue(restored.claim(4L))
    }

    @Test
    fun `primitive consumed state remains consumed after restoration`() {
        val restored =
            IncomingIntentLifecycleState.restore(
                lastIssuedRequestId = 18L,
                pendingRequestId = 18L,
                hasPendingRequest = false,
                currentIntentConsumed = true,
            )

        assertFalse(restored.hasPendingRequest)
        assertTrue(restored.currentIntentConsumed)
        assertEquals(IncomingIntentLifecycleState.NO_REQUEST_ID, restored.pendingRequestId)
        assertFalse(restored.claim(18L))
        assertEquals(19L, restored.recordDelivery())
    }

    @Test
    fun `exhausted counter wraps without colliding with active pending ID`() {
        val state =
            IncomingIntentLifecycleState.restore(
                lastIssuedRequestId = Long.MAX_VALUE,
                pendingRequestId = 1L,
                hasPendingRequest = true,
                currentIntentConsumed = false,
            )

        val recoveredId = state.recordDelivery()

        assertEquals(2L, recoveredId)
        assertEquals(2L, state.pendingRequestId)
        assertFalse(state.claim(1L))
        assertTrue(state.claim(recoveredId))
    }

    @Test
    fun `exhausted counter reuses first positive ID when it is available`() {
        val state =
            IncomingIntentLifecycleState.restore(
                lastIssuedRequestId = Long.MAX_VALUE,
                pendingRequestId = IncomingIntentLifecycleState.NO_REQUEST_ID,
                hasPendingRequest = false,
                currentIntentConsumed = true,
            )

        assertEquals(1L, state.recordDelivery())
    }

    @Test
    fun `invalid restored pending primitives fail closed`() {
        val state =
            IncomingIntentLifecycleState.restore(
                lastIssuedRequestId = -5L,
                pendingRequestId = -7L,
                hasPendingRequest = true,
                currentIntentConsumed = false,
            )

        assertEquals(IncomingIntentLifecycleState.NO_REQUEST_ID, state.lastIssuedRequestId)
        assertEquals(IncomingIntentLifecycleState.NO_REQUEST_ID, state.pendingRequestId)
        assertFalse(state.hasPendingRequest)
        assertFalse(state.claim(-7L))
        assertEquals(1L, state.recordDelivery())
    }

    @Test
    fun `discarding malformed delivery consumes current intent without changing sequence`() {
        val state = IncomingIntentLifecycleState.create()
        assertEquals(1L, state.recordDelivery())

        state.discardCurrentIntent()

        assertFalse(state.hasPendingRequest)
        assertTrue(state.currentIntentConsumed)
        assertEquals(1L, state.lastIssuedRequestId)
        assertEquals(2L, state.recordDelivery())
        assertFalse(state.currentIntentConsumed)
    }
}
