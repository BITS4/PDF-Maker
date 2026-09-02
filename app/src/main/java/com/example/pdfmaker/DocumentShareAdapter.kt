package com.example.pdfmaker

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import timber.log.Timber
import java.io.File

/** Owns every outbound FileProvider intent and grants recipients read-only temporary access. */
internal object DocumentShareAdapter {
    private const val PROVIDER_SUFFIX = ".provider"
    private const val SHARE_CLIP_LABEL = "Shared document"
    private const val OPEN_CLIP_LABEL = "Opened document"

    fun mimeType(fileName: String): String = DocumentSharePolicy.mimeType(fileName)

    fun buildShareChooser(
        context: Context,
        files: List<File>,
        chooserTitle: String,
        requestedMimeType: String? = null,
    ): Intent? =
        try {
            val plan = DocumentSharePolicy.sharePlan(files.map { file -> file.name }, requestedMimeType)
            val uris = providerUris(context, files)
            val sendIntent =
                Intent(
                    when (plan.mode) {
                        DocumentSendMode.SINGLE -> Intent.ACTION_SEND
                        DocumentSendMode.MULTIPLE -> Intent.ACTION_SEND_MULTIPLE
                    },
                ).apply {
                    type = plan.mimeType
                    clipData = readOnlyClipData(SHARE_CLIP_LABEL, uris)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    when (plan.mode) {
                        DocumentSendMode.SINGLE -> putExtra(Intent.EXTRA_STREAM, uris.single())
                        DocumentSendMode.MULTIPLE -> putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    }
                }
            Intent.createChooser(sendIntent, chooserTitle)
        } catch (_: IllegalArgumentException) {
            logFailure("share_prepare_rejected")
            null
        } catch (_: IllegalStateException) {
            logFailure("share_prepare_unavailable")
            null
        } catch (_: SecurityException) {
            logFailure("share_prepare_denied")
            null
        }

    fun share(
        context: Context,
        file: File,
        chooserTitle: String,
        requestedMimeType: String? = null,
    ): Boolean =
        share(
            context = context,
            files = listOf(file),
            chooserTitle = chooserTitle,
            requestedMimeType = requestedMimeType,
        )

    fun share(
        context: Context,
        files: List<File>,
        chooserTitle: String,
        requestedMimeType: String? = null,
    ): Boolean {
        val chooser = buildShareChooser(context, files, chooserTitle, requestedMimeType) ?: return false
        return launchChooser(context, chooser, "share_launch_unavailable")
    }

    fun buildOpenChooser(
        context: Context,
        file: File,
        chooserTitle: String,
        requestedMimeType: String? = null,
    ): Intent? =
        try {
            val mimeType =
                requestedMimeType?.let(DocumentSharePolicy::requireMimeType)
                    ?: DocumentSharePolicy.mimeType(file.name)
            val uri = providerUris(context, listOf(file)).single()
            val openIntent =
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mimeType)
                    clipData = ClipData.newRawUri(OPEN_CLIP_LABEL, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            Intent.createChooser(openIntent, chooserTitle)
        } catch (_: IllegalArgumentException) {
            logFailure("open_prepare_rejected")
            null
        } catch (_: IllegalStateException) {
            logFailure("open_prepare_unavailable")
            null
        } catch (_: SecurityException) {
            logFailure("open_prepare_denied")
            null
        }

    fun open(
        context: Context,
        file: File,
        chooserTitle: String,
        requestedMimeType: String? = null,
    ): Boolean {
        val chooser = buildOpenChooser(context, file, chooserTitle, requestedMimeType) ?: return false
        return launchChooser(context, chooser, "open_launch_unavailable")
    }

    private fun providerUris(
        context: Context,
        files: List<File>,
    ): ArrayList<Uri> =
        ArrayList(
            files.map { file ->
                require(file.isFile && file.length() > 0L) { "Only nonempty existing files can be shared" }
                FileProvider
                    .getUriForFile(context, context.packageName + PROVIDER_SUFFIX, file)
                    .also { uri ->
                        require(uri.scheme == "content" && uri.authority == context.packageName + PROVIDER_SUFFIX) {
                            "The provider returned an invalid document URI"
                        }
                    }
            },
        )

    private fun readOnlyClipData(
        label: String,
        uris: List<Uri>,
    ): ClipData =
        ClipData.newRawUri(label, uris.first()).apply {
            uris.drop(1).forEach { uri -> addItem(ClipData.Item(uri)) }
        }

    private fun launchChooser(
        context: Context,
        chooser: Intent,
        failureEvent: String,
    ): Boolean =
        try {
            if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            true
        } catch (_: ActivityNotFoundException) {
            logFailure(failureEvent)
            false
        } catch (_: IllegalArgumentException) {
            logFailure(failureEvent)
            false
        } catch (_: IllegalStateException) {
            logFailure(failureEvent)
            false
        } catch (_: SecurityException) {
            logFailure(failureEvent)
            false
        }

    private fun logFailure(event: String) {
        Timber.tag("DocumentShare").w("event=%s", event)
    }
}
