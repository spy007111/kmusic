package com.kmusic.apkhd.playback

/** Application AudioTrack format, not the downstream DAC or Bluetooth codec format. */
data class ApplicationAudioFormat(
    val sampleRate: Int,
    val channelCount: Int,
    val encoding: Int,
    val isPcm: Boolean
)

data class OutputRoute(
    val deviceId: Int,
    val deviceType: Int,
    val deviceTypeName: String,
    val displayName: String? = null,
    val isBluetooth: Boolean = false
) {
    /** Session-local route identity only; never persist an address or infer identity from a name. */
    fun sameSessionDevice(other: OutputRoute): Boolean =
        deviceId == other.deviceId && deviceType == other.deviceType
}
