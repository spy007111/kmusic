package com.kmusic.apkhd.playback

enum class CellularCallState { UNKNOWN, IDLE, RINGING, OFFHOOK }
enum class CallMonitorStatus { DISABLED, PERMISSION_REQUIRED, UNAVAILABLE, MONITORING, ERROR }

data class CallPlaybackSnapshot(
    val mediaId: String?,
    val positionMs: Long,
    val playbackEpoch: Long,
    val desiredPlaying: Boolean,
    val route: OutputRoute?
)

/** Cellular evidence is mandatory. A non-call permanent focus loss alone never authorizes play. */
class CallResumePolicy(
    private val correlationWindowMs: Long = 3_000,
    private val settleMs: Long = 600,
    private val recoveryWindowMs: Long = 8_000,
    private val maxAttempts: Int = 3
) {
    data class Candidate(val before: CallPlaybackSnapshot, val expectedEpoch: Long)
    var callState = CellularCallState.UNKNOWN
        private set
    var candidate: Candidate? = null
        private set
    val pending: Boolean get() = candidate != null
    val active: Boolean get() = callState == CellularCallState.RINGING || callState == CellularCallState.OFFHOOK
    private var priorFocus: Pair<CallPlaybackSnapshot, Long>? = null
    private var idleAtMs: Long? = null
    private var stableRoute: OutputRoute? = null
    private var routeAtMs = 0L
    private var attempts = 0
    private var lastAttemptMs = Long.MIN_VALUE

    fun stateChanged(state: CellularCallState, playback: CallPlaybackSnapshot, nowMs: Long, enabled: Boolean): Boolean {
        if (state == callState) return false
        val wasActive = active
        callState = state
        if (!enabled) { cancel(); return false }
        if (active && !wasActive) {
            val focus = priorFocus
            val before = if (playback.desiredPlaying) playback else focus?.takeIf {
                nowMs - it.second in 0..correlationWindowMs && it.first.mediaId == playback.mediaId &&
                    it.first.playbackEpoch + 1 == playback.playbackEpoch
            }?.first
            candidate = before?.takeIf { it.mediaId != null && it.desiredPlaying }?.let { Candidate(it, playback.playbackEpoch) }
            priorFocus = null
            idleAtMs = null
            attempts = 0
            stableRoute = null
        } else if (state == CellularCallState.IDLE && wasActive && pending) {
            idleAtMs = nowMs
            stableRoute = null
            return true
        }
        return false
    }

    fun focusLost(before: CallPlaybackSnapshot, afterEpoch: Long, nowMs: Long) {
        if (pending && active) {
            if (before.playbackEpoch == candidate?.expectedEpoch) candidate = candidate?.copy(expectedEpoch = afterEpoch)
            else cancel()
        }
        else if (pending) cancel()
        else if (before.desiredPlaying && before.mediaId != null) priorFocus = before to nowMs
    }

    fun adoptCallPauseEpoch(epoch: Long) { candidate = candidate?.copy(expectedEpoch = epoch) }

    fun cancel() {
        candidate = null
        priorFocus = null
        idleAtMs = null
        stableRoute = null
        attempts = 0
    }

    fun callTransportRoute(route: OutputRoute?, nowMs: Long): Boolean =
        (active || pending || priorFocus?.let { nowMs - it.second in 0..correlationWindowMs } == true) && route?.deviceType == 7

    /** Requires the original actual media route; a connected list or SCO route is insufficient. */
    fun ready(playback: CallPlaybackSnapshot, suppressionReason: Int, nowMs: Long, enabled: Boolean): Candidate? {
        val value = candidate ?: return null
        val idleAt = idleAtMs ?: return null
        if (!enabled || playback.playbackEpoch != value.expectedEpoch || playback.mediaId != value.before.mediaId ||
            nowMs - idleAt > recoveryWindowMs) { cancel(); return null }
        if (attempts >= maxAttempts) { cancel(); return null }
        if (active || callState != CellularCallState.IDLE) return null
        val route = playback.route
        val original = value.before.route
        if (route == null || original == null || route.deviceType == 7 || !original.sameSessionDevice(route)) {
            stableRoute = null
            return null
        }
        if (stableRoute?.sameSessionDevice(route) != true) { stableRoute = route; routeAtMs = nowMs }
        if (suppressionReason != 0 || nowMs - idleAt < settleMs || nowMs - routeAtMs < settleMs ||
            (attempts > 0 && nowMs - lastAttemptMs < 1_000)) return null
        return value
    }

    fun attempted(nowMs: Long) { attempts++; lastAttemptMs = nowMs }
    fun completed() = cancel()
}
