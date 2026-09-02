package com.example.pdfmaker

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

internal data class ImportedPdfDisplayScale(
    val scaledDensity: Float,
)

internal class ImportedPdfViewerActions(
    private val context: Context,
    private val state: ImportedPdfViewerState,
    private val operations: PdfEditorOperationController,
    private val displayScale: ImportedPdfDisplayScale,
    private val onShareFile: (File) -> Unit,
) {
    fun startOfficeConversion(target: ConvertTarget) {
        val sourceUri = state.workingUri ?: return
        if (target != ConvertTarget.WORD && target != ConvertTarget.PPT) return
        val timestamp = System.currentTimeMillis()
        state.setConvertVisible(false)
        operations.launch(
            target = target,
            producer = { reportProgress ->
                when (target) {
                    ConvertTarget.WORD -> pdfToDocx(context, sourceUri, "doc_$timestamp.docx", reportProgress)

                    ConvertTarget.PPT -> pdfToPptx(context, sourceUri, "ppt_$timestamp.pptx", reportProgress)

                    ConvertTarget.NONE,
                    ConvertTarget.PDF,
                    -> error("Unsupported office conversion target")
                }
            },
            consumer = onShareFile,
        )
    }

    fun shareAnnotatedPdf() {
        val sourceUri = state.workingUri ?: return
        val annotationSnapshot = snapshotAnnotations(state.annotations)
        val pageBoxWidth = state.overlays.pageBoxWidth
        val pageBoxHeight = state.overlays.pageBoxHeight
        val outputName = "shared_${System.currentTimeMillis()}.pdf"
        operations.launch(
            target = ConvertTarget.PDF,
            producer = { reportProgress ->
                buildAnnotatedPdf(
                    context,
                    sourceUri,
                    annotationSnapshot,
                    displayScale.scaledDensity,
                    pageBoxWidth,
                    pageBoxHeight,
                    outputName,
                    reportProgress,
                )
            },
            consumer = ::sharePdf,
        )
    }

    private fun sharePdf(file: File) {
        val shareUri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, shareUri)
                clipData = ClipData.newRawUri("Annotated PDF", shareUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        context.startActivity(Intent.createChooser(intent, "Share PDF"))
    }
}
