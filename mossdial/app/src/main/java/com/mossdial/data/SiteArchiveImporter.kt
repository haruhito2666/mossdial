package com.mossdial.data

import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileAlreadyExistsException
import java.util.Locale
import java.util.zip.ZipInputStream

/** What one local archive import produced. */
data class ImportedSiteArchive(
    val name: String,
    val path: String,
    val files: Int,
    val bytes: Long,
    val basePath: String,
    val siteKind: SiteKind
)

/**
 * Imports a built site from a local `.zip` archive.
 *
 * The archive is inspected before anything is written: the single wrapping folder a normal
 * "compress this folder" zip adds is removed, a build folder (`dist`, `out`, `build`, `_site`,
 * `docs`, `public`, `site`) is preferred when present, and every entry is then copied through
 * [WebRootFileStore], which owns the path checks. A source archive is refused rather than hosted
 * as a blank page.
 */
class SiteArchiveImporter(
    private val tempDirectory: File,
    private val maxArchiveBytes: Long = MAX_ARCHIVE_BYTES,
    private val maxExtractedBytes: Long = MAX_EXTRACTED_BYTES,
    private val maxEntries: Int = MAX_ENTRIES
) {
    init {
        require(maxArchiveBytes > 0) { "The archive limit must be positive" }
        require(maxExtractedBytes > 0) { "The extracted limit must be positive" }
        require(maxEntries > 0) { "The entry limit must be positive" }
    }

    fun import(
        store: WebRootFileStore,
        directory: String,
        archiveName: String,
        source: InputStream
    ): ImportedSiteArchive {
        val name = folderName(archiveName)
        val targetPath = if (directory.isEmpty()) name else "$directory/$name"
        if (store.resolve(targetPath)?.exists() == true) {
            throw FileAlreadyExistsException(targetPath)
        }
        val archive = writeTemporary(source)
        try {
            val entryNames = readEntryNames(archive)
            val root = archiveRoot(entryNames)
            val sitePrefix = chooseSiteRoot(entryNames, root)
            store.createDirectory(directory, name)
            try {
                val extracted = extract(store, archive, root, sitePrefix, targetPath)
                val targetDirectory = store.resolve(targetPath)
                    ?: throw IOException("The imported site could not be read")
                val inspection = SiteInspector.inspect(targetDirectory)
                if (inspection.looksUnbuilt) {
                    throw IOException(
                        "This zip contains source code, not a built site. Build the project and " +
                            "zip its dist, out, or build folder."
                    )
                }
                return ImportedSiteArchive(
                    name = name,
                    path = targetPath,
                    files = extracted.files,
                    bytes = extracted.bytes,
                    basePath = inspection.basePath,
                    siteKind = SiteInspector.detectKind(targetDirectory)
                )
            } catch (error: Throwable) {
                runCatching { store.delete(targetPath) }
                throw error
            }
        } finally {
            runCatching { archive.delete() }
        }
    }

    private fun writeTemporary(source: InputStream): File {
        val archive = File.createTempFile("mossdial-site-", ".zip", tempDirectory)
        try {
            archive.outputStream().use { output ->
                copyBounded(source, output, maxArchiveBytes)
            }
            return archive
        } catch (error: Throwable) {
            runCatching { archive.delete() }
            throw error
        }
    }

    private fun readEntryNames(archive: File): List<String> {
        ZipInputStream(BufferedInputStream(archive.inputStream())).use { zip ->
            val names = mutableListOf<String>()
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    if (safeSegments(entry.name) == null) {
                        throw IOException("The archive holds a file with an unsafe name")
                    }
                    names += entry.name
                    if (names.size > maxEntries) {
                        throw IOException("The archive has too many files to import")
                    }
                }
                zip.closeEntry()
            }
            if (names.isEmpty()) throw IOException("The archive is empty")
            return names
        }
    }

    private fun extract(
        store: WebRootFileStore,
        archive: File,
        root: String,
        sitePrefix: List<String>,
        targetPath: String
    ): ExtractedSite {
        var files = 0
        var bytes = 0L
        ZipInputStream(BufferedInputStream(archive.inputStream())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                val segments = safeSegments(entry.name)
                    ?: throw IOException("The archive holds a file with an unsafe name")
                val relative = if (root.isEmpty()) {
                    segments
                } else {
                    if (segments.firstOrNull() != root) {
                        throw IOException("The archive has an unexpected layout")
                    }
                    segments.drop(1)
                }
                if (relative.size <= sitePrefix.size || relative.take(sitePrefix.size) != sitePrefix) {
                    zip.closeEntry()
                    continue
                }
                val pathSegments = relative.drop(sitePrefix.size)
                ensureDirectories(store, targetPath, pathSegments.dropLast(1))
                // The entry stays open until the store has copied it; closing it here would end the
                // stream before a byte reached the web root.
                val imported = store.importFile(
                    relativeDirectory = joinPath(listOf(targetPath) + pathSegments.dropLast(1)),
                    fileName = pathSegments.last(),
                    source = zip
                )
                zip.closeEntry()
                files += 1
                bytes += imported.sizeBytes
                if (bytes > maxExtractedBytes) {
                    throw IOException("The site is larger than the import limit")
                }
                if (files > maxEntries) {
                    throw IOException("The archive has too many files to import")
                }
            }
        }
        if (files == 0) throw IOException("The archive holds no usable site files")
        return ExtractedSite(files = files, bytes = bytes)
    }

    private fun ensureDirectories(store: WebRootFileStore, base: String, segments: List<String>) {
        var current = base
        for (segment in segments) {
            val next = "$current/$segment"
            if (store.resolve(next)?.isDirectory == true) {
                current = next
                continue
            }
            store.createDirectory(current, segment)
            current = next
        }
    }

    private fun copyBounded(source: InputStream, output: java.io.OutputStream, limit: Long) {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var total = 0L
        while (true) {
            val read = source.read(buffer)
            if (read < 0) return
            total += read
            if (total > limit) {
                throw IOException("The archive is larger than the import limit")
            }
            output.write(buffer, 0, read)
        }
    }

    private fun archiveRoot(entryNames: List<String>): String {
        val roots = entryNames.mapNotNull { safeSegments(it)?.firstOrNull() }.toSet()
        if (roots.size != 1) return ""
        val only = roots.single()
        if (only.isEmpty()) return ""
        return if (entryNames.all { (safeSegments(it)?.size ?: 0) > 1 }) only else ""
    }

    private fun chooseSiteRoot(entryNames: List<String>, root: String): List<String> {
        val files = entryNames.mapNotNull { name ->
            val segments = safeSegments(name) ?: return@mapNotNull null
            val relative = if (root.isEmpty()) {
                segments
            } else {
                if (segments.firstOrNull() != root) return@mapNotNull null
                segments.drop(1)
            }
            relative.takeIf { it.isNotEmpty() }
        }
        if (files.isEmpty()) return emptyList()
        if (hasIndex(files, emptyList())) return emptyList()
        SITE_ROOT_HINTS.forEach { hint ->
            if (hasIndex(files, listOf(hint))) return listOf(hint)
        }
        val topDirectories = files.mapNotNull { it.firstOrNull() }.distinct()
        if (topDirectories.size == 1) {
            val only = topDirectories.single()
            if (hasIndex(files, listOf(only))) return listOf(only)
        }
        return emptyList()
    }

    private fun hasIndex(files: List<List<String>>, prefix: List<String>): Boolean =
        files.any { path ->
            path.size == prefix.size + 1 &&
                path.take(prefix.size) == prefix &&
                (path.last() == "index.html" || path.last() == "index.htm")
        }

    private fun joinPath(segments: List<String>): String = segments.joinToString("/")

    private fun safeSegments(name: String): List<String>? {
        if (name.isEmpty() || name.length > 1024 || '\\' in name) return null
        val segments = name.split('/')
        if (segments.size > MAX_PATH_SEGMENTS) return null
        if (segments.any { segment ->
                segment.isEmpty() || segment == "." || segment == ".." ||
                    segment.any { it == '\u0000' || it.isISOControl() }
            }
        ) {
            return null
        }
        return segments
    }

    private fun folderName(archiveName: String): String {
        val withoutExtension = archiveName
            .substringBeforeLast('.', archiveName)
            .trim()
        val cleaned = withoutExtension.replace(Regex("[^A-Za-z0-9._-]"), "-")
        val name = cleaned.ifBlank { "site" }.take(64).trim('.', '_')
        return name.ifBlank { "site" }
    }

    private data class ExtractedSite(val files: Int, val bytes: Long)

    companion object {
        const val MAX_ARCHIVE_BYTES = 256L * 1024 * 1024
        const val MAX_EXTRACTED_BYTES = 512L * 1024 * 1024
        const val MAX_ENTRIES = 10_000

        private val SITE_ROOT_HINTS = listOf("out", "docs", "public", "dist", "build", "site", "_site")
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private const val MAX_PATH_SEGMENTS = 32
    }
}
