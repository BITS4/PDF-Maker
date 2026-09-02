package com.example.pdfmaker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/** Owns capture processing and transfers artifacts only after every stage succeeds. */
@Suppress(
    "LongParameterList",
    "TooGenericExceptionCaught", // This boundary converts codec, I/O, and storage failures into UI state.
)
internal suspend fun processCapturedImage(
    rawFile: File,
    mode: ScanMode,
    side: IdCardCaptureSide,
    step: IdCardStep,
    workspace: SmartScanWorkspace,
    currentFront: () -> File?,
    onFrontCaptured: (File) -> Unit,
    onDocumentCaptured: (CapturedDoc) -> Unit,
    onIdCardReady: (front: File, back: File?) -> Unit,
    onFailure: (Exception) -> Unit,
) {
    var normalizedFile: File? = null
    var thumbnail: android.graphics.Bitmap? = null
    try {
        val destination = workspace.newArtifact(SmartScanArtifact.NORMALIZED)
        normalizedFile = destination
        withContext(Dispatchers.IO) {
            normalizeCapturedImage(rawFile, destination)
        }
        workspace.discard(rawFile)
        currentCoroutineContext().ensureActive()

        when {
            mode == ScanMode.DOCS -> {
                thumbnail =
                    withContext(Dispatchers.IO) {
                        createCaptureThumbnail(destination)
                    }
                currentCoroutineContext().ensureActive()
                onDocumentCaptured(CapturedDoc(destination, requireNotNull(thumbnail)))
                thumbnail = null
                normalizedFile = null
            }

            side == IdCardCaptureSide.SINGLE -> {
                val handedOff = workspace.handoff(listOf(destination)).single()
                normalizedFile = null
                onIdCardReady(handedOff, null)
            }

            side == IdCardCaptureSide.BOTH && step == IdCardStep.CAPTURE_FRONT -> {
                onFrontCaptured(destination)
                normalizedFile = null
            }

            side == IdCardCaptureSide.BOTH && step == IdCardStep.CAPTURE_BACK -> {
                val front = requireNotNull(currentFront()) { "The front side must be scanned again" }
                val handedOff = workspace.handoff(listOf(front, destination))
                normalizedFile = null
                onIdCardReady(handedOff[0], handedOff[1])
            }

            else -> {
                error("Select an ID-card scan mode before taking a photo")
            }
        }
    } catch (cancelled: CancellationException) {
        thumbnail?.let { BitmapOwnership.retire(listOf(it)) }
        workspace.discard(normalizedFile)
        workspace.discard(rawFile)
        throw cancelled
    } catch (error: Exception) {
        thumbnail?.let { BitmapOwnership.retire(listOf(it)) }
        workspace.discard(normalizedFile)
        workspace.discard(rawFile)
        onFailure(error)
    }
}
