package com.example.pdfmaker

import java.net.URI

/** Pure policy shared by telemetry initialization and unit tests. */
object ObservabilityPolicy {
    const val REDACTED_EVENT_MESSAGE = "Redacted application failure"
    private const val MAX_DSN_LENGTH = 2_048
    private const val MAX_LOG_TAG_LENGTH = 48
    private val safeLogTag = Regex("[A-Za-z0-9_.-]+")
    private val safeProjectId = Regex("[A-Za-z0-9_-]+")

    fun shouldEnable(
        enabled: Boolean,
        dsn: String,
        isDebug: Boolean,
    ): Boolean = enabled && !isDebug && isValidDsn(dsn)

    fun isValidDsn(dsn: String): Boolean {
        if (dsn.isBlank() || dsn.length > MAX_DSN_LENGTH) return false
        val uri = runCatching { URI(dsn) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (uri.host.isNullOrBlank() || uri.rawQuery != null || uri.rawFragment != null) return false

        val publicKey = uri.rawUserInfo?.substringBefore(':').orEmpty()
        val projectId = uri.path.orEmpty().trim('/').substringAfterLast('/')
        return publicKey.isNotBlank() && projectId.matches(safeProjectId)
    }

    fun safeLogTag(candidate: String?): String =
        candidate
            ?.takeIf { it.length in 1..MAX_LOG_TAG_LENGTH && it.matches(safeLogTag) }
            ?: "PdfMaker"

    fun safeLogMessage(priority: Int, throwable: Throwable?): String = buildString {
        append("event=application_log priority=")
        append(priority)
        if (throwable != null) {
            append(" failure_type=")
            append(throwable.javaClass.simpleName.take(64).filter(Char::isLetterOrDigit))
        }
    }

    fun sanitizedThrowable(source: Throwable): Throwable =
        TelemetryFailure().also { safe -> safe.stackTrace = source.stackTrace }

    private class TelemetryFailure : RuntimeException(REDACTED_EVENT_MESSAGE)
}
