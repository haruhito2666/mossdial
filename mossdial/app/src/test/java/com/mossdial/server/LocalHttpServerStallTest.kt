package com.mossdial.server

import java.io.File
import java.io.FileOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two slow-client bounds, exercised over a real socket.
 *
 * A unit test can prove the deadline arithmetic, but only a socket proves the server actually
 * gives a worker back. These tests keep every timeout at its one-second minimum and assert on
 * elapsed wall time rather than on an implementation detail.
 */
class LocalHttpServerStallTest {

    @Test(timeout = 15_000)
    fun `a client that trickles a request is disconnected at the request deadline`() {
        val root = Files.createTempDirectory("mossdial-deadline").toFile()
        File(root, "index.html").writeText("<p>ok</p>")
        val port = freePort()
        val server = LocalHttpServer(
            ServerConfig(
                port = port,
                bindAddress = LOOPBACK,
                requestTimeoutMillis = 1_000,
                writeTimeoutMillis = 1_000
            ),
            root
        )
        try {
            server.start()
            Socket(LOOPBACK, port).use { socket ->
                socket.soTimeout = 3_000
                val output = socket.getOutputStream()
                output.write("GET / HTTP/1.1\r\nHost: localhost\r\n".toByteArray())
                output.flush()

                val started = System.currentTimeMillis()
                // Keep the header line unfinished, one byte at a time, for longer than the whole
                // request budget. Each byte is well inside the per-read timeout.
                repeat(8) {
                    Thread.sleep(250)
                    runCatching {
                        output.write('X'.code)
                        output.flush()
                    }
                }
                val result = runCatching { socket.getInputStream().read() }
                val elapsed = System.currentTimeMillis() - started

                assertTrue(
                    "the server kept the slow request open for ${elapsed}ms",
                    elapsed < 3_000
                )
                assertTrue(
                    "the connection was not closed: $result",
                    result.getOrNull() == -1 || result.exceptionOrNull() is java.io.IOException
                )
            }
        } finally {
            server.stop()
            root.deleteRecursively()
        }
    }

    @Test(timeout = 20_000)
    fun `a client that stops reading does not keep the only worker forever`() {
        val root = Files.createTempDirectory("mossdial-write-stall").toFile()
        File(root, "index.html").writeText("<p>ok</p>")
        File(root, "large.bin").createLargeFile(32L * 1024 * 1024)
        val port = freePort()
        val server = LocalHttpServer(
            ServerConfig(
                port = port,
                bindAddress = LOOPBACK,
                maxConnections = 4,
                workerThreads = 1,
                requestTimeoutMillis = 2_000,
                writeTimeoutMillis = 1_000
            ),
            root
        )
        try {
            server.start()

            // This request fills the socket buffer and then stops reading. The watchdog should
            // close it after a second, which is the only way the single worker becomes free.
            val stalled = Socket(LOOPBACK, port)
            try {
                stalled.getOutputStream().apply {
                    write("GET /large.bin HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
                    flush()
                }
                Thread.sleep(2_500)

                Socket(LOOPBACK, port).use { second ->
                    second.soTimeout = 5_000
                    second.getOutputStream().apply {
                        write("GET /index.html HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
                        flush()
                    }
                    val line = second.getInputStream().bufferedReader().readLine()
                    assertEquals("HTTP/1.1 200 OK", line)
                }
            } finally {
                runCatching { stalled.close() }
            }
        } finally {
            server.stop()
            root.deleteRecursively()
        }
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun File.createLargeFile(bytes: Long) {
        FileOutputStream(this).use { output ->
            val chunk = ByteArray(64 * 1024)
            var remaining = bytes
            while (remaining > 0) {
                val size = minOf(remaining, chunk.size.toLong()).toInt()
                output.write(chunk, 0, size)
                remaining -= size
            }
        }
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
    }
}
