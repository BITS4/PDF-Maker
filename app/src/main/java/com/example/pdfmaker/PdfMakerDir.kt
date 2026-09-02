package com.example.pdfmaker

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * All files created by PDF Maker are saved here:
 *   /sdcard/Documents/PDFMaker/
 *
 * This is a PUBLIC directory so:
 * - MediaStore indexes it automatically
 * - Files survive app uninstall / package name changes
 * - Users can find their files in any file manager
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
