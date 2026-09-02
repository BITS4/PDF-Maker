package com.example.pdfmaker

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class ViewerArchiveIOTest {
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
}
