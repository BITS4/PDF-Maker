package com.example.pdfmaker

/** Bounds editor rendering independently of display metadata supplied by a device or document. */
internal object PdfEditorRenderPolicy {
    const val MIN_RENDER_EDGE = 320
    const val MAX_RENDER_EDGE = 2_048
    const val MAX_PACKAGE_BYTES = 512L * 1024L * 1024L

    fun targetSize(
        pageWidth: Int,
        pageHeight: Int,
        requestedWidth: Int,
    ): PixelSize? =
        RenderSizing.fitWithin(
            sourceWidth = pageWidth,
            sourceHeight = pageHeight,
            maxDimension = requestedWidth.coerceIn(MIN_RENDER_EDGE, MAX_RENDER_EDGE),
            allowUpscale = true,
        )

    fun requirePageCount(pageCount: Int): Int = PageEditPolicy.requireSupportedPageCount(pageCount)
}
