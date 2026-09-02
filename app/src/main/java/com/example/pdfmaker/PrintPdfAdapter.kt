package com.example.pdfmaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.pdf.PrintedPdfDocument
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

internal class PrintPdfAdapter(
    context: Context,
    private val sourceUri: Uri,
    private val displayName: String,
    private val onFinished: () -> Unit,
    private val onFailure: (String) -> Unit,
) : PrintDocumentAdapter() {
    private val appContext = context.applicationContext
    private val worker: ExecutorService =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "pdf-print-worker").apply { isDaemon = true }
        }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private val finishDelivered = AtomicBoolean(false)
    private val failureDelivered = AtomicBoolean(false)
    private val stateLock = Any()
    private var stagedSource: StagedPrintPdfSource? = null
    private var renderAttributes: PrintAttributes? = null
    private var previousLayoutAttributes: PrintAttributes? = null

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (isCancelled(cancellationSignal)) {
            callback.onLayoutCancelled()
            return
        }
        executeOrReject(onRejected = { callback.onLayoutFailed(PREPARE_FAILED) }) {
            try {
                checkpoint(cancellationSignal)
                val source = obtainSource(cancellationSignal)
                val normalizedAttributes = normalizeAttributes(newAttributes)
                checkpoint(cancellationSignal)
                val changed =
                    synchronized(stateLock) {
                        val didChange =
                            previousLayoutAttributes == null ||
                                previousLayoutAttributes != normalizedAttributes
                        previousLayoutAttributes = normalizedAttributes
                        renderAttributes = normalizedAttributes
                        didChange
                    }
                val info =
                    PrintDocumentInfo
                        .Builder(displayName)
                        .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                        .setPageCount(source.pageCount)
                        .build()
                callback.onLayoutFinished(info, changed)
            } catch (_: OperationCanceledException) {
                callback.onLayoutCancelled()
            } catch (error: IOException) {
                val message = userMessage(error, PREPARE_FAILED)
                callback.onLayoutFailed(message)
                notifyFailure(message)
            } catch (_: IllegalArgumentException) {
                callback.onLayoutFailed(PREPARE_FAILED)
                notifyFailure(PREPARE_FAILED)
            } catch (_: IllegalStateException) {
                callback.onLayoutFailed(PREPARE_FAILED)
                notifyFailure(PREPARE_FAILED)
            } catch (_: SecurityException) {
                callback.onLayoutFailed(PREPARE_FAILED)
                notifyFailure(PREPARE_FAILED)
            }
        }
    }

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback,
    ) {
        if (isCancelled(cancellationSignal)) {
            closeDestination(destination)
            callback.onWriteCancelled()
            return
        }
        executeOrReject(
            onRejected = {
                closeDestination(destination)
                callback.onWriteFailed(WRITE_FAILED)
            },
        ) {
            try {
                checkpoint(cancellationSignal)
                val (source, attributes) =
                    synchronized(stateLock) {
                        Pair(stagedSource, renderAttributes)
                    }
                if (source == null || attributes == null) {
                    throw PrintPipelineException("The PDF must be prepared before it can be printed.")
                }
                val selectedPages =
                    PrintPdfPolicy.selectedPages(
                        pageCount = source.pageCount,
                        requestedRanges =
                            pages.map { range ->
                                PrintPageSpan(range.start, range.end)
                            },
                    )
                val writeMode =
                    try {
                        PrintPdfPolicy.requireWritableSelection(source.pageCount, selectedPages)
                    } catch (error: IllegalArgumentException) {
                        throw PrintPipelineException(
                            UserVisibleFailurePolicy.message(UserFailureStage.PDF_PRINT, error),
                            error,
                        )
                    }

                when (writeMode) {
                    PrintWriteMode.ORIGINAL -> {
                        writeOriginal(source, destination, cancellationSignal)
                    }

                    PrintWriteMode.RENDERED_SELECTION -> {
                        writeSelectedPages(
                            source = source,
                            attributes = attributes,
                            selectedPages = selectedPages,
                            destination = destination,
                            cancellationSignal = cancellationSignal,
                        )
                    }
                }
                checkpoint(cancellationSignal)
                val writtenRanges =
                    PrintPdfPolicy
                        .collapsedRanges(selectedPages)
                        .map { range -> PageRange(range.first, range.last) }
                        .toTypedArray()
                callback.onWriteFinished(writtenRanges)
            } catch (_: OperationCanceledException) {
                callback.onWriteCancelled()
            } catch (error: IOException) {
                val message = userMessage(error, WRITE_FAILED)
                callback.onWriteFailed(message)
                notifyFailure(message)
            } catch (_: IllegalArgumentException) {
                callback.onWriteFailed(WRITE_FAILED)
                notifyFailure(WRITE_FAILED)
            } catch (_: IllegalStateException) {
                callback.onWriteFailed(WRITE_FAILED)
                notifyFailure(WRITE_FAILED)
            } catch (_: SecurityException) {
                callback.onWriteFailed(WRITE_FAILED)
                notifyFailure(WRITE_FAILED)
            } finally {
                closeDestination(destination)
            }
        }
    }

    override fun onFinish() {
        dispose()
        if (finishDelivered.compareAndSet(false, true)) {
            mainHandler.post(onFinished)
        }
    }

    fun dispose() {
        if (!closed.compareAndSet(false, true)) return
        try {
            worker.execute(::releaseSource)
        } catch (_: RejectedExecutionException) {
            releaseSource()
        }
        worker.shutdown()
    }

    private fun releaseSource() {
        synchronized(stateLock) {
            stagedSource?.close()
            stagedSource = null
            renderAttributes = null
            previousLayoutAttributes = null
        }
    }

    private fun obtainSource(cancellationSignal: CancellationSignal?): StagedPrintPdfSource {
        synchronized(stateLock) { stagedSource?.let { return it } }
        val created =
            PrintPdfSource.stage(appContext, sourceUri) {
                checkpoint(cancellationSignal)
            }
        return synchronized(stateLock) {
            if (closed.get()) {
                created.close()
                throw OperationCanceledException("Print job finished")
            }
            stagedSource ?: created.also { stagedSource = it }
        }
    }

    private fun writeOriginal(
        source: StagedPrintPdfSource,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
    ) {
        try {
            FileInputStream(source.file).use { input ->
                ParcelFileDescriptor.AutoCloseOutputStream(destination).use { output ->
                    val boundedOutput =
                        BoundedIo.limit(
                            output = output,
                            maximumBytes = PrintPdfPolicy.MAX_OUTPUT_BYTES,
                            beforeWrite = { checkpoint(cancellationSignal) },
                        )
                    BoundedIo.copy(
                        input = input,
                        output = boundedOutput,
                        maximumBytes = SafePdfInput.MAX_PDF_BYTES,
                    )
                    checkpoint(cancellationSignal)
                    boundedOutput.flush()
                }
            }
        } catch (cancelled: OperationCanceledException) {
            throw cancelled
        } catch (error: IOException) {
            throw PrintPipelineException(WRITE_FAILED, error)
        }
    }

    private fun writeSelectedPages(
        source: StagedPrintPdfSource,
        attributes: PrintAttributes,
        selectedPages: List<Int>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
    ) {
        val document = PrintedPdfDocument(appContext, attributes)
        try {
            renderSelectedPages(document, source, attributes, selectedPages, cancellationSignal)
            checkpoint(cancellationSignal)
            writeRenderedDocument(document, destination, cancellationSignal)
        } finally {
            document.close()
        }
    }

    private fun renderSelectedPages(
        document: PrintedPdfDocument,
        source: StagedPrintPdfSource,
        attributes: PrintAttributes,
        selectedPages: List<Int>,
        cancellationSignal: CancellationSignal?,
    ) {
        source.openDescriptor().use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                selectedPages.forEach { sourcePageIndex ->
                    appendSourcePage(document, renderer, sourcePageIndex, attributes, cancellationSignal)
                }
            }
        }
    }

    private fun appendSourcePage(
        document: PrintedPdfDocument,
        renderer: PdfRenderer,
        sourcePageIndex: Int,
        attributes: PrintAttributes,
        cancellationSignal: CancellationSignal?,
    ) {
        checkpoint(cancellationSignal)
        renderer.openPage(sourcePageIndex).use { sourcePage ->
            appendRenderedPage(
                document = document,
                sourcePage = sourcePage,
                sourcePageIndex = sourcePageIndex,
                attributes = attributes,
                cancellationSignal = cancellationSignal,
            )
        }
    }

    private fun writeRenderedDocument(
        document: PdfDocument,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
    ) {
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(destination).use { output ->
                val boundedOutput =
                    BoundedIo.limit(
                        output = output,
                        maximumBytes = PrintPdfPolicy.MAX_OUTPUT_BYTES,
                        beforeWrite = { checkpoint(cancellationSignal) },
                    )
                document.writeTo(boundedOutput)
                checkpoint(cancellationSignal)
                boundedOutput.flush()
            }
        } catch (cancelled: OperationCanceledException) {
            throw cancelled
        } catch (error: IOException) {
            throw PrintPipelineException(WRITE_FAILED, error)
        }
    }

    private fun appendRenderedPage(
        document: PrintedPdfDocument,
        sourcePage: PdfRenderer.Page,
        sourcePageIndex: Int,
        attributes: PrintAttributes,
        cancellationSignal: CancellationSignal?,
    ) {
        val outputPage = document.startPage(sourcePageIndex)
        var bitmap: Bitmap? = null
        try {
            val content = outputPage.info.contentRect
            val resolution = requireNotNull(attributes.resolution)
            val bitmapSize =
                PrintPdfPolicy.renderSize(
                    sourceWidth = sourcePage.width,
                    sourceHeight = sourcePage.height,
                    printableWidthPoints = content.width(),
                    printableHeightPoints = content.height(),
                    horizontalDpi = resolution.horizontalDpi,
                    verticalDpi = resolution.verticalDpi,
                ) ?: throw PrintPipelineException("A PDF page has invalid print dimensions.")
            val destination =
                PrintPdfPolicy.destinationRect(
                    sourceWidth = sourcePage.width,
                    sourceHeight = sourcePage.height,
                    contentLeft = content.left,
                    contentTop = content.top,
                    contentRight = content.right,
                    contentBottom = content.bottom,
                ) ?: throw PrintPipelineException("The selected paper margins leave no printable area.")

            bitmap = Bitmap.createBitmap(bitmapSize.width, bitmapSize.height, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).drawColor(Color.WHITE)
            val transform =
                Matrix().apply {
                    setScale(
                        bitmapSize.width.toFloat() / sourcePage.width.toFloat(),
                        bitmapSize.height.toFloat() / sourcePage.height.toFloat(),
                    )
                }
            checkpoint(cancellationSignal)
            sourcePage.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            checkpoint(cancellationSignal)
            outputPage.canvas.drawColor(Color.WHITE)
            outputPage.canvas.drawBitmap(
                bitmap,
                null,
                RectF(
                    destination.left.toFloat(),
                    destination.top.toFloat(),
                    destination.right.toFloat(),
                    destination.bottom.toFloat(),
                ),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
            )
        } finally {
            try {
                document.finishPage(outputPage)
            } finally {
                bitmap?.recycle()
            }
        }
    }

    private fun executeOrReject(
        onRejected: () -> Unit,
        task: () -> Unit,
    ) {
        try {
            worker.execute(task)
        } catch (_: RejectedExecutionException) {
            onRejected()
        }
    }

    private fun checkpoint(cancellationSignal: CancellationSignal?) {
        if (isCancelled(cancellationSignal)) throw OperationCanceledException("Print cancelled")
    }

    private fun isCancelled(cancellationSignal: CancellationSignal?): Boolean =
        closed.get() || cancellationSignal?.isCanceled == true || Thread.currentThread().isInterrupted

    private fun notifyFailure(message: String) {
        if (failureDelivered.compareAndSet(false, true)) {
            mainHandler.post { onFailure(message) }
        }
    }

    private fun userMessage(
        error: Exception,
        fallback: String,
    ): String = (error as? PrintPipelineException)?.userMessage ?: fallback

    private fun closeDestination(destination: ParcelFileDescriptor) {
        try {
            destination.close()
        } catch (_: IOException) {
            // The print framework may have already closed an auto-closing stream.
        }
    }

    private fun normalizeAttributes(attributes: PrintAttributes): PrintAttributes {
        val colorMode =
            when (attributes.colorMode) {
                PrintAttributes.COLOR_MODE_MONOCHROME -> PrintAttributes.COLOR_MODE_MONOCHROME
                else -> PrintAttributes.COLOR_MODE_COLOR
            }
        val duplexMode =
            when (attributes.duplexMode) {
                PrintAttributes.DUPLEX_MODE_LONG_EDGE -> PrintAttributes.DUPLEX_MODE_LONG_EDGE
                PrintAttributes.DUPLEX_MODE_SHORT_EDGE -> PrintAttributes.DUPLEX_MODE_SHORT_EDGE
                else -> PrintAttributes.DUPLEX_MODE_NONE
            }
        return PrintAttributes
            .Builder()
            .setMediaSize(attributes.mediaSize ?: PrintAttributes.MediaSize.ISO_A4)
            .setResolution(attributes.resolution ?: DEFAULT_RESOLUTION)
            .setMinMargins(attributes.minMargins ?: PrintAttributes.Margins.NO_MARGINS)
            .setColorMode(colorMode)
            .setDuplexMode(duplexMode)
            .build()
    }

    companion object {
        const val PREPARE_FAILED = "The PDF could not be prepared for printing."
        const val WRITE_FAILED = "The requested pages could not be written safely."
        val DEFAULT_RESOLUTION =
            PrintAttributes.Resolution(
                "pdfmaker-default",
                "PDF Maker default",
                300,
                300,
            )
    }
}
