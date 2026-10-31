package com.mossdial.server

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ServerSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The proxy against a real HTTP runtime, not a mock: the response headers, body and methods are
 * what the listener must hand back to a browser.
 */
class ReverseProxyTest {

    @Test
    fun forwardsMethodsBodiesAndForwardingHeaders() {
        val backend = FakeBackend()
        try {
            backend.start()
            val proxy = ReverseProxy(backend.port)
            val output = ByteArrayOutputStream()

            val outcome = proxy.forward(
                request = ProxyRequest(
                    method = "PATCH",
                    target = "/api/thing",
                    headers = mapOf(
                        "host" to "phone.local",
                        "accept" to "*/*",
                        "content-length" to "5"
                    ),
                    body = ByteArrayInputStream("hello".toByteArray()),
                    bodyLength = 5L,
                    clientAddress = "192.168.1.7",
                    scheme = "http",
                    headOnly = false
                ),
                output = output
            )

            assertEquals(201, outcome.status)
            val response = output.toString()
            assertTrue(response, response.contains("backend-reply"))
            assertEquals("PATCH", backend.method)
            assertEquals("192.168.1.7", backend.forwardedFor)
            assertEquals("hello", backend.body)
        } finally {
            backend.close()
        }
    }

    @Test
    fun letsTheCallerAnswerBadGatewayWhenNoBackendIsListening() {
        val port = ServerSocket(0).use { it.localPort }
        val proxy = ReverseProxy(port)

        val outcome = try {
            proxy.forward(
                request = ProxyRequest(
                    method = "GET",
                    target = "/",
                    headers = emptyMap(),
                    body = ByteArrayInputStream(ByteArray(0)),
                    bodyLength = 0L,
                    clientAddress = "127.0.0.1",
                    scheme = "http",
                    headOnly = false
                ),
                output = ByteArrayOutputStream()
            )
            null
        } catch (_: IOException) {
            ProxyOutcome(502, 0L)
        }

        assertEquals(502, outcome?.status)
    }

    /** A one-request HTTP backend, enough to prove what the proxy actually sends and relays. */
    private class FakeBackend {
        val socket = ServerSocket(0)
        val port: Int get() = socket.localPort
        var method = ""
        var forwardedFor = ""
        var body = ""

    fun start() {
        Thread {
            socket.accept().use { client ->
                client.soTimeout = 5_000
                    val input = client.getInputStream()
                    method = HttpRequestLines.readLine(input, 1024)?.substringBefore(' ').orEmpty()
                    var contentLength = 0
                    while (true) {
                        val line = HttpRequestLines.readLine(input, 8192) ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("X-Forwarded-For:", ignoreCase = true)) {
                            forwardedFor = line.substringAfter(':').trim()
                        }
                        if (line.startsWith("Content-Length:", ignoreCase = true)) {
                            contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                        }
                    }
                    if (contentLength > 0) {
                        val bytes = ByteArray(contentLength)
                        var read = 0
                        while (read < contentLength) {
                            val next = input.read(bytes, read, contentLength - read)
                            if (next < 0) break
                            read += next
                        }
                        body = String(bytes, 0, read)
                    }
                    val body = "backend-reply"
                    client.getOutputStream().apply {
                        write(
                            ("HTTP/1.1 201 Created\r\n" +
                                "Content-Type: text/plain\r\n" +
                                "Content-Length: ${body.length}\r\n" +
                                "Connection: close\r\n\r\n$body").toByteArray()
                        )
                        flush()
                    }
                }
            }.apply { isDaemon = true }.start()
        }

        fun close() {
            socket.close()
        }
    }
}
