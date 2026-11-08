package com.kmusic.apkhd.playback

import com.kmusic.apkhd.data.DeviceEqProfile
import org.junit.Assert.*
import org.junit.Test

class DeviceProfileTransitionIntegrationTest {
    private fun profile(id: String, category: DeviceEqProfilePolicy.Category) = DeviceEqProfile(
        id = id, name = id, bindingKey = DeviceEqProfilePolicy.bindingKey(category))

    @Test fun routeChangeSelectsNewAutoProfileWithoutOldManualSubmission() {
        val a = profile("old-manual", DeviceEqProfilePolicy.Category.BLUETOOTH)
        val b = profile("new-wired", DeviceEqProfilePolicy.Category.WIRED)
        val profiles = listOf(a, b)
        val decision = DeviceProfileTransition.resolve(1 to 8, 2 to 3, a.id)
        val selected = profiles.firstOrNull { it.id == decision.manualId }
            ?: DeviceEqProfilePolicy.select(profiles,
                DeviceEqProfilePolicy.Route(DeviceEqProfilePolicy.Category.WIRED, true), true)
        assertEquals(b, selected)
        assertTrue(decision.clearManual)
        assertNotEquals(a.id, selected!!.id)
    }

    @Test fun disabledAutoAfterRouteChangeFallsBackToGlobalNotOldManual() {
        val a = profile("old-manual", DeviceEqProfilePolicy.Category.BLUETOOTH)
        val decision = DeviceProfileTransition.resolve(1 to 8, 2 to 3, a.id)
        val selected = listOf(a).firstOrNull { it.id == decision.manualId }
            ?: DeviceEqProfilePolicy.select(listOf(a),
                DeviceEqProfilePolicy.Route(DeviceEqProfilePolicy.Category.WIRED, true), false)
        assertNull(selected)
    }

    @Test fun disconnectThenReconnectDoesNotReviveClearedManual() {
        val disconnected = DeviceProfileTransition.resolve(1 to 8, null, "manual")
        assertTrue(disconnected.clearManual)
        assertNull(disconnected.manualId)
        val reconnected = DeviceProfileTransition.resolve(disconnected.rememberedRoute, 1 to 8, disconnected.manualId)
        assertNull(reconnected.manualId)
        assertFalse(reconnected.clearManual)
        assertEquals(1 to 8, reconnected.rememberedRoute)
    }

    @Test fun sameDeviceIdWithChangedTypeDropsManualBeforeApplication() {
        val result = DeviceProfileTransition.resolve(1 to 8, 1 to 3, "manual")
        assertNull(result.manualId)
        assertTrue(result.clearManual)
    }
}
