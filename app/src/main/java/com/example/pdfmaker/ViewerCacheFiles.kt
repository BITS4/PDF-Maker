package com.example.pdfmaker

import android.content.Context
import java.io.File

internal fun pdfMakerCacheDirectory(context: Context): File =
    File(context.cacheDir, "pdfmaker").also { directory ->
        check(directory.exists() || directory.mkdirs()) { "Unable to prepare temporary file directory" }
    }

internal fun pdfMakerCacheFile(
    context: Context,
    requestedName: String,
): File = File(pdfMakerCacheDirectory(context), requireSafeCacheFileName(requestedName))

internal fun requireSafeCacheFileName(requestedName: String): String {
    require(requestedName.length in 1..128) { "Temporary file name has an invalid length" }
    require(requestedName != "." && requestedName != "..") { "Temporary file name is invalid" }
    require(requestedName.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }) {
        "Temporary file name contains unsupported characters"
    }
    return requestedName
}
