package com.example.pdfmaker

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import com.example.pdfmaker.ui.theme.PDFMakerTheme

class MainActivity : ComponentActivity() {
    var incomingDocumentRequest by mutableStateOf<IncomingDocumentRequest?>(null)
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingIntent(intent)
        setContent {
            PDFMakerTheme {
                AppNavigation(activity = this@MainActivity)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    fun consumeIncomingDocument(request: IncomingDocumentRequest) {
        if (incomingDocumentRequest == request) incomingDocumentRequest = null
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val incomingIntent = intent ?: return
        val uri = when (incomingIntent.action) {
            Intent.ACTION_VIEW -> incomingIntent.data
            Intent.ACTION_SEND -> incomingStream(incomingIntent)
            else -> null
        } ?: return
        incomingDocumentRequest = IncomingDocumentRequest(uri, incomingIntent.type)
    }

    private fun incomingStream(intent: Intent): Uri? =
        IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?: intent.clipData?.getItemAt(0)?.uri
}
