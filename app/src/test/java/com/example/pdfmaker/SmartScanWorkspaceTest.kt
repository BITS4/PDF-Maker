package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SmartScanWorkspaceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun artifactsAreUniqueDirectChildrenOfTheSession() {
        val workspace = SmartScanWorkspace.create(temporaryFolder.root)

        val first = workspace.newArtifact(SmartScanArtifact.CAPTURE)
        val second = workspace.newArtifact(SmartScanArtifact.NORMALIZED)

        assertEquals(workspace.directory.canonicalFile, first.canonicalFile.parentFile)
        assertEquals(workspace.directory.canonicalFile, second.canonicalFile.parentFile)
        assertNotEquals(first.name, second.name)
        assertTrue(first.name.matches(Regex("^capture-[0-9a-f-]{36}\\.jpg$")))
        assertTrue(second.name.matches(Regex("^normalized-[0-9a-f-]{36}\\.jpg$")))
        workspace.close()
    }

    @Test
    fun closeDeletesUnhandedArtifactsAndTheEmptySession() {
        val workspace = SmartScanWorkspace.create(temporaryFolder.root)
        val artifact =
            workspace.newArtifact(SmartScanArtifact.CAPTURE).apply {
                writeBytes(byteArrayOf(1, 2, 3))
            }

        workspace.close()

        assertFalse(artifact.exists())
        assertFalse(workspace.directory.exists())
    }

    @Test
    fun handoffRetainsOnlyTheRequestedCompletedArtifact() {
        val workspace = SmartScanWorkspace.create(temporaryFolder.root)
        val handedOff =
            workspace.newArtifact(SmartScanArtifact.NORMALIZED).apply {
                writeBytes(byteArrayOf(1))
            }
        val abandoned =
            workspace.newArtifact(SmartScanArtifact.CAPTURE).apply {
                writeBytes(byteArrayOf(2))
            }

        assertEquals(listOf(handedOff), workspace.handoff(listOf(handedOff, handedOff)))
        workspace.discard(handedOff)
        workspace.close()

        assertTrue(handedOff.isFile)
        assertFalse(abandoned.exists())
        assertTrue(workspace.directory.isDirectory)
    }

    @Test
    fun emptyMissingAndForeignArtifactsCannotBeHandedOff() {
        val workspace = SmartScanWorkspace.create(temporaryFolder.root)
        val empty = workspace.newArtifact(SmartScanArtifact.NORMALIZED)
        val foreign = temporaryFolder.newFile("foreign.jpg").apply { writeBytes(byteArrayOf(1)) }

        assertTrue(runCatching { workspace.handoff(emptyList()) }.isFailure)
        assertTrue(runCatching { workspace.handoff(listOf(empty)) }.isFailure)
        assertTrue(runCatching { workspace.handoff(listOf(foreign)) }.isFailure)
        workspace.close()
        assertTrue(foreign.isFile)
    }

    @Test
    fun discardDeletesOnlyOwnedSessionChildren() {
        val workspace = SmartScanWorkspace.create(temporaryFolder.root)
        val owned =
            workspace.newArtifact(SmartScanArtifact.CAPTURE).apply {
                writeBytes(byteArrayOf(1))
            }
        val foreign = temporaryFolder.newFile("foreign.jpg").apply { writeBytes(byteArrayOf(2)) }

        workspace.discard(foreign)
        workspace.discard(owned)

        assertTrue(foreign.isFile)
        assertFalse(owned.exists())
        workspace.close()
    }

    @Test
    fun closedWorkspaceIsIdempotentAndRejectsNewArtifacts() {
        val workspace = SmartScanWorkspace.create(temporaryFolder.root)

        workspace.close()
        workspace.close()

        assertTrue(runCatching { workspace.newArtifact(SmartScanArtifact.CAPTURE) }.isFailure)
        assertTrue(runCatching { workspace.handoff(listOf(temporaryFolder.root)) }.isFailure)
    }

    @Test
    fun staleCleanupDeletesOldFlatSessionsButPreservesCurrentAndRecentSessions() {
        val now = 2L * SmartScanWorkspace.STALE_SESSION_AGE_MILLIS
        val current = SmartScanWorkspace.create(temporaryFolder.root)
        val old = SmartScanWorkspace.create(temporaryFolder.root)
        val recent = SmartScanWorkspace.create(temporaryFolder.root)
        val oldArtifact = old.newArtifact(SmartScanArtifact.NORMALIZED).apply { writeBytes(byteArrayOf(1)) }
        val recentArtifact = recent.newArtifact(SmartScanArtifact.NORMALIZED).apply { writeBytes(byteArrayOf(2)) }
        setTimestamp(old.directory, oldArtifact, now - SmartScanWorkspace.STALE_SESSION_AGE_MILLIS - 1)
        setTimestamp(recent.directory, recentArtifact, now - 1)

        val deleted =
            SmartScanWorkspace.deleteStaleSessions(
                cacheRoot = temporaryFolder.root,
                currentSession = current.directory,
                nowMillis = now,
            )

        assertEquals(1, deleted)
        assertFalse(old.directory.exists())
        assertTrue(recent.directory.isDirectory)
        assertTrue(current.directory.isDirectory)
        current.close()
        recent.close()
    }

    @Test
    fun staleCleanupWillNotTraverseUnexpectedNestedDirectories() {
        val now = 2L * SmartScanWorkspace.STALE_SESSION_AGE_MILLIS
        val current = SmartScanWorkspace.create(temporaryFolder.root)
        val suspicious = SmartScanWorkspace.create(temporaryFolder.root)
        val nested = File(suspicious.directory, "nested").apply { mkdir() }
        val nestedFile = File(nested, "keep.txt").apply { writeText("keep") }
        setTimestamp(suspicious.directory, nested, now - SmartScanWorkspace.STALE_SESSION_AGE_MILLIS - 1)
        nestedFile.setLastModified(now - SmartScanWorkspace.STALE_SESSION_AGE_MILLIS - 1)

        val deleted =
            SmartScanWorkspace.deleteStaleSessions(
                cacheRoot = temporaryFolder.root,
                currentSession = current.directory,
                nowMillis = now,
            )

        assertEquals(0, deleted)
        assertTrue(nestedFile.isFile)
        current.close()
    }

    @Test
    fun invalidCleanupTimesFailClosed() {
        val workspace = SmartScanWorkspace.create(temporaryFolder.root)

        assertTrue(
            runCatching {
                SmartScanWorkspace.deleteStaleSessions(
                    temporaryFolder.root,
                    workspace.directory,
                    nowMillis = -1,
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                SmartScanWorkspace.deleteStaleSessions(
                    temporaryFolder.root,
                    workspace.directory,
                    maximumAgeMillis = 0,
                )
            }.isFailure,
        )
        workspace.close()
    }

    private fun setTimestamp(
        directory: File,
        artifact: File,
        timestamp: Long,
    ) {
        assertTrue(artifact.setLastModified(timestamp))
        assertTrue(directory.setLastModified(timestamp))
    }
}
