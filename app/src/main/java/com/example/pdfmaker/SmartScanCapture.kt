package com.example.pdfmaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.max
import kotlinx.coroutines.suspendCancellableCoroutine

enum class IdCardCaptureSide { NONE, SINGLE, BOTH }

internal enum class IdCardStep { IDLE, CAPTURE_FRONT, FLIP_CARD, CAPTURE_BACK }

data class CapturedDoc(val file: File, val thumb: Bitmap)

internal suspend fun Context.getCameraProvider(): ProcessCameraProvider =
    suspendCancellableCoroutine { continuation ->
        ProcessCameraProvider.getInstance(this).also { future ->
            future.addListener(
                { continuation.resume(future.get()) },
                ContextCompat.getMainExecutor(this),
            )
        }
    }

/** Bakes EXIF orientation into a memory-bounded JPEG for downstream processing. */
internal fun bakeExifRotation(source: File): File {
    val degrees = try {
        when (
            ExifInterface(source.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        ) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    } catch (_: Exception) {
        0f
    }
    if (degrees == 0f) return source

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(source.absolutePath, bounds)
    var sampleSize = 1
    while (max(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_CAPTURE_EDGE_PX) {
        sampleSize *= 2
    }
    val raw = BitmapFactory.decodeFile(
        source.absolutePath,
        BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        },
    ) ?: return source

    val rotated = Bitmap.createBitmap(
        raw,
        0,
        0,
        raw.width,
        raw.height,
        Matrix().apply { postRotate(degrees) },
        true,
    ).also { if (it !== raw) raw.recycle() }

    val output = File(source.parentFile, "corrected_${System.currentTimeMillis()}.jpg")
    output.outputStream().use { stream ->
        check(rotated.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)) {
            "Unable to encode corrected scan"
        }
    }
    rotated.recycle()
    runCatching {
        ExifInterface(output.absolutePath).run {
            setAttribute(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL.toString(),
            )
            saveAttributes()
        }
    }
    return output
}

internal fun smallThumb(file: File, sizePx: Int): Bitmap {
    require(sizePx > 0) { "Thumbnail size must be positive" }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    var sampleSize = 1
    while (max(bounds.outWidth, bounds.outHeight) / sampleSize > sizePx * 2) {
        sampleSize *= 2
    }
    return BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = sampleSize },
    ) ?: Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
}

private const val MAX_CAPTURE_EDGE_PX = 2_048
private const val JPEG_QUALITY = 95
