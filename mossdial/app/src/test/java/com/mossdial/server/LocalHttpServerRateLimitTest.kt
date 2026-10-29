package com.mossdial.server

import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How the file server picks the bucket a request counts against.
 *
 * cloudflared connects over loopback, so without the forwarded header every tunnelled visitor
 * shares one rate-limit bucket. The header is attacker controlled, though, so it is only honoured
 * for a loopback peer, only while the tunnel is enabled, and only when the value is a literal IP.
 */
class LocalHttpServerRateLimitTest {

    @Test
    fun aTunnelledVisitorGetsItsOwnBucket() {
        val root = siteRoot()
        val port = freePort()
        val server = server(root, port, trustProxyHeaders = true)
        try {
            server.start()
            assertEquals(200, statusOf(port, "203.0.113.7"))
            assertEquals(200, statusOf(port, "203.0.113.8"))
            assertEquals(429, statusOf(port, "203.0.113.7"))
        } finally {
            server.stop()
            root.deleteRecursively()
        }
    }

    @Test
    fun theForwardedHeaderIsIgnoredWhenTheTunnelIsOff() {
        val root = siteRoot()
        val port = freePort()
        val server = server(root, port, trustProxyHeaders = false)
        try {
            server.start()
            assertEquals(200, statusOf(port, "203.0.113.7"))
            assertEquals(429, statusOf(port, "203.0.113.8"))
        } finally {
            server.stop()
            root.deleteRecursively()
        }
    }

    @Test
    fun aValueThatIsNotAnAddressFallsBackToThePeer() {
        val root = siteRoot()
        val port = freePort()
        val server = server(root, port, trustProxyHeaders = true)
        try {
            server.start()
            assertEquals(200, statusOf(port, "not-an-address"))
            assertEquals(429, statusOf(port, "not-an-address"))
        } finally {
            server.stop()
            root.deleteRecursively()
        }
    }

    private fun siteRoot(): File = Files.createTempDirectory("mossdial-rate-limit").toFile().apply {
        File(this, "index.html").writeText("<p>ok</p>")
    }

    private fun server(
        root: File,
        port: Int,
        trustProxyHeaders: Boolean
    ): LocalHttpServer = LocalHttpServer(
        config = ServerConfig(
            port = port,
            bindAddress = LOOPBACK,
            rateLimitRequests = 1,
            workerThreads = 2
        ),
        root = root,
        trustProxyHeaders = { trustProxyHeaders }
    )

    private fun statusOf(port: Int, forwarded: String?): Int? = try {
        Socket(LOOPBACK, port).use { socket ->
            socket.soTimeout = 5_000
            val header = if (forwarded == null) "" else "CF-Connecting-IP: $forwarded\r\n"
            socket.getOutputStream().apply {
                write(
                    ("GET / HTTP/1.1\r\nHost: localhost\r\n$header" +
                        "Connection: close\r\n\r\n").toByteArray()
                )
                flush()
            }
            socket.getInputStream().bufferedReader().readLine()
                ?.removePrefix("HTTP/1.1 ")
                ?.substringBefore(' ')
                ?.trim()
                ?.toIntOrNull()
        }
    } catch (_: Exception) {
        null
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
    }
}
