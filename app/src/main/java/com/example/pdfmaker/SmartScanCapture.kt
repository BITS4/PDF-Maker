package com.example.pdfmaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume

enum class IdCardCaptureSide { NONE, SINGLE, BOTH }

internal enum class IdCardStep { IDLE, CAPTURE_FRONT, FLIP_CARD, CAPTURE_BACK }

data class CapturedDoc(
    val file: File,
    val thumb: Bitmap,
)

internal suspend fun Context.getCameraProvider(): ProcessCameraProvider =
    suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                if (!continuation.isActive) return@addListener
                runCatching(future::get)
                    .onSuccess(continuation::resume)
                    .onFailure { error -> continuation.resumeWith(Result.failure(error)) }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

/** Normalizes a camera JPEG into a bounded, orientation-corrected artifact. */
internal suspend fun normalizeCapturedImage(
    source: File,
    destination: File,
): File {
    val coroutineContext = currentCoroutineContext()

    fun checkpoint() = coroutineContext.ensureActive()

    require(source.canonicalFile != destination.canonicalFile) {
        "Capture source and destination must be different files"
    }
    SmartScanPolicy.requireSourceLength(source.length())
    checkpoint()

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(source.absolutePath, bounds)
    require(ImageInputPolicy.isSupportedMimeType(bounds.outMimeType)) {
        "The captured photo is not a supported image"
    }
    val decodePlan =
        requireNotNull(
            SmartScanPolicy.captureDecodePlan(bounds.outWidth, bounds.outHeight),
        ) { "The captured photo has invalid dimensions" }
    val orientation = readOrientation(source)

    var ownedBitmap: Bitmap? = null
    var completed = false
    try {
        ownedBitmap =
            requireNotNull(
                BitmapFactory.decodeFile(
                    source.absolutePath,
                    BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        inSampleSize = decodePlan.sampleSize
                        inScaled = false
                    },
                ),
            ) { "The captured photo could not be decoded" }
        checkpoint()

        ownedBitmap =
            replaceOwnedBitmap(
                ownedBitmap,
                applyExifOrientation(ownedBitmap, orientation),
            )
        val fitted =
            requireNotNull(
                SmartScanPolicy.fittedCaptureSize(ownedBitmap.width, ownedBitmap.height),
            ) { "The captured photo has invalid dimensions" }
        if (ownedBitmap.width != fitted.width || ownedBitmap.height != fitted.height) {
            ownedBitmap =
                replaceOwnedBitmap(
                    ownedBitmap,
                    Bitmap.createScaledBitmap(ownedBitmap, fitted.width, fitted.height, true),
                )
        }
        checkpoint()

        check((destination.parentFile?.isDirectory == true || destination.parentFile?.mkdirs() == true)) {
            "Could not create the scan output directory"
        }
        FileOutputStream(destination).use { fileOutput ->
            val boundedOutput =
                BoundedIo.limit(
                    output = fileOutput,
                    maximumBytes = SmartScanPolicy.MAX_NORMALIZED_BYTES,
                    beforeWrite = ::checkpoint,
                )
            check(ownedBitmap.compress(Bitmap.CompressFormat.JPEG, 92, boundedOutput)) {
                "The captured photo could not be encoded"
            }
            boundedOutput.flush()
            fileOutput.fd.sync()
        }
        SmartScanPolicy.requireNormalizedLength(destination.length())
        checkpoint()
        completed = true
        source.delete()
        return destination
    } finally {
        ownedBitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
        if (!completed) destination.delete()
    }
}

/** Creates the only in-memory working-set item retained by the camera screen. */
internal suspend fun createCaptureThumbnail(
    file: File,
    targetEdge: Int = SmartScanPolicy.THUMBNAIL_EDGE_PX,
): Bitmap {
    val coroutineContext = currentCoroutineContext()

    fun checkpoint() = coroutineContext.ensureActive()

    SmartScanPolicy.requireNormalizedLength(file.length())
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    val plan =
        requireNotNull(
            SmartScanPolicy.thumbnailDecodePlan(bounds.outWidth, bounds.outHeight, targetEdge),
        ) { "The scan preview has invalid dimensions" }
    checkpoint()

    var ownedBitmap: Bitmap? = null
    try {
        ownedBitmap =
            requireNotNull(
                BitmapFactory.decodeFile(
                    file.absolutePath,
                    BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.RGB_565
                        inSampleSize = plan.sampleSize
                        inScaled = false
                    },
                ),
            ) { "The scan preview could not be decoded" }
        val fitted =
            requireNotNull(
                SmartScanPolicy.fittedThumbnailSize(ownedBitmap.width, ownedBitmap.height, targetEdge),
            ) { "The scan preview has invalid dimensions" }
        if (ownedBitmap.width != fitted.width || ownedBitmap.height != fitted.height) {
            ownedBitmap =
                replaceOwnedBitmap(
                    ownedBitmap,
                    Bitmap.createScaledBitmap(ownedBitmap, fitted.width, fitted.height, true),
                )
        }
        checkpoint()
        return ownedBitmap.also { ownedBitmap = null }
    } finally {
        ownedBitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
    }
}

private fun replaceOwnedBitmap(
    current: Bitmap,
    replacement: Bitmap,
): Bitmap {
    if (replacement !== current && !current.isRecycled) current.recycle()
    return replacement
}

private fun readOrientation(source: File): Int =
    ExifInterface(source.absolutePath).getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_NORMAL,
    )

private fun applyExifOrientation(
    source: Bitmap,
    orientation: Int,
): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
            matrix.setScale(-1f, 1f)
        }

        ExifInterface.ORIENTATION_ROTATE_180 -> {
            matrix.setRotate(180f)
        }

        ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
            matrix.setScale(1f, -1f)
        }

        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.setRotate(90f)
            matrix.postScale(-1f, 1f)
        }

        ExifInterface.ORIENTATION_ROTATE_90 -> {
            matrix.setRotate(90f)
        }

        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.setRotate(-90f)
            matrix.postScale(-1f, 1f)
        }

        ExifInterface.ORIENTATION_ROTATE_270 -> {
            matrix.setRotate(-90f)
        }

        else -> {
            return source
        }
    }
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
}
