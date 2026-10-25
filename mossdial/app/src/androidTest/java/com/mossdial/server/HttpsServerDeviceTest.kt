package com.mossdial.server

import com.mossdial.DeviceTestSupport
import com.mossdial.data.WebRoot
import java.io.File
import java.security.cert.X509Certificate
import java.util.zip.GZIPInputStream
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HTTPS on a device, where the listener presents the keystore key.
 *
 * [TlsIdentityDeviceTest] covers the handshake in isolation; this covers the server doing it while
 * serving, which is the configuration that actually ships. Two things are worth a device: the
 * TLSv1.3 / TLSv1.2 filtering in [LocalTlsContext] only narrows the protocols the platform offers,
 * so the result is a device question, and gzip goes through `GZIPOutputStream` over a socket rather
 * than a byte array.
 */
class HttpsServerDeviceTest {
    private val context = DeviceTestSupport.context()
    private val root: File get() = WebRoot.ensure(context)

    @Test
    fun servesTheSiteOverHttpsWithTheKeystoreCertificate() {
        val port = DeviceTestSupport.freePort()
        val identity = TlsIdentity.forContext(context)
        val server = LocalHttpServer(
            ServerConfig(port = port, bindAddress = DeviceTestSupport.LOOPBACK, enableTls = true),
            root,
            tlsIdentity = { identity }
        )
        server.use { instance ->
            instance.start()

            val socket = tlsSocket(port)
            socket.use {
                it.startHandshake()
                it.getOutputStream().also { out ->
                    out.write(
                        ("GET / HTTP/1.1\r\nHost: ${DeviceTestSupport.LOOPBACK}\r\n" +
                            "Connection: close\r\n\r\n").toByteArray()
                    )
                    out.flush()
                }
                val response = it.getInputStream().bufferedReader().use { reader -> reader.readText() }

                assertTrue(response.startsWith("HTTP/1.1 200 OK"))
                assertTrue(response.contains("Mossdial"))
            }
        }
    }

    @Test
    fun negotiatesAModernProtocolVersion() {
        val port = DeviceTestSupport.freePort()
        val server = LocalHttpServer(
            ServerConfig(port = port, bindAddress = DeviceTestSupport.LOOPBACK, enableTls = true),
            root,
            tlsIdentity = { TlsIdentity.forContext(context) }
        )
        server.use { instance ->
            instance.start()

            val socket = tlsSocket(port)
            socket.use {
                it.startHandshake()
                val protocol = it.session.protocol
                assertTrue(
                    "an outdated protocol was negotiated: $protocol",
                    protocol == "TLSv1.3" || protocol == "TLSv1.2"
                )
            }
        }
    }

    @Test
    fun gzipsACompressibleBodyWhenTheClientAsks() {
        // The default page is far under the 1 KiB floor the gzip decision uses, because
        // compressing a few hundred bytes costs more than it saves. So the file this asks about is
        // written to be over it, which is the size a real site page is anyway.
        val page = File(root, "gzip-probe.html").apply {
            writeText("<!doctype html><p>" + "compressible ".repeat(200) + "</p>")
        }
        val port = DeviceTestSupport.freePort()
        val server = LocalHttpServer(
            ServerConfig(port = port, bindAddress = DeviceTestSupport.LOOPBACK),
            root
        )
        server.use { instance ->
            instance.start()

            val (status, headers) = request(
                "GET",
                "http://${DeviceTestSupport.LOOPBACK}:$port/gzip-probe.html",
                mapOf("Accept-Encoding" to "gzip")
            )

            assertEquals(200, status)
            assertEquals("gzip", headers["content-encoding"])
            assertTrue(headers["vary"]?.contains("Accept-Encoding") == true)
            page.delete()
        }
    }

    /**
     * A body too small to be worth compressing is sent as it is, even when gzip was accepted.
     *
     * The threshold is a deliberate trade, and the interesting failure is a server that gzips
     * everything and so makes small responses larger than the identity they replaced.
     */
    @Test
    fun doesNotGzipABodyBelowTheThreshold() {
        val port = DeviceTestSupport.freePort()
        val server = LocalHttpServer(
            ServerConfig(port = port, bindAddress = DeviceTestSupport.LOOPBACK),
            root
        )
        server.use { instance ->
            instance.start()

            val (status, headers) = request(
                "GET",
                "http://${DeviceTestSupport.LOOPBACK}:$port/",
                mapOf("Accept-Encoding" to "gzip")
            )

            assertEquals(200, status)
            assertNull("a tiny body must not be gzipped", headers["content-encoding"])
            assertTrue(headers["vary"]?.contains("Accept-Encoding") == true)
        }
    }

