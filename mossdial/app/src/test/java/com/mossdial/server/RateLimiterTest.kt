package com.mossdial.server

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimiterTest {
    @Test
    fun limitsAndResetsEachClientWindow() {
        var now = 0L
        val limiter = RateLimiter(2, 1_000, 2) { now }
        assertTrue(limiter.tryAcquire("client-a", now))
        assertTrue(limiter.tryAcquire("client-a", now))
        assertFalse(limiter.tryAcquire("client-a", now))
        now = 1_000_000_000L
        assertTrue(limiter.tryAcquire("client-a", now))
        assertEquals(1, limiter.trackedClientCount())
    }

    @Test
    fun keepsClientStateBounded() {
        val limiter = RateLimiter(1, 60_000, 2)
        assertTrue(limiter.tryAcquire("client-a", 0L))
        assertTrue(limiter.tryAcquire("client-b", 0L))
        assertTrue(limiter.tryAcquire("client-c", 0L))
        assertEquals(2, limiter.trackedClientCount())
        assertFalse(limiter.tryAcquire("client-c", 0L))
    }

    @Test
    fun literalAddressesAreNormalisedAndAnythingElseIsRejected() {
        assertEquals(
            RateLimitKeys.fingerprint(InetAddress.getByName("203.0.113.7")),
            RateLimitKeys.fingerprintLiteral("203.0.113.7")
        )
        assertEquals(
            RateLimitKeys.fingerprint(InetAddress.getByName("::1")),
            RateLimitKeys.fingerprintLiteral("::1")
        )
        assertNull("a hostname would cause a lookup", RateLimitKeys.fingerprintLiteral("example.com"))
        assertNull(RateLimitKeys.fingerprintLiteral("999.1.1.1"))
        assertNull(RateLimitKeys.fingerprintLiteral("203.0.113"))
        assertNull(RateLimitKeys.fingerprintLiteral("fe80::1%wlan0"))
    }
}
