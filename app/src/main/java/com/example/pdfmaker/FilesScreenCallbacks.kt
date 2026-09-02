package com.example.pdfmaker

import android.widget.Toast
import java.io.File

internal data class FilesScreenCallbacks(
    val onFileClick: (PdfFile) -> Unit,
    val onNavigateToHome: () -> Unit,
    val onNavigateToSettings: () -> Unit,
    val onFabClick: () -> Unit,
    val onShareFile: (PdfFile) -> Unit,
    val onMoreFile: (PdfFile) -> Unit,
)

internal object FilesScreenOperations {
    fun share(
        activity: MainActivity,
        file: PdfFile,
    ) {
        val shared =
            DocumentShareAdapter.share(
                activity = activity,
                file = File(file.filePath),
                chooserTitle = activity.getString(R.string.share_pdf_via),
            )
        if (!shared) {
            Toast
                .makeText(activity, activity.getString(R.string.could_not_share), Toast.LENGTH_SHORT)
                .show()
        }
    }

    fun delete(
        activity: MainActivity,
        file: PdfFile,
    ): Boolean {
        val deleted = FileRepository.deleteFile(activity, file.filePath)
        if (deleted) {
            FileCache.removeFile(file.filePath)
            PdfThumbnailCache.invalidate(file.filePath)
        }
        Toast
            .makeText(
                activity,
                if (deleted) "Deleted" else "Could not delete file",
                Toast.LENGTH_SHORT,
            ).show()
        return deleted
    }

    fun rename(
        activity: MainActivity,
        file: PdfFile,
        requestedName: String,
    ): Boolean {
        val newName = requestedName.trim()
        if (newName.isEmpty()) return false
        return OutputStore
            .renameWithinParent(File(file.filePath), newName)
            .fold(
                onSuccess = { renamed ->
                    FileCache.renameFile(
                        file.filePath,
                        renamed.absolutePath,
                        renamed.nameWithoutExtension,
                    )
                    PdfThumbnailCache.invalidate(file.filePath)
                    Toast.makeText(activity, "Renamed", Toast.LENGTH_SHORT).show()
                    true
                },
                onFailure = {
                    Toast.makeText(activity, "Rename failed", Toast.LENGTH_SHORT).show()
                    false
                },
            )
    }
}
