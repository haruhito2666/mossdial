package com.mossdial.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory stand-in so the settings rules are exercised without an Android runtime. */
private class FakePreferenceStore(
    private val values: MutableMap<String, Any>
) : PreferenceStore {
    constructor() : this(mutableMapOf())

    override fun getBoolean(key: String, fallback: Boolean): Boolean =
        values[key] as? Boolean ?: fallback

    override fun putBoolean(key: String, value: Boolean) {
        values[key] = value
    }

    override fun getInt(key: String, fallback: Int): Int = values[key] as? Int ?: fallback

    override fun putInt(key: String, value: Int) {
        values[key] = value
    }

    override fun getString(key: String, fallback: String): String =
        values[key] as? String ?: fallback

    override fun putString(key: String, value: String) {
        values[key] = value
    }
}

class ServerSettingsTest {
    @Test
    fun defaultsToLoopbackHttpWithoutAutoStart() {
        val settings = ServerSettings(FakePreferenceStore())

        assertEquals(8080, settings.port)
        assertFalse(settings.allowLan)
        assertFalse(settings.enableSsl)
        assertFalse(settings.autoStart)
        assertFalse(settings.desiredRunning)
        assertEquals("127.0.0.1", settings.bindAddress())
    }

    @Test
    fun persistsEveryValueItIsGiven() {
        val values = mutableMapOf<String, Any>()
        val settings = ServerSettings(FakePreferenceStore(values))

        settings.port = 8443
        settings.allowLan = true
        settings.enableSsl = true
        settings.autoStart = true

        val reopened = ServerSettings(FakePreferenceStore(values))
        assertEquals(8443, reopened.port)
        assertTrue(reopened.allowLan)
        assertTrue(reopened.enableSsl)
        assertTrue(reopened.autoStart)
        assertEquals("0.0.0.0", reopened.bindAddress())
    }

    @Test
    fun normalisesAPortOnTheWayInAndOnTheWayOut() {
        val values = mutableMapOf<String, Any>()
        val settings = ServerSettings(FakePreferenceStore(values))

        settings.port = 80
        assertEquals(1024, settings.port)

        settings.port = 70_000
        assertEquals(65535, settings.port)

        val hostile = ServerSettings(FakePreferenceStore(mutableMapOf("port" to 1)))
        assertEquals(1024, hostile.port)
    }

    @Test
    fun autoStartStaysOffUntilItIsExplicitlyTurnedOn() {
        val values = mutableMapOf<String, Any>()

        ServerSettings(FakePreferenceStore(values)).apply {
            port = 9000
            allowLan = true
            enableSsl = true
        }

        assertFalse(ServerSettings(FakePreferenceStore(values)).autoStart)
    }

    @Test
    fun persistsTheHostedSiteFolderAndItsKind() {
        val values = mutableMapOf<String, Any>()
        val settings = ServerSettings(FakePreferenceStore(values))
        assertEquals("", settings.sitePath)
        assertEquals(SiteKind.CUSTOM, settings.siteKind)
        assertEquals("", settings.siteBasePath)

        settings.sitePath = "/docs/site/"
        settings.siteKind = SiteKind.SPA
        settings.siteBasePath = "/repo/"

        assertEquals("docs/site", settings.sitePath)
        assertEquals(SiteKind.SPA, settings.siteKind)
        assertEquals("repo", settings.siteBasePath)

        val reopened = ServerSettings(FakePreferenceStore(values))
        assertEquals("docs/site", reopened.sitePath)
        assertEquals(SiteKind.SPA, reopened.siteKind)
        assertEquals("repo", reopened.siteBasePath)
        assertTrue(reopened.siteKind.spaFallback)

        settings.sitePath = "  "
        settings.siteBasePath = "../outside"

        assertEquals("", settings.sitePath)
        assertEquals("", settings.siteBasePath)

        val hostile = ServerSettings(
            FakePreferenceStore(mutableMapOf("site_kind" to "not-a-kind"))
        )
        assertEquals(SiteKind.CUSTOM, hostile.siteKind)
    }

    @Test
    fun theDesiredRunningIntentIsSeparateFromAutoStartAndSurvivesAReopen() {
        val values = mutableMapOf<String, Any>()
        val settings = ServerSettings(FakePreferenceStore(values))
        assertFalse(settings.desiredRunning)

        settings.desiredRunning = true

        val reopened = ServerSettings(FakePreferenceStore(values))
        assertTrue(reopened.desiredRunning)
        assertFalse(reopened.autoStart)

        reopened.desiredRunning = false
        assertFalse(ServerSettings(FakePreferenceStore(values)).desiredRunning)
    }
}
