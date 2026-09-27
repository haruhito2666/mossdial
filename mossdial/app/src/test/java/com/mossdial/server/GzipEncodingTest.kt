package com.mossdial.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GzipEncodingTest {
    @Test
    fun acceptsCompressibleTextWhenGzipIsEnabled() {
        assertTrue(GzipEncoding.isEligible("text/plain; charset=utf-8", 2_048, "gzip"))
        assertTrue(GzipEncoding.isEligible("application/problem+json", 2_048, "*;q=0.5"))
    }

    @Test
    fun rejectsSmallAndBinaryResponses() {
        assertFalse(GzipEncoding.isEligible("text/plain", 1_023, "gzip"))
        assertFalse(GzipEncoding.isEligible("image/png", 4_096, "gzip"))
        assertFalse(GzipEncoding.isEligible("application/octet-stream", 4_096, "gzip"))
    }

    @Test
    fun honorsZeroQualityAndExplicitGzipChoice() {
        assertFalse(GzipEncoding.isEligible("text/css", 4_096, "gzip;q=0, *;q=1"))
        assertFalse(GzipEncoding.isEligible("text/css", 4_096, "*;q=0"))
        assertTrue(GzipEncoding.isEligible("application/json", 4_096, "br, gzip;q=0.5"))
    }
}
