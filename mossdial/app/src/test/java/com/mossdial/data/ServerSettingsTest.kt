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
}
