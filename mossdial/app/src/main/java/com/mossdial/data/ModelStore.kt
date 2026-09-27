package com.mossdial.data

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * The kind of model file a runtime can load.
 */
enum class ModelKind(val extension: String, val label: String) {
    GGUF(".gguf", "GGUF"),
    ONNX(".onnx", "ONNX");

    companion object {
        fun of(fileName: String): ModelKind? {
            val lower = fileName.lowercase()
            return entries.firstOrNull { lower.endsWith(it.extension) }
        }
    }
}

/**
 * A model file that lives in the app-private model directory.
 */
data class StoredModel(
    val name: String,
    val sizeBytes: Long,
    val kind: ModelKind,
    val directory: File
) {
    val file: File get() = File(directory, name)

    val path: String get() = file.absolutePath
}

/**
 * Owns the single directory that holds downloaded and imported model weights.
 *
 * The directory is `<noBackupFilesDir>/models`, which is a sibling of the web root
 * (`<filesDir>/sites/default`), so weights can never be served by the local HTTP
 * server. [ensureDirectory] also refuses a symlink, mirroring [WebRoot].
 */
class ModelStore(private val modelsDir: File) {

    fun ensureDirectory(): File {
        if (Files.isSymbolicLink(modelsDir.toPath())) {
            throw SecurityException("The model directory must not be a symbolic link")
        }
        if (!modelsDir.exists() && !modelsDir.mkdirs()) {
            throw IOException("Unable to create the model directory")
        }
        if (!modelsDir.isDirectory) {
            throw IOException("The model directory is not a directory")
        }
        return modelsDir
    }

    /** Absolute location of the model directory without creating it. */
    fun directory(): File = modelsDir

    fun list(kind: ModelKind? = null): List<StoredModel> {
        if (!modelsDir.isDirectory) {
            return emptyList()
        }
        val entries = modelsDir.listFiles() ?: return emptyList()
        return entries.asSequence()
            .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
            .mapNotNull { file ->
                val modelKind = ModelKind.of(file.name) ?: return@mapNotNull null
                if (isSafeName(file.name)) modelKind to file else null
            }
            .filter { kind == null || it.first == kind }
            .sortedBy { it.second.name.lowercase() }
            .map { (modelKind, file) -> StoredModel(file.name, file.length(), modelKind, modelsDir) }
            .toList()
    }

    /**
     * Resolves a name to a file inside the model directory, or null when the name
     * escapes the directory or does not exist.
     */
    fun resolve(name: String): File? {
        if (!isSafeName(name)) {
            return null
        }
        val candidate = File(modelsDir, name)
        val canonicalRoot = modelsDir.canonicalFile
        val canonicalCandidate = try {
            candidate.canonicalFile
        } catch (_: IOException) {
            return null
        }
        if (canonicalCandidate.parentFile != canonicalRoot) {
            return null
        }
        if (Files.isSymbolicLink(candidate.toPath())) {
            return null
        }
        return candidate.takeIf { it.isFile }
    }

    fun delete(name: String): Boolean = resolve(name)?.delete() ?: false

    /**
     * The vocabulary that belongs to the ONNX model [name], or null when there is none.
     *
     * Two layouts are accepted, in this order: `<model stem>.txt` beside the model, which is what a
     * downloaded pair looks like, and a single `vocab.txt` the app shares. The per model file wins,
     * so two encoders with different vocabularies can both be kept in the directory.
     */
    fun vocabFor(name: String): File? {
        if (!isSafeName(name)) {
            return null
        }
        val stem = name.substringBeforeLast('.', name)
        return listOf("$stem$VOCAB_EXTENSION", SHARED_VOCAB)
            .firstNotNullOfOrNull { resolveVocab(it) }
    }

