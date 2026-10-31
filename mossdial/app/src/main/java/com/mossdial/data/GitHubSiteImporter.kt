package com.mossdial.data

import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.nio.file.FileAlreadyExistsException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * One GitHub repository the user asked to host, parsed from what they typed.
 *
 * [ref] is `null` when the caller did not name a branch or tag, which means the archive for the
 * repository's default branch (`HEAD`) is used. A ref is kept as it was typed, because branch names
 * can legitimately contain slashes.
 */
data class GitHubSiteSpec(
    val owner: String,
    val repo: String,
    val ref: String?
) {
    companion object {
        /**
         * Accepts the shapes a person actually pastes: `owner/repo`, a full `https://github.com`
         * URL, with or without `.git`, and with or without a `/tree/<branch>` suffix.
         */
        fun parse(repository: String, branch: String? = null): GitHubSiteSpec {
            val trimmed = repository.trim()
            require(trimmed.isNotEmpty()) { "Enter a GitHub repository" }

            var owner: String
            var repo: String
            var parsedRef: String? = null
            if (trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)
            ) {
                val url = try {
                    URL(trimmed)
                } catch (_: Exception) {
                    throw IllegalArgumentException("That is not a URL")
                }
                if (!url.protocol.equals("https", ignoreCase = true)) {
                    throw IllegalArgumentException("Use an https:// GitHub URL")
                }
                val host = url.host.lowercase()
                if (host != "github.com" && host != "www.github.com") {
                    throw IllegalArgumentException("Use a github.com URL")
                }
                val segments = url.path.trim('/').split('/').filter { it.isNotEmpty() }
                if (segments.size < 2) {
                    throw IllegalArgumentException("Use github.com/owner/repo")
                }
                owner = segments[0]
                repo = segments[1].removeSuffix(".git")
                if (segments.getOrNull(2) == "tree") {
                    parsedRef = segments.drop(3)
                        .takeIf { it.isNotEmpty() }
                        ?.joinToString("/") { segment ->
                            try {
                                URLDecoder.decode(segment, "UTF-8")
                            } catch (_: Exception) {
                                segment
                            }
                        }
                }
            } else {
                val segments = trimmed.removeSuffix(".git").split('/').filter { it.isNotEmpty() }
                if (segments.size != 2) {
                    throw IllegalArgumentException("Use owner/repo or a github.com URL")
                }
                owner = segments[0]
                repo = segments[1]
            }
            requireRepositoryName(owner, "owner")
            requireRepositoryName(repo, "repository")
            val ref = branch?.trim()?.takeIf { it.isNotEmpty() } ?: parsedRef
            if (ref != null) requireRef(ref)
            return GitHubSiteSpec(owner, repo, ref)
        }

        private fun requireRepositoryName(value: String, what: String) {
            require(
                value.isNotEmpty() &&
                    value.length <= 100 &&
                    value != "." &&
                    value != ".." &&
                    value.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
            ) { "That GitHub $what is not usable" }
        }

        private fun requireRef(value: String) {
            require(
                value.isNotEmpty() &&
                    value.length <= 200 &&
                    !value.startsWith('/') &&
                    !value.endsWith('/') &&
                    !value.contains("..") &&
                    value.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' || it == '/' }
            ) { "That branch or tag is not usable" }
        }
    }
}

/** What one import produced, for the screen's confirmation line. */
data class ImportedGitHubSite(
    val directory: String,
    val name: String,
    /** The web-root-relative folder that should be hosted. */
    val path: String,
    val files: Int,
    val bytes: Long,
    val siteRoot: String,
    /** The prefix the build expects, or an empty string when it was built for `/`. */
    val basePath: String,
    /** The preset that best matches the extracted output. */
    val siteKind: SiteKind
)

/** The counters one extraction pass produced, before the folder is inspected. */
private data class ExtractedSite(
    val files: Int,
    val bytes: Long
)

/**
 * Imports a hosted website straight from GitHub.
 *
 * The archive is fetched from `codeload.github.com` over HTTPS with redirects disabled, so a pasted
 * URL cannot be turned into a fetch from somewhere else. The archive lands in a temporary file, is
 * inspected once to find the folder that actually holds the site (`docs`, `public`, `dist`,
 * `build`, `site`, `_site`, or the archive root), and then every entry under that folder is copied
 * into the web root through [WebRootFileStore], which owns every path check. Nothing is extracted
 * outside the web root, and a failed import leaves no partial folder behind.
 */
