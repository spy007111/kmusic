package com.kmusic.apkhd.playback

/** Activity visibility is STARTED/STOPPED, never merely RESUMED/PAUSED. TV detection belongs to TvEnv. */
object PlaybackBackgroundPolicy {
    const val PERMISSION_GRACE_MS = 5_000L

    /** HOME/user-leave and explicit exit override a pending permission request. */
    fun resolve(
        isTv: Boolean,
        visible: Boolean,
        isChangingConfigurations: Boolean,
        finishing: Boolean,
        returningPermission: Boolean = false,
        permissionRequestedAtMs: Long? = null,
        nowMs: Long = 0,
        userLeaving: Boolean = false
    ): Boolean {
        if (!isTv) return false
        if (finishing || userLeaving) return true
        if (visible || isChangingConfigurations) return false
        return !hasPermissionGrace(returningPermission, permissionRequestedAtMs, nowMs)
    }

    /** If nonzero, schedule a visibility recheck after this delay; the exception is never permanent. */
    fun permissionRecheckDelayMs(returningPermission: Boolean, requestedAtMs: Long?, nowMs: Long): Long {
        if (!hasPermissionGrace(returningPermission, requestedAtMs, nowMs)) return 0
        return PERMISSION_GRACE_MS - (nowMs - requestedAtMs!!)
    }

    private fun hasPermissionGrace(returningPermission: Boolean, requestedAtMs: Long?, nowMs: Long): Boolean {
        if (!returningPermission || requestedAtMs == null || requestedAtMs < 0 || nowMs < requestedAtMs) return false
        return nowMs - requestedAtMs < PERMISSION_GRACE_MS
    }
}

/** Feed only Back presses after closing overlays/player/subpages and returning to the home tab. */
class TvExitConfirmation {
    enum class Action { CLOSE_LAYER, GO_HOME, SHOW_HINT, EXIT, DELEGATE }
    private var hintAtMs: Long? = null

    fun back(isTv: Boolean, hasInternalLayer: Boolean, atHome: Boolean, nowMs: Long): Action {
        if (hasInternalLayer) {
            reset()
            return Action.CLOSE_LAYER
        }
        if (!atHome) {
            reset()
            return Action.GO_HOME
        }
        if (!isTv) {
            reset()
            return Action.DELEGATE
        }
        val previous = hintAtMs
        if (previous != null && nowMs >= previous && nowMs - previous < DOUBLE_BACK_MS) {
            reset()
            return Action.EXIT
        }
        hintAtMs = nowMs.takeIf { it >= 0 }
        return Action.SHOW_HINT
    }

    /** Reset on navigation, background, resume, or any consumed non-root Back action. */
    fun reset() { hintAtMs = null }

    companion object { const val DOUBLE_BACK_MS = 2_000L }
}
