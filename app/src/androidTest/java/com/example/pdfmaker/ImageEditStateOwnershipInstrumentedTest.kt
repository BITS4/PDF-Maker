package com.example.pdfmaker

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ImageEditStateOwnershipInstrumentedTest {
    @Test
    fun clearingDuringRenderDefersSourceRetirementUntilRenderCompletes() {
        val source = bitmap()
        val state = ImageEditState(Uri.EMPTY)
        state.installSource(source)
        val request = requireNotNull(state.renderRequest())

        assertTrue(state.releaseBitmaps().isEmpty())
        assertFalse(source.isRecycled)

        val retired = state.releaseRenderSource(request.source)
        assertEquals(1, retired.size)
        assertSame(source, retired.single())
        assertTrue(state.releaseRenderSource(request.source).isEmpty())
        source.recycle()
    }

    @Test
    fun sourceRetiresOnlyAfterEveryConcurrentRenderLeaseCompletes() {
        val source = bitmap()
        val state = ImageEditState(Uri.EMPTY)
        state.installSource(source)
        val first = requireNotNull(state.renderRequest())
        val second = requireNotNull(state.renderRequest())

        assertTrue(state.releaseBitmaps().isEmpty())
        assertTrue(state.releaseRenderSource(first.source).isEmpty())
        assertEquals(listOf(source), state.releaseRenderSource(second.source))
        source.recycle()
    }

    @Test
    fun replacingAnInUseSourceDefersOnlyTheOldBitmap() {
        val first = bitmap()
        val second = bitmap()
        val state = ImageEditState(Uri.EMPTY)
        state.installSource(first)
        val request = requireNotNull(state.renderRequest())

        assertTrue(state.installSource(second).isEmpty())
        assertEquals(listOf(first), state.releaseRenderSource(request.source))
        assertSame(second, state.originalBitmap)

        state.releaseBitmaps().forEach(Bitmap::recycle)
        first.recycle()
    }

    @Test
    fun installingDecodedPixelsReleasesTheTemporaryImport() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "pdfmaker/incoming").apply { mkdirs() }
        val sourceFile =
            File(directory, "owned-${System.nanoTime()}.png").apply {
                writeBytes(byteArrayOf(1))
            }
        val state = ImageEditState(Uri.EMPTY, TemporaryImportLease.claim(sourceFile, directory))
        val bitmap = bitmap()

        state.installSource(bitmap)

        assertFalse(sourceFile.exists())
        state.releaseBitmaps().forEach(Bitmap::recycle)
    }

    private fun bitmap(): Bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
}
