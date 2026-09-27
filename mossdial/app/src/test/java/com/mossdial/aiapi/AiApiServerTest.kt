package com.mossdial.aiapi

import com.mossdial.ai.ChatTurn
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * The listener end to end over a real socket, with a fake backend in place of the model.
 *
 * The point is the parts that only exist once bytes are moving: that the token is required before
 * anything else happens, that the bounds are refused rather than absorbed, and that a reply
 * containing JSON metacharacters still comes back as a body a client can parse.
 */
class AiApiServerTest {
    private var port = 0
    private lateinit var server: AiApiServer
    private lateinit var backend: FakeBackend
    private val token = AiApiToken.generate()

    @Before
    fun setUp() {
        port = ServerSocket(0).use { it.localPort }
        backend = FakeBackend()
        server = AiApiServer(
            config = AiApiConfig(
                port = port,
                bindAddress = "127.0.0.1",
                workerThreads = 2,
                maxBodyBytes = 2_048,
                readTimeoutMillis = 5_000
            ),
            token = token,
            backend = backend
        ).apply { start() }
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun healthReportsTheLoadedModelAndTheRuntime() {
        val response = request("GET", "/health", token = token)

        assertEquals(200, response.status)
        val body = AiApiJson.parse(response.body) as JsonValue.Obj
        assertEquals("ok", body.stringMember("status"))
        assertEquals("tiny.gguf", body.stringMember("model"))
        assertTrue(body.stringMember("runtime")!!.contains("test runtime"))
        assertTrue(response.headers.containsKey("Content-Type"))
        assertEquals("no-store", response.headers["Cache-Control"])
    }

    @Test
    fun modelsListsTheModelsAndMarksTheLoadedOne() {
        val response = request("GET", "/v1/models", token = token)

        assertEquals(200, response.status)
        val data = ((AiApiJson.parse(response.body) as JsonValue.Obj)["data"] as JsonValue.Arr).items
        assertEquals(listOf("tiny.gguf", "other.gguf"), data.map { (it as JsonValue.Obj).stringMember("id") })
        assertEquals(true, ((data.first() as JsonValue.Obj)["loaded"] as JsonValue.Bool).value)
        assertEquals(false, ((data.last() as JsonValue.Obj)["loaded"] as JsonValue.Bool).value)
    }

    @Test
    fun embeddingsAnswerWithTheOpenAiShape() {
        val response = request(
            "POST",
            "/v1/embeddings",
            body = """{"model":"local","input":"hello"}""",
            token = token
        )

        assertEquals(200, response.status)
        val body = AiApiJson.parse(response.body) as JsonValue.Obj
        assertEquals("list", body.stringMember("object"))
        assertEquals("local", body.stringMember("model"))
        val data = (body["data"] as JsonValue.Arr).items
        assertEquals(1, data.size)
        val entry = data.single() as JsonValue.Obj
        assertEquals("embedding", entry.stringMember("object"))
        assertEquals("0", (entry["index"] as JsonValue.Num).raw)
        val values = (entry["embedding"] as JsonValue.Arr).items.map { (it as JsonValue.Num).raw.toFloat() }
        assertEquals(listOf(0.5f, -0.25f, 1f), values)
        val usage = body["usage"] as JsonValue.Obj
        assertEquals("0", (usage["total_tokens"] as JsonValue.Num).raw)
        assertEquals(listOf("hello"), backend.embedded)
    }

    @Test
    fun embeddingsAcceptAnArrayOfInputsAndKeepTheirOrder() {
        val response = request(
            "POST",
            "/v1/embeddings",
            body = """{"input":["one","two"]}""",
            token = token
        )

        assertEquals(200, response.status)
        assertEquals(listOf("one", "two"), backend.embedded)
        val data = ((AiApiJson.parse(response.body) as JsonValue.Obj)["data"] as JsonValue.Arr).items
        assertEquals(2, data.size)
        assertEquals("1", ((data[1] as JsonValue.Obj)["index"] as JsonValue.Num).raw)
    }

    @Test
    fun embeddingsFallBackToTheSelectedEncoderAsTheModelName() {
        val response = request("POST", "/v1/embeddings", body = """{"input":"hi"}""", token = token)

        assertEquals("encoder.onnx", (AiApiJson.parse(response.body) as JsonValue.Obj).stringMember("model"))
    }

    @Test
    fun embeddingsRefuseAnUnusableRequest() {
        assertEquals(400, post("/v1/embeddings", "not json", token).status)
        assertEquals(400, post("/v1/embeddings", "{}", token).status)
        assertEquals(400, post("/v1/embeddings", "{\"input\":\"\"}", token).status)
        assertEquals(400, post("/v1/embeddings", """{"input":[],"input":"x"}""", token).status)
        assertEquals(400, post("/v1/embeddings", """{"input":[]}""", token).status)
        assertEquals(400, post("/v1/embeddings", """{"input":"  "}""", token).status)
        assertEquals(400, post("/v1/embeddings", """{"input":[1,2]}""", token).status)
        assertEquals(400, post("/v1/embeddings", """{"input":1}""", token).status)
        assertEquals(400, post("/v1/embeddings", """{"input":"x","model":1}""", token).status)
        assertEquals(400, post("/v1/embeddings", "", token).status)
        assertTrue(backend.embedded.isEmpty())
    }

    @Test
    fun embeddingsRefuseMoreInputsThanTheRuntimeTakes() {
        val many = (1..AiApiJson.MAX_EMBEDDING_INPUTS + 1).joinToString(",") { "\"x\"" }

        assertEquals(400, post("/v1/embeddings", """{"input":[$many]}""", token).status)
    }

    @Test
    fun aBodyOverTheListenerLimitIsRefusedBeforeTheJsonIsRead() {
        val long = "x".repeat(AiApiJson.MAX_EMBEDDING_CHARS + 1)

        assertEquals(413, post("/v1/embeddings", """{"input":"$long"}""", token).status)
        assertTrue(backend.embedded.isEmpty())
    }

    @Test
    fun embeddingsAnswerUnavailableWithoutAnEncoder() {
        backend.encoder = null

        val response = request("POST", "/v1/embeddings", body = """{"input":"hi"}""", token = token)

        assertEquals(503, response.status)
        assertTrue(backend.embedded.isEmpty())
    }

    @Test
    fun embeddingsMapABackendFailureToItsOwnStatus() {
        backend.embeddingFailure = AiApiFailure.Busy("The model is already working")
        assertEquals(409, post("/v1/embeddings", """{"input":"hi"}""", token).status)

        backend.embeddingFailure = AiApiFailure.TimedOut("too slow")
        assertEquals(504, post("/v1/embeddings", """{"input":"hi"}""", token).status)

        backend.embeddingFailure = AiApiFailure.Failed("the graph refused")
        assertEquals(500, post("/v1/embeddings", """{"input":"hi"}""", token).status)
    }

    @Test
    fun theEmbeddingsRouteOnlyAcceptsPost() {
        val response = request("GET", "/v1/embeddings", token = token)

        assertEquals(405, response.status)
        assertEquals("POST", response.headers["Allow"])
    }

    @Test
    fun healthNamesTheEncoderThatWouldAnswer() {
        val withEncoder = AiApiJson.parse(request("GET", "/health", token = token).body) as JsonValue.Obj
        assertEquals("encoder.onnx", withEncoder.stringMember("embedding_model"))

        backend.encoder = null
        val without = AiApiJson.parse(request("GET", "/health", token = token).body) as JsonValue.Obj
        assertNull(without.stringMember("embedding_model"))
    }

    @Test
    fun everyRouteNeedsTheToken() {
        assertEquals(401, request("GET", "/health").status)
        assertEquals(401, request("GET", "/v1/models").status)
        assertEquals(401, request("POST", "/v1/chat/completions", body = "{}").status)
        assertEquals(401, request("POST", "/v1/embeddings", body = "{\"input\":\"hi\"}").status)
        assertEquals(401, request("GET", "/health", token = "wrong").status)
        assertEquals(401, request("GET", "/health", token = token.dropLast(1) + "x").status)
        assertEquals(401, request("GET", "/health", token = token.take(20)).status)
        assertEquals("Bearer", request("GET", "/health").headers["WWW-Authenticate"])
        assertTrue(backend.turns.isEmpty())
    }

    @Test
    fun chatAnswersWithTheOpenAiShape() {
        backend.reply = "Hello \"there\".\nC:\\tmp"
        val response = request(
            "POST",
            "/v1/chat/completions",
            body = """{"model":"local","messages":[{"role":"user","content":"hi"}]}""",
            token = token
        )

        assertEquals(200, response.status)
        val body = AiApiJson.parse(response.body) as JsonValue.Obj
        assertEquals("chat.completion", body.stringMember("object"))
        assertEquals("local", body.stringMember("model"))
        assertTrue(body.stringMember("id")!!.startsWith("chatcmpl-"))
        val choice = ((body["choices"] as JsonValue.Arr).items.single() as JsonValue.Obj)
        assertEquals("stop", choice.stringMember("finish_reason"))
        assertEquals(
            backend.reply,
            (choice["message"] as JsonValue.Obj).stringMember("content")
        )
        assertEquals(listOf(ChatTurn(ChatTurn.Role.User, "hi")), backend.turns)
    }

    @Test
    fun chatTakesTheTokenBudgetFromTheRequest() {
        request(
            "POST",
            "/v1/chat/completions",
            body = """{"max_tokens":32,"messages":[{"role":"user","content":"hi"}]}""",
            token = token
        )

        assertEquals(32, backend.maxTokens)
    }

    @Test
    fun chatFallsBackToTheConfiguredBudget() {
        request("POST", "/v1/chat/completions", body = """{"messages":[{"role":"user","content":"hi"}]}""", token = token)

        assertEquals(256, backend.maxTokens)
    }

    @Test
    fun chatReportsAMissingModelAsUnavailable() {
        backend.loaded = null
        val response = request(
            "POST",
            "/v1/chat/completions",
            body = """{"messages":[{"role":"user","content":"hi"}]}""",
            token = token
        )

        assertEquals(503, response.status)
        assertEquals("unavailable", errorCode(response.body))
    }

    @Test
    fun chatReportsAModelThatIsAlreadyGenerating() {
        backend.failure = AiApiFailure.Busy("A reply is already being generated")
        val response = request(
            "POST",
            "/v1/chat/completions",
            body = """{"messages":[{"role":"user","content":"hi"}]}""",
            token = token
        )

        assertEquals(409, response.status)
        assertEquals("busy", errorCode(response.body))
    }

    @Test
    fun chatReportsAFailedGeneration() {
        backend.failure = AiApiFailure.Failed("context is too small")
        val response = request(
            "POST",
            "/v1/chat/completions",
            body = """{"messages":[{"role":"user","content":"hi"}]}""",
            token = token
        )

        assertEquals(500, response.status)
        assertEquals("generation_failed", errorCode(response.body))
    }

    @Test
    fun refusesARequestItCannotRead() {
        assertEquals(400, post("/v1/chat/completions", "not json", token).status)
        assertEquals(400, post("/v1/chat/completions", "{}", token).status)
        assertEquals(400, post("/v1/chat/completions", """{"stream":true,"messages":[]}""", token).status)
        assertEquals(400, post("/v1/chat/completions", "", token).status)
    }

    @Test
    fun refusesABodyOverTheConfiguredLimit() {
        val long = """{"messages":[{"role":"user","content":"${"x".repeat(4_096)}"}]}"""

        assertEquals(413, post("/v1/chat/completions", long, token).status)
    }

    @Test
    fun refusesABodyWithoutAContentLength() {
        val response = raw(
            "POST /v1/chat/completions HTTP/1.1\r\nHost: localhost\r\n" +
                "Authorization: Bearer $token\r\nContent-Type: application/json\r\n\r\n"
        )

        assertEquals(411, response.status)
    }

    @Test
    fun refusesAChunkedBody() {
        val response = raw(
            "POST /v1/chat/completions HTTP/1.1\r\nHost: localhost\r\n" +
                "Authorization: Bearer $token\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n"
        )

        assertEquals(400, response.status)
        assertEquals("unsupported_body", errorCode(response.body))
    }

    @Test
    fun refusesTooManyHeaders() {
        val header = (1..64).joinToString("") { "X-Pad-$it: ${"y".repeat(64)}\r\n" }
        val response = raw("GET /health HTTP/1.1\r\nHost: localhost\r\n$header\r\n")

        assertEquals(431, response.status)
    }

    @Test
    fun refusesAMalformedRequestLine() {
        assertEquals(400, raw("GET\r\n\r\n").status)
        assertEquals(400, raw("GET /health SPDY/3\r\nHost: localhost\r\n\r\n").status)
        assertEquals(400, raw("GET health HTTP/1.1\r\nHost: localhost\r\n\r\n").status)
    }

    @Test
    fun refusesTheWrongMethodAndAnUnknownPath() {
        val readOnly = request("POST", "/health", body = "{}", token = token)
        assertEquals(405, readOnly.status)
        assertEquals("GET", readOnly.headers["Allow"])

        val writeOnly = request("GET", "/v1/chat/completions", token = token)
        assertEquals(405, writeOnly.status)
        assertEquals("POST", writeOnly.headers["Allow"])

        assertEquals(404, request("GET", "/", token = token).status)
        assertEquals(404, request("GET", "/v1/nope", token = token).status)
    }

    @Test
    fun ignoresTheQueryStringAndClosesAfterOneRequest() {
        val response = request("GET", "/health?probe=1", token = token)

        assertEquals(200, response.status)
        assertEquals("close", response.headers["Connection"])
    }

    @Test
    fun rateLimitsRepeatsFromOneClient() {
        server.close()
        server = AiApiServer(
            config = AiApiConfig(port = port, bindAddress = "127.0.0.1", rateLimitRequests = 2),
            token = token,
            backend = backend
        ).apply { start() }

        assertEquals(200, request("GET", "/health", token = token).status)
        assertEquals(200, request("GET", "/health", token = token).status)
        val limited = request("GET", "/health", token = token)
        assertEquals(429, limited.status)
        assertEquals("rate_limited", errorCode(limited.body))
        assertTrue(limited.headers.containsKey("Retry-After"))
    }

    @Test
    fun refusesToStartWithoutAToken() {
        val refused = runCatching {
            AiApiServer(
                config = AiApiConfig(port = ServerSocket(0).use { it.localPort }),
                token = "",
                backend = backend
            ).start()
        }

        assertTrue(refused.isFailure)
    }

    private fun errorCode(body: String): String? =
        ((AiApiJson.parse(body) as JsonValue.Obj)["error"] as JsonValue.Obj).stringMember("code")

    private fun post(path: String, body: String, token: String): Response =
        request("POST", path, body, token)

    private fun request(method: String, path: String, body: String = "", token: String? = null): Response {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        connection.requestMethod = if (method == "POST") "POST" else "GET"
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.instanceFollowRedirects = false
        token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        if (method == "POST") {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        }
        val status = connection.responseCode
        val stream = if (status >= 400) connection.errorStream else connection.inputStream
        val text = stream?.use { it.readBytes().toString(StandardCharsets.UTF_8) }.orEmpty()
        val headers = connection.headerFields.orEmpty()
            .filterKeys { it != null }
            .mapValues { (_, value) -> value.firstOrNull().orEmpty() }
        connection.disconnect()
        return Response(status, text, headers)
    }

    /** Sends bytes exactly as written, for the requests no client library will produce. */
    private fun raw(request: String): Response = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 5_000
        socket.getOutputStream().write(request.toByteArray(StandardCharsets.UTF_8))
        socket.getOutputStream().flush()
        val collected = ByteArrayOutputStream()
        val buffer = ByteArray(4_096)
        while (true) {
            val count = try {
                socket.getInputStream().read(buffer)
            } catch (_: IOException) {
                -1
            }
            if (count < 0) break
            collected.write(buffer, 0, count)
        }
        parseRawResponse(collected.toByteArray().toString(StandardCharsets.UTF_8))
    }