    /** The vocabulary files in the directory, for the tab's display. */
    fun listVocab(): List<String> {
        if (!modelsDir.isDirectory) {
            return emptyList()
        }
        val entries = modelsDir.listFiles() ?: return emptyList()
        return entries.asSequence()
            .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
            .map { it.name }
            .filter { isSafeVocabName(it) }
            .sorted()
            .toList()
    }

    /** A vocabulary file by name, with the same path and size checks as a weight file. */
    fun resolveVocab(name: String): File? {
        if (!isSafeVocabName(name) || !modelsDir.isDirectory) {
            return null
        }
        val candidate = File(modelsDir, name)
        if (Files.isSymbolicLink(candidate.toPath())) {
            return null
        }
        if (!candidate.isFile || candidate.length() > MAX_VOCAB_BYTES) {
            return null
        }
        val canonicalRoot = modelsDir.canonicalFile
        val canonicalCandidate = try {
            candidate.canonicalFile
        } catch (_: IOException) {
            return null
        }
        return candidate.takeIf { canonicalCandidate.parentFile == canonicalRoot }
    }

    fun deleteVocab(name: String): Boolean = resolveVocab(name)?.delete() ?: false

    companion object {
        const val RELATIVE_PATH = "models"

        /** Maximum weight size accepted from an import or a download. */
        const val MAX_MODEL_BYTES = 8L * 1024 * 1024 * 1024

        /** The extension a WordPiece vocabulary is stored under. */
        const val VOCAB_EXTENSION = ".txt"

        /** The one vocabulary shared by every model when no per model file is present. */
        const val SHARED_VOCAB = "vocab$VOCAB_EXTENSION"

        /**
         * Maximum vocabulary size. A real `vocab.txt` is a few hundred kilobytes, so this is an
         * order of magnitude over the largest one in circulation and still far below a weight file.
         */
        const val MAX_VOCAB_BYTES = 4L * 1024 * 1024

        fun directoryFor(context: Context): File =
            File(context.noBackupFilesDir, RELATIVE_PATH)

        fun forContext(context: Context): ModelStore = ModelStore(directoryFor(context))

        /**
         * Reduces an arbitrary name to a single safe file name: no separators, no
         * traversal, ASCII only, bounded length. Returns null when nothing is left.
         */
        fun sanitizeName(raw: String): String? {
            val base = raw.substringAfterLast('/').substringAfterLast('\\')
            val cleaned = buildString(base.length) {
                for (character in base) {
                    when {
                        character in 'A'..'Z' || character in 'a'..'z' || character in '0'..'9' -> append(character)
                        character == '.' || character == '_' || character == '-' -> append(character)
                        else -> append('_')
                    }
                }
            }.trim('.', ' ')
            if (cleaned.none { it.isLetterOrDigit() }) {
                return null
            }
            return if (cleaned.length <= MAX_NAME_LENGTH) cleaned else cleaned.take(MAX_NAME_LENGTH)
        }

        /**
         * True when [name] is a plain file name that stays inside the directory and
         * carries a model extension.
         */
        fun isSafeName(name: String): Boolean {
            if (name.isEmpty() || name.length > MAX_NAME_LENGTH) {
                return false
            }
            if (name != sanitizeName(name)) {
                return false
            }
            return ModelKind.of(name) != null
        }

        /** The same check for a vocabulary file name. */
        fun isSafeVocabName(name: String): Boolean =
            name.isNotEmpty() && name.length <= MAX_NAME_LENGTH &&
                name == sanitizeName(name) &&
                name.lowercase().endsWith(VOCAB_EXTENSION)

        /** True when [child] is [parent] or lives under it. Used to keep weights out of the web root. */
        fun isInside(child: File, parent: File): Boolean {
            val childPath = try {
                child.canonicalFile.toPath()
            } catch (_: IOException) {
                return false
            }
            val parentPath = try {
                parent.canonicalFile.toPath()
            } catch (_: IOException) {
                return false
            }
            return childPath.startsWith(parentPath)
        }

        private const val MAX_NAME_LENGTH = 96
    }
}
