package com.kmusic.apkhd.playback

import com.kmusic.apkhd.data.DeviceEqProfile
import java.security.MessageDigest
import java.util.Locale

/** The owner confirms the AudioTrack route and obtains identities only with user authorization. */
object DeviceEqProfilePolicy {
    enum class Category(val key: String) {
        SPEAKER("speaker"), WIRED("wired"), HDMI("hdmi"), USB("usb"), BLUETOOTH("bluetooth"), UNKNOWN("unknown")
    }

    /** No session deviceId/productName input. USB identity must distinguish units, not only VID/PID. */
    fun bindingKey(category: Category, stableIdentity: String? = null, identityAuthorized: Boolean = false): String? {
        if (category == Category.UNKNOWN) return null
        if (stableIdentity != null && identityAuthorized && stableIdentity.isNotBlank()) {
            val identity = if (category == Category.BLUETOOTH) stableIdentity.trim().uppercase(Locale.ROOT) else stableIdentity
            val namespace = "kmusic:device-eq:v1:${category.key}:"
            val bytes = MessageDigest.getInstance("SHA-256").digest((namespace + identity).toByteArray(Charsets.UTF_8))
            return "deq:v1:${category.key}:" + bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
        // Category matching is an explicit user binding; a USB/BT device is not inferred from its label.
        return "deq:v1:category:${category.key}"
    }

    fun isValidBinding(key: String): Boolean =
        Category.entries.filter { it != Category.UNKNOWN }.any { category ->
            key == "deq:v1:category:${category.key}" ||
                key.matches(Regex("deq:v1:${category.key}:[a-f0-9]{64}"))
        }

    data class Route(
        val category: Category,
        val confirmedCurrentRoute: Boolean,
        /** Already-hashed per-device key built above; never a raw address or session ID. */
        val deviceBindingKey: String? = null
    )

    /** null means GLOBAL. Same names never match, ambiguous bindings never choose arbitrarily. */
    fun select(profiles: List<DeviceEqProfile>, route: Route?, autoEnabled: Boolean): DeviceEqProfile? {
        if (!autoEnabled || route == null || !route.confirmedCurrentRoute || route.category == Category.UNKNOWN) return null
        val deviceKey = route.deviceBindingKey?.takeIf {
            isValidBinding(it) && it.startsWith("deq:v1:${route.category.key}:")
        }
        if (deviceKey != null) {
            val exact = profiles.filter { it.bindingKey == deviceKey }
            if (exact.size > 1) return null
            exact.singleOrNull()?.let { return it.defensiveCopy() }
        }
        val categoryKey = bindingKey(route.category)
        return profiles.filter { it.bindingKey == categoryKey }.singleOrNull()?.defensiveCopy()
    }
}
