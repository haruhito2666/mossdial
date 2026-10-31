package com.mossdial.server

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
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
    private val tlsIdentity: () -> TlsIdentity = { TlsIdentity.inMemory() },
    /**
     * Whether a loopback peer's `CF-Connecting-IP` may stand in for its own address.
     *
     * This is only on while the user has enabled the tunnel: cloudflared connects over loopback, so
     * without it every tunnelled visitor shares the one loopback bucket. A remote peer can never
     * use the header, and a local one only while the feature that needs it is on.
     */
    private val trustProxyHeaders: () -> Boolean = { false }
) : Closeable {
    private val proxy: ReverseProxy? = config.backendPort
        .takeIf { it != 0 }
        ?.let { ReverseProxy(it, config.backendMaxBodyBytes) }

    companion object {
        private const val MAX_REQUEST_LINE = 8 * 1024
        private const val MAX_HEADER_LINE = 8 * 1024
        private const val MAX_HEADERS = 64
        private const val MAX_HEADER_BYTES = 32 * 1024
        private const val MAX_LOG_PATH = 512
        private const val BUFFER_SIZE = 64 * 1024

        /** Ten failed accepts in a row is a broken listener, not a transient client error. */
        private const val MAX_ACCEPT_FAILURES = 10
        private const val ACCEPT_RETRY_BACKOFF_MILLIS = 100L
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

    /**
     * True only while the accept thread is actually alive.
     *
     * [isRunning] is the flag stop() clears; it would stay true if the accept loop died of a
     * throwable the loop does not catch. The service's watchdog uses this to tell "serving" from
     * "the flag still says so".
     */
    val isServing: Boolean
        get() = running.get() && acceptThread?.isAlive == true

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
        var consecutiveFailures = 0
        while (running.get() && !server.isClosed) {
            val socket = try {
                server.accept().also { consecutiveFailures = 0 }
            } catch (_: IOException) {
                if (!running.get() || server.isClosed) break
                // A listener that cannot accept is worse than one that is down: the former burns a
                // core retrying, the latter is noticed by the service's watchdog and replaced. A
                // short backoff covers a transient error; ten of them in a row means the socket is
                // broken, so stop the loop and let the watchdog reopen it.
                consecutiveFailures += 1
                if (consecutiveFailures >= MAX_ACCEPT_FAILURES) {
                    running.set(false)
                    break
                }
                try {
                    Thread.sleep(ACCEPT_RETRY_BACKOFF_MILLIS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                continue
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
        val input = BufferedInputStream(
            DeadlineInputStream(
                input = socket.getInputStream(),
                deadline = RequestDeadline(config.requestTimeoutMillis),
                armReadTimeout = { remaining -> socket.soTimeout = remaining }
            )
        )
        val output = socket.getOutputStream()
        val requestLine = readLine(input, MAX_REQUEST_LINE) ?: return
        val parts = requestLine.split(' ', limit = 3)
        // A backend may need the methods that carry or change state; the file server does not.
        val allowedMethods = if (proxy != null) {
            setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        } else {
            setOf("GET", "HEAD")
        }
        val method = parts.getOrNull(0)?.takeIf { it in allowedMethods } ?: "-"
        val target = parts.getOrNull(1)?.substringBefore('?')
        val loggedPath = logPath(target)
        val headers = readHeaders(input)
        // The rate limit is taken after the headers because only then is the real client known
        // when the request came through the tunnel. The cost is that a malformed request line has
        // still had its (bounded) headers read before it is refused.
        if (!rateLimiter.tryAcquire(clientKey(socket, headers))) {
            val retryAfter = (config.rateLimitWindowMillis + 999L) / 1_000L
            val bytes = withWriteTimeout(socket, output) { timed ->
                sendError(
                    output = timed,
                    status = 429,
                    message = "Too Many Requests",
                    headOnly = method == "HEAD",
                    extraHeaders = mapOf("Retry-After" to retryAfter.toString())
                )
            }
            recordRequest(method, loggedPath, 429, bytes)
            return
        }
        if (parts.size != 3 || !isToken(parts[0]) || !parts[1].startsWith("/") || parts[2] !in setOf("HTTP/1.0", "HTTP/1.1")) {
            val bytes = withWriteTimeout(socket, output) {
                sendError(output = it, status = 400, message = "Bad Request", headOnly = method == "HEAD")
            }
            recordRequest(method, loggedPath, 400, bytes)
            return
        }
        if (headers.containsKey("transfer-encoding")) {
            val bytes = withWriteTimeout(socket, output) {
                sendError(output = it, status = 400, message = "Bad Request", headOnly = method == "HEAD")
            }
            recordRequest(method, loggedPath, 400, bytes)
            return
        }
        val bodyLength = headers["content-length"]?.toLongOrNull()
        if (proxy == null) {
            headers["content-length"]?.let { value ->
                if (value.toLongOrNull() != 0L) {
                    val bytes = withWriteTimeout(socket, output) {
                        sendError(output = it, status = 400, message = "Bad Request", headOnly = method == "HEAD")
                    }
                    recordRequest(method, loggedPath, 400, bytes)
                    return
                }
            }
        } else {
            when {
                bodyLength != null && bodyLength < 0 -> {
                    val bytes = withWriteTimeout(socket, output) {
                        sendError(output = it, status = 400, message = "Bad Request", headOnly = method == "HEAD")
                    }
                    recordRequest(method, loggedPath, 400, bytes)
                    return
                }
                bodyLength != null && bodyLength > config.backendMaxBodyBytes -> {
                    val bytes = withWriteTimeout(socket, output) {
                        sendError(
                            output = it,
                            status = 413,
                            message = "Request Entity Too Large",
                            headOnly = method == "HEAD"
                        )
                    }
                    recordRequest(method, loggedPath, 413, bytes)
                    return
                }
                method !in setOf("GET", "HEAD") && bodyLength == null -> {
                    val bytes = withWriteTimeout(socket, output) {
                        sendError(output = it, status = 411, message = "Length Required", headOnly = false)
                    }
                    recordRequest(method, loggedPath, 411, bytes)
                    return
                }
            }
        }
        if (method == "-") {
            val bytes = withWriteTimeout(socket, output) {
                sendError(output = it, status = 405, message = "Method Not Allowed", headOnly = false)
            }
            recordRequest(method, loggedPath, 405, bytes)
            return
        }
        val activeProxy = proxy
        if (activeProxy != null) {
            val outcome = try {
                withWriteTimeout(socket, output) { timed ->
                    activeProxy.forward(
                        request = ProxyRequest(
                            method = method,
                            target = target!!,
                            headers = headers,
                            body = input,
                            bodyLength = bodyLength ?: 0L,
                            clientAddress = socket.inetAddress.hostAddress ?: "-",
                            scheme = if (config.enableTls) "https" else "http",
                            headOnly = method == "HEAD"
                        ),
                        output = timed
                    )
                }
            } catch (_: IOException) {
                val bytes = withWriteTimeout(socket, output) {
                    sendError(
                        output = it,
                        status = 502,
                        message = "Bad Gateway",
                        headOnly = method == "HEAD"
                    )
                }
                recordRequest(method, loggedPath, 502, bytes)
                return
            }
            recordRequest(method, loggedPath, outcome.status, outcome.bytes)
            return
        }
        val file = StaticFiles.resolveSite(root, target!!, config.spaFallback, config.siteBasePath)
        if (file == null) {
            val bytes = withWriteTimeout(socket, output) {
                sendError(output = it, status = 404, message = "Not Found", headOnly = method == "HEAD")
            }
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
            withWriteTimeout(socket, output) { timed ->
                sendNotModified(timed, etag, lastModifiedHeader, contentType)
            }
            recordRequest(method, loggedPath, 304, 0L)
            return
        }
        val rangeResult = if (method == "GET" && !headers.containsKey("if-range")) {
            HttpRanges.parse(headers["range"], length)
        } else {
            RangeParseResult.NotRequested
        }
        if (rangeResult is RangeParseResult.Unsatisfiable) {
            val bytes = withWriteTimeout(socket, output) { timed ->
                sendError(
                    output = timed,
                    status = 416,
                    message = "Range Not Satisfiable",
                    headOnly = false,
                    extraHeaders = mapOf(
                        "Accept-Ranges" to "bytes",
                        "Content-Range" to "bytes */$length",
                        "ETag" to etag
                    )
                )
            }
            recordRequest(method, loggedPath, 416, bytes)
            return
        }
        val range = (rangeResult as? RangeParseResult.Satisfiable)?.range
        val bytes = withWriteTimeout(socket, output) { timed ->
            sendFile(
                output = timed,
                file = file,
                resourceLength = length,
                lastModifiedHeader = lastModifiedHeader,
                etag = etag,
                contentType = contentType,
                acceptEncoding = headers["accept-encoding"],
                headOnly = method == "HEAD",
                range = range
            )
        }
        recordRequest(method, loggedPath, if (range == null) 200 else 206, bytes)
    }

    /**
     * The bucket this request counts against.
     *
     * The socket's own address is the default. A loopback peer may present the tunnel's client
     * address instead, but only while the tunnel is enabled and only when the value is a literal
     * address, so the header can never trigger a name lookup or move the bucket away from the
     * actual visitor.
     */
    private fun clientKey(socket: Socket, headers: Map<String, String>): String {
        val peer = socket.inetAddress
        if (peer.isLoopbackAddress && trustProxyHeaders()) {
            headers["cf-connecting-ip"]
                ?.let { RateLimitKeys.fingerprintLiteral(it) }
                ?.let { return it }
        }
        return RateLimitKeys.fingerprint(peer)
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
                "accept-encoding",
                "cf-connecting-ip"
            )
            if (singleton && headers.containsKey(name)) throw IOException("Duplicate header")
            headers[name] = value
        }
    }

    private fun readLine(input: InputStream, limit: Int, what: String = "Request line"): String? =
        HttpRequestLines.readLine(input, limit, what)

    private fun isToken(value: String): Boolean = HttpRequestLines.isToken(value)

    /**
     * Writes one response under a watchdog that closes the socket when a write stops making
     * progress. The wrapper is disarmed as soon as the handler returns, so a completed response
     * is never closed by a late timer.
     */
    private fun <T> withWriteTimeout(
        socket: Socket,
        output: OutputStream,
        block: (OutputStream) -> T
    ): T {
        val timed = WatchdogOutputStream(socket, output, config.writeTimeoutMillis.toLong())
        return try {
            block(timed)
        } finally {
            timed.disarm()
        }
    }

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
        // One compression pass, used for both the length and the body. The old version compressed
        // the whole file once to count the bytes and a second time to send them, which doubled the
        // CPU per request and let a file change between the two passes.
        val compressed = if (gzip) gzipBytes(file) else null
        val contentLength = when {
            range != null -> range.length
            compressed != null -> compressed.size.toLong()
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
            append("Connection: close\r\n\r\n")
        }
        output.write(headers.toByteArray(StandardCharsets.US_ASCII))
        if (!headOnly) {
            if (compressed != null) {
                output.write(compressed)
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
            append("Connection: close\r\n\r\n")
        }
        output.write(headers.toByteArray(StandardCharsets.US_ASCII))
        output.flush()
    }

    private fun gzipBytes(file: File): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output, BUFFER_SIZE).use { gzip ->
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    // The length was checked before this call; a file that grew since then must
                    // not turn "compress this small file" into an unbounded allocation.
                    if (total > GzipEncoding.MAX_CONTENT_LENGTH) {
                        throw IOException("The file grew past the compression limit")
                    }
                    gzip.write(buffer, 0, count)
                }
            }
        }
        return output.toByteArray()
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
}

data class Request(
    val method: String,
    val path: String,
    val status: Int,
    val bytes: Long
)
