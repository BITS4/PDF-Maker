package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ThumbnailCachePolicyTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `cache identity changes with requested size and source metadata`() {
        val source = temporaryFolder.newFile("sample.pdf").apply { writeText("first") }
        val original = ThumbnailCachePolicy.key(source.path, 200)

        assertNotEquals(original, ThumbnailCachePolicy.key(source.path, 400))
        source.appendText("-changed")
        assertNotEquals(original, ThumbnailCachePolicy.key(source.path, 200))
    }

    @Test
    fun `equivalent paths resolve to the same cache identity`() {
        val source = temporaryFolder.newFile("sample.pdf").apply { writeText("pdf") }
        val alternate = requireNotNull(source.parentFile).resolve("folder/../${source.name}")

        assertEquals(
            ThumbnailCachePolicy.key(source.path, 200),
            ThumbnailCachePolicy.key(alternate.path, 200),
        )
    }

    @Test
    fun `pixel weights are exact and reject malformed dimensions`() {
        assertEquals(4_194_304L, ThumbnailCachePolicy.pixelWeight(2_048, 2_048))
        assertThrows(IllegalArgumentException::class.java) {
            ThumbnailCachePolicy.pixelWeight(0, 200)
        }
    }
}
