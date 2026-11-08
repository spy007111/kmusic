package com.kmusic.apkhd.playback

import org.junit.Assert.*
import org.junit.Test

class DeviceProfileTransitionTest {
    @Test fun changingRouteDropsOldManualBeforeApplying() {
        val result = DeviceProfileTransition.resolve(1 to 8, 2 to 8, "old")
        assertNull(result.manualId)
        assertTrue(result.clearManual)
        assertEquals(2 to 8, result.rememberedRoute)
    }
    @Test fun nullRouteDropsManualAndDoesNotRememberOldDevice() {
        val result = DeviceProfileTransition.resolve(1 to 8, null, "old")
        assertNull(result.manualId)
        assertNull(result.rememberedRoute)
        assertTrue(result.clearManual)
    }
    @Test fun unchangedRouteKeepsExplicitManualSelection() {
        val result = DeviceProfileTransition.resolve(1 to 8, 1 to 8, "chosen")
        assertEquals("chosen", result.manualId)
        assertFalse(result.clearManual)
    }
    @Test fun firstManualSelectionNeedsNoRoute() {
        assertEquals("manual", DeviceProfileTransition.resolve(null, null, "manual").manualId)
    }
}
