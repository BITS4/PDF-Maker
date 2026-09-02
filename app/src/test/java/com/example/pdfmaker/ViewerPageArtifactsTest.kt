package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ViewerPageArtifactsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `creates deterministic bounded page names`() {
        assertEquals("page-001", ViewerPageArtifactPolicy.pageBaseName(0))
        assertEquals(
            "page-020",
            ViewerPageArtifactPolicy.pageBaseName(ViewerResourceLimits.MAX_RENDERED_PAGES - 1),
        )
        assertTrue(runCatching { ViewerPageArtifactPolicy.pageBaseName(-1) }.isFailure)
        assertTrue(
            runCatching {
                ViewerPageArtifactPolicy.pageBaseName(ViewerResourceLimits.MAX_RENDERED_PAGES)
            }.isFailure,
        )
    }

    @Test
    fun `artifact metadata preserves a finite aspect ratio`() {
        val file = File(temporaryFolder.root, "page.png")
        val artifact = ViewerPageArtifact(file, pageNumber = 1, width = 100, height = 200)

        assertEquals(0.5f, artifact.aspectRatio)
        assertTrue(runCatching { ViewerPageArtifact(file, 0, 100, 200) }.isFailure)
        assertTrue(runCatching { ViewerPageArtifact(file, 1, 100, 0) }.isFailure)
    }

    @Test
    fun `accepts only bounded render dimensions`() {
        assertTrue(ViewerPageArtifactPolicy.acceptsDimensions(1_448, 2_048))
        assertTrue(ViewerPageArtifactPolicy.acceptsDimensions(2_048, 2_048))
        assertFalse(ViewerPageArtifactPolicy.acceptsDimensions(0, 100))
        assertFalse(ViewerPageArtifactPolicy.acceptsDimensions(2_049, 100))
        assertFalse(ViewerPageArtifactPolicy.acceptsDimensions(Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test
    fun `accepts only nonempty bounded artifacts`() {
        assertTrue(ViewerPageArtifactPolicy.acceptsArtifact(100, 200, 1))
        assertTrue(
            ViewerPageArtifactPolicy.acceptsArtifact(
                100,
                200,
                ViewerPageArtifactPolicy.MAX_ARTIFACT_BYTES,
            ),
        )
        assertFalse(ViewerPageArtifactPolicy.acceptsArtifact(100, 200, 0))
        assertFalse(
            ViewerPageArtifactPolicy.acceptsArtifact(
                100,
                200,
                ViewerPageArtifactPolicy.MAX_ARTIFACT_BYTES + 1,
            ),
        )
    }

    @Test
    fun `cleanup deletes only generated child directories`() {
        val root = temporaryFolder.newFolder("cache")
        val owned = File(root, "viewer-pages-123e4567-e89b-12d3-a456-426614174000").apply { mkdir() }
        File(owned, "page-001.png").writeBytes(byteArrayOf(1, 2, 3))
        val outside = temporaryFolder.newFolder("outside")
        File(outside, "keep.txt").writeText("keep")
        val malformed = File(root, "viewer-pages-------------------------------------").apply { mkdir() }

        assertTrue(deleteViewerArtifactDirectory(root, owned))
        assertFalse(owned.exists())
        assertFalse(deleteViewerArtifactDirectory(root, outside))
        assertTrue(File(outside, "keep.txt").isFile)
        assertFalse(deleteViewerArtifactDirectory(root, malformed))
        assertTrue(malformed.isDirectory)
    }
}
