package com.mossdial.server

import java.util.Locale

object GzipEncoding {
    const val MIN_CONTENT_LENGTH = 1_024L

    fun isEligible(contentType: String, contentLength: Long, acceptEncoding: String?): Boolean =
        contentLength >= MIN_CONTENT_LENGTH &&
            isCompressible(contentType) &&
            acceptsGzip(acceptEncoding)

    fun isCompressible(contentType: String): Boolean {
        val mediaType = contentType.substringBefore(';').trim().lowercase(Locale.ROOT)
        return mediaType.startsWith("text/") ||
            mediaType in setOf(
                "application/json",
                "application/javascript",
                "application/xml",
                "application/xhtml+xml",
                "application/atom+xml",
                "application/rss+xml"
            ) ||
            mediaType.endsWith("+json") ||
            mediaType.endsWith("+xml")
    }

    fun acceptsGzip(acceptEncoding: String?): Boolean {
        if (acceptEncoding == null) return false
        var gzipSpecified = false
        var gzipAllowed = true
        var wildcardAllowed = false
        for (directive in acceptEncoding.split(',')) {
            val parts = directive.split(';')
            val name = parts.first().trim().lowercase(Locale.ROOT)
            if (name != "gzip" && name != "*") continue
            val quality = quality(parts.drop(1))
            if (name == "gzip") {
                gzipSpecified = true
                gzipAllowed = gzipAllowed && quality > 0.0
            } else {
                wildcardAllowed = wildcardAllowed || quality > 0.0
            }
        }
        return if (gzipSpecified) gzipAllowed else wildcardAllowed
    }

    private fun quality(parameters: List<String>): Double {
        for (parameter in parameters) {
            val pieces = parameter.split('=', limit = 2)
            if (pieces.size != 2 || pieces[0].trim().lowercase(Locale.ROOT) != "q") continue
            val value = pieces[1].trim().toDoubleOrNull() ?: return 0.0
            if (value.isNaN() || value.isInfinite() || value !in 0.0..1.0) return 0.0
            return value
        }
        return 1.0
    }
}
