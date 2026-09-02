package com.example.pdfmaker

import java.io.File
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImportedImageValidatorPolicyTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun acceptsOnlyDecoderMimeForExpectedSignature() {
        assertTrue(ImportedImageValidator.mimeMatches(IncomingDocumentKind.JPEG, "IMAGE/JPEG"))
        assertTrue(ImportedImageValidator.mimeMatches(IncomingDocumentKind.PNG, "image/png"))
        assertTrue(ImportedImageValidator.mimeMatches(IncomingDocumentKind.GIF, "image/gif"))
        assertTrue(ImportedImageValidator.mimeMatches(IncomingDocumentKind.WEBP, "image/webp"))
        assertTrue(ImportedImageValidator.mimeMatches(IncomingDocumentKind.BMP, "image/bmp"))
        assertTrue(ImportedImageValidator.mimeMatches(IncomingDocumentKind.BMP, "image/x-ms-bmp"))
        assertFalse(ImportedImageValidator.mimeMatches(IncomingDocumentKind.JPEG, "image/png"))
        assertFalse(ImportedImageValidator.mimeMatches(IncomingDocumentKind.PNG, null))
    }

    @Test
    fun rejectsNonImageKindsEvenWithImageMime() {
        assertFalse(ImportedImageValidator.mimeMatches(IncomingDocumentKind.PDF, "image/jpeg"))
        assertFalse(ImportedImageValidator.mimeMatches(IncomingDocumentKind.DOCX, "image/png"))
    }

    @Test
    fun encodedSizeMatchesTheDownstreamDecoderBoundary() {
        assertFalse(ImportedImageValidator.hasSupportedEncodedSize(0L))
        assertTrue(ImportedImageValidator.hasSupportedEncodedSize(1L))
        assertTrue(ImportedImageValidator.hasSupportedEncodedSize(ImageInputPolicy.MAX_ENCODED_BYTES))
        assertFalse(ImportedImageValidator.hasSupportedEncodedSize(ImageInputPolicy.MAX_ENCODED_BYTES + 1L))
    }

    @Test
    fun validatesBoundsAndUsesAResourceBoundedSample() {
        val decoder = FakeDecoder(ImportedImageMetadata("image/jpeg", 3_200, 3_200), 800, 800)

        assertTrue(ImportedImageValidator.validate(sourceFile(), IncomingDocumentKind.JPEG, decoder = decoder))
        assertEquals(4, decoder.requestedSampleSize)
        assertTrue(decoder.decodedClosed)
    }

    @Test
    fun rejectsMimeConfusionAndOversizedSourcesBeforeDecode() {
        val wrongMime = FakeDecoder(ImportedImageMetadata("image/png", 100, 100), 100, 100)
        val oversized = FakeDecoder(ImportedImageMetadata("image/jpeg", 5_000, 5_000), 100, 100)

        assertFalse(ImportedImageValidator.validate(sourceFile("wrong"), IncomingDocumentKind.JPEG, decoder = wrongMime))
        assertFalse(ImportedImageValidator.validate(sourceFile("large"), IncomingDocumentKind.JPEG, decoder = oversized))
        assertNull(wrongMime.requestedSampleSize)
        assertNull(oversized.requestedSampleSize)
    }

    @Test
    fun rejectsMissingOrInvalidDecodedContent() {
        val missing = FakeDecoder(ImportedImageMetadata("image/png", 100, 100), null, null)
        val invalid = FakeDecoder(ImportedImageMetadata("image/png", 100, 100), 2_000, 2_000)

        assertFalse(ImportedImageValidator.validate(sourceFile("missing"), IncomingDocumentKind.PNG, decoder = missing))
        assertFalse(ImportedImageValidator.validate(sourceFile("invalid"), IncomingDocumentKind.PNG, decoder = invalid))
        assertTrue(invalid.decodedClosed)
    }

    @Test
    fun rejectsInvalidFilesKindsAndMissingMetadata() {
        val absent = File(temporaryFolder.root, "absent.jpg")
        val empty = temporaryFolder.newFile("empty.jpg")
        val directory = temporaryFolder.newFolder("directory.jpg")
        val decoder = FakeDecoder(null, 100, 100)

        assertFalse(ImportedImageValidator.validate(absent, IncomingDocumentKind.JPEG, decoder = decoder))
        assertFalse(ImportedImageValidator.validate(empty, IncomingDocumentKind.JPEG, decoder = decoder))
        assertFalse(ImportedImageValidator.validate(directory, IncomingDocumentKind.JPEG, decoder = decoder))
        assertFalse(ImportedImageValidator.validate(sourceFile("pdf"), IncomingDocumentKind.PDF, decoder = decoder))
        assertFalse(ImportedImageValidator.validate(sourceFile("metadata"), IncomingDocumentKind.JPEG, decoder = decoder))
    }

    @Test
    fun closesDecodedContentWhenCancellationArrives() {
        val decoder = FakeDecoder(ImportedImageMetadata("image/jpeg", 100, 100), 100, 100)
        var checkpoints = 0

        assertThrows(CancellationException::class.java) {
            ImportedImageValidator.validate(
                file = sourceFile("cancelled"),
                expectedKind = IncomingDocumentKind.JPEG,
                beforeChunk = {
                    checkpoints += 1
                    if (checkpoints == 4) throw CancellationException("cancelled")
                },
                decoder = decoder,
            )
        }
        assertEquals(4, checkpoints)
        assertTrue(decoder.decodedClosed)
    }

    private fun sourceFile(label: String = "source"): File =
        temporaryFolder.newFile("$label.jpg").apply { writeBytes(byteArrayOf(1)) }

    private class FakeDecoder(
        private val metadata: ImportedImageMetadata?,
        private val decodedWidth: Int?,
        private val decodedHeight: Int?,
    ) : ImportedImageDecoder {
        var requestedSampleSize: Int? = null
        var decodedClosed = false

        override fun readBounds(file: File): ImportedImageMetadata? = metadata

        override fun decodeSample(file: File, sampleSize: Int): ImportedDecodedImage? {
            requestedSampleSize = sampleSize
            val decodedWidthValue = decodedWidth ?: return null
            val decodedHeightValue = decodedHeight ?: return null
            return object : ImportedDecodedImage {
                override val width: Int = decodedWidthValue
                override val height: Int = decodedHeightValue

                override fun close() {
                    decodedClosed = true
                }
            }
        }
    }
}
