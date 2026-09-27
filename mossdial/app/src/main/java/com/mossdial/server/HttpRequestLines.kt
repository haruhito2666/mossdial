package com.mossdial.server

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * The one request-line reader both listeners use.
 *
 * A request header block is line oriented, so reading it safely is the same problem in the static
 * file server and in the AI API: never buffer more than [limit] bytes for a line, stop the moment
 * the peer stops sending, and decode strictly. Keeping that in one place is what lets
 * [com.mossdial.server.LocalHttpServer] and `AiApiServer` agree on what a malformed request is.
 */
internal object HttpRequestLines {

    /**
     * Reads one CRLF or LF terminated line, or returns null when the peer closed cleanly before
     * sending anything. An unfinished last line is a protocol error rather than a partial value.
     */
    fun readLine(input: InputStream, limit: Int, what: String = "Request line"): String? {
        val bytes = ByteArrayOutputStream(minOf(limit, 1024))
        var previous = -1
        while (true) {
            val current = input.read()
            if (current < 0) {
                if (bytes.size() == 0) return null
                throw IOException("Unexpected end of request")
            }
            if (current == '\n'.code) {
                val value = bytes.toByteArray()
                return decode(if (previous == '\r'.code && value.isNotEmpty()) value.copyOf(value.size - 1) else value)
            }
            bytes.write(current)
            if (bytes.size() > limit) throw HttpLineTooLargeException(what)
            previous = current
        }
    }

    /** Decodes strictly, so a malformed sequence is refused instead of being replaced by U+FFFD. */
    fun decode(value: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(value))
            .toString()
    } catch (_: CharacterCodingException) {
        throw IOException("Invalid UTF-8")
    }

    /** An RFC 9110 token: everything a header name, a method or a header value may be built from. */
    fun isToken(value: String): Boolean = value.isNotEmpty() && value.all {
        it.code in 33..126 && it !in "()<>@,;:\\\"/[]?={} \t"
    }
}

/** A line over its own limit, which a caller may want to answer differently from other errors. */
class HttpLineTooLargeException(what: String) : IOException("$what too large")
