package com.example.pdfmaker

import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfFileMetadataInstrumentedTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun readsEveryPageBeforeClosingTheRenderer() {
        val output = File(context.cacheDir, "metadata-${System.nanoTime()}.pdf")
        try {
            val document = PdfDocument()
            try {
                repeat(3) { index ->
                    val info = PdfDocument.PageInfo.Builder(100, 100, index + 1).create()
                    document.startPage(info).also(document::finishPage)
                }
                output.outputStream().use(document::writeTo)
            } finally {
                document.close()
            }

            assertEquals(3, PdfFileMetadata.pageCount(output))
        } finally {
            output.delete()
        }
    }

    @Test
    fun rejectsEmptyOutputBeforeOpeningAndroidRenderer() {
        val output = File(context.cacheDir, "empty-${System.nanoTime()}.pdf").apply { createNewFile() }
        try {
            assertTrue(runCatching { PdfFileMetadata.pageCount(output) }.isFailure)
        } finally {
            output.delete()
        }
    }
}
