package com.mossdial.server

data class ByteRange(
    val start: Long,
    val endInclusive: Long
) {
    val length: Long
        get() = endInclusive - start + 1
}

sealed interface RangeParseResult {
    data object NotRequested : RangeParseResult
    data class Satisfiable(val range: ByteRange) : RangeParseResult
    data object Unsatisfiable : RangeParseResult
}

object HttpRanges {
    fun parse(headerValue: String?, resourceLength: Long): RangeParseResult {
        if (headerValue == null || resourceLength < 0) return RangeParseResult.NotRequested
        val value = headerValue.trim()
        val separator = value.indexOf('=')
        if (separator <= 0 || !value.substring(0, separator).equals("bytes", ignoreCase = true)) {
            return RangeParseResult.NotRequested
        }
        val specification = value.substring(separator + 1)
        if (specification.isEmpty() || ',' in specification) return RangeParseResult.NotRequested
        val dash = specification.indexOf('-')
        if (dash < 0 || dash != specification.lastIndexOf('-')) return RangeParseResult.NotRequested
        val first = specification.substring(0, dash)
        val last = specification.substring(dash + 1)
        if (first.isEmpty()) {
            if (last.isEmpty() || last.any { it !in '0'..'9' }) return RangeParseResult.NotRequested
            val suffixLength = parseUnsigned(last, resourceLength) ?: resourceLength
            if (suffixLength == 0L || resourceLength == 0L) return RangeParseResult.Unsatisfiable
            val length = minOf(suffixLength, resourceLength)
            return RangeParseResult.Satisfiable(ByteRange(resourceLength - length, resourceLength - 1))
        }
        if (first.any { it !in '0'..'9' }) return RangeParseResult.NotRequested
        val start = parseUnsigned(first, resourceLength) ?: return RangeParseResult.Unsatisfiable
        if (last.isNotEmpty() && last.any { it !in '0'..'9' }) return RangeParseResult.NotRequested
        val end = if (last.isEmpty()) {
            resourceLength - 1
        } else {
            parseUnsigned(last, resourceLength - 1) ?: resourceLength - 1
        }
        if (resourceLength == 0L) return RangeParseResult.Unsatisfiable
        if (end < start) return RangeParseResult.NotRequested
        if (start >= resourceLength) return RangeParseResult.Unsatisfiable
        return RangeParseResult.Satisfiable(ByteRange(start, minOf(end, resourceLength - 1)))
    }

    private fun parseUnsigned(value: String, overflowValue: Long): Long? {
        val significant = value.trimStart('0')
        if (significant.isEmpty()) return 0L
        if (significant.length > 19) return overflowValue
        return significant.toLongOrNull() ?: overflowValue
    }
}
