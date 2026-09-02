package com.example.pdfmaker

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.util.UUID

internal data class ViewerPageArtifact(
    val file: File,
    val pageNumber: Int,
    val width: Int,
    val height: Int,
) {
    init {
        require(pageNumber in 1..ViewerResourceLimits.MAX_RENDERED_PAGES) {
            "Viewer page number exceeds its limit"
        }
        require(ViewerPageArtifactPolicy.acceptsDimensions(width, height)) {
            "Viewer page dimensions exceed their limit"
        }
    }

    val aspectRatio: Float = width.toFloat() / height.toFloat()
}

internal object ViewerPageArtifactPolicy {
    const val MAX_ARTIFACT_BYTES = 20L * 1024L * 1024L
    private val directoryNamePattern =
        Regex(
            "^viewer-pages-[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
        )

    fun pageBaseName(pageIndex: Int): String {
        require(pageIndex in 0 until ViewerResourceLimits.MAX_RENDERED_PAGES) {
            "Viewer page index exceeds its limit"
        }
        return "page-${(pageIndex + 1).toString().padStart(3, '0')}"
    }

    fun acceptsDimensions(
        width: Int,
        height: Int,
    ): Boolean =
        width in 1..2_048 &&
            height in 1..2_048 &&
            width.toLong() * height.toLong() <= 4_194_304L

    fun acceptsArtifact(
        width: Int,
        height: Int,
        byteCount: Long,
    ): Boolean = acceptsDimensions(width, height) && byteCount in 1..MAX_ARTIFACT_BYTES

    fun ownsDirectory(
        cacheRoot: File,
        directory: File,
    ): Boolean =
        runCatching {
            val root = cacheRoot.canonicalFile
            val candidate = directory.canonicalFile
            candidate.parentFile == root && directoryNamePattern.matches(candidate.name)
        }.getOrDefault(false)
}

/** Stores rendered pages on disk so the UI only holds bitmaps for visible lazy-list items. */
internal class ViewerPageArtifactStore private constructor(
    private val cacheRoot: File,
    private val directory: File,
) : Closeable {
    private var closed = false

    @Synchronized
    fun persist(
        bitmap: Bitmap,
        pageIndex: Int,
    ): ViewerPageArtifact {
        check(!closed) { "Viewer page store is closed" }
        require(ViewerPageArtifactPolicy.acceptsDimensions(bitmap.width, bitmap.height)) {
            "Rendered page dimensions exceed the cache limit"
        }
        val output =
            OutputStore.writeUnique(
                directory,
                ViewerPageArtifactPolicy.pageBaseName(pageIndex),
                "png",
            ) { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    "Rendered page could not be cached"
                }
            }
        if (!ViewerPageArtifactPolicy.acceptsArtifact(bitmap.width, bitmap.height, output.length())) {
            output.delete()
            error("Rendered page artifact exceeds the cache limit")
        }
        return ViewerPageArtifact(
            file = output,
            pageNumber = pageIndex + 1,
            width = bitmap.width,
            height = bitmap.height,
        )
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        deleteViewerArtifactDirectory(cacheRoot, directory)
    }

    companion object {
        fun create(context: Context): ViewerPageArtifactStore {
            val root = pdfMakerCacheDirectory(context).canonicalFile
            val directory = File(root, "viewer-pages-${UUID.randomUUID()}").canonicalFile
            check(ViewerPageArtifactPolicy.ownsDirectory(root, directory)) {
                "Viewer page cache escaped its root"
            }
            check(directory.mkdir()) { "Unable to create the viewer page cache" }
            return ViewerPageArtifactStore(root, directory)
        }
    }
}

internal suspend fun cacheViewerPageArtifacts(
    file: PdfFile,
    kind: ViewerFileKind,
    targetWidth: Int,
    store: ViewerPageArtifactStore,
    onArtifact: (ViewerPageArtifact) -> Unit,
) {
    var pageIndex = 0
    pageStreamForFile(file, kind, targetWidth).collect { bitmap ->
        try {
            val artifact = withContext(Dispatchers.IO) { store.persist(bitmap, pageIndex) }
            onArtifact(artifact)
            pageIndex += 1
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }
}

internal fun deleteViewerArtifactDirectory(
    cacheRoot: File,
    directory: File,
): Boolean {
    if (!ViewerPageArtifactPolicy.ownsDirectory(cacheRoot, directory)) return false
    val canonicalDirectory = runCatching { directory.canonicalFile }.getOrNull() ?: return false
    canonicalDirectory.listFiles().orEmpty().forEach { child ->
        val ownedFile =
            runCatching {
                child.isFile && child.canonicalFile.parentFile == canonicalDirectory
            }.getOrDefault(false)
        if (ownedFile) child.delete()
    }
    return canonicalDirectory.delete() || !canonicalDirectory.exists()
}
