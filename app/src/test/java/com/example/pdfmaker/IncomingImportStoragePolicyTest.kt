package com.example.pdfmaker

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class IncomingImportStoragePolicyTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun onlyImportedImagesUseTemporaryCacheStorage() {
        val documentDirectory = File(temporaryFolder.root, "documents")
        val cacheDirectory = File(temporaryFolder.root, "cache")

        IncomingDocumentKind.entries.forEach { kind ->
            val expected =
                if (kind in imageKinds) File(cacheDirectory, "pdfmaker/incoming") else documentDirectory
            assertEquals(
                expected,
                IncomingImportStoragePolicy.destination(
                    kind,
                    IncomingImportRetention.USER_DOCUMENT,
                    documentDirectory,
                    cacheDirectory,
                ),
            )
            assertEquals(
                kind in imageKinds,
                IncomingImportStoragePolicy.isTemporary(kind, IncomingImportRetention.USER_DOCUMENT),
            )
            assertEquals(
                File(cacheDirectory, "pdfmaker/incoming"),
                IncomingImportStoragePolicy.destination(
                    kind,
                    IncomingImportRetention.OPERATION_TEMPORARY,
                    documentDirectory,
                    cacheDirectory,
                ),
            )
            assertTrue(
                IncomingImportStoragePolicy.isTemporary(kind, IncomingImportRetention.OPERATION_TEMPORARY),
            )
        }
    }

    @Test
    fun pendingArtifactDeletesItsFileUnlessOwnershipIsAccepted() {
        val directory = temporaryFolder.newFolder("pending")
        val pending = File(directory, "pending.pdf").apply { writeBytes(byteArrayOf(1)) }
        val artifact =
            ImportedDocumentArtifact.claim(
                pending,
                IncomingDocumentKind.PDF,
                temporary = false,
                expectedDirectory = directory,
            )

        artifact.close()

        assertFalse(pending.exists())
    }

    @Test
    fun retainedPersistentArtifactSurvivesClose() {
        val directory = temporaryFolder.newFolder("retained")
        val retained = File(directory, "retained.pdf").apply { writeBytes(byteArrayOf(1)) }
        val artifact =
            ImportedDocumentArtifact.claim(
                retained,
                IncomingDocumentKind.PDF,
                temporary = false,
                expectedDirectory = directory,
            )

        assertEquals(retained.canonicalFile, artifact.retain())
        artifact.close()

        assertTrue(retained.isFile)
    }

    @Test
    fun temporaryArtifactTransfersExactlyOneCleanupLease() {
        val directory = temporaryFolder.newFolder("transferred")
        val temporary = File(directory, "temporary.png").apply { writeBytes(byteArrayOf(1)) }
        val artifact =
            ImportedDocumentArtifact.claim(
                temporary,
                IncomingDocumentKind.PNG,
                temporary = true,
                expectedDirectory = directory,
            )

        val lease = artifact.transferTemporary()
        artifact.close()
        assertTrue(temporary.isFile)
        assertTrue(runCatching { artifact.transferTemporary() }.isFailure)

        lease.close()
        assertFalse(temporary.exists())
    }

    @Test
    fun temporaryImportLeaseDeletesOnlyItsClaimedFileAndIsIdempotent() {
        val directory = temporaryFolder.newFolder("cache", "pdfmaker", "incoming")
        val claimed = File(directory, "claimed.png").apply { writeBytes(byteArrayOf(1)) }
        val sibling = File(directory, "sibling.png").apply { writeBytes(byteArrayOf(2)) }
        val lease = TemporaryImportLease.claim(claimed, directory)

        lease.close()
        lease.close()

        assertFalse(claimed.exists())
        assertTrue(sibling.isFile)
    }

    @Test
    fun temporaryImportLeaseRejectsFilesOutsideTheExpectedDirectory() {
        val expected = temporaryFolder.newFolder("expected")
        val outside = temporaryFolder.newFile("outside.png")

        assertTrue(runCatching { TemporaryImportLease.claim(outside, expected) }.isFailure)
        assertTrue(outside.isFile)
    }

    private companion object {
        val imageKinds =
            setOf(
                IncomingDocumentKind.JPEG,
                IncomingDocumentKind.PNG,
                IncomingDocumentKind.GIF,
                IncomingDocumentKind.WEBP,
                IncomingDocumentKind.BMP,
            )
    }
}
