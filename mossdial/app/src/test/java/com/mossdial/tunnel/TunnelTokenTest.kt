package com.mossdial.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TunnelTokenTest {
    @Test
    fun acceptsABareToken() {
        assertEquals(TOKEN, TunnelToken.extract(TOKEN))
        assertEquals(TOKEN, TunnelToken.extract("  \"$TOKEN\"  "))
    }

    @Test
    fun extractsTheTokenFromTheFullCloudflareCommand() {
        assertEquals(
            TOKEN,
            TunnelToken.extract(
                "cloudflared tunnel --no-autoupdate run --token $TOKEN"
            )
        )
        assertEquals(
            TOKEN,
            TunnelToken.extract("cloudflared tunnel run --token=$TOKEN")
        )
        assertEquals(
            TOKEN,
            TunnelToken.extract("cloudflared tunnel run --token=\"$TOKEN\"")
        )
        assertEquals(
            TOKEN,
            TunnelToken.extract("cloudflared.exe service install $TOKEN")
        )
    }

    @Test
    fun rejectsTextWithoutAToken() {
        assertNull(TunnelToken.extract(""))
        assertNull(TunnelToken.extract("cloudflared tunnel run"))
        assertNull(TunnelToken.extract("two words"))
    }

    private companion object {
        const val TOKEN = "eyJhIjoiTESTTOKENVALUE0123456789abcdefghij"
    }
}
