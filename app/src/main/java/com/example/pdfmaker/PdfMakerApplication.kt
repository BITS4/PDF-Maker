package com.example.pdfmaker

import android.app.Application
import io.sentry.SentryEvent
import io.sentry.android.core.SentryAndroid
import timber.log.Timber

class PdfMakerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val telemetryEnabled =
            ObservabilityPolicy.shouldEnable(
                enabled = BuildConfig.SENTRY_ENABLED,
                dsn = BuildConfig.SENTRY_DSN,
                isDebug = BuildConfig.DEBUG,
            )
        if (telemetryEnabled) initializeSentry()
        Timber.plant(PrivacySafeTree(telemetryEnabled))
    }

    private fun initializeSentry() {
        SentryAndroid.init(this) { options ->
            options.dsn = BuildConfig.SENTRY_DSN
            options.isSendDefaultPii = false
            options.maxBreadcrumbs = 0
            options.tracesSampleRate = 0.0
            options.profilesSampleRate = 0.0
            options.isEnableUserInteractionTracing = false
            options.isEnableUserInteractionBreadcrumbs = false
            options.isAttachStacktrace = true
            options.isAttachScreenshot = false
            options.isAttachViewHierarchy = false
            options.isCollectAdditionalContext = false
            options.isCollectExternalStorageContext = false
            options.isEnableRootCheck = false
            options.isEnableAutoSessionTracking = false
            options.isSendModules = false
            options.enableAllAutoBreadcrumbs(false)
            options.isReportHistoricalAnrs = false
            options.isAttachAnrThreadDump = false
            options.anrProfilingSampleRate = 0.0
            options.sessionReplay.sessionSampleRate = 0.0
            options.sessionReplay.onErrorSampleRate = 0.0
            options.beforeSend = { event, _ -> event.withoutPotentialDocumentData() }
        }
    }
}

private fun SentryEvent.withoutPotentialDocumentData(): SentryEvent {
    user = null
    request = null
    breadcrumbs = null
    extras = emptyMap()
    tags = emptyMap()
    serverName = null
    contexts.clear()
    message?.apply {
        formatted = null
        params = null
        message = ObservabilityPolicy.REDACTED_EVENT_MESSAGE
    }
    exceptions?.forEach { exception ->
        exception.value = ObservabilityPolicy.REDACTED_EVENT_MESSAGE
    }
    return this
}
