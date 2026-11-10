package com.mossdial.server

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class LocalTlsIntegrationTest {
    @Test
    fun servesStaticFileOverTls() {
        val root = File(System.getProperty("java.io.tmpdir"), "mossdial-tls-test-${System.nanoTime()}")
        root.mkdirs()
        File(root, "index.html").writeText("<!doctype html><p>secure</p>")
        val port = ServerSocket(0).use { it.localPort }
        val server = LocalHttpServer(
            ServerConfig(port = port, bindAddress = "127.0.0.1", enableTls = true),
            root
        )
        server.start()
        try {
            val response = tlsRequest(port, "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
            assertTrue(response.startsWith("HTTP/1.1 200 OK"))
            assertTrue(response.contains("secure"))
        } finally {
            server.close()
            root.deleteRecursively()
        }
    }

    private fun tlsRequest(port: Int, request: String): String {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(trustManager), null)
        return (context.socketFactory.createSocket("127.0.0.1", port) as SSLSocket).use { socket ->
            socket.startHandshake()
            socket.getOutputStream().write(request.toByteArray())
            socket.getOutputStream().flush()
            socket.getInputStream().bufferedReader().readText()
        }
    }
}
