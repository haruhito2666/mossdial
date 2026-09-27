package com.mossdial.aiapi

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The bearer credential the API is opened with, and how a request is checked against it.
 *
 * The token is 256 bits of [SecureRandom] output in unpadded base64url, so it can be pasted into
 * an `Authorization` header, a query string in a test, or a client's API key field without any
 * escaping. It is stored by the caller in the Keystore-backed secret store, never in preferences
 * and never in a log line.
 */
object AiApiToken {
    const val SCHEME = "Bearer"
    const val BYTES = 32
    const val MIN_LENGTH = 40
    const val MAX_LENGTH = 64

    private const val ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private val random = SecureRandom()

    /** A fresh token. Two calls never return the same value. */
    fun generate(): String {
        val bytes = ByteArray(BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** Whether [token] has the shape this object produces, and so is safe to store and compare. */
    fun isValid(token: String): Boolean =
        token.length in MIN_LENGTH..MAX_LENGTH && token.all { it in ALPHABET }

    /**
     * The token in an `Authorization` header, or null when the header is not a bearer one.
     *
     * The scheme is matched case-insensitively, because that is what the header allows, and only
     * the value after it is returned: nothing here interprets the token itself.
     */
    fun bearerToken(authorization: String?): String? {
        val header = authorization?.trim().orEmpty()
        val separator = header.indexOf(' ')
        if (separator <= 0) return null
        if (!header.substring(0, separator).equals(SCHEME, ignoreCase = true)) return null
        return header.substring(separator + 1).trim().ifEmpty { null }
    }

    /**
     * Whether the request presented [expected].
     *
     * The comparison itself is the platform's constant-time one, so a caller cannot learn the
     * stored token one byte at a time by measuring how long a guess takes. It walks the length of
     * the expected token, so the one thing a caller can observe is that its own guess had the
     * wrong length; both values are the same fixed length by construction here.
     */
    fun matches(expected: String, authorization: String?): Boolean {
        if (expected.isEmpty()) return false
        val presented = bearerToken(authorization) ?: return false
        return MessageDigest.isEqual(
            expected.toByteArray(StandardCharsets.UTF_8),
            presented.toByteArray(StandardCharsets.UTF_8)
        )
    }
}
