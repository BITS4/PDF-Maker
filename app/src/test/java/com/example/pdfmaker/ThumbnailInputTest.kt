package com.example.pdfmaker

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ThumbnailInputTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun xmlPreviewAcceptsOrdinaryContentAndRejectsEntities() {
        assertEquals(
            "<document>safe</document>",
            ThumbnailInput.readXml(ByteArrayInputStream("<document>safe</document>".toByteArray())),
        )
        assertTrue(
            runCatching {
                ThumbnailInput.readXml(ByteArrayInputStream("<!DOCTYPE x><document/>".toByteArray()))
            }.isFailure,
        )
    }

    @Test
    fun textPreviewIsBoundedAndRejectsEmptySources() {
        val text = temporaryFolder.newFile("notes.txt").apply { writeText("first\nsecond") }
        val empty = temporaryFolder.newFile("empty.txt")

        assertEquals("first\nsecond", ThumbnailInput.readTextPrefix(text))
        assertTrue(runCatching { ThumbnailInput.readTextPrefix(empty) }.isFailure)
    }

    @Test
    fun sourcePolicyRejectsOversizedSparseFiles() {
        val oversized = temporaryFolder.newFile("oversized.docx")
        java.io.RandomAccessFile(oversized, "rw").use {
            it.setLength(ThumbnailInput.MAX_SOURCE_BYTES + 1)
        }

        assertTrue(!ThumbnailInput.isAllowedSource(oversized))
    }

    @Test
    fun archivePolicyRejectsTraversalAndExcessiveEntryCounts() {
        ThumbnailInput.validateArchiveEntry(1, "word/document.xml")

        assertTrue(runCatching { ThumbnailInput.validateArchiveEntry(1, "../secret") }.isFailure)
        assertTrue(runCatching { ThumbnailInput.validateArchiveEntry(1, "word\\document.xml") }.isFailure)
        assertTrue(runCatching { ThumbnailInput.validateArchiveEntry(4_097, "word/document.xml") }.isFailure)
    }
}
