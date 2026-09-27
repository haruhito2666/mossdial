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
            val trustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val context = SSLContext.getInstance("TLS")
            context.init(null, arrayOf<TrustManager>(trustManager), null)
            val socket = context.socketFactory.createSocket("127.0.0.1", port) as SSLSocket
            socket.startHandshake()
            socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
            socket.getOutputStream().flush()
            val response = socket.getInputStream().bufferedReader().readText()
            assertTrue(response.startsWith("HTTP/1.1 200 OK"))
            assertTrue(response.contains("secure"))
            socket.close()
        } finally {
            server.close()
            root.deleteRecursively()
        }
    }
}
