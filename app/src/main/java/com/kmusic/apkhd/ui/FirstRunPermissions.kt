package com.kmusic.apkhd.ui

import java.util.Collections

object FirstRunPermissionPlan {
    const val READ_EXTERNAL_STORAGE = "android.permission.READ_EXTERNAL_STORAGE"
    const val READ_MEDIA_AUDIO = "android.permission.READ_MEDIA_AUDIO"
    const val READ_PHONE_STATE = "android.permission.READ_PHONE_STATE"
    const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

    fun select(sdkInt: Int, isTv: Boolean, hasTelephony: Boolean, granted: Set<String>): List<String> {
        val permissions = buildList {
            if (sdkInt >= 33) add(READ_MEDIA_AUDIO)
            else if (sdkInt >= 26) add(READ_EXTERNAL_STORAGE)
            if (!isTv && hasTelephony) add(READ_PHONE_STATE)
            if (!isTv && sdkInt >= 33) add(POST_NOTIFICATIONS)
        }.filterNot(granted::contains)
        return Collections.unmodifiableList(permissions)
    }
}

/** One request in flight; denial and launch failure consume the step just like a grant. */
class FirstRunPermissionCoordinator(plan: List<String>) {
    private val permissions = plan.distinct().toList()
    private var index = 0
    var pending: String? = null
        private set
    var completed = permissions.isEmpty()
        private set

    fun nextPermission(granted: Set<String> = emptySet()): String? {
        if (completed || pending != null) return null
        while (index < permissions.size && permissions[index] in granted) index++
        if (index >= permissions.size) { completed = true; return null }
        return permissions[index].also { pending = it }
    }

    fun onResult(permission: String, granted: Boolean): Boolean = consume(permission)

    fun onRequestFailed(permission: String): Boolean = consume(permission)

    private fun consume(permission: String): Boolean {
        if (completed || pending != permission) return false
        pending = null
        index++
        completed = index >= permissions.size
        return true
    }

    fun finish() {
        pending = null
        index = permissions.size
        completed = true
    }
}
