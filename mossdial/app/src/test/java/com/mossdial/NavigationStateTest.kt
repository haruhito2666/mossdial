package com.mossdial

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationStateTest {
    @Test
    fun previousSettingsSelectionsStillOpenSettings() {
        // The four-tab build used index 3; an earlier five-tab build used index 4.
        assertEquals(Destination.Settings, DestinationSaver.restore(3))
        assertEquals(Destination.Settings, DestinationSaver.restore(4))
    }

    @Test
    fun namedSelectionsRestoreAcrossNavigationChanges() {
        Destination.entries.forEach { destination ->
            assertEquals(destination, DestinationSaver.restore(destination.name))
        }
    }

    @Test
    fun unknownSelectionsFallBackToServer() {
        assertEquals(Destination.Server, DestinationSaver.restore(99))
        assertEquals(Destination.Server, DestinationSaver.restore("missing"))
    }
}
