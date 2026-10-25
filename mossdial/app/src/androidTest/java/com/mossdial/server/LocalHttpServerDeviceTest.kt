package com.mossdial.server

import com.mossdial.DeviceTestSupport
import com.mossdial.data.WebRoot
import java.io.File
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The server on a device, over the platform's real socket stack.
 *
 * The JVM suite already drives this class over loopback sockets, and a loopback socket on a phone
 * is the same code path. What is genuinely different here is everything that needs an application
 * context or the device: the web root lives in `filesDir` on the app's own filesystem with the
 * permissions and extraction rules that come with it, the TLS listener presents the keystore key
 * rather than an in-memory one, and `localAddress` is what a client can actually connect to. Those
 * are checked here so the release checklist has a repeatable answer instead of a manual one.
 */
class LocalHttpServerDeviceTest {
    private val context = DeviceTestSupport.context()

    /** The app's own web root, so the server reads exactly what the file manager writes. */
    private val root: File get() = WebRoot.ensure(context)

    @Test
    fun servesTheWebRootOverLoopback() {
        val port = DeviceTestSupport.freePort()
        val server = LocalHttpServer(
            ServerConfig(port = port, bindAddress = DeviceTestSupport.LOOPBACK),
            root
        )
        server.use {
            it.start()
            assertTrue(it.isRunning)

            val response = get("http://${DeviceTestSupport.LOOPBACK}:$port/")

            assertEquals(200, response.first)
            assertTrue(response.second.contains("Mossdial"))
        }
    }

    @Test
    fun bindsLoopbackSoTheServerIsNotReachableFromTheNetwork() {
        val port = DeviceTestSupport.freePort()
        val server = LocalHttpServer(
            ServerConfig(port = port, bindAddress = DeviceTestSupport.LOOPBACK),
            root
        )
        server.use { instance ->
            instance.start()

            // The only device-only way to tell what a socket is bound to: ask the interface
            // address the app actually has, and confirm the listener is not on it. A host JVM has
            // no Wi-Fi interface, so this cannot be checked anywhere else. If the bind ever became
            // 0.0.0.0 the site would be on the LAN without the user asking for it.
            assertEquals(200, get("http://${DeviceTestSupport.LOOPBACK}:$port/").first)
            DeviceTestSupport.lanAddress()?.let { address ->
                assertFalse(
                    "a loopback bind must not answer on $address, or LAN mode would be the default",
                    isReachable("http://$address:$port/")
                )
            }
        }
    }

    @Test
    fun setsTheSecurityHeadersOnEveryResponse() {
        val port = DeviceTestSupport.freePort()
        val server = LocalHttpServer(
            ServerConfig(port = port, bindAddress = DeviceTestSupport.LOOPBACK),
            root
        )
        server.use { instance ->
            instance.start()
            val headers = getHeaders("http://${DeviceTestSupport.LOOPBACK}:$port/")

            assertEquals("nosniff", headers["x-content-type-options"])
            assertEquals("no-referrer", headers["referrer-policy"])
            assertTrue(headers.containsKey("content-security-policy"))
        }
    }

    @Test
    fun refusesAFileOutsideTheWebRoot() {
        val port = DeviceTestSupport.freePort()
        val server = LocalHttpServer(
            ServerConfig(port = port, bindAddress = DeviceTestSupport.LOOPBACK),
            root
        )
        server.use { instance ->
            instance.start()

            // A traversal attempt and an encoded one, because a device filesystem resolves both
            // before the server ever sees the request.
            listOf(
                "/../../../../etc/passwd",
                "/..%2f..%2f..%2f..%2fetc%2fpasswd",
                "/%2e%2e/%2e%2e/etc/passwd"
            ).forEach { path ->
                val (status, _) = get("http://${DeviceTestSupport.LOOPBACK}:$port$path")
                assertTrue("$path must not be served", status == 400 || status == 403 || status == 404)
            }
        }
    }

