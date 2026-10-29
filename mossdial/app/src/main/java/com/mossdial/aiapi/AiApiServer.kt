package com.mossdial.aiapi

import com.mossdial.server.DeadlineInputStream
import com.mossdial.server.HttpLineTooLargeException
import com.mossdial.server.HttpRequestLines
import com.mossdial.server.LocalTlsContext
import com.mossdial.server.RateLimitKeys
import com.mossdial.server.RateLimiter
import com.mossdial.server.RequestDeadline
import com.mossdial.server.TlsIdentity
import com.mossdial.server.WatchdogOutputStream
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A second, minimal HTTP listener that speaks the shape of the OpenAI chat API to whatever model
 * the AI tab has loaded.
 *
 * It is a plain [ServerSocket] on its own port, not a route inside the file server, for two
 * reasons: an inference can occupy a worker for a long time and must not be able to hold up the
 * site, and the two have different trust shapes. The site serves files the user chose to publish;
 * this one runs the model, so every route including the health check is behind a bearer token,
 * even on loopback.
 *
 * Every request is bounded and single-use. Headers and the body are capped, a request that stops
 * part way through is answered with a status instead of being waited on, connections are served by
 * a fixed pool with a bounded queue, and a peer that arrives when the pool is full has its socket
 * closed rather than being queued without limit. There is no keep-alive: one request per connection
 * keeps the state a client can pin here as small as it can be made.
 */
