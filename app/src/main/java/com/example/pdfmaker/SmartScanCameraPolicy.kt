package com.example.pdfmaker

import android.view.OrientationEventListener
import android.view.Surface

/** Pure mapping between sensor degrees and CameraX capture rotation. */
internal object SmartScanCameraPolicy {
    fun surfaceRotationForDegrees(orientation: Int): Int? =
        when (orientation) {
            OrientationEventListener.ORIENTATION_UNKNOWN -> null
            in 45 until 135 -> Surface.ROTATION_270
            in 135 until 225 -> Surface.ROTATION_180
            in 225 until 315 -> Surface.ROTATION_90
            else -> Surface.ROTATION_0
        }
}
