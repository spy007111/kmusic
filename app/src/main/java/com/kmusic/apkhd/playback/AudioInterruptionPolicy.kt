package com.kmusic.apkhd.playback

data class ExternalPlaybackCategories(
    val media: Int = 0,
    val game: Int = 0,
    val voice: Int = 0,
    val navigation: Int = 0,
    val uiSonification: Int = 0,
    val otherOrUnknown: Int = 0
) {
    val total: Int get() = media + game + voice + navigation + uiSonification + otherOrUnknown
    val blocking: Int get() = media + game + voice + navigation + otherOrUnknown
}

data class ExternalPlaybackEvidence(
    val activeCount: Int,
    val musicActive: Boolean,
    val readable: Boolean = true,
    val categories: ExternalPlaybackCategories? = null
) {
    // Missing or inconsistent metadata never grants permission to ignore an active track.
    val blockingCount: Int get() = categories?.takeIf { it.total == activeCount }?.blocking ?: activeCount
    val anyActive: Boolean get() = blockingCount > 0 || musicActive
    val allInactive: Boolean get() = readable && blockingCount == 0 && !musicActive
}

/** Anonymous activity is attributable to others only after our own playback has stopped and settled. */
class AudioInterruptionPolicy(private val ownSettleMs: Long = 800, private val quietMs: Long = 800) {
    data class Candidate(val before: CallPlaybackSnapshot, val expectedEpoch: Long)
    var candidate: Candidate? = null
        private set
    val pending: Boolean get() = candidate != null
    val awaitingQuietCheck: Boolean get() = pending && quietAt != null
    var status: String = "等待音频中断"
        private set
    private var stoppedAt: Long? = null
    private var externalConfirmed = false
    private var quietAt: Long? = null
    private var routeAt: Long? = null
    private var attempts = 0
    private var attemptedAt: Long? = null
    private var musicTailAt: Long? = null

    fun focusLost(before: CallPlaybackSnapshot, afterEpoch: Long, nowMs: Long, enabled: Boolean) {
        cancel("未获得外部播放结束证据，保持暂停")
        if (!enabled || !before.desiredPlaying || before.mediaId == null) return
        candidate = Candidate(before, afterEpoch)
        stoppedAt = nowMs
        status = "等待匿名外部播放证据"
    }

    fun cancel(reason: String = "恢复候选已取消") {
        candidate = null; stoppedAt = null; externalConfirmed = false; quietAt = null; routeAt = null
        attempts = 0; attemptedAt = null; musicTailAt = null; status = reason
    }

    fun observe(evidence: ExternalPlaybackEvidence, ownActive: Boolean, nowMs: Long) {
        if (!pending) return
        if (ownActive) { cancel("本应用播放意图已变化"); return }
        if (!evidence.readable) { quietAt = null; status = "公开活动信息不可用，保持暂停"; return }
        if (nowMs - (stoppedAt ?: nowMs) < ownSettleMs) return
        if (evidence.blockingCount > 0) {
            externalConfirmed = true; quietAt = null; routeAt = null; musicTailAt = null
            status = "外部音频仍活动，等待停止"
        } else if (evidence.musicActive) {
            quietAt = null
            if (externalConfirmed && musicTailAt == null) musicTailAt = nowMs
            status = "活动状态尚未清静，保持暂停"
        } else if (externalConfirmed && evidence.allInactive) {
            musicTailAt = null
            if (quietAt == null) quietAt = nowMs
            status = if (evidence.activeCount > 0) "媒体中断已结束，仅余公开UI提示，等待稳定" else "外部音频已停止，等待稳定"
        } else status = "未获得外部播放结束证据，保持暂停"
    }

    fun requiresSettleCheck(nowMs: Long): Boolean = pending && (
        stoppedAt?.let { nowMs - it < ownSettleMs } == true || awaitingQuietCheck ||
            musicTailAt?.let { nowMs - it < 5_000 } == true)

    fun ready(current: CallPlaybackSnapshot, suppression: Int, callBusy: Boolean, nowMs: Long, enabled: Boolean): Boolean {
        val value = candidate ?: return false
        if (!enabled || current.playbackEpoch != value.expectedEpoch || current.mediaId != value.before.mediaId) {
            cancel(); return false
        }
        if (attempts >= 3) { cancel("焦点恢复未获许可，保持暂停"); return false }
        val quiet = quietAt ?: return false
        if (nowMs - quiet > 8_000) { cancel("恢复窗口已结束，保持暂停"); return false }
        val route = current.route
        if (callBusy || suppression != 0 || !externalConfirmed || route == null || value.before.route == null ||
            route.deviceType == 7 || !value.before.route.sameSessionDevice(route)) { routeAt = null; return false }
        if (routeAt == null) routeAt = nowMs
        return nowMs - quiet >= quietMs && nowMs - (routeAt ?: nowMs) >= quietMs &&
            attemptedAt?.let { nowMs - it >= 1_000 } != false
    }

    fun attempted(nowMs: Long) { attempts++; attemptedAt = nowMs }
    fun adoptAttemptEpoch(epoch: Long) { candidate = candidate?.copy(expectedEpoch = epoch) }
    fun completed() = cancel("已通过Media3恢复播放")
}
