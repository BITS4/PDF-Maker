package com.example.pdfmaker

import android.content.Context
import android.net.Uri
import androidx.camera.core.Camera
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

@Stable
internal class SmartScanSessionState(
    private val context: Context,
    private val scope: CoroutineScope,
    val workspace: SmartScanWorkspace,
    private val performCaptureHaptic: () -> Unit,
    private val onDocsDone: (List<Uri>) -> Unit,
    private val onIdCardDone: (frontUri: Uri, backUri: Uri?) -> Unit,
) {
    val previewView = PreviewView(context)

    var imageCapture by mutableStateOf<ImageCapture?>(null)
        private set
    var camera by mutableStateOf<Camera?>(null)
        private set
    var torchOn by mutableStateOf(false)
        private set
    var gridOn by mutableStateOf(false)
        private set
    var isCapturing by mutableStateOf(false)
        private set
    var showFlash by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var deviceRotation by mutableIntStateOf(android.view.Surface.ROTATION_0)
        private set
    var scanMode by mutableStateOf(ScanMode.DOCS)
        private set
    var showIdCardSetup by mutableStateOf(false)
        private set
    var idCardSide by mutableStateOf(IdCardCaptureSide.NONE)
        private set
    var idCardStep by mutableStateOf(IdCardStep.IDLE)
        private set
    var capturedDocs by mutableStateOf<List<CapturedDoc>>(emptyList())
        private set
    var previewWidth by mutableIntStateOf(0)
        private set
    var previewHeight by mutableIntStateOf(0)
        private set

    private val captureCoordinator = CaptureCoordinator()
    private val documentCoordinator = DocumentCoordinator()
    private var closed = false

    fun onCameraBound(
        boundCamera: Camera,
        boundCapture: ImageCapture,
    ) {
        camera = boundCamera
        imageCapture = boundCapture
    }

    fun reportCameraBindingFailure(error: Throwable) {
        Timber.w(error, "Smart Scan camera binding failed")
        errorMessage = "Camera could not start. Close other camera apps and try again."
    }

    fun updateDeviceRotation(rotation: Int) {
        deviceRotation = rotation
        imageCapture?.setTargetRotation(rotation)
    }

    fun updatePreviewSize(
        width: Int,
        height: Int,
    ) {
        if (width == previewWidth && height == previewHeight) return
        previewWidth = width
        previewHeight = height
    }

    fun toggleTorch() {
        if (!isCapturing) torchOn = !torchOn
    }

    fun toggleGrid() {
        if (!isCapturing) gridOn = !gridOn
    }

    fun clearFlash() {
        showFlash = false
    }

    fun dismissError() {
        errorMessage = null
    }

    fun selectDocumentMode() {
        scanMode = ScanMode.DOCS
        showIdCardSetup = false
        captureCoordinator.discardFrontCapture()
        idCardSide = IdCardCaptureSide.NONE
        idCardStep = IdCardStep.IDLE
    }

    fun openIdCardSetup() {
        scanMode = ScanMode.ID_CARD
        showIdCardSetup = true
    }

    fun selectIdCardSide(side: IdCardCaptureSide) {
        require(side != IdCardCaptureSide.NONE) { "An ID-card side must be selected" }
        idCardSide = side
        showIdCardSetup = false
        captureCoordinator.discardFrontCapture()
        documentCoordinator.discardDocuments()
        idCardStep = if (side == IdCardCaptureSide.BOTH) IdCardStep.CAPTURE_FRONT else IdCardStep.IDLE
    }

    fun dismissIdCardSetup() {
        showIdCardSetup = false
        scanMode = ScanMode.DOCS
        captureCoordinator.discardFrontCapture()
        idCardSide = IdCardCaptureSide.NONE
        idCardStep = IdCardStep.IDLE
    }

    fun readyForBackCapture() {
        if (idCardStep == IdCardStep.FLIP_CARD) idCardStep = IdCardStep.CAPTURE_BACK
    }

    fun handoffDocuments(selectedUris: List<Uri>) {
        documentCoordinator.handoffDocuments(selectedUris)
    }

    fun capture() {
        captureCoordinator.capture()
    }

    fun close() {
        if (closed) return
        closed = true
        captureCoordinator.cancelAndDiscardFront()
        camera?.cameraControl?.enableTorch(false)
        documentCoordinator.discardDocuments()
        workspace.close()
    }

    private inner class CaptureCoordinator {
        private var frontFile: File? = null
        private var captureJob: Job? = null

        fun capture() {
            val activeCapture = imageCapture ?: return
            if (isCapturing || idCardStep == IdCardStep.FLIP_CARD || closed) return
            if (scanMode == ScanMode.ID_CARD && idCardSide == IdCardCaptureSide.NONE) {
                errorMessage = "Choose which side of the ID card to scan first."
                return
            }
            if (!canCaptureAnotherDocument()) {
                errorMessage = "A scan can contain up to ${SmartScanPolicy.MAX_DOCUMENT_PAGES} pages."
                return
            }
            val rawFile = allocateCaptureArtifact() ?: return
            val snapshot = SmartScanCaptureSnapshot(scanMode, idCardSide, idCardStep)
            isCapturing = true
            errorMessage = null
            performCaptureHaptic()
            startCameraCapture(activeCapture, rawFile, snapshot)
        }

        fun cancelAndDiscardFront() {
            captureJob?.cancel()
            discardFrontCapture()
        }

        fun discardFrontCapture() {
            frontFile?.let(workspace::discard)
            frontFile = null
        }

        private fun canCaptureAnotherDocument(): Boolean =
            scanMode != ScanMode.DOCS ||
                SmartScanPolicy.canStartDocumentCapture(capturedDocs.size, pendingCaptures = 0)

        private fun allocateCaptureArtifact(): File? =
            try {
                workspace.newArtifact(SmartScanArtifact.CAPTURE)
            } catch (error: IllegalStateException) {
                reportWorkspaceAllocationFailure(error)
                null
            } catch (error: SecurityException) {
                reportWorkspaceAllocationFailure(error)
                null
            }

        private fun reportWorkspaceAllocationFailure(error: RuntimeException) {
            Timber.w(error, "Smart Scan workspace allocation failed")
            errorMessage = "Storage is unavailable. Free some space and try again."
        }

        private fun startCameraCapture(
            capture: ImageCapture,
            rawFile: File,
            snapshot: SmartScanCaptureSnapshot,
        ) {
            val callback =
                SmartScanCameraCallback(
                    savedCallback = { processSavedImage(rawFile, snapshot) },
                    errorCallback = { error -> reportCaptureFailure(rawFile, error) },
                )
            try {
                capture.takePicture(
                    ImageCapture.OutputFileOptions.Builder(rawFile).build(),
                    ContextCompat.getMainExecutor(context),
                    callback,
                )
            } catch (error: IllegalArgumentException) {
                reportCaptureStartFailure(rawFile, error)
            } catch (error: IllegalStateException) {
                reportCaptureStartFailure(rawFile, error)
            } catch (error: SecurityException) {
                reportCaptureStartFailure(rawFile, error)
            }
        }

        private fun processSavedImage(
            rawFile: File,
            snapshot: SmartScanCaptureSnapshot,
        ) {
            if (!scope.isActive || closed) {
                workspace.discard(rawFile)
                isCapturing = false
                return
            }
            showFlash = true
            captureJob =
                scope.launch {
                    try {
                        processCapturedImage(
                            rawFile = rawFile,
                            mode = snapshot.mode,
                            side = snapshot.side,
                            step = snapshot.step,
                            workspace = workspace,
                            currentFront = { frontFile },
                            onFrontCaptured = ::acceptFrontCapture,
                            onDocumentCaptured = ::acceptDocumentCapture,
                            onIdCardReady = ::completeIdCardCapture,
                            onFailure = ::reportProcessingFailure,
                        )
                    } finally {
                        isCapturing = false
                    }
                }
        }

        private fun acceptFrontCapture(file: File) {
            discardFrontCapture()
            frontFile = file
            idCardStep = IdCardStep.FLIP_CARD
        }

        private fun acceptDocumentCapture(document: CapturedDoc) {
            capturedDocs = capturedDocs + document
        }

        private fun completeIdCardCapture(
            front: File,
            back: File?,
        ) {
            frontFile = null
            torchOn = false
            onIdCardDone(Uri.fromFile(front), back?.let(Uri::fromFile))
        }

        private fun reportProcessingFailure(error: Exception) {
            Timber.w(error, "Smart Scan image processing failed")
            errorMessage = SmartScanPolicy.processingFailureMessage(error)
        }

        private fun reportCaptureFailure(
            rawFile: File,
            error: ImageCaptureException,
        ) {
            workspace.discard(rawFile)
            isCapturing = false
            Timber.w(error, "Smart Scan camera capture failed")
            errorMessage = "Photo capture failed. Hold still and try again."
        }

        private fun reportCaptureStartFailure(
            rawFile: File,
            error: RuntimeException,
        ) {
            workspace.discard(rawFile)
            isCapturing = false
            Timber.w(error, "Smart Scan capture could not start")
            errorMessage = "Photo capture could not start. Reopen the camera and try again."
        }
    }

    private inner class DocumentCoordinator {
        fun handoffDocuments(selectedUris: List<Uri>) {
            if (selectedUris.isEmpty() && capturedDocs.isEmpty()) return
            if (!scope.isActive || closed) return
            val capturedUris = capturedDocs.map { document -> Uri.fromFile(document.file) }
            val selection = SmartScanPolicy.selectForHandoff(capturedUris, selectedUris)
            if (selection.rejectedCount > 0) {
                errorMessage = additionalPageLimitMessage()
                return
            }
            if (!handoffCapturedFiles()) return

            val handedOffDocs = capturedDocs
            capturedDocs = emptyList()
            BitmapOwnership.retire(handedOffDocs.map(CapturedDoc::thumb))
            torchOn = false
            onDocsDone(selection.items)
        }

        fun discardDocuments() {
            val discarded = capturedDocs
            capturedDocs = emptyList()
            discarded.forEach { document -> workspace.discard(document.file) }
            BitmapOwnership.retire(discarded.map(CapturedDoc::thumb))
        }

        private fun handoffCapturedFiles(): Boolean {
            if (capturedDocs.isEmpty()) return true
            return try {
                workspace.handoff(capturedDocs.map(CapturedDoc::file))
                true
            } catch (error: IllegalArgumentException) {
                reportHandoffFailure(error)
                false
            } catch (error: IllegalStateException) {
                reportHandoffFailure(error)
                false
            } catch (error: SecurityException) {
                reportHandoffFailure(error)
                false
            }
        }

        private fun reportHandoffFailure(error: RuntimeException) {
            Timber.w(error, "Smart Scan handoff failed")
            errorMessage = "A captured page is no longer available. Retake the missing page."
        }

        private fun additionalPageLimitMessage(): String {
            val remaining = SmartScanPolicy.remainingDocumentSlots(capturedDocs.size)
            val noun = if (remaining == 1) "page" else "pages"
            return "Choose no more than $remaining additional $noun; nothing was added."
        }
    }
}

private data class SmartScanCaptureSnapshot(
    val mode: ScanMode,
    val side: IdCardCaptureSide,
    val step: IdCardStep,
)

private class SmartScanCameraCallback(
    private val savedCallback: () -> Unit,
    private val errorCallback: (ImageCaptureException) -> Unit,
) : ImageCapture.OnImageSavedCallback {
    override fun onImageSaved(output: ImageCapture.OutputFileResults) = savedCallback()

    override fun onError(exception: ImageCaptureException) = errorCallback(exception)
}

@Composable
internal fun SmartScanWorkspaceEffects(
    state: SmartScanSessionState,
    cacheRoot: File,
) {
    LaunchedEffect(state.workspace) {
        withContext(Dispatchers.IO) {
            runCatching {
                SmartScanWorkspace.deleteStaleSessions(
                    cacheRoot = cacheRoot,
                    currentSession = state.workspace.directory,
                )
            }.onFailure { error -> Timber.w(error, "Smart Scan cache cleanup failed") }
        }
    }
    DisposableEffect(state) {
        onDispose(state::close)
    }
}
