package com.tajapps.pdfmaker

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
    val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
    val dir = File(publicDir, "PDFMaker")
    if (!dir.exists()) dir.mkdirs()
    return dir
}