class GitHubSiteImporter(
    private val tempDirectory: File,
    private val connectionFactory: (URL) -> HttpURLConnection = {
        it.openConnection() as HttpURLConnection
    },
    private val maxArchiveBytes: Long = MAX_ARCHIVE_BYTES,
    private val maxExtractedBytes: Long = MAX_EXTRACTED_BYTES,
    private val maxEntries: Int = MAX_ENTRIES
) {
    init {
        require(maxArchiveBytes > 0) { "The archive limit must be positive" }
        require(maxExtractedBytes > 0) { "The extracted limit must be positive" }
        require(maxEntries > 0) { "The entry limit must be positive" }
    }

    /** Imports [repository] into [directory] inside the web root. */
    fun import(
        store: WebRootFileStore,
        directory: String,
        repository: String,
        branch: String? = null
    ): ImportedGitHubSite {
        val spec = GitHubSiteSpec.parse(repository, branch)
        val name = folderName(spec.repo)
        val targetPath = if (directory.isEmpty()) name else "$directory/$name"
        val existing = store.resolve(targetPath)
        if (existing != null && existing.exists()) {
            throw FileAlreadyExistsException(targetPath)
        }
        val archive = downloadArchive(spec)
        try {
            val entryNames = readEntryNames(archive)
            val root = archiveRoot(entryNames)
            val sitePrefix = chooseSiteRoot(entryNames, root)
            store.createDirectory(directory, name)
            try {
                val extracted = extractEntries(store, archive, root, sitePrefix, targetPath)
                val targetDirectory = store.resolve(targetPath)
                    ?: throw IOException("The imported site could not be read")
                val inspection = SiteInspector.inspect(targetDirectory)
                if (inspection.looksUnbuilt) {
                    throw IOException(
                        "This repository is the site's source code, not its built output. " +
                            "Run the project's build and import the generated dist, out, or build " +
                            "folder, or import a branch that contains those built files."
                    )
                }
                return ImportedGitHubSite(
                    directory = directory,
                    name = name,
                    path = targetPath,
                    files = extracted.files,
                    bytes = extracted.bytes,
                    siteRoot = if (sitePrefix.isEmpty()) "archive root" else sitePrefix.joinToString("/"),
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

    private fun downloadArchive(spec: GitHubSiteSpec): File {
        val refs = if (spec.ref == null) {
            listOf("HEAD")
        } else {
            listOf("refs/heads/${spec.ref}", "refs/tags/${spec.ref}", spec.ref).distinct()
        }
        var lastStatus = 0
        refs.forEach { ref ->
            val url = URL("https://codeload.github.com/${spec.owner}/${spec.repo}/zip/$ref")
            val connection = connectionFactory(url)
            try {
                connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
                connection.readTimeout = READ_TIMEOUT_MILLIS
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", USER_AGENT)
                val status = connection.responseCode
                if (status != HttpURLConnection.HTTP_OK) {
                    lastStatus = status
                    return@forEach
                }
                val declaredLength = connection.contentLengthLong
                if (declaredLength > maxArchiveBytes) {
                    throw IOException("The repository archive is larger than the import limit")
                }
                val archive = File.createTempFile("mossdial-github-", ".zip", tempDirectory)
                try {
                    connection.inputStream.use { input ->
                        archive.outputStream().use { output ->
                            copyBounded(input, output, maxArchiveBytes)
                        }
                    }
                    return archive
                } catch (error: Throwable) {
                    runCatching { archive.delete() }
                    throw error
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException(
            if (lastStatus == HttpURLConnection.HTTP_NOT_FOUND) {
                "That repository, branch or tag could not be found"
            } else {
                "GitHub could not be reached (status $lastStatus)"
            }
        )
    }

    private fun readEntryNames(archive: File): List<String> {
        ZipInputStream(BufferedInputStream(archive.inputStream())).use { zip ->
            val names = mutableListOf<String>()
            while (true) {
                val entry = zip.nextEntry ?: break
                names.add(entry.name)
                zip.closeEntry()
                if (names.size > maxEntries) {
                    throw IOException("The repository has too many files to import")
                }
            }
            if (names.isEmpty()) throw IOException("The repository archive is empty")
            return names
        }
    }

    private fun extractEntries(
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
                if (segments.firstOrNull() != root) {
                    throw IOException("The archive has an unexpected layout")
                }
                val relative = segments.drop(1)
                if (relative.size <= sitePrefix.size || relative.take(sitePrefix.size) != sitePrefix) {
                    zip.closeEntry()
                    continue
                }
                val pathSegments = relative.drop(sitePrefix.size)
                ensureDirectories(store, targetPath, pathSegments.dropLast(1))
                // The entry stays open until the store has copied it: closing it here would end the
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
                    throw IOException("The repository has too many files to import")
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
                throw IOException("The repository archive is larger than the import limit")
            }
            output.write(buffer, 0, read)
        }
    }

    companion object {
        const val MAX_ARCHIVE_BYTES = 256L * 1024 * 1024
        const val MAX_EXTRACTED_BYTES = 512L * 1024 * 1024
        const val MAX_ENTRIES = 10_000

        /** Folders a static site commonly lives in, checked in this order. */
        private val SITE_ROOT_HINTS = listOf("out", "docs", "public", "dist", "build", "site", "_site")

        private const val CONNECT_TIMEOUT_MILLIS = 15_000
        private const val READ_TIMEOUT_MILLIS = 30_000
        private const val USER_AGENT = "Mossdial"
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private const val MAX_PATH_SEGMENTS = 32

        /**
         * The folder inside the archive that should become the web root.
         *
         * A repository that already has `index.html` at its root imports as it is. Otherwise the
         * common static-site folders are checked in order, and finally a single top-level folder
         * that holds the site is used. With nothing recognisable the archive root is imported and
         * the user can move files in the Files tab.
         */
        private fun chooseSiteRoot(entryNames: List<String>, root: String): List<String> {
            val files = entryNames
                .filter { !it.endsWith("/") }
                .mapNotNull { name ->
                    safeSegments(name)
                        ?.takeIf { it.firstOrNull() == root }
                        ?.drop(1)
                        ?.takeIf { it.isNotEmpty() }
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

        private fun archiveRoot(entryNames: List<String>): String {
            val roots = entryNames.mapNotNull { name ->
                safeSegments(name)?.firstOrNull()
            }.toSet()
            if (roots.size != 1 || roots.single().isEmpty()) {
                throw IOException("The archive has an unexpected layout")
            }
            return roots.single()
        }

        /** Splits a zip entry into path segments, or null when the name could escape the root. */
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

        private fun joinPath(segments: List<String>): String = segments.joinToString("/")

        /** The web-root folder name for a repository, safe for [WebRootFileStore.validateName]. */
        private fun folderName(repo: String): String {
            val cleaned = repo.trim().replace(Regex("[^A-Za-z0-9._-]"), "-")
            val name = cleaned.ifBlank { "github-site" }.take(64).trim('.', '_')
            return name.ifBlank { "github-site" }
        }
    }
}
