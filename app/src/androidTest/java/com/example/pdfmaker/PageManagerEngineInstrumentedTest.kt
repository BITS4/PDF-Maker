package com.example.pdfmaker

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PageManagerEngineInstrumentedTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun stagedSnapshotFeedsPreviewAndSaveAfterProviderChanges() {
        runBlocking {
            val providerFile = createPdf("provider", 160 to 80, 80 to 160)
            val providerUri =
                FileProvider.getUriForFile(context, "${context.packageName}.provider", providerFile)
            val source = SafePdfInput.fromUri(context, providerUri)
            val snapshot = source.file
            val snapshotBytes = snapshot.readBytes()
            var pages: List<PageState> = emptyList()
            var output: File? = null
            try {
                createPdf(providerFile, 90 to 120)

                pages = loadPageStates(source)
                assertEquals(2, pages.size)
                val result =
                    savePages(
                        context = context,
                        source = source,
                        edits = listOf(PageEdit(rotation = 90, deleted = false), PageEdit(0, true)),
                        baseName = "snapshot-test",
                    )
                assertTrue(result is PageManagerSaveResult.Saved)
                val saved = result as PageManagerSaveResult.Saved
                output = saved.file

                assertEquals(1, saved.pageCount)
                assertEquals(1, PdfFileMetadata.pageCount(saved.file))
                assertArrayEquals(snapshotBytes, snapshot.readBytes())
                assertEquals(1, PdfFileMetadata.pageCount(providerFile))
                assertTrue(snapshot.exists())
            } finally {
                val bitmaps = pages.map(PageState::bitmap)
                recyclePageStates(pages)
                assertTrue(bitmaps.all(Bitmap::isRecycled))
                source.close()
                assertFalse(snapshot.exists())
                output?.delete()
                providerFile.delete()
            }
        }
    }

    @Test
    fun canceledSavePreservesSourceAndCallerOwnedPreviewsWithoutOutput() {
        runBlocking {
            val providerFile = createPdf("cancel", 120 to 160, 160 to 120)
            val providerUri =
                FileProvider.getUriForFile(context, "${context.packageName}.provider", providerFile)
            val source = SafePdfInput.fromUri(context, providerUri)
            val snapshot = source.file
            val originalBytes = snapshot.readBytes()
            val pages = loadPageStates(source)
            val directory = getPdfMakerDir(context)
            val filesBefore =
                directory
                    .listFiles()
                    .orEmpty()
                    .map(File::getCanonicalPath)
                    .toSet()
            try {
                val failure =
                    try {
                        savePages(
                            context = context,
                            source = source,
                            edits = pages.map { PageEdit(it.rotation, it.deleted) },
                            baseName = "cancelled-save",
                            beforeOutputCommit = {
                                throw CancellationException("instrumented cancellation")
                            },
                        )
                        null
                    } catch (error: Throwable) {
                        error
                    }

                assertTrue(failure is CancellationException)
                assertArrayEquals(originalBytes, snapshot.readBytes())
                assertTrue(snapshot.exists())
                assertTrue(pages.none { it.bitmap.isRecycled })
                assertEquals(
                    filesBefore,
                    directory
                        .listFiles()
                        .orEmpty()
                        .map(File::getCanonicalPath)
                        .toSet(),
                )
            } finally {
                recyclePageStates(pages)
                source.close()
                providerFile.delete()
            }
        }
    }

    private fun createPdf(
        name: String,
        vararg pageSizes: Pair<Int, Int>,
    ): File = createPdf(File(getPdfMakerDir(context), "$name-${System.nanoTime()}.pdf"), *pageSizes)

    private fun createPdf(
        target: File,
        vararg pageSizes: Pair<Int, Int>,
    ): File {
        val document = PdfDocument()
        try {
            pageSizes.forEachIndexed { index, (width, height) ->
                val info = PdfDocument.PageInfo.Builder(width, height, index + 1).create()
                val page = document.startPage(info)
                try {
                    page.canvas.drawColor(if (index % 2 == 0) Color.RED else Color.BLUE)
                } finally {
                    document.finishPage(page)
                }
            }
            target.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }
        return target
    }
}
