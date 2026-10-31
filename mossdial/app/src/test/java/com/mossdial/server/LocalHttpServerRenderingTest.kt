package com.mossdial.server

import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The response a browser actually receives for a page and its assets.
 *
 * A correct route is not enough: a document with a restrictive CSP or a script with the wrong
 * media type still renders as a blank page. These assertions cover the headers that decide whether
 * a modern static site is allowed to run.
 */
class LocalHttpServerRenderingTest {

    @Test
    fun `a page and its assets are served without a host-breaking policy`() {
        val root = Files.createTempDirectory("mossdial-render").toFile()
        write(root, "index.html", "<link rel=stylesheet href=/repo/style.css><div id=app></div>")
        write(root, "style.css", "body{color:#111}")
        write(root, "assets/app.js", "document.getElementById('app').textContent='ok'")
        write(root, "module.wasm", "\u0000asm".repeat(600))
        val port = freePort()
        val server = LocalHttpServer(
            ServerConfig(
                port = port,
                bindAddress = LOOPBACK,
                siteBasePath = "repo"
            ),
            root
        )
        try {
            server.start()

            val page = request(port, "/")
            assertEquals(200, page.status)
            assertEquals("text/html; charset=utf-8", page.headers["content-type"])
            assertNull("a hosted site must not be given a CSP it cannot satisfy", page.headers["content-security-policy"])
            assertNull(page.headers["referrer-policy"])
            assertEquals("nosniff", page.headers["x-content-type-options"])

            val etag = page.headers["etag"]
            assertTrue("a page should carry a validator", etag != null)
            val notModified = request(port, "/", "If-None-Match: $etag\r\n")
            assertEquals(304, notModified.status)
            assertNull(notModified.headers["content-security-policy"])

            val script = request(port, "/repo/assets/app.js")
            assertEquals(200, script.status)
            assertEquals("text/javascript; charset=utf-8", script.headers["content-type"])

            val style = request(port, "/repo/style.css")
            assertEquals(200, style.status)
            assertEquals("text/css; charset=utf-8", style.headers["content-type"])

            val wasm = request(port, "/module.wasm", extraHeaders = "Accept-Encoding: gzip\r\n")
            assertEquals(200, wasm.status)
            assertEquals("application/wasm", wasm.headers["content-type"])
            assertEquals("gzip", wasm.headers["content-encoding"])

            val missing = request(port, "/repo/assets/missing.js")
            assertEquals(404, missing.status)
        } finally {
            server.stop()
            root.deleteRecursively()
        }
    }

    private fun write(root: File, path: String, content: String) {
        val file = File(root, path)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    private fun request(
        port: Int,
        target: String,
        extraHeaders: String = ""
    ): Response = try {
        Socket(LOOPBACK, port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().apply {
                write(
                    ("GET $target HTTP/1.1\r\nHost: localhost\r\n" +
                        extraHeaders +
                        "Connection: close\r\n\r\n").toByteArray()
                )
                flush()
            }
            val raw = socket.getInputStream().readBytes()
            val separator = indexOf(raw, "\r\n\r\n".toByteArray())
            val head = String(raw, 0, separator.coerceAtLeast(0), Charsets.ISO_8859_1)
            val lines = head.split("\r\n")
            val status = lines.firstOrNull()
                ?.removePrefix("HTTP/1.1 ")
                ?.substringBefore(' ')
                ?.trim()
                ?.toIntOrNull()
                ?: 0
            val headers = lines.drop(1).mapNotNull { line ->
                val colon = line.indexOf(':')
                if (colon <= 0) null else line.substring(0, colon).trim().lowercase() to
                    line.substring(colon + 1).trim()
            }.toMap()
            Response(status, headers, if (separator < 0) raw else raw.copyOfRange(separator + 4, raw.size))
        }
    } catch (_: Exception) {
        Response(0, emptyMap(), ByteArray(0))
    }

    private fun indexOf(value: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty()) return 0
        for (index in 0..value.size - needle.size) {
            var matches = true
            for (offset in needle.indices) {
                if (value[index + offset] != needle[offset]) {
                    matches = false
                    break
                }
            }
            if (matches) return index
        }
        return -1
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private data class Response(
        val status: Int,
        val headers: Map<String, String>,
        val body: ByteArray
    )

    private companion object {
        const val LOOPBACK = "127.0.0.1"
    }
}
