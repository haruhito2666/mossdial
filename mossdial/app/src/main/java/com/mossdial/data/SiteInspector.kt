package com.mossdial.data

import com.mossdial.server.PathPolicy
import com.mossdial.server.StaticFiles
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * What a hosted folder looks like from the browser's side.
 *
 * This is a preflight check, not a renderer: it reads the folder's entry page once, finds the
 * local files that page asks for, and reports the ones that are missing. A site that references
 * `/repo/assets/app.js` while its files live under `/assets/app.js` is the common GitHub Pages
 * failure — the HTML arrives, every asset 404s, and the browser shows a blank page.
 *
 * [basePath] is the prefix the build gave itself. [missingAssets] is bounded so a pathological
 * page cannot turn this into a large retained list.
 */
data class SiteInspection(
    val indexFile: String?,
    val indexBytes: Long,
    val basePath: String,
    val referencedAssets: Int,
    val missingAssets: List<String>,
    /** True when the entry page loads framework source instead of built output. */
    val looksUnbuilt: Boolean
) {
    val hasIndex: Boolean
        get() = indexFile != null

    val indexIsEmpty: Boolean
        get() = indexFile != null && indexBytes == 0L

    val isHealthy: Boolean
        get() = hasIndex && !indexIsEmpty && missingAssets.isEmpty() && !looksUnbuilt
}

/**
 * Reads a static site's entry page and the local asset references in it.
 *
 * The parser is deliberately small: it looks for `src`, `href`, and the first URL of `srcset`.
 * That is enough to catch the blank-page cases — a missing entry page, an empty entry page, a
 * build that was copied without its assets, or a build whose base path no longer matches where it
 * is hosted. It never executes anything and never follows a URL.
 */
object SiteInspector {
    private const val MAX_INDEX_BYTES = 2 * 1024 * 1024
    private const val MAX_MISSING_REPORTED = 8
    private const val MAX_SCANNED_FILES = 10_000

    private val INDEX_NAMES = listOf("index.html", "index.htm")
    private val REFERENCE = Regex(
        """(?is)\b(?:src|href)\s*=\s*(?:"([^"]*)"|'([^']*)')"""
    )
    private val BASE_TAG = Regex(
        """(?is)<base\b[^>]*\bhref\s*=\s*(?:"([^"]*)"|'([^']*)')"""
    )
    private val SRCSET = Regex(
        """(?is)\bsrcset\s*=\s*(?:"([^"]*)"|'([^']*)')"""
    )

    /**
     * Folders that are assets wherever they appear. A request below one of these is checked even
     * when its final segment has no extension, because `assets/app` is still an asset path.
     */
    private val ASSET_ROOTS = setOf(
        "_next",
        "assets",
        "static",
        "css",
        "js",
        "images",
        "img",
        "fonts",
        "media",
        "favicon.ico",
        "manifest.webmanifest"
    )

    /** Extensions a browser cannot execute as a built page. */
    private val SOURCE_EXTENSIONS = setOf(
        "ts", "tsx", "jsx", "vue", "svelte", "scss", "sass", "less"
    )

    fun inspect(root: File, basePath: String = ""): SiteInspection {
        val index = findIndex(root)
            ?: return SiteInspection(
                indexFile = null,
                indexBytes = 0L,
                basePath = normalizeBasePath(basePath),
                referencedAssets = 0,
                missingAssets = emptyList(),
                looksUnbuilt = false
            )
        val html = readBounded(index).orEmpty()
        val references = references(html)
        val detected = if (basePath.isNotBlank()) {
            normalizeBasePath(basePath)
        } else {
            baseTag(html) ?: detectBasePath(root, references)
        }
        val missing = missingAssets(root, references, detected)
        return SiteInspection(
            indexFile = index.toRelativeString(root).replace(File.separatorChar, '/'),
            indexBytes = index.length(),
            basePath = detected,
            referencedAssets = references.size,
            missingAssets = missing.take(MAX_MISSING_REPORTED),
            looksUnbuilt = looksUnbuilt(references)
        )
    }

    fun detectBasePath(root: File): String = inspect(root).basePath

    /** Which preset best describes the built output, based on the files it actually contains. */
    fun detectKind(root: File): SiteKind {
        if (File(root, "_next").isDirectory) return SiteKind.NEXT_EXPORT
        var html = 0
        var javascript = 0
        root.walkTopDown()
            .maxDepth(4)
            .filter { it.isFile }
            .take(MAX_SCANNED_FILES)
            .forEach { file ->
                when (file.extension.lowercase(Locale.ROOT)) {
                    "html", "htm" -> html += 1
                    "js", "mjs" -> javascript += 1
                }
            }
        return when {
            html > 1 -> SiteKind.STATIC_EXPORT
            html == 1 && javascript > 0 -> SiteKind.SPA
            else -> SiteKind.CUSTOM
        }
    }

    private fun findIndex(root: File): File? {
        INDEX_NAMES.forEach { name ->
            val candidate = File(root, name)
            if (candidate.isFile) return candidate
        }
        return null
    }

