package com.mossdial.service

import android.app.ActivityManager
import android.content.Context
import com.mossdial.DeviceTestSupport
import com.mossdial.data.ServerSettings
import com.mossdial.data.WebRoot
import java.io.File
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The foreground service as the system actually runs it.
 *
 * Every other test in this project constructs the class it is testing. This one cannot: the point
 * is that `ServerService` is started by the platform, posts a notification within the five second
 * window Android allows, opens the socket on its own thread.
 * A JVM test covers `ServerState`, `ServerStatus` and `AutoStartPolicy` in isolation and none of
 * the rest, because none of it exists without a real service lifecycle.
 *
 * The listener binds loopback on a port the operating system handed out, so nothing here is
 * published to a network.
 */
class ServerServiceDeviceTest {
    private val context = DeviceTestSupport.context()
    private lateinit var settings: ServerSettings
    private var port = 0

    @Before
    fun reservePortsAndStopAnythingRunning() {
        settings = ServerSettings(context)
        port = DeviceTestSupport.freePort()
        settings.port = port
        stopAndWait()
    }

    @After
    fun stopTheServer() {
        stopAndWait()
    }

    @Test
    fun startsTheForegroundServiceAndServesTheSite() {
        assertTrue(ServerControl.start(context, settings))

        DeviceTestSupport.await(message = "the service to report the server as running") {
            ServerState.isRunning()
        }
        assertEquals(port, ServerState.snapshot().port)

        DeviceTestSupport.await(message = "the site to answer on $port") {
            statusOf("http://${DeviceTestSupport.LOOPBACK}:$port/") == 200
        }
    }

    /**
     * The web root is created by the service, not by the test.
     *
     * `WebRoot.ensure` is called inside the service's start path, so a successful serve is also
     * proof that the app-private directory and its default page exist with the permissions the
     * platform gave them.
     */
    @Test
    fun createsTheWebRootAsPartOfStarting() {
        val root = WebRoot.ensure(context)

        assertTrue(root.isDirectory)
        assertTrue(File(root, "index.html").isFile)
    }

    @Test
    fun stopsTheServerAndReleasesThePort() {
        ServerControl.start(context, settings)
        DeviceTestSupport.await(message = "the server to start") { ServerState.isRunning() }

        assertTrue(ServerControl.stop(context))

        DeviceTestSupport.await(message = "the service to report the server as stopped") {
            !ServerState.isRunning()
        }
        DeviceTestSupport.await(message = "port $port to be released") {
            !isListening(port)
        }
    }

    @Test
    fun thePublishedStatusMatchesWhatWasAskedFor() {
        ServerControl.start(context, settings)

        DeviceTestSupport.await(message = "the running status to be published") {
            ServerState.isRunning()
        }
        val status = ServerState.snapshot()

        assertEquals(ServerPhase.Running, status.phase)
        assertEquals(port, status.port)
        assertFalse("loopback is the default and must stay so", status.allowLan)
    }

    /** Wait until Android has processed the stop, before a later test requests a start. */
    @Suppress("DEPRECATION")
    private fun stopAndWait() {
        val stopped = CountDownLatch(1)
        val listener: (ServerStatus) -> Unit = { status ->
            if (status.phase == ServerPhase.Stopped) stopped.countDown()
        }
        ServerState.addListener(listener)
        try {
            assertTrue("the stop request must be accepted", ServerControl.stop(context))
            assertTrue("the service must publish its stopped state", stopped.await(30, TimeUnit.SECONDS))
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            DeviceTestSupport.await(timeoutMillis = 30_000L, message = "the service to finish stopping") {
                activityManager.getRunningServices(Int.MAX_VALUE).none {
                    it.service.className == ServerService::class.java.name
                }
            }
        } finally {
            ServerState.removeListener(listener)
        }
    }

    /**
     * The status line of a GET over a plain socket, or null when nothing answered.
     *
     * `HttpURLConnection` is not used because the platform refuses cleartext traffic from API 28
     * unless the manifest opts in, and the app is a server that never fetches a URL.
     */
    private fun statusOf(url: String): Int? = try {
        val target = java.net.URI(url)
        val port = target.port.takeIf { it > 0 } ?: 80
        java.net.Socket(target.host, port).use { socket ->
            socket.soTimeout = 2_000
            socket.getOutputStream().also { out ->
                out.write(
                    ("GET ${target.path.ifEmpty { "/" }} HTTP/1.1\r\nHost: ${target.host}\r\n" +
                        "Connection: close\r\n\r\n").toByteArray()
                )
                out.flush()
            }
            socket.getInputStream().bufferedReader().use { reader ->
                reader.readLine()
                    ?.removePrefix("HTTP/1.1 ")
                    ?.substringBefore(' ')
                    ?.trim()
                    ?.toIntOrNull()
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun isListening(port: Int): Boolean = try {
        Socket(DeviceTestSupport.LOOPBACK, port).use { true }
    } catch (_: Exception) {
        false
    }
}
