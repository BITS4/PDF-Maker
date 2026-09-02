package com.example.pdfmaker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class PdfEditorOperationController(private val scope: CoroutineScope) {
    var target by mutableStateOf(ConvertTarget.NONE)
        private set

    var progress by mutableIntStateOf(0)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    private var activeJob: Job? = null

    val isRunning: Boolean
        get() = activeJob?.isActive == true

    @Suppress("TooGenericExceptionCaught")
    fun launch(
        target: ConvertTarget,
        producer: suspend (reportProgress: (Int) -> Unit) -> File,
        consumer: (File) -> Unit,
    ) {
        if (isRunning) return
        this.target = target
        progress = 0
        errorMessage = null

        lateinit var launchedJob: Job
        launchedJob = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                val file = producer { nextProgress ->
                    scope.launch(Dispatchers.Main) {
                        if (activeJob === launchedJob) progress = nextProgress.coerceIn(0, 100)
                    }
                }
                withContext(Dispatchers.Main) { consumer(file) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                withContext(Dispatchers.Main) {
                    errorMessage =
                        UserVisibleFailureReporter.message(
                            UserFailureStage.PDF_EDITOR_OPERATION,
                            error,
                        )
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    if (activeJob === launchedJob) {
                        activeJob = null
                        this@PdfEditorOperationController.target = ConvertTarget.NONE
                    }
                }
            }
        }
        activeJob = launchedJob
        launchedJob.start()
    }

    fun cancel() {
        activeJob?.cancel()
    }

    suspend fun cancelAndJoin() {
        activeJob?.cancelAndJoin()
    }

    fun cancelAndRelease(release: () -> Unit) {
        val job = activeJob
        if (job == null) {
            release()
        } else {
            job.cancel()
            job.invokeOnCompletion { release() }
        }
    }

    fun dismissError() {
        errorMessage = null
    }
}

@Composable
internal fun rememberPdfEditorOperationController(): PdfEditorOperationController {
    val scope = rememberCoroutineScope()
    return remember(scope) { PdfEditorOperationController(scope) }
}
