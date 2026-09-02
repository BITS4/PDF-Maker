package com.example.pdfmaker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageLoadCoordinatorTest {
    @Test
    fun `new requests supersede prior sources`() {
        val coordinator = PageLoadCoordinator()
        val first = coordinator.begin("content://documents/a.pdf")
        val second = coordinator.begin("content://documents/b.pdf")

        assertFalse(coordinator.isCurrent(first))
        assertTrue(coordinator.isCurrent(second))
    }

    @Test
    fun `reselecting the same source still creates a distinct request`() {
        val coordinator = PageLoadCoordinator()
        val first = coordinator.begin("content://documents/a.pdf")
        val retry = coordinator.begin("content://documents/a.pdf")

        assertNotEquals(first, retry)
        assertFalse(coordinator.isCurrent(first))
        assertTrue(coordinator.isCurrent(retry))
    }

    @Test
    fun `disposal invalidates the active request`() {
        val coordinator = PageLoadCoordinator()
        val request = coordinator.begin("content://documents/a.pdf")

        coordinator.invalidate()

        assertFalse(coordinator.isCurrent(request))
    }

    @Test
    fun `request identity includes its source`() {
        val coordinator = PageLoadCoordinator()
        val request = coordinator.begin("content://documents/a.pdf")
        val forgedSource = request.copy(sourceId = "content://documents/b.pdf")

        assertFalse(coordinator.isCurrent(forgedSource))
        assertTrue(coordinator.isCurrent(request))
    }
}
