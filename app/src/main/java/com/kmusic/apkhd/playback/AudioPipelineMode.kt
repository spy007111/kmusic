package com.kmusic.apkhd.playback

/** Only controls the application pipeline, not the platform mixer, device codec or DAC. */
enum class AudioPipelineMode(val floatOutput: Boolean, val softwareDsp: Boolean, val offload: Boolean) {
    PROCESSED_PCM(false, true, false),
    OFFLOAD_PREFERRED(false, false, true),
    PURE_PCM(true, false, false),
    USB_HIGH_FIDELITY(true, false, false);

    companion object {
        /** Strict USB wins conflicting preferences, then pure PCM; no mode proves device bit-perfect output. */
        fun resolve(offloadRequested: Boolean, pureAudioModeRequested: Boolean, usbHighFidelity: Boolean = false): AudioPipelineMode = when {
            usbHighFidelity -> USB_HIGH_FIDELITY
            pureAudioModeRequested -> PURE_PCM
            offloadRequested -> OFFLOAD_PREFERRED
            else -> PROCESSED_PCM
        }
    }
}
