package com.example.pdfmaker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfToJpgSharePolicyTest {
    @Test
    fun `accepts bounded JPEG envelopes with standard initial markers`() {
        listOf(0xC0, 0xC4, 0xDB, 0xE0, 0xEF, 0xFE).forEach { marker ->
            assertTrue(
                PdfToJpgPolicy.hasJpegEnvelope(
                    fileBytes = 1_024L,
                    prefix = jpegPrefix(marker),
                    suffix = jpegSuffix(),
                ),
            )
        }
    }

    @Test
    fun `rejects missing or deceptive JPEG magic`() {
        val invalidPrefixes =
            listOf(
                byteArrayOf(),
                byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()),
                byteArrayOf(0x00, 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()),
                byteArrayOf(0xFF.toByte(), 0x00, 0xFF.toByte(), 0xE0.toByte()),
                byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0xE0.toByte()),
                jpegPrefix(0x00),
                jpegPrefix(0xD8),
            )

        invalidPrefixes.forEach { prefix ->
            assertFalse(PdfToJpgPolicy.hasJpegEnvelope(1_024L, prefix, jpegSuffix()))
        }
        assertFalse(
            PdfToJpgPolicy.hasJpegEnvelope(
                1_024L,
                jpegPrefix(0xE0),
                byteArrayOf(0xFF.toByte(), 0x00),
            ),
        )
    }

    @Test
    fun `rejects truncated and oversized JPEG envelopes`() {
        val minimumBytes =
            (PdfToJpgPolicy.JPEG_PROBE_PREFIX_BYTES + PdfToJpgPolicy.JPEG_PROBE_SUFFIX_BYTES).toLong()

        assertFalse(PdfToJpgPolicy.hasJpegEnvelope(minimumBytes - 1L, jpegPrefix(0xE0), jpegSuffix()))
        assertTrue(PdfToJpgPolicy.hasJpegEnvelope(minimumBytes, jpegPrefix(0xE0), jpegSuffix()))
        assertTrue(
            PdfToJpgPolicy.hasJpegEnvelope(
                PdfToJpgPolicy.MAX_JPEG_BYTES,
                jpegPrefix(0xE0),
                jpegSuffix(),
            ),
        )
        assertFalse(
            PdfToJpgPolicy.hasJpegEnvelope(
                PdfToJpgPolicy.MAX_JPEG_BYTES + 1L,
                jpegPrefix(0xE0),
                jpegSuffix(),
            ),
        )
    }

    @Test
    fun `accepts only decoded JPEG metadata within generated image bounds`() {
        assertTrue(PdfToJpgPolicy.hasJpegMetadata("image/jpeg", 2_000, 2_000))
        assertTrue(PdfToJpgPolicy.hasJpegMetadata("IMAGE/JPEG", PdfToJpgPolicy.MAX_RENDER_EDGE, 1))

        assertFalse(PdfToJpgPolicy.hasJpegMetadata(null, 1, 1))
        assertFalse(PdfToJpgPolicy.hasJpegMetadata("image/png", 1, 1))
        assertFalse(PdfToJpgPolicy.hasJpegMetadata("image/jpeg", 0, 1))
        assertFalse(PdfToJpgPolicy.hasJpegMetadata("image/jpeg", 1, -1))
        assertFalse(PdfToJpgPolicy.hasJpegMetadata("image/jpeg", PdfToJpgPolicy.MAX_RENDER_EDGE + 1, 1))
        assertFalse(PdfToJpgPolicy.hasJpegMetadata("image/jpeg", 3_000, 3_000))
    }

    private fun jpegPrefix(marker: Int): ByteArray =
        byteArrayOf(
            0xFF.toByte(),
            0xD8.toByte(),
            0xFF.toByte(),
            marker.toByte(),
        )

    private fun jpegSuffix(): ByteArray = byteArrayOf(0xFF.toByte(), 0xD9.toByte())
}
