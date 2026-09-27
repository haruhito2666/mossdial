package com.mossdial.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpCachingTest {
    @Test
    fun formatsQuotedWeakEtags() {
        assertEquals("W/\"a-0\"", HttpCaching.formatEtag(10, 0L))
        assertEquals("W/\"1f-10\"", HttpCaching.formatEtag(31, 16L))
    }

    @Test
    fun matchesEtagsWithWeakComparison() {
        val etag = HttpCaching.formatEtag(10, 0L)
        assertTrue(HttpCaching.ifNoneMatchMatches("\"a-0\"", etag))
        assertTrue(HttpCaching.ifNoneMatchMatches("W/\"a-0\"", etag))
        assertTrue(HttpCaching.ifNoneMatchMatches("\"other\", $etag", etag))
        assertTrue(HttpCaching.ifNoneMatchMatches("*", etag))
    }
}
