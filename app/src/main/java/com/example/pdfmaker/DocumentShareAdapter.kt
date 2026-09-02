package com.example.pdfmaker

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import androidx.core.content.FileProvider
import timber.log.Timber
import java.io.File
import java.util.Locale

internal object DocumentShareAdapter {
    private const val PDF_MIME = "application/pdf"
    private const val DOCX_MIME =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    private const val PPTX_MIME =
        "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    private const val FALLBACK_MIME = "application/octet-stream"

    fun mimeType(fileName: String): String =
        when (fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)) {
            "pdf" -> PDF_MIME
            "docx" -> DOCX_MIME
            "pptx" -> PPTX_MIME
            else -> FALLBACK_MIME
        }

    fun share(
        activity: MainActivity,
        file: File,
        chooserTitle: String,
    ): Boolean =
        try {
            val uri =
                FileProvider.getUriForFile(
                    activity,
                    "${activity.packageName}.provider",
                    file,
                )
            val sendIntent =
                Intent(Intent.ACTION_SEND).apply {
                    type = mimeType(file.name)
                    clipData = ClipData.newRawUri("Shared document", uri)
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            activity.startActivity(Intent.createChooser(sendIntent, chooserTitle))
            true
        } catch (error: ActivityNotFoundException) {
            logUnavailable(error)
            false
        } catch (error: IllegalArgumentException) {
            logUnavailable(error)
            false
        } catch (error: SecurityException) {
            logUnavailable(error)
            false
        }

    private fun logUnavailable(error: RuntimeException) {
        Timber
            .tag("DocumentShare")
            .w("Share unavailable (%s)", error.javaClass.simpleName)
    }
}
