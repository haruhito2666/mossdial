package com.mossdial.server

import java.io.File
import java.util.Locale

object StaticFiles {
    private val contentTypes = mapOf(
        "html" to "text/html; charset=utf-8",
        "htm" to "text/html; charset=utf-8",
        "css" to "text/css; charset=utf-8",
        "js" to "text/javascript; charset=utf-8",
        "json" to "application/json; charset=utf-8",
        "txt" to "text/plain; charset=utf-8",
        "md" to "text/markdown; charset=utf-8",
        "xml" to "application/xml; charset=utf-8",
        "svg" to "image/svg+xml",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "ico" to "image/x-icon",
        "pdf" to "application/pdf",
        "wasm" to "application/wasm"
    )

    fun resolve(root: File, rawPath: String): File? {
        val requested = PathPolicy.resolve(root, rawPath) ?: return null
        if (!requested.exists()) return null
        if (!requested.isDirectory) return requested.takeIf { it.isFile }
        val index = File(requested, "index.html")
        return index.takeIf { it.isFile }
    }

    fun contentType(file: File): String {
        val extension = file.extension.lowercase(Locale.ROOT)
        return contentTypes[extension] ?: "application/octet-stream"
    }
}
