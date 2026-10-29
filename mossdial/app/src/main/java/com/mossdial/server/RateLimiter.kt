package com.mossdial.server

import java.net.InetAddress
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit

class RateLimiter(
    private val requestLimit: Int,
    windowMillis: Long,
    private val maxClients: Int,
    private val nanoTime: () -> Long = System::nanoTime
) {
    private val windowNanos = TimeUnit.MILLISECONDS.toNanos(windowMillis)
    private val lock = Any()
    private val clients = LinkedHashMap<String, ClientState>(16, 0.75f, true)

    init {
        require(requestLimit > 0)
        require(windowMillis in 1..TimeUnit.DAYS.toMillis(1))
        require(maxClients > 0)
    }

    fun tryAcquire(clientKey: String, nowNanos: Long = nanoTime()): Boolean {
        require(clientKey.isNotEmpty())
        return synchronized(lock) {
            var state = clients[clientKey]
            if (state != null && nowNanos - state.windowStart >= windowNanos) {
                clients.remove(clientKey)
                state = null
            }
            if (state == null) {
                if (clients.size >= maxClients) removeExpired(nowNanos)
                if (clients.size >= maxClients) removeOldest()
                state = ClientState(nowNanos)
                clients[clientKey] = state
            }
            if (state.count >= requestLimit) {
                false
            } else {
                state.count++
                true
            }
        }
    }

    fun trackedClientCount(): Int = synchronized(lock) { clients.size }

    private fun removeExpired(nowNanos: Long) {
        val iterator = clients.entries.iterator()
        while (iterator.hasNext()) {
            if (nowNanos - iterator.next().value.windowStart >= windowNanos) iterator.remove()
        }
    }

    private fun removeOldest() {
        val iterator = clients.entries.iterator()
        if (iterator.hasNext()) {
            iterator.next()
            iterator.remove()
        }
    }

    private data class ClientState(
        val windowStart: Long,
        var count: Int = 0
    )
}

internal object RateLimitKeys {
    private const val HEX = "0123456789abcdef"

    fun fingerprint(address: InetAddress): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(address.address)
        return buildString(24) {
            for (index in 0 until 12) {
                val value = digest[index].toInt() and 0xff
                append(HEX[value ushr 4])
                append(HEX[value and 0x0f])
            }
        }
    }

    /**
     * Fingerprints a literal IPv4 or IPv6 address, or returns null when [value] is not one.
     *
     * Tunnel headers are attacker controlled, so this accepts only numeric literals: a hostname
     * would turn every request into a DNS lookup, and a malformed value must not be able to move
     * the caller into a bucket of its choosing.
     */
    fun fingerprintLiteral(value: String): String? {
        val literal = value.trim()
        if (literal.isEmpty() || literal.length > 64) return null
        if (literal.contains(':')) {
            val valid = literal.all { character ->
                character.isDigit() || character in 'a'..'f' || character in 'A'..'F' ||
                    character == ':' || character == '.'
            }
            if (!valid) return null
            return runCatching { InetAddress.getByName(literal) }.getOrNull()?.let(::fingerprint)
        }
        val parts = literal.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for ((index, part) in parts.withIndex()) {
            if (part.isEmpty() || part.length > 3 || !part.all(Char::isDigit)) return null
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            bytes[index] = octet.toByte()
        }
        return fingerprint(InetAddress.getByAddress(bytes))
    }
}
