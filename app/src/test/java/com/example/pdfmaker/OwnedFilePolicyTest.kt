package com.example.pdfmaker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OwnedFilePolicyTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `accepts nested files under any configured root`() {
        val firstRoot = temporaryFolder.newFolder("first")
        val secondRoot = temporaryFolder.newFolder("second")
        val nested =
            File(secondRoot, "nested/report.pdf").apply {
                parentFile?.mkdirs()
                writeText("pdf")
            }

        assertTrue(OwnedFilePolicy.contains(listOf(firstRoot, secondRoot), nested))
    }

    @Test
    fun `rejects siblings roots and traversal outside the owned tree`() {
        val root = temporaryFolder.newFolder("owned")
        val sibling = temporaryFolder.newFile("private.pdf")
        val traversal = File(root, "../${sibling.name}")

        assertFalse(OwnedFilePolicy.contains(listOf(root), sibling))
        assertFalse(OwnedFilePolicy.contains(listOf(root), traversal))
        assertFalse(OwnedFilePolicy.contains(emptyList(), sibling))
    }

    @Test
    fun `rejects deleting the configured root itself`() {
        val root = temporaryFolder.newFolder("documents")

        assertFalse(OwnedFilePolicy.contains(listOf(root), root))
    }
}
