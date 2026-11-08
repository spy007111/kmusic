package com.kmusic.apkhd.playback

import com.kmusic.apkhd.data.DeviceEqProfile
import org.junit.Assert.*
import org.junit.Test

class DeviceEqProfilePolicyTest {
    private fun profile(id: String, binding: String?) = DeviceEqProfile(id = id, name = "Same user label", bindingKey = binding)
    private val speaker = DeviceEqProfilePolicy.Category.SPEAKER
    private val usb = DeviceEqProfilePolicy.Category.USB
    private val bluetooth = DeviceEqProfilePolicy.Category.BLUETOOTH

    @Test fun categoryKeysAreStableAndUnknownHasNoKey() {
        assertEquals("deq:v1:category:speaker", DeviceEqProfilePolicy.bindingKey(speaker))
        assertNotEquals(DeviceEqProfilePolicy.bindingKey(speaker), DeviceEqProfilePolicy.bindingKey(DeviceEqProfilePolicy.Category.WIRED))
        assertNull(DeviceEqProfilePolicy.bindingKey(DeviceEqProfilePolicy.Category.UNKNOWN, "id", true))
    }

    @Test fun trustedIdentityIsNamespacedHashedAndNeverContainsRawIdentity() {
        val raw = "AA:BB:CC:DD:EE:FF"
        val key = DeviceEqProfilePolicy.bindingKey(bluetooth, raw, true)!!
        assertFalse(key.contains(raw))
        assertTrue(DeviceEqProfilePolicy.isValidBinding(key))
        assertEquals(key, DeviceEqProfilePolicy.bindingKey(bluetooth, raw.lowercase(), true))
        assertNotEquals(key, DeviceEqProfilePolicy.bindingKey(bluetooth, "AA:BB:CC:DD:EE:00", true))
        assertNotEquals(key, DeviceEqProfilePolicy.bindingKey(usb, raw, true))
    }

    @Test fun unauthorizedIdentityDoesNotGenerateDeviceBinding() {
        assertEquals(DeviceEqProfilePolicy.bindingKey(bluetooth), DeviceEqProfilePolicy.bindingKey(bluetooth, "private-address", false))
        assertEquals(DeviceEqProfilePolicy.bindingKey(usb), DeviceEqProfilePolicy.bindingKey(usb, "unpermitted-serial", false))
        assertEquals(DeviceEqProfilePolicy.bindingKey(usb), DeviceEqProfilePolicy.bindingKey(usb, "", true))
        assertFalse(DeviceEqProfilePolicy.isValidBinding("AA:BB:CC:DD:EE:FF"))
        assertFalse(DeviceEqProfilePolicy.isValidBinding("session-device-7"))
        assertFalse(DeviceEqProfilePolicy.isValidBinding("deq:v1:category:unknown"))
    }

    @Test fun usbSameModelDifferentReliableSerialsHaveDifferentKeys() {
        val first = DeviceEqProfilePolicy.bindingKey(usb, "VID1234:PID5678:serial-a", true)
        val second = DeviceEqProfilePolicy.bindingKey(usb, "VID1234:PID5678:serial-b", true)
        assertNotEquals(first, second)
    }

    @Test fun autoDisabledUnknownAndUnconfirmedRoutesFallBackToGlobal() {
        val profiles = listOf(profile("speaker", DeviceEqProfilePolicy.bindingKey(speaker)))
        assertNull(DeviceEqProfilePolicy.select(profiles, null, true))
        assertNull(DeviceEqProfilePolicy.select(profiles, DeviceEqProfilePolicy.Route(speaker, false), true))
        assertNull(DeviceEqProfilePolicy.select(profiles, DeviceEqProfilePolicy.Route(speaker, true), false))
        assertNull(DeviceEqProfilePolicy.select(profiles, DeviceEqProfilePolicy.Route(DeviceEqProfilePolicy.Category.UNKNOWN, true), true))
    }

    @Test fun explicitCategoryBindingWorksButUnboundManualProfileNeverAutoMatches() {
        val p = profile("category", DeviceEqProfilePolicy.bindingKey(usb))
        assertEquals(p, DeviceEqProfilePolicy.select(listOf(p), DeviceEqProfilePolicy.Route(usb, true), true))
        assertNull(DeviceEqProfilePolicy.select(listOf(profile("manual", null)), DeviceEqProfilePolicy.Route(usb, true), true))
        assertNull(DeviceEqProfilePolicy.select(listOf(p), DeviceEqProfilePolicy.Route(speaker, true), true))
    }

    @Test fun exactDeviceBindingWinsAndDuplicateNamesDoNotMatchOtherDevice() {
        val a = DeviceEqProfilePolicy.bindingKey(bluetooth, "device-a", true)
        val b = DeviceEqProfilePolicy.bindingKey(bluetooth, "device-b", true)
        val profiles = listOf(profile("category", DeviceEqProfilePolicy.bindingKey(bluetooth)), profile("a", a), profile("b", b))
        assertEquals("a", DeviceEqProfilePolicy.select(profiles, DeviceEqProfilePolicy.Route(bluetooth, true, a), true)!!.id)
        assertEquals("b", DeviceEqProfilePolicy.select(profiles, DeviceEqProfilePolicy.Route(bluetooth, true, b), true)!!.id)
        assertEquals("category", DeviceEqProfilePolicy.select(profiles, DeviceEqProfilePolicy.Route(bluetooth, true), true)!!.id)
    }

    @Test fun noNameBasedFallbackAndWrongCategoryDeviceKeyCannotApply() {
        val key = DeviceEqProfilePolicy.bindingKey(bluetooth, "device-a", true)
        val p = profile("bt", key)
        assertNull(DeviceEqProfilePolicy.select(listOf(p), DeviceEqProfilePolicy.Route(usb, true, key), true))
        assertNull(DeviceEqProfilePolicy.select(listOf(p), DeviceEqProfilePolicy.Route(bluetooth, true, "Same user label"), true))
    }

    @Test fun ambiguousBindingsDoNotSelectArbitrarily() {
        val key = DeviceEqProfilePolicy.bindingKey(bluetooth, "device-a", true)
        val profiles = listOf(profile("one", key), profile("two", key))
        assertNull(DeviceEqProfilePolicy.select(profiles, DeviceEqProfilePolicy.Route(bluetooth, true, key), true))
        val category = DeviceEqProfilePolicy.bindingKey(usb)
        assertNull(DeviceEqProfilePolicy.select(listOf(profile("one", category), profile("two", category)), DeviceEqProfilePolicy.Route(usb, true), true))
    }
}
