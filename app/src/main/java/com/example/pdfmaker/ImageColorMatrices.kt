package com.example.pdfmaker

import android.graphics.ColorMatrix

internal object ImageColorMatrices {
    fun docs(): ColorMatrix = saturatedScale(saturation = 0.04f, scale = 1.85f, translation = -95f)

    fun highContrastBlackAndWhite(): ColorMatrix =
        saturatedScale(saturation = 0f, scale = 2.1f, translation = -110f)

    fun vividDocument(): ColorMatrix =
        saturatedScale(saturation = 1.7f, scale = 1.3f, translation = -20f)

    fun forFilter(filter: ImageFilter): ColorMatrix =
        when (filter) {
            ImageFilter.IMAGE -> saturatedScale(saturation = 1.35f, scale = 1.12f, translation = 10f)
            ImageFilter.ENHANCE -> saturatedScale(saturation = 1.15f, scale = 1.22f, translation = 15f)
            ImageFilter.ENHANCE2 -> saturatedScale(saturation = 1.45f, scale = 1.42f, translation = 22f)
            ImageFilter.BW -> saturatedScale(saturation = 0f, scale = 1.2f, translation = -10f)
            ImageFilter.GRAY -> saturatedScale(saturation = 0.18f, scale = 1.08f, translation = 5f)
            ImageFilter.INVERT -> invert()
            ImageFilter.ORIGINAL,
            ImageFilter.AI_ENHANCE,
            ImageFilter.DOCS,
            ImageFilter.BW2,
            ImageFilter.SUPER,
            -> error("The selected filter uses a specialized pipeline")
        }

    fun adjustment(contrastFactor: Float, translation: Float): ColorMatrix =
        scale(contrastFactor, translation)

    private fun saturatedScale(saturation: Float, scale: Float, translation: Float): ColorMatrix =
        ColorMatrix().apply {
            setSaturation(saturation)
            postConcat(scale(scale, translation))
        }

    private fun invert(): ColorMatrix =
        ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )

    private fun scale(scale: Float, translation: Float): ColorMatrix =
        ColorMatrix(
            floatArrayOf(
                scale, 0f, 0f, 0f, translation,
                0f, scale, 0f, 0f, translation,
                0f, 0f, scale, 0f, translation,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
}
