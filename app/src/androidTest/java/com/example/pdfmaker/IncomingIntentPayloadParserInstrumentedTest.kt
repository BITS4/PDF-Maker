package com.example.pdfmaker

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IncomingIntentPayloadParserInstrumentedTest {
    @Test
    fun acceptsContentViewIntentWithDeclaredType() {
        val uri = Uri.parse("content://documents.provider/report.pdf")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply { type = "application/pdf" }

        val accepted = IncomingIntentPayloadParser.parse(intent) as IncomingIntentParseResult.Accepted

        assertEquals(uri, accepted.uri)
        assertEquals("application/pdf", accepted.declaredMimeType)
    }

    @Test
    fun acceptsSendUriFromExtraAndClipFallback() {
        val extraUri = Uri.parse("content://documents.provider/extra.pdf")
        val clipUri = Uri.parse("content://documents.provider/clip.pdf")
        val extraIntent = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, extraUri)
        val clipIntent =
            Intent(Intent.ACTION_SEND).apply {
                clipData = ClipData.newRawUri("document", clipUri)
            }

        assertEquals(
            extraUri,
            (IncomingIntentPayloadParser.parse(extraIntent) as IncomingIntentParseResult.Accepted).uri,
        )
        assertEquals(
            clipUri,
            (IncomingIntentPayloadParser.parse(clipIntent) as IncomingIntentParseResult.Accepted).uri,
        )
    }

    @Test
    fun rejectsMalformedMissingAndNonContentPayloads() {
        val malformed =
            Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_STREAM, Bundle())
                clipData = ClipData.newPlainText("not a URI", "payload")
            }
        val missing = Intent(Intent.ACTION_SEND)
        val fileView = Intent(Intent.ACTION_VIEW, Uri.parse("file:///sdcard/private.pdf"))

        assertSame(IncomingIntentParseResult.Invalid, IncomingIntentPayloadParser.parse(malformed))
        assertSame(IncomingIntentParseResult.Invalid, IncomingIntentPayloadParser.parse(missing))
        assertSame(IncomingIntentParseResult.Invalid, IncomingIntentPayloadParser.parse(fileView))
    }

    @Test
    fun ignoresUnrelatedAndNullIntents() {
        assertSame(IncomingIntentParseResult.NotIncoming, IncomingIntentPayloadParser.parse(Intent(Intent.ACTION_MAIN)))
        assertSame(IncomingIntentParseResult.NotIncoming, IncomingIntentPayloadParser.parse(null))
    }
}
