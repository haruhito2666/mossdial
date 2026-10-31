package com.mossdial.server

import java.io.File
import java.io.IOException
import java.util.Locale

object StaticFiles {
    private val contentTypes = mapOf(
        "html" to "text/html; charset=utf-8",
        "htm" to "text/html; charset=utf-8",
        "css" to "text/css; charset=utf-8",
        "js" to "text/javascript; charset=utf-8",
        "json" to "application/json; charset=utf-8",
        "map" to "application/json; charset=utf-8",
        "webmanifest" to "application/manifest+json",
        "mjs" to "text/javascript; charset=utf-8",
        "cjs" to "text/javascript; charset=utf-8",
        "txt" to "text/plain; charset=utf-8",
        "md" to "text/markdown; charset=utf-8",
        "xml" to "application/xml; charset=utf-8",
        "svg" to "image/svg+xml",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "avif" to "image/avif",
        "ico" to "image/x-icon",
        "pdf" to "application/pdf",
        "wasm" to "application/wasm",
        "woff" to "font/woff",
        "woff2" to "font/woff2",
        "ttf" to "font/ttf",
        "otf" to "font/otf",
        "mp3" to "audio/mpeg",
        "mp4" to "video/mp4",
        "webm" to "video/webm"
    )

    fun resolve(root: File, rawPath: String): File? {
        val requested = PathPolicy.resolve(root, rawPath) ?: return null
        if (!requested.exists()) return null
        if (!requested.isDirectory) return requested.takeIf { it.isFile }
        // PathPolicy canonicalised the directory, but the child is a separate path and can be a
        // symlink. Re-check each candidate, or a link named index.html can serve a file outside the
        // web root.
        return directoryIndex(root, requested)
    }

    /**
     * Resolves a request the way a static site expects it.
     *
     * The exact file or directory index is tried first. Then two clean-URL shapes are tried —
     * `/about` may be `about.html` or `about/index.html` — because Next.js, Astro and most static
     * exporters emit one of those while a site's own links usually omit the extension. Finally,
     * when [spaFallback] is on and the path has no extension, the root `index.html` is returned so
     * a client-side router can take the route. A missing `.js`, `.css`, `.wasm` or any other asset
     * still 404s, so a broken build cannot masquerade as a page.
     *
     * [basePath] is the optional prefix a build generated for itself, for example `/repo` for a
     * GitHub Pages project. It is removed before a path is resolved, so the same file tree works
     * whether the site was built for a subpath or for the root.
     */
    fun resolveSite(
        root: File,
        rawPath: String,
        spaFallback: Boolean,
        basePath: String = ""
    ): File? {
        val path = stripBasePath(rawPath, basePath)
        resolve(root, path)?.let { return it }
        val requested = PathPolicy.resolve(root, path) ?: return null
        val canonicalRoot = root.canonicalFile
        if (requested == canonicalRoot) return null
        val relative = requested.toRelativeString(canonicalRoot)
            .replace(File.separatorChar, '/')
            .trimEnd('/')
        if (relative.isNotEmpty()) {
            listOf(
                "$relative.html",
                "$relative.htm",
                "$relative/index.html",
                "$relative/index.htm"
            ).forEach { candidate ->
                val file = File(canonicalRoot, candidate)
                if (file.isFile && isInsideRoot(root, file)) return file
            }
        }
        if (spaFallback && !path.substringBefore('?').substringAfterLast('/').contains('.')) {
            return resolve(root, "/index.html") ?: resolve(root, "/index.htm")
        }
        return null
    }

    /**
     * Removes a site's build-time base path from a request.
     *
     * A request that does not start with the prefix is left alone. That is what lets a site keep
     * working when its links are rooted at `/repo/` while the same folder is also reachable at `/`.
     */
    fun stripBasePath(rawPath: String, basePath: String): String {
        val base = basePath.trim().trim('/')
        if (base.isEmpty()) return rawPath
        val path = rawPath.substringBefore('?')
        val prefix = "/$base"
        val stripped = when {
            path == prefix -> "/"
            path.startsWith("$prefix/") -> path.removePrefix(prefix)
            else -> path
        }
        val query = rawPath.substringAfter('?', "")
        return if (query.isEmpty()) stripped else "$stripped?$query"
    }

    private fun directoryIndex(root: File, directory: File): File? {
        for (name in listOf("index.html", "index.htm")) {
            val index = File(directory, name)
            if (index.isFile && isInsideRoot(root, index)) return index
        }
        return null
    }

    fun contentType(file: File): String {
        val extension = file.extension.lowercase(Locale.ROOT)
        return contentTypes[extension] ?: "application/octet-stream"
    }

    private fun isInsideRoot(root: File, file: File): Boolean = try {
        val rootPath = root.canonicalFile.toPath()
        val filePath = file.canonicalFile.toPath()
        filePath != rootPath && filePath.startsWith(rootPath)
    } catch (_: IOException) {
        false
    }
}