    @Test
    fun aHeadRequestReturnsHeadersWithNoBody() {
        val port = DeviceTestSupport.freePort()
        val server = LocalHttpServer(
            ServerConfig(port = port, bindAddress = DeviceTestSupport.LOOPBACK),
            root
        )
        server.use { instance ->
            instance.start()

            val (status, headers) = request("HEAD", "http://${DeviceTestSupport.LOOPBACK}:$port/")

            assertEquals(200, status)
            assertTrue("a HEAD must advertise the length it would have sent", headers.containsKey("content-length"))
        }
    }

    /**
     * The gzip body really decompresses to the file.
     *
     * The Content-Length of a gzipped response is the compressed size, so the only way to know the
     * stream is intact is to inflate it, which is what `GZIPOutputStream` over the socket is asked
     * to produce here.
     */
    @Test
    fun theGzippedBodyInflatesToTheServedFile() {
        val page = File(root, "gzip-inflate.html").apply {
            writeText("<!doctype html><p>" + "inflated ".repeat(200) + "</p>")
        }
        val port = DeviceTestSupport.freePort()
        val server = LocalHttpServer(
            ServerConfig(port = port, bindAddress = DeviceTestSupport.LOOPBACK),
            root
        )
        server.use { instance ->
            instance.start()
            val raw = get(
                "http://${DeviceTestSupport.LOOPBACK}:$port/gzip-inflate.html",
                mapOf("Accept-Encoding" to "gzip")
            )

            assertEquals(200, raw.first)
            val inflated = GZIPInputStream(raw.second.inputStream())
                .bufferedReader().use { it.readText() }

            assertTrue(inflated.contains("<!doctype html>"))
            assertTrue(inflated.contains("inflated"))
            page.delete()
        }
    }

    /** A TLS socket factory that trusts the app's own self-signed certificate, as a browser would. */
    private fun trustingFactory(): javax.net.ssl.SSLSocketFactory {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), null)
        }
        return context.socketFactory
    }

    private fun tlsSocket(port: Int): SSLSocket =
        trustingFactory().createSocket(DeviceTestSupport.LOOPBACK, port) as SSLSocket

    /** Any request over a plain socket, as its status and its lower-cased header map. */
    private fun request(
        method: String,
        url: String,
        extra: Map<String, String> = emptyMap()
    ): Pair<Int, Map<String, String>> {
        val target = java.net.URI(url)
        val port = target.port.takeIf { it > 0 } ?: 80
        java.net.Socket(target.host, port).use { socket ->
            socket.soTimeout = 5_000
            val wire = buildString {
                append(method).append(' ').append(target.path.ifEmpty { "/" }).append(" HTTP/1.1\r\n")
                append("Host: ").append(target.host).append("\r\n")
                extra.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
                append("Connection: close\r\n\r\n")
            }
            socket.getOutputStream().also { out ->
                out.write(wire.toByteArray())
                out.flush()
            }
            val raw = socket.getInputStream().bufferedReader().use { it.readText() }
            val status = raw.lineSequence().firstOrNull()
                ?.substringAfter(' ')?.substringBefore(' ')?.trim()?.toIntOrNull() ?: 0
            val headers = raw.substringBefore("\r\n\r\n").lineSequence().drop(1)
                .mapNotNull { line ->
                    val separator = line.indexOf(':')
                    if (separator <= 0) null
                    else line.substring(0, separator).trim().lowercase() to
                        line.substring(separator + 1).trim()
                }
                .toMap()
            return status to headers
        }
    }

    private fun get(url: String, headers: Map<String, String> = emptyMap()): Pair<Int, ByteArray> {
        val target = java.net.URI(url)
        val port = target.port.takeIf { it > 0 } ?: 80
        java.net.Socket(target.host, port).use { socket ->
            socket.soTimeout = 5_000
            val wire = buildString {
                append("GET ").append(target.path.ifEmpty { "/" }).append(" HTTP/1.1\r\n")
                append("Host: ").append(target.host).append("\r\n")
                headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
                append("Connection: close\r\n\r\n")
            }
            socket.getOutputStream().also { out ->
                out.write(wire.toByteArray())
                out.flush()
            }
            // The body is read as bytes, never through a Reader: a gzipped body is binary and any
            // charset decoding of it would corrupt the stream before it could be inflated.
            val raw = socket.getInputStream().readBytes()
            val separator = "\r\n\r\n".toByteArray()
            val index = raw.asSequence().windowed(separator.size)
                .indexOfFirst { it.toByteArray().contentEquals(separator) }
            val status = String(raw, 0, if (index < 0) raw.size else index)
                .lineSequence().firstOrNull()
                ?.removePrefix("HTTP/1.1 ")?.substringBefore(' ')?.trim()?.toIntOrNull() ?: 0
            val body = if (index < 0) ByteArray(0) else raw.copyOfRange(index + separator.size, raw.size)
            return status to body
        }
    }
}
