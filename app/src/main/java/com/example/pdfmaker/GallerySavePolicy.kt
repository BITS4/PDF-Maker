package com.example.pdfmaker

internal data class GallerySaveReport(
    val requestedCount: Int,
    val savedCount: Int,
    val errors: List<String>,
) {
    init {
        require(requestedCount >= 0 && savedCount in 0..requestedCount)
    }

    val isComplete: Boolean = requestedCount > 0 && savedCount == requestedCount && errors.isEmpty()

    val userMessage: String
        get() = when {
            isComplete -> "$savedCount image(s) saved to the gallery"
            requestedCount == 0 -> "There are no images to save"
            savedCount > 0 -> "$savedCount of $requestedCount images saved. ${errors.firstOrNull().orEmpty()}".trim()
            else -> errors.firstOrNull() ?: "No images could be saved"
        }
}

internal object GallerySavePolicy {
    const val MIN_MEDIA_STORE_SDK = 29

    fun supportsGalleryWrite(sdkInt: Int): Boolean = sdkInt >= MIN_MEDIA_STORE_SDK

    fun report(requestedCount: Int, savedCount: Int, errors: List<String>): GallerySaveReport =
        GallerySaveReport(requestedCount, savedCount, errors.distinct())
}
