package com.example.pdfmaker

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.pdfmaker.ui.theme.PDFMakerTheme

class MainActivity : ComponentActivity() {
    private var incomingIntentLifecycle = IncomingIntentLifecycleState.create()

    var incomingDocumentRequest by mutableStateOf<IncomingDocumentRequest?>(null)
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingIntentLifecycle = restoreIncomingIntentLifecycle(savedInstanceState)
        enableEdgeToEdge()
        restoreOrHandleIncomingIntent(intent)
        setContent {
            PDFMakerTheme {
                AppNavigation(activity = this@MainActivity)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNewIncomingIntent(intent)
    }

    internal fun handleNewIncomingIntent(intent: Intent) {
        when (val parsed = IncomingIntentPayloadParser.parse(intent)) {
            IncomingIntentParseResult.NotIncoming -> Unit
            IncomingIntentParseResult.Invalid -> discardIncomingIntent()
            is IncomingIntentParseResult.Accepted -> {
                setIntent(intent)
                acceptIncomingIntent(parsed, incomingIntentLifecycle.recordDelivery())
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong(STATE_LAST_REQUEST_ID, incomingIntentLifecycle.lastIssuedRequestId)
        outState.putLong(STATE_PENDING_REQUEST_ID, incomingIntentLifecycle.pendingRequestId)
        outState.putBoolean(STATE_HAS_PENDING_REQUEST, incomingIntentLifecycle.hasPendingRequest)
        outState.putBoolean(STATE_CURRENT_INTENT_CONSUMED, incomingIntentLifecycle.currentIntentConsumed)
        super.onSaveInstanceState(outState)
    }

    fun claimIncomingDocument(requestId: Long): Boolean {
        if (!incomingIntentLifecycle.claim(requestId)) return false
        incomingDocumentRequest = null
        neutralizeIncomingIntent()
        return true
    }

    private fun restoreOrHandleIncomingIntent(intent: Intent?) {
        if (incomingIntentLifecycle.currentIntentConsumed) {
            neutralizeIncomingIntent()
            return
        }
        when (val parsed = IncomingIntentPayloadParser.parse(intent)) {
            IncomingIntentParseResult.NotIncoming -> discardIncomingIntent()
            IncomingIntentParseResult.Invalid -> discardIncomingIntent()
            is IncomingIntentParseResult.Accepted -> {
                val requestId =
                    incomingIntentLifecycle.pendingRequestId
                        .takeIf { incomingIntentLifecycle.hasPendingRequest }
                        ?: incomingIntentLifecycle.recordDelivery()
                acceptIncomingIntent(parsed, requestId)
            }
        }
    }

    private fun acceptIncomingIntent(
        parsed: IncomingIntentParseResult.Accepted,
        requestId: Long,
    ) {
        incomingDocumentRequest =
            IncomingDocumentRequest(
                uri = parsed.uri,
                declaredMimeType = parsed.declaredMimeType,
                requestId = requestId,
            )
    }

    private fun discardIncomingIntent() {
        incomingIntentLifecycle.discardCurrentIntent()
        incomingDocumentRequest = null
        neutralizeIncomingIntent()
    }

    private fun neutralizeIncomingIntent() {
        setIntent(Intent(this, MainActivity::class.java))
    }

    private fun restoreIncomingIntentLifecycle(savedInstanceState: Bundle?): IncomingIntentLifecycleState {
        if (savedInstanceState == null) return IncomingIntentLifecycleState.create()
        return IncomingIntentLifecycleState.restore(
            lastIssuedRequestId = savedInstanceState.getLong(STATE_LAST_REQUEST_ID),
            pendingRequestId = savedInstanceState.getLong(STATE_PENDING_REQUEST_ID),
            hasPendingRequest = savedInstanceState.getBoolean(STATE_HAS_PENDING_REQUEST),
            currentIntentConsumed = savedInstanceState.getBoolean(STATE_CURRENT_INTENT_CONSUMED),
        )
    }

    private companion object {
        const val STATE_LAST_REQUEST_ID = "incoming.last-request-id"
        const val STATE_PENDING_REQUEST_ID = "incoming.pending-request-id"
        const val STATE_HAS_PENDING_REQUEST = "incoming.has-pending-request"
        const val STATE_CURRENT_INTENT_CONSUMED = "incoming.current-intent-consumed"
    }
}
