package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class CompressionPolicyTest {
    @Test
    fun `page policy accepts both configured boundaries`() {
        assertEquals(1, CompressionPolicy.requirePageCount(1))
        assertEquals(
            CompressionPolicy.MAX_PAGES,
            CompressionPolicy.requirePageCount(CompressionPolicy.MAX_PAGES),
        )
    }

    @Test
    fun `page policy rejects every outside boundary class`() {
        listOf(Int.MIN_VALUE, -1, 0, CompressionPolicy.MAX_PAGES + 1, Int.MAX_VALUE).forEach { count ->
            assertThrows(IllegalArgumentException::class.java) {
                CompressionPolicy.requirePageCount(count)
            }
        }
    }

    @Test
    fun `render sizing bounds every aspect ratio without upscaling`() {
        assertEquals(PixelSize(1_600, 400), CompressionPolicy.renderSize(8_000, 2_000, 1_600))
        assertEquals(PixelSize(400, 1_600), CompressionPolicy.renderSize(2_000, 8_000, 1_600))
        assertEquals(PixelSize(1_600, 1_600), CompressionPolicy.renderSize(8_000, 8_000, 1_600))
        assertEquals(PixelSize(400, 300), CompressionPolicy.renderSize(400, 300, 1_600))
    }

    @Test
    fun `render sizing rejects invalid source and target dimensions`() {
        listOf(
            Triple(0, 1_000, 1_600),
            Triple(1_000, 0, 1_600),
            Triple(-1, 1_000, 1_600),
            Triple(1_000, -1, 1_600),
            Triple(1_000, 1_000, 0),
            Triple(1_000, 1_000, -1),
        ).forEach { (width, height, maximum) ->
            assertThrows(IllegalArgumentException::class.java) {
                CompressionPolicy.renderSize(width, height, maximum)
            }
        }
    }

    @Test
    fun `page progress maps valid pages into the bounded work band`() {
        assertEquals(0, CompressionPolicy.pageProgress(0, 1))
        assertEquals(0, CompressionPolicy.pageProgress(0, 2))
        assertEquals(45, CompressionPolicy.pageProgress(1, 2))
        assertEquals(89, CompressionPolicy.pageProgress(199, 200))
    }

    @Test
    fun `page progress rejects invalid page counts and indexes`() {
        listOf(-1, 0, CompressionPolicy.MAX_PAGES + 1).forEach { count ->
            assertThrows(IllegalArgumentException::class.java) {
                CompressionPolicy.pageProgress(0, count)
            }
        }
        listOf(-1, 2).forEach { index ->
            assertThrows(IllegalArgumentException::class.java) {
                CompressionPolicy.pageProgress(index, 2)
            }
        }
    }

    @Test
    fun `external progress is clamped at both extremes`() {
        assertEquals(0, CompressionPolicy.clampProgress(Int.MIN_VALUE))
        assertEquals(0, CompressionPolicy.clampProgress(0))
        assertEquals(53, CompressionPolicy.clampProgress(53))
        assertEquals(100, CompressionPolicy.clampProgress(100))
        assertEquals(100, CompressionPolicy.clampProgress(Int.MAX_VALUE))
    }

    @Test
    fun `display name handles plain encoded and windows path leaves`() {
        assertEquals("report", CompressionPolicy.displayBaseName("folder/report.PDF"))
        assertEquals("invoice", CompressionPolicy.displayBaseName("folder%2Finvoice.pdf"))
        assertEquals("scan", CompressionPolicy.displayBaseName("folder%2fscan.PdF"))
        assertEquals("paper", CompressionPolicy.displayBaseName("folder\\paper.pdf"))
    }

    @Test
    fun `display name removes controls directional overrides and unsafe blanks`() {
        assertEquals("safe-name", CompressionPolicy.displayBaseName("\u202Esafe\u0000-name.pdf"))
        assertEquals("document", CompressionPolicy.displayBaseName(null))
        assertEquals("document", CompressionPolicy.displayBaseName("\u0000\u202E.pdf"))
    }

    @Test
    fun `display name is deterministically bounded`() {
        val name = CompressionPolicy.displayBaseName("${"a".repeat(200)}.pdf")

        assertEquals(CompressionPolicy.MAX_DISPLAY_NAME_LENGTH, name.length)
        assertTrue(name.all { it == 'a' })
    }

    @Test
    fun `saved percentage handles unknown equal larger and empty outputs`() {
        assertNull(CompressionPolicy.savedPercent(0L, 100L))
        assertNull(CompressionPolicy.savedPercent(-1L, 100L))
        assertEquals(0, CompressionPolicy.savedPercent(1_000L, 1_000L))
        assertEquals(0, CompressionPolicy.savedPercent(1_000L, 2_000L))
        assertEquals(40, CompressionPolicy.savedPercent(1_000L, 600L))
        assertEquals(100, CompressionPolicy.savedPercent(1_000L, 0L))
    }

    @Test
    fun `saved percentage rejects impossible negative output size`() {
        assertThrows(IllegalArgumentException::class.java) {
            CompressionPolicy.savedPercent(1_000L, -1L)
        }
    }

    @Test
    fun `busy phases consume back while stable phases navigate`() {
        CompressionPhase.entries.forEach { phase ->
            val expected =
                if (phase == CompressionPhase.PREPARING || phase == CompressionPhase.COMPRESSING) {
                    CompressionBackAction.CANCEL_OPERATION
                } else {
                    CompressionBackAction.NAVIGATE_BACK
                }
            assertEquals(expected, CompressionPolicy.backAction(phase))
        }
    }

    @Test
    fun `operation generations advance and wrap without becoming negative`() {
        assertEquals(1L, CompressionPolicy.nextGeneration(0L))
        assertEquals(42L, CompressionPolicy.nextGeneration(41L))
        assertEquals(1L, CompressionPolicy.nextGeneration(Long.MAX_VALUE))
        assertThrows(IllegalArgumentException::class.java) {
            CompressionPolicy.nextGeneration(-1L)
        }
    }

    @Test
    fun `compression levels stay inside bounded rendering and encoding ranges`() {
        CompressLevel.entries.forEach { level ->
            assertTrue(level.jpegQuality in 1..100)
            assertTrue(level.maxDimensionPx in 1..1_600)
            assertFalse(level.estimatedReduction.isBlank())
        }
    }

    @Test
    fun `failure messages cover every stage without leaking exception details`() {
        val secret = "private-provider-path"
        val errors =
            listOf(
                SecurityException(secret),
                IOException(secret),
                IllegalArgumentException(secret),
                IllegalStateException(secret),
                RuntimeException(secret),
            )

        CompressionFailureStage.entries.forEach { stage ->
            errors.forEach { error ->
                val message = CompressionPolicy.failureMessage(stage, error)
                assertTrue(message.isNotBlank())
                assertFalse(message.contains(secret))
            }
        }
    }
}
