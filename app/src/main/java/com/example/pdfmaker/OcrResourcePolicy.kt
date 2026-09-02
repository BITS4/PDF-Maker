package com.example.pdfmaker

internal data class AcceptedOcrText(
    val text: String,
    val totalCharacters: Int,
)

/** Resource limits shared by PDF and image OCR before expensive work is started. */
internal object OcrResourcePolicy {
    const val MAX_PDF_PAGES = 200
    const val MAX_PDF_RENDER_DIMENSION = 1_600
    const val MAX_IMAGE_DIMENSION = 2_000
    const val MAX_TEXT_CHARACTERS_PER_PAGE = 100_000
    const val MAX_TOTAL_TEXT_CHARACTERS = 2_000_000
    const val MAX_FORMATTED_TEXT_CHARACTERS = MAX_TOTAL_TEXT_CHARACTERS + (MAX_PDF_PAGES * 32)
    const val MAX_TOTAL_RENDERED_PIXELS = 384_000_000L

    fun requirePdfPageCount(pageCount: Int): Int {
        require(pageCount in 1..MAX_PDF_PAGES) {
            "PDF must contain between 1 and $MAX_PDF_PAGES pages"
        }
        return pageCount
    }

    fun pdfRenderSize(
        sourceWidth: Int,
        sourceHeight: Int,
    ): PixelSize =
        requireNotNull(
            RenderSizing.fitWithin(
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                maxDimension = MAX_PDF_RENDER_DIMENSION,
                allowUpscale = true,
            ),
        ) { "PDF page has invalid dimensions" }

    fun imageDecodeSize(
        sourceWidth: Int,
        sourceHeight: Int,
    ): PixelSize =
        requireNotNull(
            RenderSizing.fitWithin(
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                maxDimension = MAX_IMAGE_DIMENSION,
            ),
        ) { "Image has invalid dimensions" }

    fun imageSampleSize(
        sourceWidth: Int,
        sourceHeight: Int,
        target: PixelSize,
    ): Int {
        require(sourceWidth > 0 && sourceHeight > 0) { "Image has invalid dimensions" }
        require(target.width > 0 && target.height > 0) { "OCR target has invalid dimensions" }
        var sampleSize = 1
        while (sourceWidth / sampleSize > target.width || sourceHeight / sampleSize > target.height) {
            sampleSize *= 2
        }
        return sampleSize
    }

    fun requireDecodedImage(
        width: Int,
        height: Int,
    ): PixelSize {
        require(
            width in 1..MAX_IMAGE_DIMENSION &&
                height in 1..MAX_IMAGE_DIMENSION,
        ) { "Decoded image exceeds the OCR pixel limit" }
        return PixelSize(width, height)
    }

    fun updatedRenderedPixels(
        currentPixels: Long,
        additionalPixels: Long,
    ): Long {
        require(currentPixels in 0..MAX_TOTAL_RENDERED_PIXELS) {
            "OCR render workload is invalid"
        }
        require(additionalPixels > 0 && additionalPixels <= MAX_TOTAL_RENDERED_PIXELS - currentPixels) {
            "OCR render workload exceeds its pixel limit"
        }
        return currentPixels + additionalPixels
    }

    fun acceptRecognizedText(
        pageNumber: Int,
        recognizedText: String,
        currentCharacters: Int,
    ): AcceptedOcrText {
        require(pageNumber in 1..MAX_PDF_PAGES) { "OCR page number is invalid" }
        require(currentCharacters in 0..MAX_TOTAL_TEXT_CHARACTERS) {
            "OCR text total is invalid"
        }
        require(recognizedText.length <= MAX_TEXT_CHARACTERS_PER_PAGE) {
            "A page contains too much recognized text"
        }
        val normalized = recognizedText.trim()
        require(normalized.length <= MAX_TOTAL_TEXT_CHARACTERS - currentCharacters) {
            "Recognized text exceeds the aggregate limit"
        }
        return AcceptedOcrText(
            text = normalized,
            totalCharacters = currentCharacters + normalized.length,
        )
    }

    fun requireFormattedLength(
        currentLength: Int,
        additionalLength: Int,
    ): Int {
        require(currentLength in 0..MAX_FORMATTED_TEXT_CHARACTERS) {
            "Formatted OCR text size is invalid"
        }
        require(additionalLength >= 0 && additionalLength <= MAX_FORMATTED_TEXT_CHARACTERS - currentLength) {
            "Formatted OCR text exceeds its limit"
        }
        return currentLength + additionalLength
    }
}
