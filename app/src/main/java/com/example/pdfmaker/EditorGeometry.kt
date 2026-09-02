package com.example.pdfmaker

/** Converts a signature's screen-space placement to bounded page coordinates. */
internal fun normalizeSignaturePlacement(
    x: Float,
    y: Float,
    scaleFactor: Float,
    pageWidth: Int,
    pageHeight: Int,
): NormalizedSignaturePlacement? {
    if (pageWidth < 2 || pageHeight < 2 || !scaleFactor.isFinite()) return null

    val normalizedWidth = (0.4f * scaleFactor).coerceIn(0.05f, 1f)
    return NormalizedSignaturePlacement(
        x = (x / pageWidth).coerceIn(0f, 1f),
        y = (y / pageHeight).coerceIn(0f, 1f),
        width = normalizedWidth,
    )
}
