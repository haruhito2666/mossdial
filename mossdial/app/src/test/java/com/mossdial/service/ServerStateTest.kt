package com.mossdial.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerStateTest {
    @Test
    fun startsOutStopped() {
        assertEquals(ServerPhase.Stopped, ServerState.snapshot().phase)
        assertFalse(ServerState.isRunning())
        assertFalse(ServerStatus.STOPPED.isActive)
    }

    @Test
    fun aStartingServerCountsAsActiveButNotRunning() {
        try {
            val status = ServerState.publish(
                ServerStatus.starting(8080, allowLan = false, enableTls = false)
            )

            assertTrue(status.isActive)
            assertFalse(ServerState.isRunning())
        } finally {
            ServerState.publish(ServerStatus.STOPPED)
        }
    }

    @Test
    fun publishesEveryPhaseToEveryListener() {
        val seen = mutableListOf<ServerPhase>()
        val listener: (ServerStatus) -> Unit = { seen.add(it.phase) }
        ServerState.addListener(listener)
        try {
            ServerState.publish(ServerStatus.starting(8080, allowLan = true, enableTls = true))
            ServerState.publish(ServerStatus.running(8080, allowLan = true, enableTls = true))
            ServerState.publish(ServerStatus.STOPPED)
        } finally {
            ServerState.removeListener(listener)
        }

        assertEquals(
            listOf(ServerPhase.Starting, ServerPhase.Running, ServerPhase.Stopped),
            seen
        )
    }

    @Test
    fun aRemovedListenerStopsHearingAboutChanges() {
        var calls = 0
        val listener: (ServerStatus) -> Unit = { calls += 1 }
        ServerState.addListener(listener)
        ServerState.removeListener(listener)

        ServerState.publish(ServerStatus.STOPPED)

        assertEquals(0, calls)
    }

    @Test
    fun aMisbehavingListenerCannotStopTheServerFromPublishing() {
        val broken: (ServerStatus) -> Unit = { error("boom") }
        ServerState.addListener(broken)
        try {
            val published = ServerState.publish(
                ServerStatus.running(8443, allowLan = false, enableTls = true)
            )

            assertEquals(ServerPhase.Running, published.phase)
            assertTrue(ServerState.isRunning())
            assertEquals(8443, ServerState.snapshot().port)
        } finally {
            ServerState.removeListener(broken)
            ServerState.publish(ServerStatus.STOPPED)
        }
    }

    @Test
    fun theRunningStatusCarriesTheConfigurationItStartedWith() {
        val status = ServerStatus.running(port = 8443, allowLan = true, enableTls = true)

        assertTrue(status.isActive)
        assertEquals(8443, status.port)
        assertTrue(status.allowLan)
        assertTrue(status.enableTls)
        assertEquals("Serving on port 8443", status.detail)
    }
}
