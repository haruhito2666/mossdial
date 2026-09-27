package com.mossdial.server

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPOutputStream

class LocalHttpServer(
    private val config: ServerConfig,
    private val root: File,
    private val onRequest: (Request) -> Unit = {},
    /**
     * Where TLS gets its key. Supplied rather than created here, because the identity has to survive
     * a restart and only the app, not this class, knows where a device keeps one. The default is the
     * in-memory identity, which is what a host JVM test gets.
     */
    private val tlsIdentity: () -> TlsIdentity = { TlsIdentity.inMemory() }
) : Closeable {
    companion object {
        private const val MAX_REQUEST_LINE = 8 * 1024
        private const val MAX_HEADER_LINE = 8 * 1024
        private const val MAX_HEADERS = 64
        private const val MAX_HEADER_BYTES = 32 * 1024
        private const val MAX_LOG_PATH = 512
        private const val BUFFER_SIZE = 64 * 1024
    }

    private val running = AtomicBoolean(false)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val rateLimiter = RateLimiter(
        requestLimit = config.rateLimitRequests,
        windowMillis = config.rateLimitWindowMillis,
        maxClients = config.maxRateLimitClients
    )
    private val requestStatistics = RequestStatistics(config.requestLogCapacity)
    private var serverSocket: ServerSocket? = null
    private var tlsContext: LocalTlsContext? = null
    private var executor: ThreadPoolExecutor? = null
    private var acceptThread: Thread? = null

    val isRunning: Boolean
        get() = running.get()

    fun statisticsSnapshot(): ServerStatisticsSnapshot =
        requestStatistics.snapshot(rateLimiter.trackedClientCount())

    @Synchronized
    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            require(root.isDirectory) { "Document root does not exist" }
            val socket = if (config.enableTls) {
                LocalTlsContext.create(tlsIdentity()).also { tlsContext = it }.createServerSocket(
                    config.bindAddress,
                    config.port,
                    config.maxConnections
                )
            } else {
                ServerSocket(config.port, config.maxConnections, InetAddress.getByName(config.bindAddress))
            }
            val worker = ThreadPoolExecutor(
                config.workerThreads,
                config.workerThreads,
                0L,
                TimeUnit.MILLISECONDS,
                ArrayBlockingQueue(config.maxConnections),
                { task -> Thread(task, "MossdialHttpWorker").apply { isDaemon = true } },
                ThreadPoolExecutor.AbortPolicy()
            )
            serverSocket = socket
            executor = worker
            acceptThread = Thread({ acceptLoop(socket, worker) }, "MossdialHttpAccept").apply {
                isDaemon = true
                start()
            }
        } catch (error: Throwable) {
            running.set(false)
            serverSocket?.close()
            executor?.shutdownNow()
            executor = null
            throw error
        }
    }

    @Synchronized
    fun stop() {
        running.set(false)
        serverSocket?.close()
        serverSocket = null
        tlsContext = null
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        executor?.shutdownNow()
        executor = null
        acceptThread?.interrupt()
        acceptThread = null
    }

    override fun close() = stop()

    private fun acceptLoop(server: ServerSocket, worker: ThreadPoolExecutor) {
        while (running.get() && !server.isClosed) {
            val socket = try {
                server.accept()
            } catch (_: IOException) {
                if (running.get()) continue else break
            }
            sockets += socket
            try {
                worker.execute {
                    try {
                        socket.use { handle(it) }
                    } catch (_: IOException) {
                    } finally {
                        sockets -= socket
                    }
                }
            } catch (_: RuntimeException) {
                sockets -= socket
                runCatching { socket.close() }
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = config.requestTimeoutMillis
        val input = BufferedInputStream(socket.getInputStream())
        val output = socket.getOutputStream()
        val clientKey = RateLimitKeys.fingerprint(socket.inetAddress)
        val requestLine = readLine(input, MAX_REQUEST_LINE) ?: return
        val parts = requestLine.split(' ', limit = 3)
        val method = parts.getOrNull(0)?.takeIf { it == "GET" || it == "HEAD" } ?: "-"
        val target = parts.getOrNull(1)?.substringBefore('?')
        val loggedPath = logPath(target)
        if (!rateLimiter.tryAcquire(clientKey)) {
            val retryAfter = (config.rateLimitWindowMillis + 999L) / 1_000L
            val bytes = sendError(
                output = output,
                status = 429,
                message = "Too Many Requests",
                headOnly = method == "HEAD",
                extraHeaders = mapOf("Retry-After" to retryAfter.toString())
            )
            recordRequest(method, loggedPath, 429, bytes)
            return
        }
        if (parts.size != 3 || !isToken(parts[0]) || !parts[1].startsWith("/") || parts[2] !in setOf("HTTP/1.0", "HTTP/1.1")) {
            val bytes = sendError(output, 400, "Bad Request", method == "HEAD")
            recordRequest(method, loggedPath, 400, bytes)
            return
        }
        val headers = readHeaders(input)
        if (headers.containsKey("transfer-encoding")) {
            val bytes = sendError(output, 400, "Bad Request", method == "HEAD")
            recordRequest(method, loggedPath, 400, bytes)
            return
        }
        headers["content-length"]?.let { value ->
            if (value.toLongOrNull() != 0L) {
                val bytes = sendError(output, 400, "Bad Request", method == "HEAD")
                recordRequest(method, loggedPath, 400, bytes)
                return
            }
        }
        if (method == "-") {
            val bytes = sendError(output, 405, "Method Not Allowed", false)
            recordRequest(method, loggedPath, 405, bytes)
            return
        }
        val file = StaticFiles.resolve(root, target!!)
        if (file == null) {
            val bytes = sendError(output, 404, "Not Found", method == "HEAD")
            recordRequest(method, loggedPath, 404, bytes)
            return
        }
        val length = file.length()
        val lastModified = file.lastModified()
        val contentType = StaticFiles.contentType(file)
        val etag = HttpCaching.formatEtag(length, lastModified)
        val lastModifiedHeader = HttpCaching.formatLastModified(lastModified)
        val notModified = if (headers.containsKey("if-none-match")) {
            HttpCaching.ifNoneMatchMatches(headers["if-none-match"], etag)
        } else {
            HttpCaching.ifModifiedSinceMatches(headers["if-modified-since"], lastModified)
        }
        if (notModified) {
            sendNotModified(output, etag, lastModifiedHeader, contentType)
            recordRequest(method, loggedPath, 304, 0L)
            return
        }
        val rangeResult = if (method == "GET" && !headers.containsKey("if-range")) {
            HttpRanges.parse(headers["range"], length)
        } else {
            RangeParseResult.NotRequested
        }
        if (rangeResult is RangeParseResult.Unsatisfiable) {
            val bytes = sendError(
                output = output,
                status = 416,
                message = "Range Not Satisfiable",
                headOnly = false,
                extraHeaders = mapOf(
                    "Accept-Ranges" to "bytes",
                    "Content-Range" to "bytes */$length",
                    "ETag" to etag
                )
            )
            recordRequest(method, loggedPath, 416, bytes)
            return
        }
        val range = (rangeResult as? RangeParseResult.Satisfiable)?.range
        val bytes = sendFile(
            output = output,
            file = file,
            resourceLength = length,
            lastModifiedHeader = lastModifiedHeader,
            etag = etag,
            contentType = contentType,
            acceptEncoding = headers["accept-encoding"],
            headOnly = method == "HEAD",
            range = range
        )
        recordRequest(method, loggedPath, if (range == null) 200 else 206, bytes)
    }

    private fun readHeaders(input: InputStream): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        var total = 0
        var count = 0
        while (true) {
            val line = readLine(input, MAX_HEADER_LINE, "Header line") ?: throw IOException("Malformed headers")
            total += line.toByteArray(StandardCharsets.UTF_8).size + 2
            count++
            if (total > MAX_HEADER_BYTES || count > MAX_HEADERS) throw IOException("Headers too large")
            if (line.isEmpty()) return headers
            val separator = line.indexOf(':')
            if (separator <= 0) throw IOException("Malformed headers")
            val name = line.substring(0, separator).trim().lowercase()
            val value = line.substring(separator + 1).trim()
            if (!isToken(name) || value.any { (it.code < 0x20 && it != '\t') || it.code == 0x7f }) {
                throw IOException("Malformed headers")
            }
            val singleton = name in setOf(
                "content-length",
                "transfer-encoding",
                "connection",
                "range",
                "if-none-match",
                "if-modified-since",
                "if-range",
                "accept-encoding"
            )
            if (singleton && headers.containsKey(name)) throw IOException("Duplicate header")
            headers[name] = value
        }
    }

    private fun readLine(input: InputStream, limit: Int, what: String = "Request line"): String? =
        HttpRequestLines.readLine(input, limit, what)

    private fun isToken(value: String): Boolean = HttpRequestLines.isToken(value)

    private fun sendFile(
        output: OutputStream,
        file: File,
        resourceLength: Long,
        lastModifiedHeader: String,
        etag: String,
        contentType: String,
        acceptEncoding: String?,
        headOnly: Boolean,
        range: ByteRange?
    ): Long {
        val compressible = GzipEncoding.isCompressible(contentType)
        val gzip = range == null && GzipEncoding.isEligible(contentType, resourceLength, acceptEncoding)
        val contentLength = when {
            range != null -> range.length
            gzip -> gzipContentLength(file)
            else -> resourceLength
        }
        val headers = buildString {
            append(if (range == null) "HTTP/1.1 200 OK\r\n" else "HTTP/1.1 206 Partial Content\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: $contentLength\r\n")
            append("ETag: $etag\r\n")
            append("Last-Modified: $lastModifiedHeader\r\n")
            append("Accept-Ranges: bytes\r\n")
            if (range != null) {
                append("Content-Range: bytes ${range.start}-${range.endInclusive}/$resourceLength\r\n")
            }
            if (gzip) append("Content-Encoding: gzip\r\n")
            if (compressible) append("Vary: Accept-Encoding\r\n")
            append("Cache-Control: no-cache\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Content-Security-Policy: default-src 'self'; object-src 'none'; base-uri 'none'\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(headers.toByteArray(StandardCharsets.US_ASCII))
        if (!headOnly) {
            if (gzip) {
                writeGzip(file, output)
            } else {
                writeIdentity(file, output, range?.start ?: 0L, contentLength)
            }
        }
        output.flush()
        return if (headOnly) 0L else contentLength
    }

    private fun sendNotModified(
        output: OutputStream,
        etag: String,
        lastModifiedHeader: String,
        contentType: String
    ) {
        val headers = buildString {
            append("HTTP/1.1 304 Not Modified\r\n")
            append("ETag: $etag\r\n")
            append("Last-Modified: $lastModifiedHeader\r\n")
            append("Accept-Ranges: bytes\r\n")
            if (GzipEncoding.isCompressible(contentType)) append("Vary: Accept-Encoding\r\n")
            append("Cache-Control: no-cache\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Content-Security-Policy: default-src 'self'; object-src 'none'; base-uri 'none'\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(headers.toByteArray(StandardCharsets.US_ASCII))
        output.flush()
    }

    private fun gzipContentLength(file: File): Long {
        val output = CountingOutputStream()
        writeGzip(file, output)
        return output.count
    }

    private fun writeGzip(file: File, output: OutputStream) {
        GZIPOutputStream(NonClosingOutputStream(output), BUFFER_SIZE).use { gzip ->
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    gzip.write(buffer, 0, count)
                }
            }
        }
    }

    private fun writeIdentity(file: File, output: OutputStream, start: Long, length: Long) {
        file.inputStream().use { input ->
            var remainingStart = start
            while (remainingStart > 0) {
                val skipped = input.skip(remainingStart)
                if (skipped > 0) {
                    remainingStart -= skipped
                } else if (input.read() < 0) {
                    throw IOException("Unexpected end of file")
                } else {
                    remainingStart--
                }
            }
            var remaining = length
            val buffer = ByteArray(BUFFER_SIZE)
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (count < 0) throw IOException("Unexpected end of file")
                output.write(buffer, 0, count)
                remaining -= count
            }
        }
    }

    private fun sendError(
        output: OutputStream,
        status: Int,
        message: String,
        headOnly: Boolean,
        extraHeaders: Map<String, String> = emptyMap()
    ): Long {
        val body = "<!doctype html><meta charset=utf-8><title>$status</title><h1>$status</h1><p>$message</p>".toByteArray(StandardCharsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 $status $message\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ${body.size}\r\n")
            extraHeaders.forEach { (name, value) -> append("$name: $value\r\n") }
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(headers.toByteArray(StandardCharsets.US_ASCII))
        if (!headOnly) output.write(body)
        output.flush()
        return if (headOnly) 0L else body.size.toLong()
    }

    private fun recordRequest(method: String, path: String, status: Int, bytes: Long) {
        requestStatistics.record(
            entry = RequestLogEntry(method, path, status, bytes, System.currentTimeMillis()),
            retainRequest = config.enableLogging
        )
        if (config.enableLogging) {
            runCatching { onRequest(Request(method, path, status, bytes)) }
        }
    }

    private fun logPath(target: String?): String {
        val path = target?.substringBefore('?') ?: "-"
        val sanitized = buildString(minOf(path.length, MAX_LOG_PATH)) {
            for (character in path.take(MAX_LOG_PATH)) {
                if (character.code >= 0x20 && character.code != 0x7f) append(character)
            }
        }
        val bounded = if (sanitized.length < path.length) sanitized + "..." else sanitized
        return bounded.ifEmpty { "-" }
    }

    private class NonClosingOutputStream(output: OutputStream) : FilterOutputStream(output) {
        override fun close() {
            flush()
        }
    }

    private class CountingOutputStream : OutputStream() {
        var count = 0L
            private set

        override fun write(value: Int) {
            count++
        }

        override fun write(value: ByteArray, offset: Int, length: Int) {
            count += length
        }
    }
}

data class Request(
    val method: String,
    val path: String,
    val status: Int,
    val bytes: Long
)
