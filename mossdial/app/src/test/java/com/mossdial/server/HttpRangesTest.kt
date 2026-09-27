package com.mossdial.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpRangesTest {
    @Test
    fun parsesExplicitAndOpenEndedRanges() {
        val explicit = HttpRanges.parse("bytes=2-5", 10)
        val openEnded = HttpRanges.parse("bytes=7-", 10)
        assertEquals(ByteRange(2, 5), (explicit as RangeParseResult.Satisfiable).range)
        assertEquals(ByteRange(7, 9), (openEnded as RangeParseResult.Satisfiable).range)
    }

    @Test
    fun clampsExplicitAndSuffixRanges() {
        val explicit = HttpRanges.parse("bytes=8-99", 10)
        val suffix = HttpRanges.parse("bytes=-4", 10)
        assertEquals(ByteRange(8, 9), (explicit as RangeParseResult.Satisfiable).range)
        assertEquals(ByteRange(6, 9), (suffix as RangeParseResult.Satisfiable).range)
    }

    @Test
    fun reportsUnsatisfiableValidRanges() {
        assertTrue(HttpRanges.parse("bytes=10-12", 10) is RangeParseResult.Unsatisfiable)
        assertTrue(HttpRanges.parse("bytes=-0", 10) is RangeParseResult.Unsatisfiable)
        assertTrue(HttpRanges.parse("bytes=0-0", 0) is RangeParseResult.Unsatisfiable)
        assertTrue(HttpRanges.parse("bytes=0-", 0) is RangeParseResult.Unsatisfiable)
    }

    @Test
    fun ignoresMalformedAndMultipleRanges() {
        assertEquals(RangeParseResult.NotRequested, HttpRanges.parse("items=0-1", 10))
        assertEquals(RangeParseResult.NotRequested, HttpRanges.parse("bytes=5-4", 10))
        assertEquals(RangeParseResult.NotRequested, HttpRanges.parse("bytes=0-1,3-4", 10))
    }
}
