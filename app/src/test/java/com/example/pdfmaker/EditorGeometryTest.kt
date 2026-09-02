package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EditorGeometryTest {
    @Test
    fun `normalizes screen coordinates and signature width`() {
        val placement =
            normalizeSignaturePlacement(
                x = 250f,
                y = 400f,
                scaleFactor = 1.5f,
                pageWidth = 1_000,
                pageHeight = 800,
            )

        assertNotNull(placement)
        assertEquals(0.25f, placement!!.x, 0.0001f)
        assertEquals(0.5f, placement.y, 0.0001f)
        assertEquals(0.6f, placement.width, 0.0001f)
    }

    @Test
    fun `clamps placement to the page and supported size range`() {
        val placement =
            normalizeSignaturePlacement(
                x = -100f,
                y = 2_000f,
                scaleFactor = 20f,
                pageWidth = 1_000,
                pageHeight = 1_000,
            )!!

        assertEquals(0f, placement.x, 0f)
        assertEquals(1f, placement.y, 0f)
        assertEquals(1f, placement.width, 0f)
    }

    @Test
    fun `rejects invalid page dimensions and scale`() {
        assertNull(normalizeSignaturePlacement(1f, 1f, 1f, 1, 100))
        assertNull(normalizeSignaturePlacement(1f, 1f, 1f, 100, 1))
        assertNull(normalizeSignaturePlacement(1f, 1f, Float.NaN, 100, 100))
    }
}
