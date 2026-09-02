package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplaySizeParserTest {
    @Test
    fun `parses binary display units`() {
        assertEquals(42L, FileCatalog.parseDisplaySizeBytes("42 B"))
        assertEquals(2_048L, FileCatalog.parseDisplaySizeBytes("2 KB"))
        assertEquals(1_572_864L, FileCatalog.parseDisplaySizeBytes("1.5 MB"))
        assertEquals(2_684_354_560L, FileCatalog.parseDisplaySizeBytes("2.5 GB"))
        assertEquals(1_099_511_627_776L, FileCatalog.parseDisplaySizeBytes("1 TB"))
    }

    @Test
    fun `accepts localized decimals and optional byte unit`() {
        assertEquals(1_536L, FileCatalog.parseDisplaySizeBytes(" 1,5 kb "))
        assertEquals(99L, FileCatalog.parseDisplaySizeBytes("99"))
    }

    @Test
    fun `rejects malformed or negative sizes`() {
        listOf("", "unknown", "-2 MB", "2 XB", "MB").forEach {
            assertEquals(0L, FileCatalog.parseDisplaySizeBytes(it))
        }
    }

    @Test
    fun `saturates values beyond long range`() {
        assertEquals(Long.MAX_VALUE, FileCatalog.parseDisplaySizeBytes("999999999999999999999 TB"))
    }
}
