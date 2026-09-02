package com.example.pdfmaker

import android.content.Context
import android.view.OrientationEventListener
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.concurrent.ExecutionException

@Composable
internal fun SmartScanCameraBindingEffect(
    state: SmartScanSessionState,
    lifecycleOwner: LifecycleOwner,
) {
    LaunchedEffect(state.previewWidth, state.previewHeight) {
        if (state.previewWidth <= 0 || state.previewHeight <= 0) return@LaunchedEffect
        try {
            val (camera, capture) =
                bindSmartScanCamera(
                    context = state.previewView.context,
                    lifecycleOwner = lifecycleOwner,
                    previewView = state.previewView,
                    targetRotation = state.deviceRotation,
                )
            state.onCameraBound(camera, capture)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ExecutionException) {
            state.reportCameraBindingFailure(error)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            state.reportCameraBindingFailure(error)
        } catch (error: IllegalArgumentException) {
            state.reportCameraBindingFailure(error)
        } catch (error: IllegalStateException) {
            state.reportCameraBindingFailure(error)
        } catch (error: SecurityException) {
            state.reportCameraBindingFailure(error)
        }
    }
}

@Composable
internal fun SmartScanCameraStateEffects(state: SmartScanSessionState) {
    LaunchedEffect(state.camera, state.torchOn) {
        state.camera?.cameraControl?.enableTorch(state.torchOn)
    }
    LaunchedEffect(state.showFlash) {
        if (!state.showFlash) return@LaunchedEffect
        delay(120)
        state.clearFlash()
    }
}

@Composable
internal fun SmartScanOrientationEffect(state: SmartScanSessionState) {
    DisposableEffect(state) {
        val listener =
            object : OrientationEventListener(state.previewView.context) {
                override fun onOrientationChanged(orientation: Int) {
                    SmartScanCameraPolicy.surfaceRotationForDegrees(orientation)?.let(state::updateDeviceRotation)
                }
            }
        listener.enable()
        onDispose { listener.disable() }
    }
}

internal suspend fun bindSmartScanCamera(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    targetRotation: Int,
): Pair<Camera, ImageCapture> {
    val provider = context.getCameraProvider()
    val preview =
        Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
    val capture =
        ImageCapture
            .Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setJpegQuality(97)
            .setTargetRotation(targetRotation)
            .build()
    val useCaseGroup =
        previewView.viewPort?.let { viewport ->
            UseCaseGroup
                .Builder()
                .addUseCase(preview)
                .addUseCase(capture)
                .setViewPort(viewport)
                .build()
        }

    provider.unbindAll()
    val camera =
        if (useCaseGroup == null) {
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                capture,
            )
        } else {
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                useCaseGroup,
            )
        }
    return camera to capture
}
