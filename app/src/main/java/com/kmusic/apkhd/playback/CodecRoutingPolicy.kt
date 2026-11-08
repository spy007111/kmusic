package com.kmusic.apkhd.playback

/** MIME-level workaround only. A codec preference does not validate its container or network data. */
internal object CodecRoutingPolicy {
    private const val ALAC = "audio/alac"
    private const val VORBIS = "audio/vorbis"

    fun useSoftwareDecoder(
        mime: String,
        ffmpegAvailable: Boolean,
        ffmpegSupportsMime: Boolean,
        secure: Boolean = false,
        tunneling: Boolean = false
    ): Boolean = !secure && !tunneling && ffmpegAvailable && ffmpegSupportsMime &&
        (mime == ALAC || mime == VORBIS)

    fun isPlatformSoftware(name: String, softwareOnly: Boolean = false): Boolean = softwareOnly ||
        name.startsWith("c2.android.", ignoreCase = true) || name.startsWith("OMX.google.", ignoreCase = true)

    fun <T> platformSoftwareFirst(
        mime: String,
        candidates: List<T>,
        name: (T) -> String,
        softwareOnly: (T) -> Boolean = { false },
        secure: Boolean = false,
        tunneling: Boolean = false
    ): List<T> = if (mime == VORBIS && !secure && !tunneling) candidates.sortedBy {
        if (isPlatformSoftware(name(it), softwareOnly(it))) 0 else 1
    } else candidates
}