    private fun parseRawResponse(response: String): Response {
        val head = response.substringBefore("\r\n\r\n")
        val body = response.substringAfter("\r\n\r\n", "")
        val lines = head.split("\r\n")
        val status = lines.first().split(' ').getOrNull(1)?.toIntOrNull() ?: 0
        val headers = lines.drop(1)
            .mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1).trim()
            }
            .toMap()
        return Response(status, body, headers)
    }

    private data class Response(val status: Int, val body: String, val headers: Map<String, String>)

    private class FakeBackend : AiApiBackend {
        var loaded: String? = "tiny.gguf"
        var reply: String = "Hi there"
        var failure: AiApiFailure? = null
        var turns: List<ChatTurn> = emptyList()
        var maxTokens: Int = 0
        var encoder: String? = "encoder.onnx"
        var vector: FloatArray = floatArrayOf(0.5f, -0.25f, 1f)
        var embeddingFailure: AiApiFailure? = null
        val embedded = mutableListOf<String>()

        override fun runtimeStatus(): String = "llama.cpp 0, test runtime"

        override fun activeModel(): String? = loaded

        override fun activeEmbeddingModel(): String? = encoder

        override fun models(): List<AiApiModel> =
            listOf(AiApiModel("tiny.gguf", true), AiApiModel("other.gguf", false))

        override fun generate(turns: List<ChatTurn>, maxTokens: Int, timeoutMillis: Long): String {
            this.turns = turns
            this.maxTokens = maxTokens
            failure?.let { throw it }
            return reply
        }

        override fun embed(text: String, timeoutMillis: Long): FloatArray {
            embedded += text
            embeddingFailure?.let { throw it }
            return vector
        }
    }
}
