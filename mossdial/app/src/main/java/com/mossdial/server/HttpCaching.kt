package com.mossdial.server

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

object HttpCaching {
    private val httpDateFormatter = DateTimeFormatter
        .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        .withZone(ZoneOffset.UTC)

    fun formatEtag(contentLength: Long, lastModifiedMillis: Long): String =
        "W/\"${contentLength.toString(16)}-${lastModifiedMillis.toString(16)}\""

    fun formatLastModified(lastModifiedMillis: Long): String =
        httpDateFormatter.format(Instant.ofEpochMilli(lastModifiedMillis))

    fun ifNoneMatchMatches(headerValue: String?, currentEtag: String): Boolean {
        if (headerValue == null) return false
        val value = headerValue.trim()
        if (value == "*") return true
        val tags = parseTags(value) ?: return false
        val current = normalize(currentEtag)
        return tags.any { it == "*" || normalize(it) == current }
    }

    fun ifModifiedSinceMatches(headerValue: String?, lastModifiedMillis: Long): Boolean {
        val timestamp = parseHttpDate(headerValue ?: return false) ?: return false
        val modified = Math.floorDiv(lastModifiedMillis, 1_000L) * 1_000L
        return modified <= timestamp
    }

    private fun parseHttpDate(value: String): Long? = try {
        DateTimeFormatter.RFC_1123_DATE_TIME.parse(value.trim(), Instant::from).toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }

    private fun parseTags(value: String): List<String>? {
        val tags = mutableListOf<String>()
        var index = 0
        while (index < value.length) {
            while (index < value.length && value[index] == ' ') index++
            if (index >= value.length) return tags
            if (value[index] == '*') {
                tags += "*"
                index++
            } else {
                if (value.startsWith("W/", index)) index += 2
                if (index >= value.length || value[index] != '"') return null
                index++
                val start = index
                while (index < value.length && value[index] != '"') index++
                if (index >= value.length) return null
                tags += value.substring(start, index)
                index++
            }
            while (index < value.length && value[index] == ' ') index++
            if (index == value.length) return tags
            if (value[index] != ',') return null
            index++
            if (index == value.length) return null
        }
        return tags
    }

    private fun normalize(value: String): String = value.removePrefix("W/").removeSurrounding("\"")
}
