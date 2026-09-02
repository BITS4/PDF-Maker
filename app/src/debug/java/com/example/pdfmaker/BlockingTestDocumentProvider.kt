package com.example.pdfmaker

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.OpenableColumns
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Debug-only provider used to verify cancellation and recreation at the real Android IPC boundary. */
class BlockingTestDocumentProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor = queryResult()

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
        cancellationSignal: CancellationSignal?,
    ): Cursor {
        queryCount.incrementAndGet()
        queryFailure?.let { failure -> throw failure }
        while (blocking) {
            if (cancellationSignal?.isCanceled == true) {
                cancellationCount.incrementAndGet()
                throw OperationCanceledException("Test provider query cancelled")
            }
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        }
        return queryResult()
    }

    override fun getType(uri: Uri): String = "application/pdf"

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor {
        val appContext = checkNotNull(context)
        val source = File(appContext.cacheDir, "blocking-provider.pdf")
        source.writeText("%PDF-1.7\n%%EOF")
        return ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private fun queryResult(): Cursor =
        MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
            addRow(arrayOf<Any?>("blocked.pdf", 15L))
        }

    companion object {
        const val AUTHORITY = "com.example.pdfmaker.debug.blocking-documents"
        val documentUri: Uri = Uri.parse("content://$AUTHORITY/document.pdf")

        private const val POLL_INTERVAL_MILLIS = 5L
        private val queryCount = AtomicInteger()
        private val cancellationCount = AtomicInteger()

        @Volatile
        private var blocking = true

        @Volatile
        private var queryFailure: RuntimeException? = null

        fun reset() {
            blocking = true
            queryFailure = null
            queryCount.set(0)
            cancellationCount.set(0)
        }

        fun release() {
            blocking = false
        }

        fun failQueriesWith(failure: RuntimeException) {
            queryFailure = failure
            blocking = false
        }

        fun awaitQueries(
            expected: Int,
            timeout: Long,
            unit: TimeUnit,
        ): Boolean = awaitCounter(queryCount, expected, timeout, unit)

        fun awaitCancellations(
            expected: Int,
            timeout: Long,
            unit: TimeUnit,
        ): Boolean = awaitCounter(cancellationCount, expected, timeout, unit)

        private fun awaitCounter(
            counter: AtomicInteger,
            expected: Int,
            timeout: Long,
            unit: TimeUnit,
        ): Boolean {
            val deadline = System.nanoTime() + unit.toNanos(timeout)
            while (counter.get() < expected && System.nanoTime() < deadline) {
                SystemClock.sleep(POLL_INTERVAL_MILLIS)
            }
            return counter.get() >= expected
        }
    }
}
