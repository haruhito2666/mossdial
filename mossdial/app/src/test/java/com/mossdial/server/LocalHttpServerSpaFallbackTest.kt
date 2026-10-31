package com.mossdial.server

import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The server-side behaviour a single-page app depends on: its routes answer with the app shell,
 * while its assets answer with themselves or not at all.
 */
class LocalHttpServerSpaFallbackTest {

    @Test
    fun `an extensionless route serves the app shell and a missing asset stays missing`() {
        val root = Files.createTempDirectory("mossdial-spa-server").toFile()
        File(root, "index.html").writeText("<div id=app></div>")
        val port = freePort()
        val server = LocalHttpServer(ServerConfig(port = port, bindAddress = LOOPBACK), root)
        try {
            server.start()
            val (routeStatus, routeBody) = response(port, "/route")
            val (assetStatus, _) = response(port, "/missing.js")

            assertEquals(200, routeStatus)
            assertTrue(routeBody, routeBody.contains("<div id=app></div>"))
            assertEquals(404, assetStatus)
        } finally {
            server.stop()
            root.deleteRecursively()
        }
    }

    private fun response(port: Int, target: String): Pair<Int, String> = try {
        Socket(LOOPBACK, port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().apply {
                write(("GET $target HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n").toByteArray())
                flush()
            }
            val body = socket.getInputStream().bufferedReader().readText()
            val status = body.lineSequence().first()
                .removePrefix("HTTP/1.1 ")
                .substringBefore(' ')
                .trim()
                .toIntOrNull()
                ?: 0
            status to body.substringAfter("\r\n\r\n", "")
        }
    } catch (_: Exception) {
        0 to ""
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
    }
}
