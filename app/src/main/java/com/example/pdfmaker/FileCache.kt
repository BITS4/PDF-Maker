package com.example.pdfmaker

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

object FileCache {
    var files by mutableStateOf<List<PdfFile>>(emptyList())
    var isLoading by mutableStateOf(false)
    var version by mutableIntStateOf(0) // incremented on invalidate — screens use as LaunchedEffect key
    private var loaded = false
    private var loadJob: Job? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Load files once. Subsequent calls are no-ops unless [forceRefresh] = true.
     * Uses a background dispatcher so the UI is never blocked.
     */
    fun load(
        context: Context,
        forceRefresh: Boolean = false,
    ) {
        val applicationContext = context.applicationContext
        scope.launch {
            if (loaded && !forceRefresh) return@launch
            if (loadJob?.isActive == true && !forceRefresh) return@launch

            loadJob?.cancel()
            val currentLoad = currentCoroutineContext()[Job]
            loadJob = currentLoad
            isLoading = true
            try {
                val result =
                    withContext(Dispatchers.IO) {
                        FileRepository.loadPdfFiles(applicationContext)
                    }
                if (loadJob === currentLoad) {
                    files = result
                    loaded = true
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: java.io.IOException) {
                reportLoadFailure(error)
            } catch (error: SecurityException) {
                reportLoadFailure(error)
            } catch (error: IllegalArgumentException) {
                reportLoadFailure(error)
            } catch (error: IllegalStateException) {
                reportLoadFailure(error)
            } finally {
                if (loadJob === currentLoad) {
                    isLoading = false
                    loadJob = null
                }
            }
        }
    }

    /** Call after a delete so the list updates without a full reload. */
    fun removeFile(filePath: String) {
        files = files.filter { it.filePath != filePath }
    }

    /** Call after creating a new PDF so it appears immediately. */
    fun prependFile(file: PdfFile) {
        files = listOf(file) + files
    }

    /** Update file path and name in cache after a rename. */
    fun renameFile(
        oldPath: String,
        newPath: String,
        newName: String,
    ) {
        files = files.map { if (it.filePath == oldPath) it.copy(filePath = newPath, name = newName) else it }
    }

    /** Force next load() call to actually re-scan and notify observers. */
    fun invalidate() {
        loadJob?.cancel()
        loadJob = null
        loaded = false
        isLoading = false
        version++
    }

    private fun reportLoadFailure(error: Exception) {
        Timber.tag("FileCache").w(
            ObservabilityPolicy.sanitizedThrowable(error),
            "event=document_catalog_load_failure",
        )
    }
}