    private fun readBounded(file: File): String? = try {
        file.inputStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var remaining = MAX_INDEX_BYTES
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (read < 0) break
                output.write(buffer, 0, read)
                remaining -= read
            }
            String(output.toByteArray(), Charsets.UTF_8)
        }
    } catch (_: IOException) {
        null
    }

    private fun references(html: String): List<String> {
        val values = mutableListOf<String>()
        REFERENCE.findAll(html).forEach { match ->
            values += match.groupValues[1].ifEmpty { match.groupValues[2] }
        }
        SRCSET.findAll(html).forEach { match ->
            val value = match.groupValues[1].ifEmpty { match.groupValues[2] }
            value.split(',')
                .firstOrNull()
                ?.trim()
                ?.substringBefore(' ')
                ?.takeIf { it.isNotEmpty() }
                ?.let { values += it }
        }
        return values.mapNotNull(::normalizeReference).distinct()
    }

    /**
     * A same-origin path, or null for an external or non-file reference.
     *
     * Relative references are resolved as if the page were at `/`, which is where the server serves
     * the folder's index. `../` is skipped rather than guessed at.
     */
    private fun normalizeReference(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("#") || trimmed.startsWith("?")) return null
        val lower = trimmed.lowercase(Locale.ROOT)
        if (lower.startsWith("data:") ||
            lower.startsWith("blob:") ||
            lower.startsWith("mailto:") ||
            lower.startsWith("tel:") ||
            lower.startsWith("javascript:") ||
            lower.startsWith("http://") ||
            lower.startsWith("https://") ||
            lower.startsWith("//")
        ) {
            return null
        }
        if (trimmed.startsWith("../") || '\\' in trimmed) return null
        val withoutFragment = trimmed.substringBefore('#').substringBefore('?')
        val path = when {
            withoutFragment.startsWith("/") -> withoutFragment
            withoutFragment.startsWith("./") -> "/" + withoutFragment.removePrefix("./")
            else -> "/$withoutFragment"
        }
        if (path == "/" || path.any { it == '\u0000' || it.isISOControl() }) return null
        return path
    }

    private fun baseTag(html: String): String? {
        val match = BASE_TAG.find(html) ?: return null
        val value = match.groupValues[1].ifEmpty { match.groupValues[2] }.trim()
        if (!value.startsWith("/") || value.startsWith("//")) return null
        val path = value.substringBefore('?').substringBefore('#')
        val directory = if (path.substringAfterLast('/').contains('.')) {
            path.substringBeforeLast('/', "")
        } else {
            path
        }
        return normalizeBasePath(directory).ifEmpty { null }
    }

    /**
     * Finds a shared first path segment that is not a real folder in the exported tree.
     *
     * A build with base path `/repo/` asks for `/repo/_next/...` and `/repo/assets/...`; the
     * folder has `_next` and `assets`, but no `repo`. That combination is a reliable signal. A
     * single reference is not enough, because it is more likely a genuinely missing file.
     */
    private fun detectBasePath(root: File, references: List<String>): String {
        val candidates = mutableMapOf<String, Int>()
        val stronglyShaped = mutableSetOf<String>()
        references.forEach { reference ->
            val segments = reference.trim('/').split('/')
            if (segments.size < 2) return@forEach
            val first = segments.first()
            if (first.lowercase(Locale.ROOT) in ASSET_ROOTS) return@forEach
            if (File(root, first).exists()) return@forEach
            candidates[first] = (candidates[first] ?: 0) + 1
            if (segments.getOrNull(1)?.lowercase(Locale.ROOT) in ASSET_ROOTS) {
                stronglyShaped += first
            }
        }
        val only = candidates.entries.singleOrNull() ?: return ""
        // Two references are enough on their own. A single reference is enough only when its
        // second segment is a known asset folder, as in /repo/_next/... or /repo/assets/....
        if (only.value < 2 && only.key !in stronglyShaped) return ""
        return normalizeBasePath(only.key)
    }

    private fun missingAssets(
        root: File,
        references: List<String>,
        basePath: String
    ): List<String> = references.mapNotNull { reference ->
        if (!isAsset(reference)) return@mapNotNull null
        val path = StaticFiles.stripBasePath(reference, basePath)
        val resolved = PathPolicy.resolve(root, path)
        if (resolved != null && resolved.exists()) null else reference
    }

    private fun isAsset(reference: String): Boolean {
        val last = reference.substringAfterLast('/')
        if (last.contains('.')) return true
        val first = reference.trimStart('/').substringBefore('/')
        return first.lowercase(Locale.ROOT) in ASSET_ROOTS
    }

    /**
     * A build loads hashed assets from `assets/`, `_next/` or `static/`. A page that points at
     * `src/main.tsx`, a `.vue` file, or SCSS is the repository's source, not a deployment.
     */
    private fun looksUnbuilt(references: List<String>): Boolean = references.any { reference ->
        val extension = reference.substringAfterLast('/')
            .substringAfterLast('.', "")
            .lowercase(Locale.ROOT)
        extension in SOURCE_EXTENSIONS ||
            (reference.startsWith("/src/") && extension in setOf("js", "mjs", "cjs"))
    }

    internal fun normalizeBasePath(value: String?): String {
        val trimmed = value?.trim()?.trim('/') ?: return ""
        if (trimmed.isEmpty() || trimmed.length > 200) return ""
        if (trimmed.any { it == '\\' || it.isISOControl() }) return ""
        val segments = trimmed.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return ""
        return trimmed
    }
}
