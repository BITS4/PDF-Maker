package com.example.pdfmaker

import java.io.File

/** Resolves paths before authorizing destructive operations on application-owned documents. */
internal object OwnedFilePolicy {
    fun contains(
        roots: Collection<File>,
        candidate: File,
    ): Boolean {
        if (roots.isEmpty()) return false
        val resolvedCandidate = runCatching { candidate.canonicalFile }.getOrNull() ?: return false
        return roots.any { root ->
            val resolvedRoot = runCatching { root.canonicalFile }.getOrNull() ?: return@any false
            resolvedCandidate != resolvedRoot && resolvedCandidate.toPath().startsWith(resolvedRoot.toPath())
        }
    }
}
