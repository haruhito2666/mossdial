package com.mossdial.server

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The address another device on this network can use to reach the phone.
 *
 * `0.0.0.0` is a bind address, not a destination: it tells the socket to listen everywhere. A
 * screen that shows it as the address to open is showing a URL no browser can dial, so the UI asks
 * for the real site-local IPv4 address instead and falls back to the bind address only when the
 * device has no network interface up.
 *
 * Enumerating interfaces is not free and the answer changes rarely, so the result is cached for a
 * few seconds. A phone that moves between networks picks up its new address within that window.
 */
object LanAddress {
    private const val CACHE_NANOS = 5_000_000_000L

    private val lock = Any()
    private var cached: String? = null
    private var cachedAtNanos = 0L
    private var cachedOnce = false

    fun siteLocalIPv4(): String? = synchronized(lock) {
        val now = System.nanoTime()
        if (cachedOnce && now - cachedAtNanos < CACHE_NANOS) return cached
        val found = findSiteLocalIPv4()
        cached = found
        cachedAtNanos = now
        cachedOnce = true
        found
    }

    private fun findSiteLocalIPv4(): String? = try {
        NetworkInterface.getNetworkInterfaces()
            ?.asSequence()
            ?.filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            ?.flatMap { it.inetAddresses.asSequence() }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
    } catch (_: Exception) {
        null
    }
}
