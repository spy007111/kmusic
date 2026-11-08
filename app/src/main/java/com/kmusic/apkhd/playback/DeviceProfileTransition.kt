package com.kmusic.apkhd.playback

/** Routing decisions are made before any effect is applied, never from a stale manual selection. */
internal object DeviceProfileTransition {
    data class Result(val manualId: String?, val clearManual: Boolean, val rememberedRoute: Pair<Int, Int>?)
    fun resolve(previous: Pair<Int, Int>?, current: Pair<Int, Int>?, manualId: String?): Result {
        val changed = previous != null && previous != current
        return Result(if (changed) null else manualId, changed && manualId != null, current)
    }
}
