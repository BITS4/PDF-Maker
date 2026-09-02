package com.example.pdfmaker

import java.io.File
import java.util.UUID

internal enum class SmartScanArtifact(
    val prefix: String,
    val extension: String,
) {
    CAPTURE("capture", "jpg"),
    NORMALIZED("normalized", "jpg"),
}

/** Owns one scan session's cache artifacts until they are explicitly handed to the editor. */
internal class SmartScanWorkspace private constructor(
    val directory: File,
) : AutoCloseable {
    private val ownedFiles = linkedSetOf<File>()
    private val handedOffFiles = linkedSetOf<File>()
    private var closed = false

    @Synchronized
    fun newArtifact(kind: SmartScanArtifact): File {
        check(!closed) { "The scan session is already closed" }
        check((directory.isDirectory || directory.mkdirs()) && directory.isDirectory) {
            "Could not create the scan cache directory"
        }
        val file = File(directory, "${kind.prefix}-${UUID.randomUUID()}.${kind.extension}")
        check(isDirectChild(file)) { "Invalid scan artifact path" }
        ownedFiles += file
        return file
    }

    @Synchronized
    fun handoff(files: Iterable<File>): List<File> {
        check(!closed) { "The scan session is already closed" }
        val requested = files.distinctBy(::canonicalPath)
        require(requested.isNotEmpty()) { "There are no scan artifacts to hand off" }
        requested.forEach { file ->
            require(isDirectChild(file) && file in ownedFiles && file.isFile && file.length() > 0L) {
                "A scan artifact is unavailable"
            }
        }
        ownedFiles.removeAll(requested.toSet())
        handedOffFiles.addAll(requested)
        return requested
    }

    @Synchronized
    fun discard(file: File?) {
        if (file == null || !isDirectChild(file)) return
        val wasOwned = ownedFiles.remove(file)
        val lifecycleAllowsDelete = wasOwned || closed
        val isProtectedHandoff = file in handedOffFiles
        if (lifecycleAllowsDelete && !isProtectedHandoff && file.isFile) file.delete()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        ownedFiles.toList().forEach(::discard)
        ownedFiles.clear()
        directory.delete()
    }

    private fun isDirectChild(file: File): Boolean =
        runCatching {
            file.canonicalFile.parentFile == directory.canonicalFile
        }.getOrDefault(false)

    companion object {
        private const val CACHE_DIRECTORY = "smart-scan"
        private const val SESSION_PREFIX = "session-"
        internal const val STALE_SESSION_AGE_MILLIS = 24L * 60L * 60L * 1_000L
        private val sessionName = Regex("^session-[0-9a-f-]{36}$")

        fun create(cacheRoot: File): SmartScanWorkspace {
            val root = File(cacheRoot, CACHE_DIRECTORY)
            check((root.isDirectory || root.mkdirs()) && root.isDirectory) {
                "Could not create the Smart Scan cache"
            }
            val directory = File(root, "$SESSION_PREFIX${UUID.randomUUID()}")
            check(directory.mkdir() && directory.isDirectory) {
                "Could not create a Smart Scan session"
            }
            return SmartScanWorkspace(directory)
        }

        fun deleteStaleSessions(
            cacheRoot: File,
            currentSession: File,
            nowMillis: Long = System.currentTimeMillis(),
            maximumAgeMillis: Long = STALE_SESSION_AGE_MILLIS,
        ): Int {
            require(nowMillis >= 0L) { "Current time is invalid" }
            require(maximumAgeMillis > 0L) { "Maximum cache age must be positive" }
            val root = File(cacheRoot, CACHE_DIRECTORY)
            val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return 0
            val canonicalCurrent = runCatching { currentSession.canonicalFile }.getOrNull()
            val cutoff = (nowMillis - maximumAgeMillis).coerceAtLeast(0L)
            var deleted = 0
            root.listFiles().orEmpty().forEach { candidate ->
                val canonicalCandidate = runCatching { candidate.canonicalFile }.getOrNull()
                val isSibling =
                    canonicalCandidate != null &&
                        canonicalCandidate.parentFile == canonicalRoot &&
                        canonicalCandidate != canonicalCurrent
                val isSessionDirectory = candidate.isDirectory && sessionName.matches(candidate.name)
                val eligible = isSibling && isSessionDirectory && newestTimestamp(candidate) <= cutoff
                if (eligible && deleteFlatSession(candidate)) deleted += 1
            }
            return deleted
        }

        private fun newestTimestamp(directory: File): Long =
            directory.listFiles().orEmpty().fold(directory.lastModified()) { newest, file ->
                maxOf(newest, file.lastModified())
            }

        private fun deleteFlatSession(directory: File): Boolean {
            val children = directory.listFiles() ?: return false
            if (children.any { !it.isFile }) return false
            children.forEach { child -> if (!child.delete()) return false }
            return directory.delete()
        }

        private fun canonicalPath(file: File): String = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
    }
}
