package com.example.pdfmaker

import java.io.File

internal sealed interface PageManagerSaveResult {
    data class Saved(
        val file: File,
        val pageCount: Int,
    ) : PageManagerSaveResult

    data class Failed(
        val stage: PageManagerFailureStage,
        val error: Exception,
    ) : PageManagerSaveResult
}
