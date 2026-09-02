package com.example.pdfmaker

import android.util.Log
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.protocol.Message
import timber.log.Timber

/** Emits useful severity/category data without forwarding messages or document identifiers. */
class PrivacySafeTree(
    private val telemetryEnabled: Boolean,
) : Timber.Tree() {
    override fun log(
        priority: Int,
        tag: String?,
        message: String,
        t: Throwable?,
    ) {
        if (priority < Log.INFO) return
        val safeTag = ObservabilityPolicy.safeLogTag(tag)
        Log.println(priority, safeTag, ObservabilityPolicy.safeLogMessage(priority, t))

        if (!telemetryEnabled || priority < Log.WARN) return
        Sentry.captureEvent(
            SentryEvent().apply {
                logger = safeTag
                level = priority.toSentryLevel()
                this.message =
                    Message().apply {
                        this.message = ObservabilityPolicy.REDACTED_EVENT_MESSAGE
                    }
                if (t != null) {
                    this.throwable = ObservabilityPolicy.sanitizedThrowable(t)
                }
            },
        )
    }

    private fun Int.toSentryLevel(): SentryLevel =
        when {
            this >= Log.ASSERT -> SentryLevel.FATAL
            this >= Log.ERROR -> SentryLevel.ERROR
            else -> SentryLevel.WARNING
        }
}
