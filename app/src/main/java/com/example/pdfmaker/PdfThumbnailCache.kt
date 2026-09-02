package com.example.pdfmaker

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.xml.sax.SAXException
import timber.log.Timber
import java.io.IOException
import javax.xml.parsers.ParserConfigurationException

/** Memory-bounded entry point for document previews. */
object PdfThumbnailCache {
    private val cache =
        WeightedLruCache<ThumbnailCacheKey, Bitmap>(
            maximumEntries = ThumbnailCachePolicy.MAX_ENTRIES,
            maximumWeight = ThumbnailCachePolicy.MAX_PIXEL_WEIGHT,
            weightOf = { bitmap -> ThumbnailCachePolicy.pixelWeight(bitmap.width, bitmap.height) },
        )

    @Suppress("UNUSED_PARAMETER")
    suspend fun getThumbnail(
        context: Context,
        filePath: String,
        sizePx: Int = ThumbnailGenerationPolicy.DEFAULT_SIZE_PX,
    ): Bitmap? =
        withContext(Dispatchers.IO) {
            val requestContext = coroutineContext
            requestContext.ensureActive()
            val key = ThumbnailCachePolicy.key(filePath, sizePx)
            cache[key]?.takeUnless(Bitmap::isRecycled)?.let { cached -> return@withContext cached }

            val thumbnail =
                generateSafely(filePath, sizePx) {
                    requestContext.ensureActive()
                } ?: return@withContext null
            var accepted = false
            try {
                requestContext.ensureActive()
                cache.put(key, thumbnail)
                accepted = true
                thumbnail
            } finally {
                if (!accepted && !thumbnail.isRecycled) thumbnail.recycle()
            }
        }

    private fun generateSafely(
        filePath: String,
        sizePx: Int,
        checkCancellation: () -> Unit,
    ): Bitmap? =
        try {
            ThumbnailGenerator.generate(filePath, sizePx, checkCancellation)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            logPreviewFailure(filePath, failure)
            null
        } catch (failure: SAXException) {
            logPreviewFailure(filePath, failure)
            null
        } catch (failure: ParserConfigurationException) {
            logPreviewFailure(filePath, failure)
            null
        } catch (failure: SecurityException) {
            logPreviewFailure(filePath, failure)
            null
        } catch (failure: IllegalArgumentException) {
            logPreviewFailure(filePath, failure)
            null
        } catch (failure: IllegalStateException) {
            logPreviewFailure(filePath, failure)
            null
        }

    private fun logPreviewFailure(
        filePath: String,
        failure: Throwable,
    ) {
        val kind = ThumbnailGenerationPolicy.classify(filePath).name
        // Deliberately exclude user-controlled paths and exception messages from telemetry.
        Timber.w("Thumbnail generation failed for %s (%s)", kind, failure.javaClass.simpleName)
    }

    fun invalidate(filePath: String) {
        val canonicalPath = ThumbnailCachePolicy.key(filePath, 1).canonicalPath
        cache.removeWhere { key -> key.canonicalPath == canonicalPath }
    }

    fun clear() {
        cache.clear()
    }
}

@Composable
fun rememberPdfThumbnail(
    context: Context,
    filePath: String,
    sizePx: Int = ThumbnailGenerationPolicy.DEFAULT_SIZE_PX,
): Bitmap? {
    var bitmap by remember(filePath, sizePx) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(filePath, sizePx) {
        bitmap = null
        bitmap = PdfThumbnailCache.getThumbnail(context.applicationContext, filePath, sizePx)
    }
    return bitmap
}
