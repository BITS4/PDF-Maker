package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DocumentEdgeDetectorPolicyTest {
    @Test
    fun `edge scan finds each supported boundary`() {
        val width = 100
        val height = 120
        val edges = BooleanArray(width * height)
        for (x in 4 until width - 4) {
            edges[15 * width + x] = true
            edges[100 * width + x] = true
        }
        for (y in 4 until height - 4) {
            edges[y * width + 20] = true
            edges[y * width + 80] = true
        }

        assertEquals(EdgeBounds(left = 20, top = 15, right = 80, bottom = 100), findEdgeBounds(edges, width, height))
    }

    @Test
    fun `edge scan uses conservative defaults when no boundary is visible`() {
        assertEquals(
            EdgeBounds(left = 5, top = 6, right = 95, bottom = 114),
            findEdgeBounds(BooleanArray(100 * 120), width = 100, height = 120),
        )
    }

    @Test
    fun `edge scan rejects inconsistent dimensions`() {
        assertThrows(IllegalArgumentException::class.java) {
            findEdgeBounds(BooleanArray(3), width = 2, height = 2)
        }
    }
}
