package com.kmusic.apkhd.playback

enum class PauseCause { NONE, USER, ROUTE_DISCONNECT, FOCUS, CALL, ERROR, ENDED, SERVICE_STOPPED }

data class PlaybackIntent(
    val playbackEpoch: Long = 0,
    val desiredPlaying: Boolean = false,
    val pauseCause: PauseCause = PauseCause.NONE,
    val suppressionReason: Int = 0
)

/** Pure intent reducer. Errors keep the user's play intent; focus suppression blocks recovery. */
class PlaybackIntentPolicy(private val reconnectWindowMs: Long = 60_000L) {
    var intent: PlaybackIntent = PlaybackIntent()
        private set
    private var reconnect: Reconnect? = null
    private data class Reconnect(val route: OutputRoute, val atMs: Long, val epoch: Long)

    fun play(): PlaybackIntent {
        reconnect = null
        intent = intent.copy(playbackEpoch = intent.playbackEpoch + 1, desiredPlaying = true, pauseCause = PauseCause.NONE)
        return intent
    }

    fun pause(cause: PauseCause): PlaybackIntent {
        reconnect = null
        intent = intent.copy(playbackEpoch = intent.playbackEpoch + 1, desiredPlaying = false, pauseCause = cause)
        return intent
    }

    fun invalidate(): PlaybackIntent {
        reconnect = null
        intent = intent.copy(playbackEpoch = intent.playbackEpoch + 1)
        return intent
    }

    fun error(): PlaybackIntent {
        reconnect = null
        intent = intent.copy(pauseCause = PauseCause.ERROR)
        return intent
    }

    fun suppression(reason: Int): PlaybackIntent {
        if (reason == intent.suppressionReason) return intent
        reconnect = null
        intent = intent.copy(
            suppressionReason = reason,
            pauseCause = if (reason != 0) PauseCause.FOCUS else if (intent.pauseCause == PauseCause.FOCUS && intent.desiredPlaying) PauseCause.NONE else intent.pauseCause
        )
        return intent
    }

    fun disconnected(route: OutputRoute, nowMs: Long, autoResume: Boolean): PlaybackIntent {
        val wasPlaying = intent.desiredPlaying && intent.suppressionReason == 0
        pause(PauseCause.ROUTE_DISCONNECT)
        if (autoResume && wasPlaying && route.isBluetooth) reconnect = Reconnect(route, nowMs, intent.playbackEpoch)
        return intent
    }

    /** Connected-device topology alone is never sufficient; a live AudioTrack must confirm this route. */
    fun canResume(confirmedRoute: OutputRoute?, nowMs: Long, autoResume: Boolean): Boolean {
        val candidate = reconnect ?: return false
        return autoResume && confirmedRoute != null && candidate.route.sameSessionDevice(confirmedRoute) &&
            nowMs - candidate.atMs in 0..reconnectWindowMs && candidate.epoch == intent.playbackEpoch &&
            !intent.desiredPlaying && intent.pauseCause == PauseCause.ROUTE_DISCONNECT && intent.suppressionReason == 0
    }
}
