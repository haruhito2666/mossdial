package com.mossdial.data

/**
 * The rules that decide which values the server may be configured with.
 *
 * Kept free of Android types so port parsing, clamping and the loopback/LAN binding choice are
 * all covered by JVM tests, and so every entry point validates the same way: a value typed on the
 * settings screen, a value read back from preferences and a value arriving in a service intent all
 * pass through [normalizePort] and [bindAddress].
 */
object ServerSettingsRules {
    const val DEFAULT_PORT = 8080
    const val MIN_PORT = 1024
    const val MAX_PORT = 65535
    const val MAX_PORT_DIGITS = 5

    const val LOOPBACK_ADDRESS = "127.0.0.1"
    const val ANY_ADDRESS = "0.0.0.0"

    val PORT_RANGE: IntRange = MIN_PORT..MAX_PORT

    fun isValidPort(port: Int): Boolean = port in PORT_RANGE

    /** Clamps any integer into the range, so a stored or forwarded value can never be unusable. */
    fun normalizePort(port: Int): Int = port.coerceIn(MIN_PORT, MAX_PORT)

    /**
     * Parses a port typed by the user, or returns null when the text is not a port at all.
     *
     * Returning null instead of a fallback keeps the settings screen able to say what is wrong
     * with the input; a caller that needs a value regardless uses [parsePortOrDefault].
     */
    fun parsePort(text: String?): Int? {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.length > MAX_PORT_DIGITS) return null
        if (trimmed.any { it !in '0'..'9' }) return null
        val port = trimmed.toIntOrNull() ?: return null
        return port.takeIf { isValidPort(it) }
    }

    fun parsePortOrDefault(text: String?): Int = parsePort(text) ?: DEFAULT_PORT

    /** LAN access binds every interface; otherwise the socket is reachable from this device only. */
    fun bindAddress(allowLan: Boolean): String = if (allowLan) ANY_ADDRESS else LOOPBACK_ADDRESS

    const val MIN_LOG_LINES = 16
    const val MAX_LOG_LINES = 4_096
    val LOG_LINES_CHOICES = listOf(64, 128, 256, 512, 1_024, 4_096)

    /** Clamps a stored or forwarded log size, so the request log is never unbounded or empty. */
    fun normalizeLogLines(lines: Int): Int = lines.coerceIn(MIN_LOG_LINES, MAX_LOG_LINES)

    // The performance envelope a phone can actually hold. Each list is what the settings screen
    // offers; the normalisers only clamp, so a value written by a future build is still usable.
    val CONNECTION_CHOICES = listOf(8, 16, 32, 64, 128, 256)
    const val MIN_CONNECTIONS = 1
    const val MAX_CONNECTIONS = 512
    const val DEFAULT_CONNECTIONS = 32
    fun normalizeConnections(value: Int): Int = value.coerceIn(MIN_CONNECTIONS, MAX_CONNECTIONS)

    val WORKER_CHOICES = listOf(1, 2, 4, 8, 16, 32)
    const val MIN_WORKERS = 1
    const val MAX_WORKERS = 64
    const val DEFAULT_WORKERS = 4
    fun normalizeWorkers(value: Int): Int = value.coerceIn(MIN_WORKERS, MAX_WORKERS)

    val TIMEOUT_CHOICES_MILLIS = listOf(5_000, 15_000, 30_000, 60_000, 120_000)
    const val MIN_TIMEOUT_MILLIS = 1_000
    const val MAX_TIMEOUT_MILLIS = 120_000
    const val DEFAULT_REQUEST_TIMEOUT_MILLIS = 15_000
    const val DEFAULT_WRITE_TIMEOUT_MILLIS = 30_000
    fun normalizeTimeoutMillis(value: Int): Int =
        value.coerceIn(MIN_TIMEOUT_MILLIS, MAX_TIMEOUT_MILLIS)

    val RATE_LIMIT_CHOICES = listOf(60, 120, 300, 600, 1_200, 3_000)
    const val MIN_RATE_LIMIT = 1
    const val MAX_RATE_LIMIT = 100_000
    const val DEFAULT_RATE_LIMIT = 300
    fun normalizeRateLimit(value: Int): Int = value.coerceIn(MIN_RATE_LIMIT, MAX_RATE_LIMIT)

    const val BYTES_PER_MEGABYTE = 1L * 1024 * 1024
    val BODY_LIMIT_CHOICES_MEGABYTES = listOf(4, 8, 16, 32, 64)
    const val MIN_BODY_LIMIT_MEGABYTES = 1
    const val MAX_BODY_LIMIT_MEGABYTES = 256
    const val DEFAULT_BODY_LIMIT_MEGABYTES = 64
    fun normalizeBodyLimitMegabytes(value: Long): Long =
        value.coerceIn(MIN_BODY_LIMIT_MEGABYTES.toLong(), MAX_BODY_LIMIT_MEGABYTES.toLong())
}
