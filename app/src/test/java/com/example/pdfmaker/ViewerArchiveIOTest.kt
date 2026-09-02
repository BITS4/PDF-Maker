package com.example.pdfmaker

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.RandomAccessFile

class ViewerArchiveIOTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `reads an entry at the configured boundary`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        assertArrayEquals(bytes, readBoundedViewerEntry(ByteArrayInputStream(bytes), bytes.size))
        assertArrayEquals(byteArrayOf(), readBoundedViewerEntry(ByteArrayInputStream(byteArrayOf()), 1))
    }

    @Test
    fun `rejects an entry beyond the configured boundary`() {
        assertThrows(IOException::class.java) {
            readBoundedViewerEntry(ByteArrayInputStream(ByteArray(5)), 4)
        }
    }

    @Test
    fun `rejects a non-positive boundary`() {
        assertThrows(IllegalArgumentException::class.java) {
            readBoundedViewerEntry(ByteArrayInputStream(byteArrayOf(1)), 0)
        }
    }

    @Test
    fun `archive budget counts captured and skipped expanded bytes`() {
        val budget = ViewerArchiveBudget(maximumEntries = 3, maximumExpandedBytes = 6)
        budget.beginEntry("word/document.xml")
        assertArrayEquals(
            byteArrayOf(1, 2, 3),
            budget.readEntry(ByteArrayInputStream(byteArrayOf(1, 2, 3)), maximumEntryBytes = 3),
        )
        budget.beginEntry("word/styles.xml")
        budget.skipEntry(ByteArrayInputStream(byteArrayOf(4, 5, 6)))

        assertEquals(2, budget.entryCount)
        assertEquals(6L, budget.expandedBytes)
    }

    @Test
    fun `archive budget rejects aggregate expansion and too many entries`() {
        val aggregateBudget = ViewerArchiveBudget(maximumEntries = 2, maximumExpandedBytes = 4)
        aggregateBudget.beginEntry("word/document.xml")
        aggregateBudget.readEntry(ByteArrayInputStream(byteArrayOf(1, 2, 3)), maximumEntryBytes = 4)
        aggregateBudget.beginEntry("word/styles.xml")
        assertThrows(IOException::class.java) {
            aggregateBudget.readEntry(ByteArrayInputStream(byteArrayOf(4, 5)), maximumEntryBytes = 4)
        }

        val entryBudget = ViewerArchiveBudget(maximumEntries = 1, maximumExpandedBytes = 8)
        entryBudget.beginEntry("word/document.xml")
        assertThrows(IOException::class.java) { entryBudget.beginEntry("word/styles.xml") }
    }

    @Test
    fun `archive budget rejects unsafe paths and active XML content`() {
        listOf("../secret", "/absolute", "word\\document.xml", "C:/secret", "word/./document.xml")
            .forEach { name ->
                assertFalse(isSafeViewerArchiveEntryName(name))
                assertThrows(IOException::class.java) {
                    ViewerArchiveBudget().beginEntry(name)
                }
            }
        assertTrue(isSafeViewerArchiveEntryName("word/document.xml"))

        val budget = ViewerArchiveBudget(maximumEntries = 1, maximumExpandedBytes = 1_024)
        budget.beginEntry("word/document.xml")
        assertThrows(IllegalArgumentException::class.java) {
            budget.readXml(ByteArrayInputStream("<!DOCTYPE doc><doc/>".toByteArray()))
        }
    }

    @Test
    fun `bounded viewer file detects oversized sources and returns UTF-8 text`() {
        val text = temporaryFolder.newFile("notes.txt").apply { writeText("safe preview") }
        assertEquals("safe preview", readBoundedViewerText(text))

        val oversized = temporaryFolder.newFile("oversized.txt")
        RandomAccessFile(oversized, "rw").use { it.setLength(MAX_VIEWER_TEXT_BYTES.toLong() + 1) }
        assertThrows(IllegalArgumentException::class.java) { readBoundedViewerText(oversized) }
    }
}
