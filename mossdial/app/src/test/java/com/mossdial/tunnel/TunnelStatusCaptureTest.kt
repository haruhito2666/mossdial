package com.mossdial.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelStatusCaptureTest {
    @Test
    fun keepsOnlyTheMostRecentBoundedTail() {
        var tail: String? = null
        repeat(400) { index ->
            tail = TunnelStatusCapture.append(tail.orEmpty(), "chunk-$index ", secrets)
        }

        assertTrue(tail!!.length <= TunnelStatusCapture.MAX_KEPT_CHARS)
        assertTrue(tail!!.endsWith("chunk-399 "))
        assertFalse(tail!!.contains("chunk-0 "))
    }
    @Test
    fun clipNeverExceedsTheDisplayLimit() {
        val line = TunnelStatusCapture.line("x".repeat(TunnelStatusCapture.MAX_CAPTURED_CHARS * 3), secrets)

        assertEquals(TunnelStatusCapture.MAX_CAPTURED_CHARS, line.length)
        assertTrue(line.endsWith(TunnelStatusCapture.CLIP_MARKER))
        assertTrue(line.startsWith("xxx"))
    }

    @Test
    fun collapsesEveryLineBreakIntoASingleLine() {
        val line = TunnelStatusCapture.line("first\nsecond\r\nthird", secrets)

        assertEquals("first second third", line)
    }

    @Test
    fun stripsTerminalEscapeSequences() {
        val line = TunnelStatusCapture.line("before\u001B[31mred\u001B[0mafter", secrets)

        assertEquals("before [31mred [0mafter", line)
    }

    @Test
    fun removesTheTokenFromCapturedOutput() {
        val tail = TunnelStatusCapture.append("", "auth failed for token $TOKEN here", listOf(TOKEN))

        assertFalse(tail!!.contains(TOKEN))
        assertEquals("auth failed for token *** here", TunnelStatusCapture.line(tail))
    }

    @Test
    fun removesTheTokenEvenWhenItWasSplitAcrossCaptures() {
        val first = TunnelStatusCapture.append("", "token=${TOKEN.take(12)}", listOf(TOKEN))
        val second = TunnelStatusCapture.append(first.orEmpty(), TOKEN.drop(12), listOf(TOKEN))

        val line = TunnelStatusCapture.line(second.orEmpty(), listOf(TOKEN))

        assertFalse(line.contains(TOKEN.take(12)))
        assertFalse(line.contains(TOKEN.drop(12)))
    }

    @Test
    fun ignoresChunksWithoutDisplayableContent() {
        assertNull(TunnelStatusCapture.append("", "   \n\t  ", secrets))
        assertNull(TunnelStatusCapture.append("", "", secrets))
        assertNull(TunnelStatusCapture.append("kept", "\r\n", secrets))
        assertEquals("kept", TunnelStatusCapture.line("kept", secrets))
    }

    @Test
    fun blankTailProducesBlankLine() {
        assertEquals("", TunnelStatusCapture.line("   ", secrets))
    }

    private companion object {
        val secrets = listOf("eyJhIjoiTESTTOKENVALUE0123456789abcdefghij")
        const val TOKEN = "eyJhIjoiTESTTOKENVALUE0123456789abcdefghij"
    }
}
