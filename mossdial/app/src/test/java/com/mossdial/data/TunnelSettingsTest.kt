package com.mossdial.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TunnelSettingsTest {
    @Test
    fun prefersThePackagedEngineWhenItIsAvailable() {
        assertEquals(
            BUNDLED,
            TunnelSettings.executableFor(
                useBundled = true,
                bundledPath = BUNDLED,
                customPath = CUSTOM
            )
        )
    }

    @Test
    fun usesTheCustomPathWhenTheUserTurnsThePackagedEngineOff() {
        assertEquals(
            CUSTOM,
            TunnelSettings.executableFor(
                useBundled = false,
                bundledPath = BUNDLED,
                customPath = CUSTOM
            )
        )
    }

    @Test
    fun fallsBackToTheCustomPathWhenThePackagedEngineIsMissing() {
        assertEquals(
            CUSTOM,
            TunnelSettings.executableFor(
                useBundled = true,
                bundledPath = null,
                customPath = CUSTOM
            )
        )
    }

    private companion object {
        const val BUNDLED = "/data/app/~~AbCd==/com.mossdial/lib/arm64/libcloudflared.so"
        const val CUSTOM = "/data/local/tmp/cloudflared"
    }
}
