package com.tajapps.pdfmaker

import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object FileRepository {

    private val SUPPORTED_MIME = setOf(
        "application/pdf",
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "text/plain"
    )
    private val SUPPORTED_EXT = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt")

    fun loadPdfFiles(context: Context): List<PdfFile> {
        val found = mutableMapOf<String, PdfFile>()

        // ── 1. Always scan our public PDFMaker folder first (no permissions needed) ──
        val pdfMakerDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "PDFMaker"
        )
        scanDir(pdfMakerDir, found, maxDepth = 2)

        // ── 2. MediaStore scan for files elsewhere on the device ──
        val mimeSelections = SUPPORTED_MIME.joinToString(" OR ") {
            "${MediaStore.Files.FileColumns.MIME_TYPE} = ?"
        }
        val extSelections = SUPPORTED_EXT.joinToString(" OR ") {
            "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
        }
        val selection = "($mimeSelections) OR ($extSelections)"
        val args = (SUPPORTED_MIME.toList() + SUPPORTED_EXT.map { "%.${it}" }).toTypedArray()

        val projection = arrayOf(
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.MIME_TYPE
        )
        val sortOrder = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"

        val volumes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.getExternalVolumeNames(context).map {
                MediaStore.Files.getContentUri(it)
            }
        } else {
            listOf(MediaStore.Files.getContentUri("external"))
        }

        volumes.forEach { uri ->
            try {
                context.contentResolver.query(uri, projection, selection, args, sortOrder)
                    ?.use { cursor ->
                        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                        val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                        val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                        val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)

                        while (cursor.moveToNext()) {
                            val name = cursor.getString(nameCol) ?: continue
                            val path = cursor.getString(pathCol) ?: continue
                            if (path in found) continue
                            if (!File(path).exists()) continue
                            val ext = name.substringAfterLast('.', "").lowercase()
                            if (ext !in SUPPORTED_EXT) continue
                            val sizeBytes   = cursor.getLong(sizeCol)
                            val dateSeconds = cursor.getLong(dateCol)
                            found[path] = PdfFile(
                                name         = name.substringBeforeLast('.'),
                                filePath     = path,
                                size         = formatSize(sizeBytes),
                                date         = formatDate(dateSeconds * 1000L),
                                pageCount    = if (ext == "pdf") estimatePageCount(sizeBytes) else 1,
                                lastModified = dateSeconds * 1000L
                            )
                        }
                    }
            } catch (_: Exception) {}
        }

        // ── 3. Fallback: scan common public directories ──
        val fallbackDirs = buildList {
            add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
            add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS))
            context.getExternalFilesDirs(null).filterNotNull().forEach { add(it) }
        }
        fallbackDirs.forEach { dir -> scanDir(dir, found, maxDepth = 3) }

        return found.values.sortedByDescending { it.lastModified }
    }

    private fun scanDir(dir: File, found: MutableMap<String, PdfFile>, maxDepth: Int) {
        if (maxDepth <= 0 || !dir.exists() || !dir.canRead()) return
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                scanDir(file, found, maxDepth - 1)
            } else {
                val ext = file.extension.lowercase()
                if (ext in SUPPORTED_EXT && file.path !in found) {
                    found[file.path] = PdfFile(
                        name         = file.nameWithoutExtension,
                        filePath     = file.absolutePath,
                        size         = formatSize(file.length()),
                        date         = formatDate(file.lastModified()),
                        pageCount    = if (ext == "pdf") estimatePageCount(file.length()) else 1,
                        lastModified = file.lastModified()
                    )
                }
            }
        }
    }

    fun getRecentFiles(context: Context): List<PdfFile> {
        val cutoff = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
        return loadPdfFiles(context).filter { it.lastModified >= cutoff }
    }

    fun deleteFile(filePath: String): Boolean =
        try { File(filePath).delete() } catch (_: Exception) { false }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024     -> "${bytes / 1_024} kB"
        else               -> "$bytes B"
    }

    fun formatDate(millis: Long): String =
        SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(millis))

    private fun estimatePageCount(bytes: Long): Int =
        (bytes / 102_400).toInt().coerceAtLeast(1)
}
