package com.example.pdfmaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Validates app-owned JPG results immediately before exposing them through FileProvider. */
internal fun prepareJpgShareFiles(
    context: Context,
    files: List<File>,
): List<File>? =
    try {
        val exportDirectory = getPdfMakerDir(context).canonicalFile
        val safeFiles =
            files.map { file ->
                file.canonicalFile.also { canonical ->
                    require(
                        canonical.isFile &&
                            canonical.parentFile == exportDirectory &&
                            canonical.extension.equals("jpg", ignoreCase = true),
                    ) { "Only converted JPG files can be shared" }
                }
            }
        PdfToJpgPolicy.requireShareBatch(safeFiles.map(File::length))
        safeFiles.forEach(::requireJpegShareContent)
        safeFiles
    } catch (_: IllegalArgumentException) {
        rejectJpgSharePreparation()
    } catch (_: IOException) {
        rejectJpgSharePreparation()
    } catch (_: IllegalStateException) {
        rejectJpgSharePreparation()
    } catch (_: SecurityException) {
        rejectJpgSharePreparation()
    }

private fun rejectJpgSharePreparation(): Nothing? {
    Timber.tag("PdfToJpg").w("event=share_prepare_rejected")
    return null
}

private fun requireJpegShareContent(file: File) {
    RandomAccessFile(file, "r").use { input ->
        val fileBytes = input.length()
        require(
            fileBytes >= (PdfToJpgPolicy.JPEG_PROBE_PREFIX_BYTES + PdfToJpgPolicy.JPEG_PROBE_SUFFIX_BYTES),
        ) { "Converted image is not a JPEG" }

        val prefix = ByteArray(PdfToJpgPolicy.JPEG_PROBE_PREFIX_BYTES)
        val suffix = ByteArray(PdfToJpgPolicy.JPEG_PROBE_SUFFIX_BYTES)
        input.readFully(prefix)
        input.seek(fileBytes - suffix.size)
        input.readFully(suffix)
        require(PdfToJpgPolicy.hasJpegEnvelope(fileBytes, prefix, suffix)) {
            "Converted image has an invalid JPEG signature"
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        input.seek(0L)
        BitmapFactory.decodeFileDescriptor(input.fd, null, bounds)
        require(PdfToJpgPolicy.hasJpegMetadata(bounds.outMimeType, bounds.outWidth, bounds.outHeight)) {
            "Converted image has invalid JPEG content"
        }
        val plan = requireNotNull(PdfToJpgPolicy.resultThumbnailPlan(bounds.outWidth, bounds.outHeight)) {
            "Converted image has invalid JPEG dimensions"
        }

        input.seek(0L)
        val decoded =
            requireNotNull(
                BitmapFactory.decodeFileDescriptor(
                    input.fd,
                    null,
                    BitmapFactory.Options().apply {
                        inSampleSize = plan.sampleSize
                        inPreferredConfig = Bitmap.Config.RGB_565
                        inScaled = false
                    },
                ),
            ) { "Converted image content could not be decoded" }
        decoded.recycle()
    }
}
