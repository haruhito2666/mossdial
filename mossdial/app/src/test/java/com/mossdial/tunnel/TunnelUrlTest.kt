package com.mossdial.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TunnelUrlTest {
    @Test
    fun findsTheUrlAQuickTunnelPrints() {
        val line = "2026-10-30T10:00:00Z INF +--------------------------------------------------------------------------------------------+\n" +
            "2026-10-30T10:00:00Z INF |  https://spontaneous-test.trycloudflare.com  |\n"

        assertEquals(
            "https://spontaneous-test.trycloudflare.com",
            TunnelUrl.find(line)
        )
    }

    @Test
    fun ignoresOrdinaryLogLines() {
        assertNull(TunnelUrl.find("Registered tunnel connection connIndex=0"))
    }
}
