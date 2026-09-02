package com.example.pdfmaker

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Persistent files created by PDF Maker are saved in its app-specific Documents directory.
 *
 * Android removes this directory when the app is uninstalled. Users export individual files through
 * the system share sheet, which grants read-only access without exposing unrelated app data.
 */
fun getPdfMakerDir(context: Context): File {
    val root = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        ?: File(context.filesDir, "documents")
    return File(root, "PDFMaker").also { directory ->
        check((directory.exists() && directory.isDirectory) || directory.mkdirs()) {
            "Could not create the application document directory"
        }
    }
}
