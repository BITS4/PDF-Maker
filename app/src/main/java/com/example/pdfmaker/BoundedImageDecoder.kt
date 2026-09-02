package com.example.pdfmaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.UUID

/** Decodes a stable, bounded snapshot of an image supplied by a content provider or app cache. */
object BoundedImageDecoder {
    @Suppress("TooGenericExceptionCaught") // Provider/codec failures are deliberately returned through Result.
    fun decode(
        context: Context,
        uri: Uri,
    ): Result<Bitmap> =
        try {
            Result.success(decodeOrThrow(context, uri))
        } catch (error: Exception) {
            Result.failure(error)
        }

    private fun decodeOrThrow(
        context: Context,
        uri: Uri,
    ): Bitmap {
        val staged = stage(context, uri)
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(staged.absolutePath, bounds)
            require(ImageInputPolicy.isSupportedMimeType(bounds.outMimeType)) {
                "The selected content is not a supported image"
            }
            val plan =
                requireNotNull(ImageInputPolicy.decodePlan(bounds.outWidth, bounds.outHeight)) {
                    "The selected image has invalid dimensions"
                }
            val orientation = readExifOrientation(staged, bounds.outMimeType)

            var owned: Bitmap? = null
            try {
                owned =
                    requireNotNull(
                        BitmapFactory.decodeFile(
                            staged.absolutePath,
                            BitmapFactory.Options().apply {
                                inSampleSize = plan.sampleSize
                                inPreferredConfig = Bitmap.Config.ARGB_8888
                                inScaled = false
                            },
                        ),
                    ) { "The selected image could not be decoded" }

                val fitted =
                    requireNotNull(
                        ImageInputPolicy.fitWithinLimits(owned.width, owned.height),
                    ) { "The decoded image has invalid dimensions" }
                if (fitted.width != owned.width || fitted.height != owned.height) {
                    val scaled = Bitmap.createScaledBitmap(owned, fitted.width, fitted.height, true)
                    if (scaled !== owned) owned.recycle()
                    owned = scaled
                }

                val oriented = applyExifOrientation(owned, orientation)
                if (oriented !== owned) owned.recycle()
                owned = null
                return oriented
            } finally {
                owned?.takeUnless(Bitmap::isRecycled)?.recycle()
            }
        } finally {
            staged.delete()
        }
    }

    private fun stage(
        context: Context,
        uri: Uri,
    ): File {
        val directory = File(context.cacheDir, "image-inputs")
        check((directory.exists() && directory.isDirectory) || directory.mkdirs()) {
            "Could not create the image staging directory"
        }
        val temporary = File(directory, ".image-${UUID.randomUUID()}.tmp")
        var completed = false
        try {
            openAllowedStream(context, uri).use { source ->
                FileOutputStream(temporary).use { output ->
                    val copied = BoundedIo.copy(source, output, ImageInputPolicy.MAX_ENCODED_BYTES)
                    require(copied > 0) { "The selected image is empty" }
                    output.flush()
                    output.fd.sync()
                }
            }
            completed = true
            return temporary
        } finally {
            if (!completed) temporary.delete()
        }
    }

    private fun openAllowedStream(
        context: Context,
        uri: Uri,
    ): InputStream =
        when (uri.scheme?.lowercase(Locale.ROOT)) {
            "content" -> {
                require(!uri.authority.isNullOrBlank()) { "The image provider is invalid" }
                context.contentResolver.openInputStream(uri)
                    ?: error("The image provider returned no data")
            }

            "file" -> {
                val path = requireNotNull(uri.path) { "The image file path is missing" }
                val source = File(path).canonicalFile
                val cacheRoot = context.cacheDir.canonicalFile.toPath()
                require(source.toPath().startsWith(cacheRoot) && source.isFile) {
                    "Only app-cached image files can be opened"
                }
                source.inputStream()
            }

            else -> {
                throw IllegalArgumentException("Only content-provider or app-cached images can be opened")
            }
        }

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

    private fun readExifOrientation(
        source: File,
        mimeType: String?,
    ): Int {
        if (mimeType !in setOf("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif")) {
            return ExifInterface.ORIENTATION_NORMAL
        }
        return ExifInterface(source.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    }
}
