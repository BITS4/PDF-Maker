package com.example.pdfmaker

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.Normalizer
import java.util.UUID

/** Central validation for every user- or provider-controlled output name. */
object SafeFileName {
    private const val MAX_BASE_LENGTH = 80

    fun baseName(raw: String?, fallback: String = "document"): String {
        val normalized = Normalizer.normalize(raw.orEmpty(), Normalizer.Form.NFKC)
        val cleaned = buildString(normalized.length.coerceAtMost(MAX_BASE_LENGTH)) {
            normalized.forEach { character ->
                when {
                    character.isLetterOrDigit() -> append(character)
                    character == ' ' || character == '-' || character == '_' || character == '.' -> append(character)
                    character == '/' || character == '\\' -> append('_')
                    !Character.isISOControl(character) &&
                        Character.getDirectionality(character) !in unsafeDirectionalClasses -> append('_')
                }
            }
        }
            .trim()
            .trim('.', ' ', '_')
            .take(MAX_BASE_LENGTH)
            .trimEnd('.', ' ')

        return cleaned.takeUnless { it.isBlank() || it == "." || it == ".." }
            ?: fallbackValidated(fallback)
    }

    fun extension(raw: String): String {
        val value = raw.trim().removePrefix(".").lowercase()
        require(value.length in 1..10 && value.all(Char::isLetterOrDigit)) {
            "Unsupported file extension"
        }
        return value
    }

    private fun fallbackValidated(fallback: String): String {
        val cleaned = fallback.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(MAX_BASE_LENGTH)
        return cleaned.ifBlank { "document" }
    }

    private val unsafeDirectionalClasses = setOf(
        Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE,
        Character.DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE,
        Character.DIRECTIONALITY_POP_DIRECTIONAL_FORMAT,
        Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING,
        Character.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING,
        Character.DIRECTIONALITY_RIGHT_TO_LEFT_ISOLATE,
        Character.DIRECTIONALITY_LEFT_TO_RIGHT_ISOLATE,
        Character.DIRECTIONALITY_FIRST_STRONG_ISOLATE,
        Character.DIRECTIONALITY_POP_DIRECTIONAL_ISOLATE,
    )
}

/** Creates and replaces files without escaping the supplied application-owned directory. */
object OutputStore {
    private const val MAX_COLLISION_ATTEMPTS = 10_000

    @Synchronized
    fun writeUnique(
        directory: File,
        requestedBaseName: String?,
        extension: String,
        writer: (OutputStream) -> Unit,
    ): File {
        val dir = requireDirectory(directory)
        val target = nextAvailableFile(dir, requestedBaseName, extension)
        val temporary = containedChild(dir, ".pdfmaker-${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                writer(output)
                output.flush()
                output.fd.sync()
            }
            moveWithoutReplacing(temporary, target)
            return target
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    @Synchronized
    fun commitTemporaryUnique(
        temporary: File,
        directory: File,
        requestedBaseName: String?,
        extension: String,
    ): File {
        require(temporary.isFile) { "Temporary output does not exist" }
        val dir = requireDirectory(directory)
        val target = nextAvailableFile(dir, requestedBaseName, extension)
        moveWithoutReplacing(temporary, target)
        return target
    }

    @Synchronized
    fun replaceAtomically(target: File, bytes: ByteArray) {
        val parent = target.canonicalFile.parentFile ?: error("File has no parent directory")
        requireDirectory(parent)
        requireContained(parent, target)
        val temporary = containedChild(parent, ".pdfmaker-${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            moveReplacing(temporary, target.canonicalFile)
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    @Synchronized
    fun renameWithinParent(original: File, requestedBaseName: String?): Result<File> = runCatching {
        val source = original.canonicalFile
        require(source.isFile) { "Source file does not exist" }
        val parent = source.parentFile ?: error("Source file has no parent")
        val safeBase = SafeFileName.baseName(requestedBaseName, source.nameWithoutExtension)
        val safeExtension = source.extension.takeIf { it.isNotBlank() }?.let(SafeFileName::extension)
        val newName = if (safeExtension == null) safeBase else "$safeBase.$safeExtension"
        val target = containedChild(parent, newName)
        if (target == source) return@runCatching source
        require(!target.exists()) { "A file with that name already exists" }
        moveWithoutReplacing(source, target)
        target
    }

    fun nextAvailableFile(directory: File, requestedBaseName: String?, extension: String): File {
        val dir = requireDirectory(directory)
        val base = SafeFileName.baseName(requestedBaseName)
        val ext = SafeFileName.extension(extension)
        repeat(MAX_COLLISION_ATTEMPTS) { index ->
            val suffix = if (index == 0) "" else " ($index)"
            val candidate = containedChild(dir, "$base$suffix.$ext")
            if (!candidate.exists()) return candidate
        }
        error("Could not allocate a unique output name")
    }

    private fun requireDirectory(directory: File): File {
        val canonical = directory.canonicalFile
        check((canonical.exists() && canonical.isDirectory) || canonical.mkdirs()) {
            "Could not create output directory"
        }
        return canonical
    }

    private fun containedChild(directory: File, childName: String): File =
        File(directory, childName).canonicalFile.also { requireContained(directory, it) }

    private fun requireContained(directory: File, candidate: File) {
        val root = directory.canonicalFile.toPath()
        require(candidate.canonicalFile.toPath().parent == root) { "Output path escaped its directory" }
    }

    private fun moveWithoutReplacing(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath())
        }
    }

    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
