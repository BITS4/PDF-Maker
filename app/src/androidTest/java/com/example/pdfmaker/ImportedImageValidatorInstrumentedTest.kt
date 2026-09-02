package com.example.pdfmaker

import android.content.Context
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImportedImageValidatorInstrumentedTest {
    private lateinit var context: Context
    private val createdFiles = mutableListOf<File>()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @After
    fun tearDown() {
        createdFiles.forEach(File::delete)
    }

    @Test
    fun acceptsEverySupportedImageCodecAfterRealDecode() {
        val jpeg = imageFile("complete.jpg", Bitmap.CompressFormat.JPEG)
        val png = imageFile("complete.png", Bitmap.CompressFormat.PNG)
        @Suppress("DEPRECATION")
        val webp = imageFile("complete.webp", Bitmap.CompressFormat.WEBP)
        val gif = file("complete.gif", onePixelGif())
        val bmp = file("complete.bmp", onePixelBmp())

        assertEquals(IncomingDocumentKind.JPEG, ImportedDocumentInspector.inspect(jpeg))
        assertEquals(IncomingDocumentKind.PNG, ImportedDocumentInspector.inspect(png))
        assertEquals(IncomingDocumentKind.WEBP, ImportedDocumentInspector.inspect(webp))
        assertEquals(IncomingDocumentKind.GIF, ImportedDocumentInspector.inspect(gif))
        assertEquals(IncomingDocumentKind.BMP, ImportedDocumentInspector.inspect(bmp))
    }

    @Test
    fun rejectsTruncatedAndSignatureDisguisedImages() {
        val truncated = file("truncated.jpg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))
        val pngBytes = encodedImage(Bitmap.CompressFormat.PNG)
        val disguised =
            file(
                "disguised.jpg",
                byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + pngBytes,
            )

        assertNull(ImportedDocumentInspector.inspect(truncated))
        assertNull(ImportedDocumentInspector.inspect(disguised))
        listOf(
            "truncated.png" to byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
            "truncated.gif" to "GIF89a".toByteArray(),
            "truncated.webp" to "RIFF0000WEBP".toByteArray(),
            "truncated.bmp" to "BM".toByteArray(),
        ).forEach { (name, bytes) ->
            assertNull(ImportedDocumentInspector.inspect(file(name, bytes)))
        }
    }

    @Test
    fun propagatesCancellationBetweenBoundedDecodeStages() {
        val jpeg = imageFile("cancelled.jpg", Bitmap.CompressFormat.JPEG)
        var checkpoints = 0

        assertThrows(CancellationException::class.java) {
            ImportedDocumentInspector.inspect(jpeg) {
                checkpoints += 1
                if (checkpoints == 6) throw CancellationException("cancelled")
            }
        }
        assertEquals(6, checkpoints)
    }

    private fun imageFile(name: String, format: Bitmap.CompressFormat): File =
        file(name, encodedImage(format))

    private fun encodedImage(format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888)
        return try {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(format, 90, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun onePixelGif(): ByteArray =
        byteArrayOf(
            0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00,
            0x01, 0x00, 0x80.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00,
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x21, 0xF9.toByte(), 0x04, 0x01, 0x00,
            0x00, 0x00, 0x00, 0x2C, 0x00, 0x00, 0x00, 0x00,
            0x01, 0x00, 0x01, 0x00, 0x00, 0x02, 0x02, 0x44,
            0x01, 0x00, 0x3B,
        )

    private fun onePixelBmp(): ByteArray =
        ByteBuffer.allocate(58).order(ByteOrder.LITTLE_ENDIAN).run {
            put('B'.code.toByte())
            put('M'.code.toByte())
            putInt(58)
            putInt(0)
            putInt(54)
            putInt(40)
            putInt(1)
            putInt(1)
            putShort(1.toShort())
            putShort(24.toShort())
            putInt(0)
            putInt(4)
            putInt(2_835)
            putInt(2_835)
            putInt(0)
            putInt(0)
            put(byteArrayOf(0x20, 0x80.toByte(), 0xE0.toByte(), 0x00))
            array()
        }

    private fun file(name: String, bytes: ByteArray): File =
        File(context.cacheDir, "${System.nanoTime()}-$name").apply {
            writeBytes(bytes)
            createdFiles += this
        }
}
