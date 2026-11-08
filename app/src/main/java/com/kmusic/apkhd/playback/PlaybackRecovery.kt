package com.kmusic.apkhd.playback

/** An asynchronous result belongs to both a playback intent and a logical queue. */
data class PlaybackRecoveryToken(val playbackEpoch: Long, val queueEpoch: Long, val mediaId: String)

object PlaybackRecovery {
    fun canCommit(
        token: PlaybackRecoveryToken,
        playbackEpoch: Long,
        queueEpoch: Long,
        mediaId: String?,
        desiredPlaying: Boolean,
        suppressed: Boolean
    ): Boolean = token.playbackEpoch == playbackEpoch && token.queueEpoch == queueEpoch &&
        token.mediaId == mediaId && desiredPlaying && !suppressed

    fun needsRepair(state: Int, readyState: Int, idleState: Int, wantsPlay: Boolean,
                    audible: Boolean, suppressed: Boolean, hasError: Boolean): Boolean =
        wantsPlay && !audible && !suppressed && (state == idleState || (state == readyState && hasError))
}
