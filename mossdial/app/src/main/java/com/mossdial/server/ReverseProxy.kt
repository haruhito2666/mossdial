package com.mossdial.server

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale

/**
 * The reverse proxy that turns the file server into a front end for any local HTTP runtime.
 *
 * The listener stays the one public socket. A request that belongs to a backend is forwarded to a
 * loopback port, where the user's own runtime — Node, Bun, Deno, Python, PHP, Go, a Next.js dev
 * server, a static API — answers. Mossdial adds the forwarding headers, relays the response and
 * keeps every bound it already applies to static traffic: one request per client socket, bounded
 * header and body sizes, a request deadline and a write watchdog.
 *
 * The proxy is deliberately transport-only. It never interprets the backend's application state,
 * and it never starts the runtime; that stays with the process the user is running.
 */
class ReverseProxy(
    private val port: Int,
    private val maxBodyBytes: Long = MAX_BODY_BYTES,
    private val connectTimeoutMillis: Int = CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = READ_TIMEOUT_MILLIS
) {
    init {
        require(port in 1..65535) { "The backend port must be a usable port" }
        require(maxBodyBytes > 0) { "The backend body limit must be positive" }
        require(connectTimeoutMillis > 0) { "The backend connect timeout must be positive" }
        require(readTimeoutMillis > 0) { "The backend read timeout must be positive" }
    }

    /**
     * Forwards one request and writes the upstream response to [output].
     *
     * [input] is positioned at the request body; [bodyLength] is the Content-Length the listener
     * already read. The returned byte count is what was sent to the client, which is what the
     * request log records.
     */
    fun forward(request: ProxyRequest, output: OutputStream): ProxyOutcome {
        Socket().use { upstream ->
            upstream.tcpNoDelay = true
            upstream.connect(InetSocketAddress(UPSTREAM_HOST, port), connectTimeoutMillis)
            upstream.soTimeout = readTimeoutMillis
            val upstreamOutput = upstream.getOutputStream()
            writeRequest(upstreamOutput, request)
            copyBody(upstreamOutput, request)
            upstreamOutput.flush()

            val upstreamInput = BufferedInputStream(upstream.getInputStream(), BUFFER_SIZE)
            return readResponse(upstreamInput, request, output)
        }
    }

    private fun writeRequest(output: OutputStream, request: ProxyRequest) {
        val head = buildString {
            append(request.method).append(' ').append(request.target).append(" HTTP/1.1\r\n")
            request.headers.forEach { (name, value) ->
                if (name !in HOP_BY_HOP_HEADERS && name != "content-length") {
                    append(name).append(": ").append(value).append("\r\n")
                }
            }
            append("Host: ").append(request.hostHeader).append("\r\n")
            append("X-Forwarded-For: ").append(request.clientAddress).append("\r\n")
            append("X-Forwarded-Proto: ").append(request.scheme).append("\r\n")
            request.forwardedHost?.let { append("X-Forwarded-Host: ").append(it).append("\r\n") }
            append("Content-Length: ").append(request.bodyLength).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.US_ASCII))
    }

    private fun copyBody(output: OutputStream, request: ProxyRequest) {
        if (request.bodyLength <= 0L) return
        val buffer = ByteArray(BUFFER_SIZE)
        var remaining = request.bodyLength
        while (remaining > 0) {
            val size = minOf(buffer.size.toLong(), remaining).toInt()
            val read = request.body.read(buffer, 0, size)
            if (read < 0) throw IOException("The request body ended early")
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun readResponse(input: InputStream, request: ProxyRequest, output: OutputStream): ProxyOutcome {
        val statusLine = HttpRequestLines.readLine(input, MAX_STATUS_LINE, "Upstream status line")
            ?: throw IOException("The backend closed before answering")
        val pieces = statusLine.split(' ', limit = 3)
        val status = pieces.getOrNull(1)?.toIntOrNull()
            ?: throw IOException("The backend sent an unreadable status line")
        val reason = pieces.getOrNull(2)?.takeIf { it.isNotEmpty() } ?: "Gateway"

        val headers = linkedMapOf<String, String>()
        var headerBytes = 0
        while (true) {
            val line = HttpRequestLines.readLine(input, MAX_HEADER_LINE, "Upstream header line")
                ?: throw IOException("The backend headers ended early")
            if (line.isEmpty()) break
            headerBytes += line.length + 2
            if (headerBytes > MAX_HEADER_BYTES) throw IOException("The backend sent too many headers")
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            val name = line.substring(0, separator).trim().lowercase(Locale.ROOT)
            val value = line.substring(separator + 1).trim()
            if (name !in HOP_BY_HOP_HEADERS) headers[name] = value
        }

        val noBody = request.headOnly || status == 204 || status == 304 || status < 200
        val chunked = headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true
        val contentLength = headers["content-length"]?.toLongOrNull()
        val responseHeaders = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            headers.forEach { (name, value) ->
                if (name != "content-length" && name != "transfer-encoding" && name != "connection") {
                    append(name).append(": ").append(value).append("\r\n")
                }
            }
            if (contentLength != null && !chunked) {
                append("Content-Length: ").append(contentLength).append("\r\n")
            }
            append("Connection: close\r\n\r\n")
        }
        output.write(responseHeaders.toByteArray(Charsets.US_ASCII))

        var bytes = 0L
        if (!noBody) {
            bytes = when {
                chunked -> copyChunked(input, output)
                contentLength != null -> copyExactly(input, output, contentLength)
                else -> copyUntilClosed(input, output)
            }
        }
        output.flush()
        return ProxyOutcome(status = status, bytes = bytes)
    }

    private fun copyExactly(input: InputStream, output: OutputStream, length: Long): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var remaining = length
        while (remaining > 0) {
            val size = minOf(buffer.size.toLong(), remaining).toInt()
            val read = input.read(buffer, 0, size)
            if (read < 0) throw IOException("The backend body ended early")
            output.write(buffer, 0, read)
            remaining -= read
        }
        return length
    }

    private fun copyUntilClosed(input: InputStream, output: OutputStream): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return total
            output.write(buffer, 0, read)
            total += read
        }
    }

    private fun copyChunked(input: InputStream, output: OutputStream): Long {
        var total = 0L
        while (true) {
            val line = HttpRequestLines.readLine(input, MAX_CHUNK_LINE, "Upstream chunk size")
                ?: throw IOException("The backend chunked body ended early")
            val size = line.substringBefore(';').trim().toIntOrNull(16)
                ?: throw IOException("The backend sent an unreadable chunk size")
            if (size == 0) {
                while (true) {
                    val trailer = HttpRequestLines.readLine(input, MAX_HEADER_LINE, "Upstream trailer")
                        ?: break
                    if (trailer.isEmpty()) break
                }
                return total
            }
            copyExactly(input, output, size.toLong())
            val crlf = HttpRequestLines.readLine(input, 2, "Upstream chunk terminator")
            if (!crlf.isNullOrEmpty()) {
                throw IOException("The backend sent a malformed chunk terminator")
            }
            total += size
        }
    }

    companion object {
        const val MAX_BODY_BYTES = 64L * 1024 * 1024
        const val UPSTREAM_HOST = "127.0.0.1"

        private const val CONNECT_TIMEOUT_MILLIS = 3_000
        private const val READ_TIMEOUT_MILLIS = 120_000
        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_STATUS_LINE = 1024
        private const val MAX_HEADER_LINE = 8 * 1024
        private const val MAX_HEADER_BYTES = 32 * 1024
        private const val MAX_CHUNK_LINE = 64

        private val HOP_BY_HOP_HEADERS = setOf(
            "connection",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade"
        )
    }
}

/** One request ready to hand to a backend. */
data class ProxyRequest(
    val method: String,
    val target: String,
    val headers: Map<String, String>,
    val body: InputStream,
    val bodyLength: Long,
    val clientAddress: String,
    val scheme: String,
    val headOnly: Boolean
) {
    /** The Host header the backend should see, defaulting to the proxy's own loopback port. */
    val hostHeader: String = headers["host"]?.takeIf { it.isNotBlank() } ?: "127.0.0.1"

    val forwardedHost: String? = headers["host"]?.takeIf { it.isNotBlank() }
}

/** What one proxied request served, for the app's own counters and log. */
data class ProxyOutcome(
    val status: Int,
    val bytes: Long
)
