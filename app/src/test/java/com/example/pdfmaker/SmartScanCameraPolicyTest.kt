package com.example.pdfmaker

import android.view.OrientationEventListener
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmartScanCameraPolicyTest {
    @Test
    fun unknownOrientationDoesNotChangeTheCurrentCaptureRotation() {
        assertNull(SmartScanCameraPolicy.surfaceRotationForDegrees(OrientationEventListener.ORIENTATION_UNKNOWN))
    }

    @Test
    fun rotationRangesIncludeEveryBoundaryWithoutGaps() {
        val expectations =
            mapOf(
                0 to Surface.ROTATION_0,
                44 to Surface.ROTATION_0,
                45 to Surface.ROTATION_270,
                134 to Surface.ROTATION_270,
                135 to Surface.ROTATION_180,
                224 to Surface.ROTATION_180,
                225 to Surface.ROTATION_90,
                314 to Surface.ROTATION_90,
                315 to Surface.ROTATION_0,
                359 to Surface.ROTATION_0,
            )

        expectations.forEach { (orientation, expectedRotation) ->
            assertEquals(expectedRotation, SmartScanCameraPolicy.surfaceRotationForDegrees(orientation))
        }
    }
}
