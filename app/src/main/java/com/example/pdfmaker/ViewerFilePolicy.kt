package com.example.pdfmaker

internal enum class ViewerFileKind {
    PDF,
    DOCX,
    XLSX,
    CSV,
    TXT,
    IMAGE,
    PPTX,
    UNSUPPORTED,
}

internal fun detectViewerFileKind(
    filePath: String,
    displayName: String = "",
): ViewerFileKind {
    val extension = viewerExtension(filePath).ifEmpty { viewerExtension(displayName) }
    return when (extension) {
        "pdf" -> ViewerFileKind.PDF
        "doc", "docx" -> ViewerFileKind.DOCX
        "xls", "xlsx" -> ViewerFileKind.XLSX
        "ppt", "pptx" -> ViewerFileKind.PPTX
        "csv", "tsv" -> ViewerFileKind.CSV
        "txt", "md", "log", "text" -> ViewerFileKind.TXT
        "jpg", "jpeg", "png", "gif", "webp", "bmp" -> ViewerFileKind.IMAGE
        else -> ViewerFileKind.UNSUPPORTED
    }
}

internal fun shouldOpenExternally(kind: ViewerFileKind): Boolean = kind == ViewerFileKind.DOCX || kind == ViewerFileKind.XLSX || kind == ViewerFileKind.PPTX

internal fun canRenderInApp(kind: ViewerFileKind): Boolean = kind != ViewerFileKind.UNSUPPORTED && !shouldOpenExternally(kind)

internal fun viewerMimeType(kind: ViewerFileKind): String =
    when (kind) {
        ViewerFileKind.PDF -> "application/pdf"
        ViewerFileKind.DOCX -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        ViewerFileKind.XLSX -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        ViewerFileKind.PPTX -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        ViewerFileKind.CSV -> "text/csv"
        ViewerFileKind.TXT -> "text/plain"
        ViewerFileKind.IMAGE -> "image/*"
        ViewerFileKind.UNSUPPORTED -> "*/*"
    }

internal fun viewerTargetWidth(
    screenWidthDp: Int,
    density: Float,
): Int {
    val safeWidth = screenWidthDp.coerceAtLeast(1)
    val safeDensity = density.takeIf { it.isFinite() && it > 0f } ?: 1f
    return viewerRenderWidth((safeWidth * safeDensity * 2f).toInt().coerceAtLeast(1_080))
}

internal fun viewerRenderWidth(requestedWidth: Int): Int = requestedWidth.coerceIn(320, 2_048)

private fun viewerExtension(value: String): String =
    value
        .substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .substringAfterLast('.', "")
        .lowercase()
