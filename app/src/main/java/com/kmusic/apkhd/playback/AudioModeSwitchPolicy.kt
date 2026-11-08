package com.kmusic.apkhd.playback

/** Immutable transport state captured before replacing the application audio pipeline. */
data class AudioModeSwitchSnapshot<T>(
    val items: List<T>,
    val index: Int,
    val positionMs: Long,
    val mediaId: String?,
    val playbackEpoch: Long,
    val playWhenReady: Boolean,
    val desiredPlaying: Boolean,
    val suppressionReason: Int,
    val repeatMode: Int,
    val shuffleEnabled: Boolean,
    val speed: Float,
    val pitch: Float,
    val volume: Float,
    val prepared: Boolean
)

object AudioModeSwitchPolicy {
    fun normalize(mode: String): String = if (mode in setOf("processed", "offload", "pure", "usb")) mode else "processed"

    fun <T> shouldResume(snapshot: AudioModeSwitchSnapshot<T>, currentEpoch: Long, expectedEpoch: Long,
        currentMediaId: String?, currentDesiredPlaying: Boolean, exitRequested: Boolean): Boolean =
        !exitRequested && snapshot.items.isNotEmpty() && snapshot.playWhenReady && snapshot.desiredPlaying &&
            snapshot.suppressionReason == 0 && currentDesiredPlaying && currentEpoch == expectedEpoch &&
            currentMediaId == snapshot.mediaId
}
