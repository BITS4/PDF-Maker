package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class PageRotationPolicyTest {
    @Test
    fun `clockwise rotation wraps after a full turn`() {
        assertEquals(90, PageEditPolicy.rotateClockwise(0))
        assertEquals(0, PageEditPolicy.rotateClockwise(270))
    }

    @Test
    fun `counterclockwise rotation wraps below zero`() {
        assertEquals(270, PageEditPolicy.rotateCounterClockwise(0))
        assertEquals(180, PageEditPolicy.rotateCounterClockwise(270))
    }

    @Test
    fun `normalization accepts signed multi-turn values`() {
        assertEquals(90, PageEditPolicy.normalizeRotation(810))
        assertEquals(270, PageEditPolicy.normalizeRotation(-450))
    }

    @Test
    fun `non-quarter-turn rotations are rejected`() {
        try {
            PageEditPolicy.normalizeRotation(45)
            fail("Arbitrary rotations must not enter the page model")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
