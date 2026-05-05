package com.example.pdfmaker

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object FileCache {

    var files     by mutableStateOf<List<PdfFile>>(emptyList())
    var isLoading by mutableStateOf(false)
    var version   by mutableIntStateOf(0)   // incremented on invalidate — screens use as LaunchedEffect key
    private var loaded = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Load files once. Subsequent calls are no-ops unless [forceRefresh] = true.
     * Uses a background dispatcher so the UI is never blocked.
     */
    fun load(context: Context, forceRefresh: Boolean = false) {
        if (loaded && !forceRefresh) return
        isLoading = true
        scope.launch(Dispatchers.IO) {
            val result = FileRepository.loadPdfFiles(context)
            files     = result
            isLoading = false
            loaded    = true
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
    fun renameFile(oldPath: String, newPath: String, newName: String) {
        files = files.map { if (it.filePath == oldPath) it.copy(filePath = newPath, name = newName) else it }
    }

    /** Force next load() call to actually re-scan and notify observers. */
    fun invalidate() { loaded = false; version++ }
}
