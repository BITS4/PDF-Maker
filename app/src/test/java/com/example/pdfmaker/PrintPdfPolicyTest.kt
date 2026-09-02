package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PrintPdfPolicyTest {
    @Test
    fun `accepts both supported page-count boundaries`() {
        assertEquals(1, PrintPdfPolicy.requirePageCount(1))
        assertEquals(PrintPdfPolicy.MAX_PAGES, PrintPdfPolicy.requirePageCount(PrintPdfPolicy.MAX_PAGES))
    }

    @Test
    fun `rejects empty negative and oversized page counts`() {
        listOf(-1, 0, PrintPdfPolicy.MAX_PAGES + 1, Int.MAX_VALUE).forEach { pageCount ->
            assertThrows(IllegalArgumentException::class.java) {
                PrintPdfPolicy.requirePageCount(pageCount)
            }
        }
    }

    @Test
    fun `selects and deduplicates overlapping requested ranges`() {
        val selected =
            PrintPdfPolicy.selectedPages(
                pageCount = 8,
                requestedRanges =
                    listOf(
                        PrintPageSpan(5, 7),
                        PrintPageSpan(1, 3),
                        PrintPageSpan(2, 6),
                    ),
            )

        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), selected)
    }

    @Test
    fun `clamps the all-pages sentinel and negative starts to document bounds`() {
        assertEquals(
            listOf(0, 1, 2, 3),
            PrintPdfPolicy.selectedPages(4, listOf(PrintPageSpan(0, Int.MAX_VALUE))),
        )
        assertEquals(
            listOf(0, 1),
            PrintPdfPolicy.selectedPages(4, listOf(PrintPageSpan(Int.MIN_VALUE, 1))),
        )
    }

    @Test
    fun `ignores reversed and fully out-of-bounds ranges`() {
        assertEquals(
            listOf(2),
            PrintPdfPolicy.selectedPages(
                pageCount = 5,
                requestedRanges =
                    listOf(
                        PrintPageSpan(4, 2),
                        PrintPageSpan(-10, -1),
                        PrintPageSpan(5, 10),
                        PrintPageSpan(2, 2),
                    ),
            ),
        )
        assertTrue(PrintPdfPolicy.selectedPages(2, emptyList()).isEmpty())
    }

    @Test
    fun `collapses unsorted duplicate page indexes into minimal ranges`() {
        assertEquals(
            listOf(PrintPageSpan(0, 2), PrintPageSpan(5, 6), PrintPageSpan(9, 9)),
            PrintPdfPolicy.collapsedRanges(listOf(6, 1, 0, 2, 5, 5, 9)),
        )
        assertTrue(PrintPdfPolicy.collapsedRanges(emptyList()).isEmpty())
    }

    @Test
    fun `rejects negative indexes when reporting written ranges`() {
        assertThrows(IllegalArgumentException::class.java) {
            PrintPdfPolicy.collapsedRanges(listOf(-1, 0))
        }
    }

    @Test
    fun `uses lossless original mode only for a complete ordered selection`() {
        assertEquals(
            PrintWriteMode.ORIGINAL,
            PrintPdfPolicy.requireWritableSelection(4, listOf(0, 1, 2, 3)),
        )
        assertEquals(
            PrintWriteMode.RENDERED_SELECTION,
            PrintPdfPolicy.requireWritableSelection(4, listOf(0, 2, 3)),
        )
    }

    @Test
    fun `rejects empty unordered duplicate foreign and oversized partial selections`() {
        val oversizedPartial = (0 until PrintPdfPolicy.MAX_RENDERED_PAGES + 1).toList()
        listOf(
            emptyList(),
            listOf(1, 0),
            listOf(0, 0),
            listOf(0, 3),
            oversizedPartial,
        ).forEach { selected ->
            val pageCount = if (selected === oversizedPartial) selected.size + 1 else 3
            assertThrows(IllegalArgumentException::class.java) {
                PrintPdfPolicy.requireWritableSelection(pageCount, selected)
            }
        }
    }

    @Test
    fun `derives a bounded print-resolution bitmap without changing aspect ratio`() {
        val landscape = requireNotNull(PrintPdfPolicy.renderSize(612, 792, 540, 720, 300, 600))
        val veryLarge = requireNotNull(PrintPdfPolicy.renderSize(10_000, 10_000, 10_000, 10_000, 1_200, 1_200))

        assertEquals(PixelSize(2_250, 2_912), landscape)
        assertTrue(veryLarge.width <= PrintPdfPolicy.MAX_RENDER_EDGE)
        assertTrue(veryLarge.height <= PrintPdfPolicy.MAX_RENDER_EDGE)
        assertTrue(veryLarge.pixelCount <= PrintPdfPolicy.MAX_RENDER_PIXELS)
        assertEquals(veryLarge.width, veryLarge.height)
    }

    @Test
    fun `rejects every invalid render-size dimension`() {
        assertNull(PrintPdfPolicy.renderSize(0, 10, 10, 10, 300, 300))
        assertNull(PrintPdfPolicy.renderSize(10, 0, 10, 10, 300, 300))
        assertNull(PrintPdfPolicy.renderSize(10, 10, 0, 10, 300, 300))
        assertNull(PrintPdfPolicy.renderSize(10, 10, 10, 0, 300, 300))
        assertNull(PrintPdfPolicy.renderSize(10, 10, 10, 10, 0, 300))
        assertNull(PrintPdfPolicy.renderSize(10, 10, 10, 10, 300, 0))
    }

    @Test
    fun `centers landscape and portrait pages inside printable content`() {
        assertEquals(
            PrintDestinationRect(0, 125, 1_000, 875),
            PrintPdfPolicy.destinationRect(4, 3, 0, 0, 1_000, 1_000),
        )
        assertEquals(
            PrintDestinationRect(350, 200, 650, 800),
            PrintPdfPolicy.destinationRect(1, 2, 200, 200, 800, 800),
        )
    }

    @Test
    fun `rejects invalid source and printable destination rectangles`() {
        assertNull(PrintPdfPolicy.destinationRect(0, 1, 0, 0, 10, 10))
        assertNull(PrintPdfPolicy.destinationRect(1, 0, 0, 0, 10, 10))
        assertNull(PrintPdfPolicy.destinationRect(1, 1, 10, 0, 10, 10))
        assertNull(PrintPdfPolicy.destinationRect(1, 1, 0, 10, 10, 10))
    }
}
