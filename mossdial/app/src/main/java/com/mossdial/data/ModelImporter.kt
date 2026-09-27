package com.mossdial.data

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Copies a model that the user picked from another app into the model directory.
 *
 * Everything the copy needs from the platform is behind [ModelSource] so the copy
 * loop can be exercised by plain JVM tests. The production implementation is
 * [ContentResolverModelSource], which reads through [android.content.ContentResolver]
 * and never asks for a filesystem path.
 */
class ModelImporter(
    private val store: ModelStore,
    private val source: ModelSource
) {

    interface ModelSource {
        /** Display name of the picked document, without any directory part. */
        fun displayName(location: String): String?

        fun openStream(location: String): InputStream
    }

    class ImportException(message: String, cause: Throwable? = null) : IOException(message, cause)

    /**
     * Imports the document at [location] and returns the stored model.
     *
     * The copy is written to a temporary file and only renamed into place once the
     * whole document has been read, so a cancelled or failed import never leaves a
     * truncated weight file behind. A GGUF file has its magic bytes checked.
     */
    fun import(location: String): StoredModel {
        val rawName = source.displayName(location)
            ?: throw ImportException("The selected document has no name")
        val name = ModelStore.sanitizeName(rawName)
            ?: throw ImportException("The selected document has no usable name")
        val kind = ModelKind.of(name)
            ?: throw ImportException("Only .gguf and .onnx files can be imported")
        val file = copyIn(location, name, ModelStore.MAX_MODEL_BYTES) { temporary, written ->
            if (written == 0L) {
                throw ImportException("$rawName is empty")
            }
            if (kind == ModelKind.GGUF && !hasGgufMagic(temporary)) {
                throw ImportException("$rawName is not a GGUF file")
            }
        }
        return StoredModel(file.name, file.length(), kind, store.directory())
    }

    /**
     * Imports a `vocab.txt` for an ONNX encoder and returns the stored file.
     *
     * A WordPiece vocabulary cannot be derived from the graph, so it arrives beside the model the
     * same way a second weight file would. The content is checked for the three tokens every
     * WordPiece pass needs, which catches picking the wrong document, and the file lands under the
     * much smaller [ModelStore.MAX_VOCAB_BYTES] cap.
     */
    fun importVocabulary(location: String): File {
        val rawName = source.displayName(location)
            ?: throw ImportException("The selected document has no name")
        val name = ModelStore.sanitizeName(rawName)
            ?: throw ImportException("The selected document has no usable name")
        if (!ModelStore.isSafeVocabName(name)) {
            throw ImportException("A vocabulary must be a .txt file")
        }
        return copyIn(location, name, ModelStore.MAX_VOCAB_BYTES) { temporary, written ->
            if (written == 0L) {
                throw ImportException("$rawName is empty")
            }
            val text = temporary.readText(Charsets.UTF_8)
            if (text.indexOf('\u0000') >= 0) {
                throw ImportException("$rawName is not a text file")
            }
            val lines = text.lines()
            for (required in REQUIRED_TOKENS) {
                if (lines.none { it.trimEnd('\r') == required }) {
                    throw ImportException("$rawName is not a WordPiece vocabulary, $required is missing")
                }
            }
            if (lines.size > MAX_VOCAB_LINES) {
                throw ImportException("$rawName holds more than $MAX_VOCAB_LINES tokens")
            }
        }
    }

    /**
     * Copies the document into the model directory under [name], or under the first free variant
     * of it, and validates the finished copy before it is put in place.
     */
    private fun copyIn(
        location: String,
        name: String,
        cap: Long,
        validate: (File, Long) -> Unit
    ): File {
        val directory = store.ensureDirectory()
        val target = uniqueTarget(directory, name)
        val temporary = File(directory, target.name + PART_SUFFIX)
        try {
            val written = source.openStream(location).use { input ->
                FileOutputStream(temporary).use { output ->
                    copy(input, output, cap)
                }
            }
            validate(temporary, written)
            if (!temporary.renameTo(target)) {
                throw IOException("Unable to store the imported file as ${target.name}")
            }
            return target
        } catch (failure: Throwable) {
            temporary.delete()
            throw if (failure is IOException) failure else ImportException("Import failed: $name", failure)
        }
    }

    private fun uniqueTarget(directory: File, name: String): File {
        var candidate = File(directory, name)
        var suffix = 2
        val stem = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "")
        while (candidate.exists() || File(directory, candidate.name + PART_SUFFIX).exists()) {
            candidate = File(directory, "$stem-$suffix.$extension")
            suffix++
            if (suffix > 999) {
                throw ImportException("Too many models named $name")
            }
        }
        return candidate
    }

    private fun copy(input: InputStream, output: OutputStream, cap: Long): Long {
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) {
                break
            }
            total += read
            if (total > cap) {
                throw ImportException("The file is larger than the $cap byte limit")
            }
            output.write(buffer, 0, read)
        }
        output.flush()
        return total
    }

    private fun hasGgufMagic(file: File): Boolean = try {
        file.inputStream().use { input ->
            val magic = ByteArray(4)
            input.read(magic) == 4 && String(magic, Charsets.US_ASCII) == GGUF_MAGIC
        }
    } catch (_: IOException) {
        false
    }

    companion object {
        private const val BUFFER_BYTES = 64 * 1024
        private const val PART_SUFFIX = ".part"
        private const val GGUF_MAGIC = "GGUF"
        private const val MAX_VOCAB_LINES = 1 shl 17
        private val REQUIRED_TOKENS = listOf("[UNK]", "[CLS]", "[SEP]")
    }
}

/**
 * Reads picked documents through the [android.content.ContentResolver].
 */
class ContentResolverModelSource(private val context: Context) : ModelImporter.ModelSource {

    override fun displayName(location: String): String? {
        val uri = parse(location) ?: return null
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        return try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor: Cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun openStream(location: String): InputStream {
        val uri = parse(location) ?: throw ModelImporter.ImportException("Unsupported document location")
        return context.contentResolver.openInputStream(uri)
            ?: throw ModelImporter.ImportException("The selected document cannot be opened")
    }

    private fun parse(location: String): Uri? = try {
        Uri.parse(location)
    } catch (_: Exception) {
        null
    }
}
