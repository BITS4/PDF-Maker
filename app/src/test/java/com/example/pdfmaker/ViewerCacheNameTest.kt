package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ViewerCacheNameTest {
    @Test
    fun `accepts generated document names`() {
        assertEquals("shared_123.pdf", requireSafeCacheFileName("shared_123.pdf"))
        assertEquals("slides-42.pptx", requireSafeCacheFileName("slides-42.pptx"))
    }

    @Test
    fun `rejects traversal separators and control characters`() {
        listOf("../secret.pdf", "..\\secret.pdf", "bad\nname.pdf").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { requireSafeCacheFileName(name) }
        }
    }

    @Test
    fun `rejects blank dot and oversized names`() {
        listOf("", ".", "..", "a".repeat(129)).forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { requireSafeCacheFileName(name) }
        }
    }
}
