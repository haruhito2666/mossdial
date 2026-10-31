package com.mossdial.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelConfigTest {
    @Test
    fun buildsConfigFromValidSettings() {
        val config = TunnelConfig(EXECUTABLE, TOKEN)

        assertEquals(EXECUTABLE, config.executablePath)
        assertEquals(TOKEN, config.token)
        assertEquals(TunnelConfig.PROTOCOL_HTTP2, config.protocol)
    }

    @Test
    fun rejectsBlankRelativeAndNonAbsolutePaths() {
        assertThrows(IllegalArgumentException::class.java) { TunnelConfig("", TOKEN) }
        assertThrows(IllegalArgumentException::class.java) { TunnelConfig("   ", TOKEN) }
        assertThrows(IllegalArgumentException::class.java) { TunnelConfig("cloudflared", TOKEN) }
        assertThrows(IllegalArgumentException::class.java) { TunnelConfig("./cloudflared", TOKEN) }
    }

    @Test
    fun rejectsPathsThatSmuggleShellSyntax() {
        // The command is never handed to a shell, but a pasted shell snippet must not be
        // accepted as a path either: it can only ever be a configuration mistake.
        val rejected = listOf(
            "/data/local/tmp/cloudflared; rm -rf /",
            "/data/local/tmp/cloudflared && id",
            "/data/local/tmp/cloudflared | cat",
            "/data/local/tmp/my cloudflared",
            "/data/local/tmp/cloudflared\nid",
            "/data/local/tmp/\$(id)",
            "/data/local/tmp/`id`",
            "/data/local/tmp/cloudflared\u0000",
            "/data/local/tmp/../tmp/cloudflared/../../cloudflared\u0000",
            "/data/local/tmp/c*"
        )

        rejected.forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { TunnelConfig(path, TOKEN) }
        }
    }

    @Test
    fun rejectsPathThatIsTooLong() {
        val path = "/" + "a".repeat(TunnelConfig.MAX_EXECUTABLE_PATH_LENGTH)

        assertThrows(IllegalArgumentException::class.java) { TunnelConfig(path, TOKEN) }
    }

    @Test
    fun rejectsTokensThatCannotBeATunnelToken() {
        val rejected = listOf(
            "",
            "   ",
            "short",
            "token with spaces",
            "token\nwith\nnewlines",
            "token\u0000value",
            "token\tvalue"
        )

        rejected.forEach { token ->
            assertThrows(IllegalArgumentException::class.java) { TunnelConfig(EXECUTABLE, token) }
        }
    }

    @Test
    fun rejectsOversizedToken() {
        val token = "a".repeat(TunnelConfig.MAX_TOKEN_LENGTH + 1)

        assertThrows(IllegalArgumentException::class.java) { TunnelConfig(EXECUTABLE, token) }
    }

    @Test
    fun acceptsTokenAtBothLengthBounds() {
        val shortest = "a".repeat(TunnelConfig.MIN_TOKEN_LENGTH)
        val longest = "a".repeat(TunnelConfig.MAX_TOKEN_LENGTH)

        assertEquals(shortest, TunnelConfig(EXECUTABLE, shortest).token)
        assertEquals(longest, TunnelConfig(EXECUTABLE, longest).token)
    }

    @Test
    fun rejectsATunnelIdWhereATokenBelongs() {
        val id = "9037c7c6-5d35-4ab3-8233-e5e9a40083e0"

        val error = assertThrows(IllegalArgumentException::class.java) {
            TunnelConfig(EXECUTABLE, id)
        }

        assertTrue(error.message.orEmpty().contains("Tunnel ID"))
    }

    @Test
    fun buildsAQuickTunnelWithoutAToken() {
        val config = TunnelConfig.quick(EXECUTABLE, 8080)

        assertTrue(config.quick)
        assertEquals("", config.token)
        assertEquals(8080, config.port)
    }

    @Test
    fun rejectsAnUnusableQuickTunnelPort() {
        assertThrows(IllegalArgumentException::class.java) {
            TunnelConfig.quick(EXECUTABLE, 80)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TunnelConfig.quick(EXECUTABLE, 70_000)
        }
    }

    @Test
    fun rejectsUnknownProtocol() {
        assertThrows(IllegalArgumentException::class.java) {
            TunnelConfig(EXECUTABLE, TOKEN, "carrier-pigeon")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TunnelConfig(EXECUTABLE, TOKEN, "HTTP2")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TunnelConfig(EXECUTABLE, TOKEN, "")
        }
    }

    @Test
    fun acceptsEveryAdvertisedProtocol() {
        TunnelConfig.ALLOWED_PROTOCOLS.forEach { protocol ->
            assertEquals(protocol, TunnelConfig(EXECUTABLE, TOKEN, protocol).protocol)
        }
    }

    @Test
    fun allowsJoinsAndDotsInPathSegments() {
        val path = "/data/local/tmp/my_tunnel-1.0+build/cloudflared"

        assertEquals(path, TunnelConfig(path, TOKEN).executablePath)
    }

    @Test
    fun allowsTheShapeOfAnAndroidNativeLibraryDirectory() {
        val path = "/data/app/~~AbCd==/com.mossdial-XyZ==/lib/arm64/libcloudflared.so"

        assertEquals(path, TunnelConfig(path, TOKEN).executablePath)
    }

    @Test
    fun describeNeverContainsTheToken() {
        val described = TunnelCommand.describe(TunnelConfig(EXECUTABLE, TOKEN, TunnelConfig.PROTOCOL_QUIC))

        assertFalse(described.contains(TOKEN))
        assertTrue(described.contains("***"))
        assertTrue(described.contains("run"))
    }

    private companion object {
        const val EXECUTABLE = "/data/local/tmp/cloudflared"
        const val TOKEN = "eyJhIjoiTESTTOKENVALUE0123456789abcdefghij"
    }
}
