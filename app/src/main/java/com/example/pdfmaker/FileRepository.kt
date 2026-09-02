package com.example.pdfmaker

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileRepository {
    private val supportedExtensions = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt")

    /** Lists only application-owned files. Access to arbitrary device files goes through Android's picker. */
    fun loadPdfFiles(context: Context): List<PdfFile> {
        val found = mutableMapOf<String, PdfFile>()
        val roots = ownedRoots(context)
        roots.forEach { root -> scanOwnedDirectory(root, root, found, maxDepth = 2) }
        return found.values.sortedByDescending { it.lastModified }
    }

    private fun ownedRoots(context: Context): Set<File> =
        buildSet {
            add(getPdfMakerDir(context).canonicalFile)
            add(File(context.filesDir, "documents/PDFMaker").canonicalFile)
            context
                .getExternalFilesDirs(Environment.DIRECTORY_DOCUMENTS)
                .filterNotNull()
                .forEach { add(File(it, "PDFMaker").canonicalFile) }
        }

    private fun scanOwnedDirectory(
        root: File,
        directory: File,
        found: MutableMap<String, PdfFile>,
        maxDepth: Int,
    ) {
        if (maxDepth <= 0 || !directory.isDirectory || !directory.canRead()) return
        val rootPath = root.canonicalFile.toPath()
        directory.listFiles()?.forEach { candidate ->
            val file = runCatching { candidate.canonicalFile }.getOrNull() ?: return@forEach
            if (!file.toPath().startsWith(rootPath)) return@forEach
            if (file.isDirectory) {
                scanOwnedDirectory(root, file, found, maxDepth - 1)
            } else {
                val extension = file.extension.lowercase(Locale.ROOT)
                if (extension in supportedExtensions && file.path !in found) {
                    found[file.path] =
                        PdfFile(
                            name = file.nameWithoutExtension,
                            filePath = file.absolutePath,
                            size = formatSize(file.length()),
                            date = formatDate(file.lastModified()),
                            pageCount = if (extension == "pdf") estimatePageCount(file.length()) else 1,
                            lastModified = file.lastModified(),
                        )
                }
            }
        }
    }

    fun getRecentFiles(context: Context): List<PdfFile> {
        val cutoff = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
        return loadPdfFiles(context).filter { it.lastModified >= cutoff }
    }

    fun deleteFile(
        context: Context,
        filePath: String,
    ): Boolean =
        try {
            val candidate = File(filePath).canonicalFile
            OwnedFilePolicy.contains(ownedRoots(context), candidate) && candidate.isFile && candidate.delete()
        } catch (_: java.io.IOException) {
            false
        } catch (_: SecurityException) {
            false
        }

    fun formatSize(bytes: Long): String =
        when {
            bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
            bytes >= 1_024 -> "${bytes / 1_024} kB"
            else -> "$bytes B"
        }

    fun formatDate(millis: Long): String = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(millis))

    private fun estimatePageCount(bytes: Long): Int = (bytes / 102_400).toInt().coerceAtLeast(1)
}
