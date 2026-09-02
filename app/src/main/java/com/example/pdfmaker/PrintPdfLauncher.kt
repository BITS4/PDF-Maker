package com.example.pdfmaker

import android.content.Context
import android.net.Uri
import android.print.PrintAttributes
import android.print.PrintManager

internal fun startPdfPrintJob(
    context: Context,
    sourceUri: Uri,
    requestedName: String,
    onFinished: () -> Unit,
    onFailure: (String) -> Unit,
) {
    val safeBaseName = SafeFileName.baseName(requestedName.substringBeforeLast('.'), "document")
    val adapter =
        PrintPdfAdapter(
            context = context,
            sourceUri = sourceUri,
            displayName = "$safeBaseName.pdf",
            onFinished = onFinished,
            onFailure = onFailure,
        )
    val started = tryStartPrintJob(context, safeBaseName, adapter)
    if (!started) {
        adapter.dispose()
        onFailure("The system print service could not be opened.")
    }
}

private fun tryStartPrintJob(
    context: Context,
    jobName: String,
    adapter: PrintPdfAdapter,
): Boolean =
    try {
        val printManager =
            context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
                ?: error("Printing is unavailable")
        printManager.print(jobName, adapter, defaultPrintAttributes())
        true
    } catch (_: IllegalArgumentException) {
        false
    } catch (_: IllegalStateException) {
        false
    } catch (_: SecurityException) {
        false
    }

private fun defaultPrintAttributes(): PrintAttributes =
    PrintAttributes
        .Builder()
        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
        .setResolution(PrintPdfAdapter.DEFAULT_RESOLUTION)
        .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
        .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
        .setDuplexMode(PrintAttributes.DUPLEX_MODE_NONE)
        .build()