class AiApiServer(
    private val config: AiApiConfig,
    private val token: String,
    private val backend: AiApiBackend,
    private val onRequest: (AiApiRequest) -> Unit = {},
    /**
     * Where TLS gets its identity. Supplied rather than created here for the same reason as the
     * file server: the certificate has to survive a restart, and only the app knows where the
     * device keeps its key. A host JVM gets a throwaway identity.
     */
    private val tlsIdentity: () -> TlsIdentity = { TlsIdentity.inMemory() }
) : Closeable {
    companion object {
        private const val BUFFER_SIZE = 32 * 1024
        private const val MAX_LOG_PATH = 256
        private const val HEX = "0123456789abcdef"
        private const val HEALTH_PATH = "/health"
        private const val MODELS_PATH = "/v1/models"
        private const val COMPLETIONS_PATH = "/v1/chat/completions"
        private const val EMBEDDINGS_PATH = "/v1/embeddings"

        /** Ten failed accepts in a row is a broken listener, not a transient client error. */
        private const val MAX_ACCEPT_FAILURES = 10
        private const val ACCEPT_RETRY_BACKOFF_MILLIS = 100L
        private val EMPTY_BODY = ByteArray(0)
        private val HTTP_VERSIONS = setOf("HTTP/1.0", "HTTP/1.1")
        private val SINGLETON_HEADERS =
            setOf("authorization", "content-length", "content-type", "host", "connection")
        private val REASONS = mapOf(
            200 to "OK",
            400 to "Bad Request",
            401 to "Unauthorized",
            404 to "Not Found",
            405 to "Method Not Allowed",
            408 to "Request Timeout",
            409 to "Conflict",
            411 to "Length Required",
            413 to "Content Too Large",
            429 to "Too Many Requests",
            431 to "Request Header Fields Too Large",
            500 to "Internal Server Error",
            503 to "Service Unavailable",
            504 to "Gateway Timeout"
        )
    }

    private val running = AtomicBoolean(false)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val rateLimiter = RateLimiter(
        requestLimit = config.rateLimitRequests,
        windowMillis = config.rateLimitWindowMillis,
        maxClients = config.maxRateLimitClients
    )
    private val random = SecureRandom()
    private var serverSocket: ServerSocket? = null
    private var executor: ThreadPoolExecutor? = null
    private var acceptThread: Thread? = null
    private var startedAtMillis = 0L

    val isRunning: Boolean
        get() = running.get()

    /**
     * True only while the accept thread is alive.
     *
     * [isRunning] is the flag stop() clears; it would stay true if the accept loop died of a
     * throwable the loop does not catch. The service's watchdog uses this to tell "serving" from
     * "the flag still says so", exactly as it does for the file server.
     */
    val isServing: Boolean
        get() = running.get() && acceptThread?.isAlive == true

    @Synchronized
    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            require(token.isNotEmpty()) { "The AI API needs a bearer token" }
            val socket = if (config.enableTls) {
                LocalTlsContext.create(tlsIdentity()).createServerSocket(
                    config.bindAddress,
                    config.port,
                    config.maxConnections
                )
            } else {
                ServerSocket(
                    config.port,
                    config.maxConnections,
                    InetAddress.getByName(config.bindAddress)
                )
            }
            val worker = ThreadPoolExecutor(
                config.workerThreads,
                config.workerThreads,
                0L,
                TimeUnit.MILLISECONDS,
                ArrayBlockingQueue(config.maxConnections),
                { task -> Thread(task, "MossdialAiApiWorker").apply { isDaemon = true } },
                ThreadPoolExecutor.AbortPolicy()
            )
            serverSocket = socket
            executor = worker
            startedAtMillis = System.currentTimeMillis()
            acceptThread = Thread({ acceptLoop(socket, worker) }, "MossdialAiApiAccept").apply {
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
                // The queue is full, so this connection is not served at all. Closing it here is
                // the bound: a client that keeps opening connections is told no, not remembered.
                sockets -= socket
                runCatching { socket.close() }
            }
        }
    }

    private fun handle(socket: Socket) {
        val started = System.nanoTime()
        var method = "-"
        var path = "-"
        var status = 0
        try {
            socket.soTimeout = config.readTimeoutMillis
            socket.tcpNoDelay = true
            val input = BufferedInputStream(
                DeadlineInputStream(
                    input = socket.getInputStream(),
                    deadline = RequestDeadline(config.readTimeoutMillis),
                    armReadTimeout = { remaining -> socket.soTimeout = remaining }
                ),
                BUFFER_SIZE
            )
            val output = socket.getOutputStream()

            val line = HttpRequestLines.readLine(input, config.maxRequestLineBytes) ?: return
            val parts = line.split(' ', limit = 3)
            if (parts.size != 3 ||
                !HttpRequestLines.isToken(parts[0]) ||
                !parts[1].startsWith("/") ||
                parts[2] !in HTTP_VERSIONS
            ) {
                status = write(
                    socket,
                    output,
                    error(400, "bad_request", "invalid_request_error", "Malformed request line")
                )
                return
            }
            method = parts[0]
            val target = parts[1].substringBefore('?')
            path = logPath(target)

            val headers = readHeaders(input)
            if (!rateLimiter.tryAcquire(RateLimitKeys.fingerprint(socket.inetAddress))) {
                status = write(
                    socket,
                    output,
                    error(
                        429,
                        "rate_limited",
                        "server_error",
                        "Too many requests in this window",
                        mapOf("Retry-After" to ((config.rateLimitWindowMillis + 999L) / 1_000L).toString())
                    )
                )
                return
            }
            if (!AiApiToken.matches(token, headers["authorization"])) {
                status = write(
                    socket,
                    output,
                    error(
                        401,
                        "unauthorized",
                        "invalid_request_error",
                        "A valid bearer token is required",
                        mapOf("WWW-Authenticate" to AiApiToken.SCHEME)
                    )
                )
                return
            }
            val body = readBody(input, headers, method)
            status = write(socket, output, route(method, target, body))
        } catch (failure: AiApiProtocolError) {
            status = writeQuietly(socket, failure.response())
        } catch (_: SocketTimeoutException) {
            status = writeQuietly(
                socket,
                error(408, "request_timeout", "invalid_request_error", "The request took too long")
            )
        } catch (_: IOException) {
            // The peer went away part way through. There is nobody left to answer.
        } finally {
            val millis = (System.nanoTime() - started) / 1_000_000
            runCatching { onRequest(AiApiRequest(method, path, status, millis)) }
        }
    }

    private fun readHeaders(input: InputStream): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        var total = 0
        var count = 0
        while (true) {
            val line = try {
                HttpRequestLines.readLine(input, config.maxHeaderBytes, "Header line")
            } catch (_: HttpLineTooLargeException) {
                throw AiApiProtocolError(431, "headers_too_large", "Too many header bytes")
            } catch (_: SocketTimeoutException) {
                throw AiApiProtocolError(408, "request_timeout", "The headers took too long")
            } catch (_: IOException) {
                throw AiApiProtocolError(400, "bad_headers", "The header block could not be read")
            } ?: throw AiApiProtocolError(400, "bad_headers", "The header block ended early")
            total += line.toByteArray(StandardCharsets.UTF_8).size + 2
            count++
            if (total > config.maxHeaderBytes || count > config.maxHeaders) {
                throw AiApiProtocolError(431, "headers_too_large", "Too many header bytes")
            }
            if (line.isEmpty()) return headers
            val separator = line.indexOf(':')
            if (separator <= 0) throw AiApiProtocolError(400, "bad_headers", "A header has no name")
            val name = line.substring(0, separator).trim().lowercase()
            val value = line.substring(separator + 1).trim()
            if (!HttpRequestLines.isToken(name) ||
                value.any { (it.code < 0x20 && it != '\t') || it.code == 0x7f }
            ) {
                throw AiApiProtocolError(400, "bad_headers", "A header is malformed")
            }
            if (name in SINGLETON_HEADERS && headers.containsKey(name)) {
                throw AiApiProtocolError(400, "bad_headers", "Duplicate $name header")
            }
            headers[name] = value
        }
    }

    private fun readBody(input: InputStream, headers: Map<String, String>, method: String): ByteArray {
        if (headers.containsKey("transfer-encoding")) {
            throw AiApiProtocolError(400, "unsupported_body", "Chunked request bodies are not supported")
        }
        val declared = headers["content-length"]
        val length = when {
            declared == null -> if (method == "POST") -1L else 0L
            else -> declared.toLongOrNull()
                ?: throw AiApiProtocolError(400, "bad_headers", "Content-Length is not a number")
        }
        if (length < 0) throw AiApiProtocolError(411, "length_required", "Content-Length is required")
        if (length > config.maxBodyBytes) {
            throw AiApiProtocolError(413, "body_too_large", "The body is over ${config.maxBodyBytes} bytes")
        }
        if (length == 0L) return EMPTY_BODY
        if (method != "POST") {
            throw AiApiProtocolError(400, "unexpected_body", "This endpoint does not take a body")
        }
        val body = ByteArray(length.toInt())
        var read = 0
        while (read < body.size) {
            val count = try {
                input.read(body, read, body.size - read)
            } catch (_: SocketTimeoutException) {
                throw AiApiProtocolError(408, "request_timeout", "The body took too long")
            }
            if (count <= 0) throw AiApiProtocolError(400, "incomplete_body", "The body ended early")
            read += count
        }
        return body
    }

    private fun route(method: String, target: String, body: ByteArray): AiApiResponse = try {
        when {
            target == HEALTH_PATH && method == "GET" -> health()
            target == MODELS_PATH && method == "GET" -> models()
            target == COMPLETIONS_PATH && method == "POST" -> chat(body)
            target == EMBEDDINGS_PATH && method == "POST" -> embeddings(body)
            target == EMBEDDINGS_PATH -> error(
                405,
                "method_not_allowed",
                "invalid_request_error",
                "This endpoint is written to; use POST",
                mapOf("Allow" to "POST")
            )
            target == HEALTH_PATH || target == MODELS_PATH -> error(
                405,
                "method_not_allowed",
                "invalid_request_error",
                "This endpoint is read-only; use GET",
                mapOf("Allow" to "GET")
            )
            target == COMPLETIONS_PATH -> error(
                405,
                "method_not_allowed",
                "invalid_request_error",
                "This endpoint is written to; use POST",
                mapOf("Allow" to "POST")
            )
            else -> error(404, "not_found", "invalid_request_error", "No such endpoint")
        }
    } catch (failure: AiApiBadRequest) {
        error(400, "invalid_request", "invalid_request_error", failure.message ?: "The request could not be read")
    } catch (failure: AiApiFailure) {
        when (failure) {
            is AiApiFailure.Busy -> error(409, "busy", "server_error", failure.message.orEmpty())
            is AiApiFailure.Unavailable -> error(503, "unavailable", "server_error", failure.message.orEmpty())
            is AiApiFailure.TimedOut -> error(504, "timeout", "server_error", failure.message.orEmpty())
            is AiApiFailure.Failed -> error(500, "generation_failed", "server_error", failure.message.orEmpty())
        }
    }

    private fun health(): AiApiResponse = AiApiResponse(
        200,
        AiApiJson.health(
            status = "ok",
            model = backend.activeModel(),
            runtimeStatus = backend.runtimeStatus(),
            uptimeSeconds = (System.currentTimeMillis() - startedAtMillis) / 1_000L,
            embeddingModel = backend.activeEmbeddingModel()
        )
    )

    private fun models(): AiApiResponse = AiApiResponse(
        200,
        AiApiJson.models(backend.models(), System.currentTimeMillis() / 1_000L)
    )

    private fun chat(body: ByteArray): AiApiResponse {
        if (body.isEmpty()) throw AiApiBadRequest("The request body is empty")
        val request = AiApiJson.parseChatRequest(body)
        val loaded = backend.activeModel() ?: throw AiApiFailure.Unavailable("No model is loaded")
        val content = backend.generate(
            turns = request.messages,
            maxTokens = request.maxTokens ?: config.defaultMaxTokens,
            timeoutMillis = config.generationTimeoutMillis
        )
        return AiApiResponse(
            200,
            AiApiJson.chatCompletion(
                id = nextRequestId(),
                created = System.currentTimeMillis() / 1_000L,
                model = request.model?.takeIf { it.isNotBlank() } ?: loaded,
                content = content,
                finishReason = "stop"
            )
        )
    }

    /**
     * One embedding per input, in order, because this runtime is single threaded: a batch would mean
     * either a second session or a graph that takes several rows, and neither is worth the state.
     */
    private fun embeddings(body: ByteArray): AiApiResponse {
        if (body.isEmpty()) throw AiApiBadRequest("The request body is empty")
        val request = AiApiJson.parseEmbeddingRequest(body)
        val selected = backend.activeEmbeddingModel()
            ?: throw AiApiFailure.Unavailable("No ONNX encoder is selected in the AI tab")
        val vectors = ArrayList<FloatArray>(request.inputs.size)
        // One deadline covers the whole batch. Passing the full timeout to every input would let a
        // request of sixteen inputs occupy the worker for sixteen times the configured budget.
        val deadlineNanos = System.nanoTime() +
            TimeUnit.MILLISECONDS.toNanos(config.generationTimeoutMillis)
        for (text in request.inputs) {
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0) {
                throw AiApiFailure.TimedOut(
                    "The batch did not finish within ${config.generationTimeoutMillis / 1_000L}s"
                )
            }
            val remainingMillis = maxOf(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos))
            vectors += backend.embed(text, remainingMillis)
        }
        return AiApiResponse(
            200,
            AiApiJson.embeddings(
                model = request.model?.takeIf { it.isNotBlank() } ?: selected,
                vectors = vectors
            )
        )
    }

    private fun error(
        status: Int,
        code: String,
        type: String,
        message: String,
        headers: Map<String, String> = emptyMap()
    ): AiApiResponse = AiApiResponse(status, AiApiJson.error(message, type, code), headers)

    private fun write(socket: Socket, output: OutputStream, response: AiApiResponse): Int {
        val body = response.body.toByteArray(StandardCharsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(response.status).append(' ')
            append(REASONS[response.status] ?: "Error").append("\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            response.headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Connection: close\r\n\r\n")
        }
        // A write has no timeout in Java, so a stalled reader is released by the watchdog closing
        // the socket. Every completed chunk re-arms it, which is what lets a slow response finish.
        val timed = WatchdogOutputStream(socket, output, config.writeTimeoutMillis.toLong())
        try {
            timed.write(head.toByteArray(StandardCharsets.US_ASCII))
            timed.write(body)
            timed.flush()
        } finally {
            timed.disarm()
        }
        return response.status
    }

    private fun writeQuietly(socket: Socket, response: AiApiResponse): Int = try {
        write(socket, socket.getOutputStream(), response)
    } catch (_: IOException) {
        response.status
    }

    private fun logPath(target: String): String {
        val sanitized = buildString(minOf(target.length, MAX_LOG_PATH)) {
            for (character in target.take(MAX_LOG_PATH)) {
                if (character.code >= 0x20 && character.code != 0x7f) append(character)
            }
        }
        val bounded = if (sanitized.length < target.length) "$sanitized..." else sanitized
        return bounded.ifEmpty { "/" }
    }

    private fun nextRequestId(): String {
        val bytes = ByteArray(12)
        random.nextBytes(bytes)
        return buildString {
            append("chatcmpl-")
            for (byte in bytes) {
                val value = byte.toInt() and 0xFF
                append(HEX[value ushr 4])
                append(HEX[value and 0xF])
            }
        }
    }

    /** A request the listener refuses before it reaches the model, with the status to answer. */
    private class AiApiProtocolError(val status: Int, val code: String, val reason: String) :
        IOException(reason) {
        fun response(): AiApiResponse = AiApiResponse(
            status,
            AiApiJson.error(reason, "invalid_request_error", code)
        )
    }
}

/** What the API served, for the app's own log line. Carries no request or token material. */
data class AiApiRequest(
    val method: String,
    val path: String,
    val status: Int,
    val millis: Long
)

private class AiApiResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap()
)
