package com.mossdial.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelResolverTest {
    @Test
    fun buildsAConfigurationFromCompleteSettings() {
        val resolved = TunnelResolver.resolve(EXECUTABLE, TOKEN, TunnelConfig.PROTOCOL_QUIC)

        assertTrue(resolved is TunnelResolution.Ready)
        val config = (resolved as TunnelResolution.Ready).config
        assertEquals(EXECUTABLE, config.executablePath)
        assertEquals(TOKEN, config.token)
        assertEquals(TunnelConfig.PROTOCOL_QUIC, config.protocol)
    }

    @Test
    fun reportsAMissingExecutablePathFirst() {
        val resolved = TunnelResolver.resolve("", TOKEN, TunnelConfig.PROTOCOL_HTTP2)

        assertTrue(resolved is TunnelResolution.Invalid)
        assertTrue((resolved as TunnelResolution.Invalid).reason.contains("executable"))
    }

    @Test
    fun reportsAMissingOrAbsentToken() {
        listOf(null, "", "   ").forEach { token ->
            val resolved = TunnelResolver.resolve(EXECUTABLE, token, TunnelConfig.PROTOCOL_HTTP2)

            assertTrue("token=$token", resolved is TunnelResolution.Invalid)
            assertTrue((resolved as TunnelResolution.Invalid).reason.contains("token"))
        }
    }

    @Test
    fun reportsTheValidationFailureForAnUnusablePath() {
        val resolved = TunnelResolver.resolve("cloudflared", TOKEN, TunnelConfig.PROTOCOL_HTTP2)

        assertTrue(resolved is TunnelResolution.Invalid)
        assertTrue((resolved as TunnelResolution.Invalid).reason.contains("absolute"))
    }

    @Test
    fun reportsTheValidationFailureForAnUnusableProtocol() {
        val resolved = TunnelResolver.resolve(EXECUTABLE, TOKEN, "smoke-signal")

        assertTrue(resolved is TunnelResolution.Invalid)
        assertFalse((resolved as TunnelResolution.Invalid).reason.contains(TOKEN))
    }

    @Test
    fun neverEchoesTheTokenInAFailureReason() {
        val rejected = listOf("x" to TOKEN, EXECUTABLE to "short", "bad path" to TOKEN)
            .map { (path, token) -> TunnelResolver.resolve(path, token, TunnelConfig.PROTOCOL_HTTP2) }
            .filterIsInstance<TunnelResolution.Invalid>()
            .map { it.reason }

        assertTrue(rejected.isNotEmpty())
        rejected.forEach { reason ->
            assertFalse(reason.contains(TOKEN))
        }
    }

    private companion object {
        const val EXECUTABLE = "/data/local/tmp/cloudflared"
        const val TOKEN = "eyJhIjoiTESTTOKENVALUE0123456789abcdefghij"
    }
}
