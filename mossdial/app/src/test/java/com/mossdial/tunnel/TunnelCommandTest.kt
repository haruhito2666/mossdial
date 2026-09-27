package com.mossdial.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelCommandTest {
    @Test
    fun buildsTheDocumentedRemotelyManagedTunnelInvocation() {
        val command = TunnelCommand.command(TunnelConfig(EXECUTABLE, TOKEN))

        assertEquals(
            listOf(
                EXECUTABLE,
                "tunnel",
                "--no-autoupdate",
                "run",
                "--token",
                TOKEN,
                "--protocol",
                TunnelConfig.PROTOCOL_HTTP2
            ),
            command
        )
    }

    @Test
    fun passesTheTokenAsExactlyOneArgument() {
        val command = TunnelCommand.command(TunnelConfig(EXECUTABLE, TOKEN))

        val index = command.indexOf("--token")
        assertTrue(index >= 0)
        assertEquals(TOKEN, command[index + 1])
        assertEquals(1, command.count { it == TOKEN })
        assertEquals(command.size, command.distinct().size)
    }

    @Test
    fun omitsAutomaticUpdatesSoTheUserBinaryIsNeverReplaced() {
        val command = TunnelCommand.command(TunnelConfig(EXECUTABLE, TOKEN))

        assertTrue(command.contains("--no-autoupdate"))
        assertFalse(command.any { it.startsWith("--config") })
    }

    @Test
    fun selectsTheRequestedProtocol() {
        val quic = TunnelCommand.command(TunnelConfig(EXECUTABLE, TOKEN, TunnelConfig.PROTOCOL_QUIC))
        val http2 = TunnelCommand.command(TunnelConfig(EXECUTABLE, TOKEN, TunnelConfig.PROTOCOL_HTTP2))

        assertEquals(TunnelConfig.PROTOCOL_QUIC, quic[quic.indexOf("--protocol") + 1])
        assertEquals(TunnelConfig.PROTOCOL_HTTP2, http2[http2.indexOf("--protocol") + 1])
    }

    @Test
    fun argumentListExcludesTheExecutable() {
        val config = TunnelConfig(EXECUTABLE, TOKEN)

        val arguments = TunnelCommand.arguments(config)

        assertFalse(arguments.contains(EXECUTABLE))
        assertEquals(TunnelCommand.command(config).drop(1), arguments)
    }

    @Test
    fun describeRedactsTheTokenButKeepsTheShape() {
        val described = TunnelCommand.describe(TunnelConfig(EXECUTABLE, TOKEN))

        assertFalse(described.contains(TOKEN))
        assertEquals(
            "\"$EXECUTABLE\" tunnel --no-autoupdate run --token *** --protocol http2",
            described
        )
    }

    @Test
    fun describeRedactsATokenThatLooksLikeAFlag() {
        val config = TunnelConfig(EXECUTABLE, TOKEN)

        val described = TunnelCommand.describe(config)

        assertTrue(described.endsWith("--protocol http2"))
        assertFalse(described.split(" ").any { it.startsWith("eyJhIjoi") })
    }

    private companion object {
        const val EXECUTABLE = "/data/local/tmp/cloudflared"
        const val TOKEN = "eyJhIjoiTESTTOKENVALUE0123456789abcdefghij"
    }
}
