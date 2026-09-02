package com.example.pdfmaker

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfToJpgShareInstrumentedTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun acceptsGeneratedJpegAndRejectsSpoofedJpgContent() {
        val directory = getPdfMakerDir(context)
        val valid = directory.resolve("share-content-valid.jpg")
        val spoofed = directory.resolve("share-content-spoofed.jpg")
        val wrongMagic = directory.resolve("share-content-text.jpg")
        try {
            val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            try {
                valid.outputStream().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
                }
            } finally {
                bitmap.recycle()
            }
            spoofed.writeBytes(
                byteArrayOf(
                    0xFF.toByte(),
                    0xD8.toByte(),
                    0xFF.toByte(),
                    0xE0.toByte(),
                    0x00,
                    0x00,
                    0xFF.toByte(),
                    0xD9.toByte(),
                ),
            )
            wrongMagic.writeText("not a jpeg")

            assertEquals(valid.canonicalFile, prepareJpgShareFiles(context, listOf(valid))?.single())
            assertNull(prepareJpgShareFiles(context, listOf(spoofed)))
            assertNull(prepareJpgShareFiles(context, listOf(wrongMagic)))
        } finally {
            valid.delete()
            spoofed.delete()
            wrongMagic.delete()
        }
    }
}