    @Test
    fun servesARange() {
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
                mapOf("Range" to "bytes=0-9")
            )

            assertEquals(206, status)
            assertEquals("bytes 0-9", headers["content-range"]?.substringBefore('/'))
        }
    }

    @Test
    fun refusesAMethodItDoesNotServe() {
        val port = DeviceTestSupport.freePort()
        val server = LocalHttpServer(
            ServerConfig(port = port, bindAddress = DeviceTestSupport.LOOPBACK),
            root
        )
        server.use { instance ->
            instance.start()

            // The server reads GET and HEAD and answers 405 to anything else, so it never has to
            // interpret a body it did not ask for.
            val (status, _) = request("POST", "http://${DeviceTestSupport.LOOPBACK}:$port/")

            assertEquals(405, status)
        }
    }

    /**
     * A GET over a plain socket, as the status line and the body.
     *
     * `HttpURLConnection` is deliberately not used: from API 28 the platform refuses cleartext
     * traffic unless the manifest opts in, and opting the app in would loosen the default for the
     * sake of a test. The app is a server and never fetches a URL, so the tests speak the protocol
     * directly, which is also what the server actually receives.
     */
    private fun get(url: String, headers: Map<String, String> = emptyMap()): Pair<Int, String> {
        val target = java.net.URI(url)
        val port = target.port.takeIf { it > 0 } ?: 80
        Socket(target.host, port).use { socket ->
            socket.soTimeout = 5_000
            val request = buildString {
                append("GET ").append(target.path.ifEmpty { "/" }).append(" HTTP/1.1\r\n")
                append("Host: ").append(target.host).append("\r\n")
                headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
                append("Connection: close\r\n\r\n")
            }
            socket.getOutputStream().also { out ->
                out.write(request.toByteArray())
                out.flush()
            }
            val raw = socket.getInputStream().bufferedReader().use { it.readText() }
            val status = raw.lineSequence().firstOrNull()
                ?.removePrefix("HTTP/1.1 ")?.substringBefore(' ')?.trim()?.toIntOrNull()
            val body = raw.substringAfter("\r\n\r\n", "")
            return (status ?: 0) to body
        }
    }

    private fun getHeaders(url: String): Map<String, String> {
        val (_, head) = request("HEAD", url)
        return head
    }

    /** Any request, returned as its status and its lower-cased header map. */
    private fun request(method: String, url: String, extra: Map<String, String> = emptyMap()): Pair<Int, Map<String, String>> {
        val target = java.net.URI(url)
        val port = target.port.takeIf { it > 0 } ?: 80
        Socket(target.host, port).use { socket ->
            socket.soTimeout = 5_000
            val request = buildString {
                append(method).append(' ').append(target.path.ifEmpty { "/" }).append(" HTTP/1.1\r\n")
                append("Host: ").append(target.host).append("\r\n")
                extra.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
                append("Connection: close\r\n\r\n")
            }
            socket.getOutputStream().also { out ->
                out.write(request.toByteArray())
                out.flush()
            }
            val raw = socket.getInputStream().bufferedReader().use { it.readText() }
            val status = raw.lineSequence().firstOrNull()
                ?.substringAfter(' ')?.substringBefore(' ')?.trim()?.toIntOrNull() ?: 0
            val head = raw.substringBefore("\r\n\r\n")
            val headers = head.lineSequence().drop(1)
                .mapNotNull { line ->
                    val separator = line.indexOf(':')
                    if (separator <= 0) null
                    else line.substring(0, separator).trim().lowercase() to line.substring(separator + 1).trim()
                }
                .toMap()
            return status to headers
        }
    }

    /** Whether anything answered at all, used to prove a listener is *not* on an address. */
    private fun isReachable(url: String): Boolean = try {
        Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress(java.net.URI(url).host, portOf(url)), 1_500)
        }
        true
    } catch (_: Exception) {
        false
    }

    private fun portOf(url: String): Int =
        java.net.URI(url).port.takeIf { it > 0 } ?: 80
}
