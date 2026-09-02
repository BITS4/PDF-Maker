package com.example.pdfmaker

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
class IncomingIntentLifecycleInstrumentedTest {
    @Before
    fun setUp() {
        BlockingTestDocumentProvider.reset()
    }

    @After
    fun tearDown() {
        BlockingTestDocumentProvider.release()
    }

    @Test
    fun identicalDeliveriesReceiveDistinctIdsAndStaleClaimCannotWin() {
        launchMain().use { scenario ->
            scenario.onActivity { activity ->
                val delivery = incomingView(Uri.parse("content://missing.provider/repeated.pdf"))
                activity.handleNewIncomingIntent(delivery)
                val first = requireNotNull(activity.incomingDocumentRequest)
                activity.handleNewIncomingIntent(delivery)
                val second = requireNotNull(activity.incomingDocumentRequest)

                assertNotEquals(first.requestId, second.requestId)
                assertFalse(activity.claimIncomingDocument(first.requestId))
                assertEquals(second.requestId, activity.incomingDocumentRequest?.requestId)
                assertTrue(activity.claimIncomingDocument(second.requestId))
                assertNull(activity.incomingDocumentRequest)
            }
        }
    }

    @Test
    fun consumedIncomingIntentDoesNotReplayAfterActivityRecreation() {
        launchMain().use { scenario ->
            scenario.onActivity { activity ->
                activity.handleNewIncomingIntent(incomingView(Uri.parse("content://missing.provider/once.pdf")))
                val request = requireNotNull(activity.incomingDocumentRequest)
                assertTrue(activity.claimIncomingDocument(request.requestId))
            }

            scenario.recreate()

            scenario.onActivity { activity -> assertNull(activity.incomingDocumentRequest) }
        }
    }

    @Test
    fun malformedSendPayloadIsRejectedAndCancelsPendingDelivery() {
        launchMain().use { scenario ->
            scenario.onActivity { activity ->
                activity.handleNewIncomingIntent(incomingView(Uri.parse("content://missing.provider/pending.pdf")))
                assertTrue(activity.incomingDocumentRequest != null)

                val malformed =
                    Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, Bundle())
                        clipData = ClipData.newPlainText("not a URI", "payload")
                    }
                activity.handleNewIncomingIntent(malformed)

                assertNull(activity.incomingDocumentRequest)
            }
        }
    }

    @Test
    fun pendingDeliverySurvivesRecreationAndCancelsBlockedProviderWork() {
        val firstRequestId = AtomicLong()
        ActivityScenario.launch<MainActivity>(incomingView(BlockingTestDocumentProvider.documentUri)).use { scenario ->
            assertTrue(BlockingTestDocumentProvider.awaitQueries(1, WAIT_SECONDS, TimeUnit.SECONDS))
            scenario.onActivity { activity ->
                firstRequestId.set(requireNotNull(activity.incomingDocumentRequest).requestId)
            }

            scenario.recreate()

            assertTrue(BlockingTestDocumentProvider.awaitCancellations(1, WAIT_SECONDS, TimeUnit.SECONDS))
            assertTrue(BlockingTestDocumentProvider.awaitQueries(2, WAIT_SECONDS, TimeUnit.SECONDS))
            scenario.onActivity { activity ->
                assertEquals(firstRequestId.get(), requireNotNull(activity.incomingDocumentRequest).requestId)
                activity.handleNewIncomingIntent(Intent(Intent.ACTION_SEND).apply { type = "application/pdf" })
                assertNull(activity.incomingDocumentRequest)
            }
            assertTrue(BlockingTestDocumentProvider.awaitCancellations(2, WAIT_SECONDS, TimeUnit.SECONDS))
        }
    }

    private fun launchMain(): ActivityScenario<MainActivity> =
        ActivityScenario.launch(
            Intent(ApplicationProvider.getApplicationContext<Context>(), MainActivity::class.java),
        )

    private fun incomingView(uri: Uri): Intent = Intent(Intent.ACTION_VIEW, uri).apply { type = "application/pdf" }

    private companion object {
        const val WAIT_SECONDS = 5L
    }
}
