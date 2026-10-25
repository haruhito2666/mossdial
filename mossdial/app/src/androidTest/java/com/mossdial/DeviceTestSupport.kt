package com.mossdial

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import java.net.ServerSocket
import org.junit.Assume.assumeTrue

/**
 * Shared helpers for the device smoke tests.
 *
 * These are deliberately small. Everything here is either a free port, a real application
 * context, or a wait for something the app does on its own thread, because the tests below are
 * checking the parts that only exist on a device: the platform keystore, the real socket stack,
 * and a foreground service the system actually started.
 */
internal object DeviceTestSupport {
    fun context(): Context = ApplicationProvider.getApplicationContext()

    /** A port the operating system has confirmed free, so two tests cannot collide on one. */
    fun freePort(): Int = ServerSocket(0).use { it.localPort }

    /** The address every listener in these tests binds, so nothing is ever published to the LAN. */
    const val LOOPBACK = "127.0.0.1"

    /**
     * Waits until [condition] holds, or fails the calling test after [timeoutMillis].
     *
     * The service under test does its socket work on its own thread, so there is no moment at
     * which a caller can be certain the socket is open. Polling with a deadline turns "it is
     * starting" into a pass and "it never came up" into a clear failure instead of a hang.
     */
    fun await(timeoutMillis: Long = 10_000L, message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        var lastError: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                if (condition()) return
            } catch (error: Throwable) {
                lastError = error
            }
            Thread.sleep(25L)
        }
        throw AssertionError(
            "Timed out after ${timeoutMillis}ms waiting for: $message" +
                (lastError?.let { " (last error: $it)" } ?: "")
        )
    }

    /**
     * An address this device has on a real network interface, or null when it has none.
     *
     * Used to prove what a listener is *not* bound to, which is the one property of the default
     * loopback bind that cannot be checked on a host JVM: with no Wi-Fi or cellular interface there
     * is no non-loopback address to try, and with one there is exactly the address a mistaken
     * `0.0.0.0` bind would publish on.
     */
    fun lanAddress(): String? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    /**
     * Skips the calling test unless this is real hardware.
     *
     * The emulator's keystore is a software implementation, and it completes a `Signature` call
     * with a keystore key but will not complete a TLS server handshake signed with one. A test that
     * asserts the handshake would therefore fail for a reason that has nothing to do with the app,
     * so the tests that need the real daemon gate on this instead. A device-only behaviour is
     * skipped rather than failed, because skipping is what tells the reader the gap is still open.
     *
     * The emulator identifies itself through the fingerprint system properties; a real device has
     * none of them.
     */
    fun assumeRealDevice() {
        val fingerprint = android.os.Build.FINGERPRINT.orEmpty()
        val model = android.os.Build.MODEL.orEmpty()
        assumeTrue(
            "Skipped on an emulator: the software keystore cannot complete a TLS handshake with a " +
                "keystore key. Run this on real hardware to close the gap.",
            !fingerprint.startsWith("generic") &&
                !fingerprint.contains("vbox") &&
                !fingerprint.contains("emulator", ignoreCase = true) &&
                !fingerprint.contains("genymotion", ignoreCase = true) &&
                !model.contains("Emulator", ignoreCase = true) &&
                !model.contains("Android SDK built for", ignoreCase = true)
        )
    }
}
