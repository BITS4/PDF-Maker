package com.example.pdfmaker

import android.content.Context
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
        check(
            DocumentShareAdapter.share(
                context = context,
                file = file,
                chooserTitle = "Share PDF",
                requestedMimeType = "application/pdf",
            ),
        ) { "Document sharing is unavailable" }
    }
}
