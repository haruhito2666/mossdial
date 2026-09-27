package com.mossdial.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerSettingsRulesTest {
    @Test
    fun acceptsOnlyPortsInsideTheUnprivilegedRange() {
        assertFalse(ServerSettingsRules.isValidPort(0))
        assertFalse(ServerSettingsRules.isValidPort(1023))
        assertFalse(ServerSettingsRules.isValidPort(65536))
        assertTrue(ServerSettingsRules.isValidPort(1024))
        assertTrue(ServerSettingsRules.isValidPort(8080))
        assertTrue(ServerSettingsRules.isValidPort(65535))
    }

    @Test
    fun clampsAnyStoredValueIntoRange() {
        assertEquals(1024, ServerSettingsRules.normalizePort(80))
        assertEquals(65535, ServerSettingsRules.normalizePort(70000))
        assertEquals(8080, ServerSettingsRules.normalizePort(8080))
    }

    @Test
    fun rejectsTextThatIsNotAPort() {
        assertNull(ServerSettingsRules.parsePort(null))
        assertNull(ServerSettingsRules.parsePort(""))
        assertNull(ServerSettingsRules.parsePort("   "))
        assertNull(ServerSettingsRules.parsePort("80a"))
        assertNull(ServerSettingsRules.parsePort("-8080"))
        assertNull(ServerSettingsRules.parsePort("8080.0"))
        assertNull(ServerSettingsRules.parsePort("8 080"))
        assertNull(ServerSettingsRules.parsePort("123456"))
    }

    @Test
    fun rejectsPortsOutsideTheRangeWithoutRewritingThem() {
        assertNull(ServerSettingsRules.parsePort("80"))
        assertNull(ServerSettingsRules.parsePort("1023"))
        assertNull(ServerSettingsRules.parsePort("65536"))
        assertNull(ServerSettingsRules.parsePort("00000"))
    }

    @Test
    fun parsesValidPortsAndToleratesPadding() {
        assertEquals(8080, ServerSettingsRules.parsePort("8080"))
        assertEquals(1024, ServerSettingsRules.parsePort("1024"))
        assertEquals(65535, ServerSettingsRules.parsePort("65535"))
        assertEquals(8443, ServerSettingsRules.parsePort(" 8443 "))
    }

    @Test
    fun fallsBackToTheDefaultPortOnlyWhenAsked() {
        assertEquals(8080, ServerSettingsRules.parsePortOrDefault("not a port"))
        assertEquals(8443, ServerSettingsRules.parsePortOrDefault("8443"))
    }

    @Test
    fun bindsEveryInterfaceOnlyWhenLanIsAllowed() {
        assertEquals("127.0.0.1", ServerSettingsRules.bindAddress(allowLan = false))
        assertEquals("0.0.0.0", ServerSettingsRules.bindAddress(allowLan = true))
    }
}
