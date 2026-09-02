package com.example.pdfmaker

/** Keeps the lock flow scoped to unlocked PDF entries from the shared file catalogue. */
internal object LockPdfSelectionPolicy {
    fun unlockedPdfCandidates(
        files: List<PdfFile>,
        isLocked: (String) -> Boolean,
    ): List<PdfFile> = files.filter { file ->
        hasPdfExtension(file.filePath) && !isLocked(file.filePath)
    }

    fun hasPdfExtension(path: String): Boolean {
        val leaf = path.substringAfterLast('/').substringAfterLast('\\')
        val separator = leaf.lastIndexOf('.')
        return separator > 0 &&
            separator < leaf.lastIndex &&
            leaf.substring(separator + 1).equals("pdf", ignoreCase = true)
    }
}
