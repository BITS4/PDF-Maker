package com.example.pdfmaker

import android.content.Intent
import android.net.Uri
import android.os.BadParcelableException
import androidx.core.content.IntentCompat

internal sealed interface IncomingIntentParseResult {
    data object NotIncoming : IncomingIntentParseResult

    data object Invalid : IncomingIntentParseResult

    data class Accepted(
        val uri: Uri,
        val declaredMimeType: String?,
    ) : IncomingIntentParseResult
}

/** Treats the exported Activity intent as an untrusted Android IPC boundary. */
internal object IncomingIntentPayloadParser {
    fun parse(intent: Intent?): IncomingIntentParseResult {
        val incomingIntent = intent ?: return IncomingIntentParseResult.NotIncoming
        if (incomingIntent.action != Intent.ACTION_VIEW && incomingIntent.action != Intent.ACTION_SEND) {
            return IncomingIntentParseResult.NotIncoming
        }
        val uri = safeIncomingUri(incomingIntent) ?: return IncomingIntentParseResult.Invalid

        if (uri.scheme != "content" || uri.authority.isNullOrBlank()) {
            return IncomingIntentParseResult.Invalid
        }
        return IncomingIntentParseResult.Accepted(uri, incomingIntent.type)
    }

    private fun safeIncomingUri(intent: Intent): Uri? =
        try {
            when (intent.action) {
                Intent.ACTION_VIEW -> intent.data
                Intent.ACTION_SEND -> incomingStream(intent)
                else -> null
            }
        } catch (_: BadParcelableException) {
            null
        } catch (_: ClassCastException) {
            null
        } catch (_: IndexOutOfBoundsException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: SecurityException) {
            null
        }

    private fun incomingStream(intent: Intent): Uri? {
        val extra = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        if (extra != null) return extra
        val clip = intent.clipData ?: return null
        return if (clip.itemCount > 0) clip.getItemAt(0).uri else null
    }
}
