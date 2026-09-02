package com.example.pdfmaker

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SafeFileNameTest {
    @Test
    fun removesTraversalAndPathSeparators() {
        val result = SafeFileName.baseName(" ../../private\\secret ")

        assertFalse(result.contains('/'))
        assertFalse(result.contains('\\'))
        assertFalse(result == "..")
        assertTrue(result.contains("private"))
    }

    @Test
    fun normalizesUnicodeAndDropsDirectionalOverrides() {
        assertEquals("Caféreport", SafeFileName.baseName("Cafe\u0301\u202Ereport"))
    }

    @Test
    fun substitutesSafeFallbackForReservedOrBlankNames() {
        assertEquals("scan", SafeFileName.baseName(" .. ", "scan"))
        assertEquals("document", SafeFileName.baseName("\u0000\u0001"))
    }

    @Test
    fun boundsNamesAndValidatesExtensions() {
        assertEquals(80, SafeFileName.baseName("a".repeat(200)).length)
        assertEquals("pdf", SafeFileName.extension(".PDF"))
        try {
            SafeFileName.extension("../pdf")
            fail("Traversal extension must be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}

class OutputStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun writesInsideRootAndAllocatesCollisionSafeNames() {
        val root = temporaryFolder.newFolder("outputs")
        val first = OutputStore.writeUnique(root, "report", "pdf") { it.write(byteArrayOf(1)) }
        val second = OutputStore.writeUnique(root, "report", "pdf") { it.write(byteArrayOf(2)) }

        assertEquals(root.canonicalFile, first.parentFile.canonicalFile)
        assertEquals("report.pdf", first.name)
        assertEquals("report (1).pdf", second.name)
        assertArrayEquals(byteArrayOf(1), first.readBytes())
        assertArrayEquals(byteArrayOf(2), second.readBytes())
    }

    @Test
    fun traversalCannotEscapeRoot() {
        val root = temporaryFolder.newFolder("safe")
        val output = OutputStore.writeUnique(root, "../../outside", "pdf") { it.write(7) }

        assertEquals(root.canonicalFile, output.parentFile.canonicalFile)
        assertFalse(File(root.parentFile, "outside.pdf").exists())
    }

    @Test
    fun failedWriterLeavesNeitherOutputNorTemporaryFile() {
        val root = temporaryFolder.newFolder("atomic")
        try {
            OutputStore.writeUnique(root, "broken", "pdf") {
                it.write(byteArrayOf(1, 2, 3))
                error("simulated failure")
            }
            fail("Writer should fail")
        } catch (_: IllegalStateException) {
            // expected
        }

        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun atomicReplacementPreservesTargetNameAndContents() {
        val target = temporaryFolder.newFile("locked.pdf").apply { writeBytes(byteArrayOf(1)) }

        OutputStore.replaceAtomically(target, byteArrayOf(9, 8, 7))

        assertArrayEquals(byteArrayOf(9, 8, 7), target.readBytes())
        assertEquals("locked.pdf", target.name)
    }

    @Test
    fun renameSanitizesTraversalAndRefusesExistingDestination() {
        val root = temporaryFolder.newFolder("rename")
        val source = File(root, "source.pdf").apply { writeText("source") }
        val renamed = OutputStore.renameWithinParent(source, "../renamed").getOrThrow()
        assertEquals(root.canonicalFile, renamed.parentFile.canonicalFile)
        assertTrue(renamed.name.endsWith("renamed.pdf"))

        val other = File(root, "other.pdf").apply { writeText("keep") }
        val collision = OutputStore.renameWithinParent(renamed, "other")
        assertTrue(collision.isFailure)
        assertEquals("keep", other.readText())
        assertNotEquals(other.canonicalFile, renamed.canonicalFile)
    }
}
