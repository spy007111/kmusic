package com.kmusic.apkhd.playback

/** Null means absent in storage; false, zero and an existing hidden setting are intentional values. */
data class AudioModePreferences(
    val usbHighFidelity: Boolean? = null,
    val pureAudioMode: Boolean? = null,
    val audioOffload: Boolean? = null,
    val eqEnabled: Boolean? = null,
    val eqPreset: Int? = null,
    val fxBass: Int? = null,
    val fxLoudness: Int? = null,
    val callAutoResume: Boolean? = null,
    val bluetoothAutoResume: Boolean? = null,
    val dsdOutputRate: Int? = null,
    val dsdBypassEq: Boolean? = null,
    val truePeakEnabled: Boolean? = null,
    val truePeakCeilingDb: Float? = null
)

data class ResolvedAudioModePreferences(
    val mode: String,
    val pipeline: AudioPipelineMode,
    val usbHighFidelity: Boolean,
    val pureAudioMode: Boolean,
    val audioOffload: Boolean,
    val eqEnabled: Boolean,
    val eqPreset: Int,
    val fxBass: Int,
    val fxLoudness: Int,
    val callAutoResume: Boolean,
    val bluetoothAutoResume: Boolean,
    val dsdOutputRate: Int,
    val dsdBypassEq: Boolean,
    val truePeakEnabled: Boolean,
    val truePeakCeilingDb: Float
)

/** Conservative first-install defaults, not a universal sound-quality ranking or a DSP preset. */
object AudioModeDefaults {
    private val modes = listOf("processed", "offload", "pure", "usb")

    fun visibleModes(usbConnectedOrSupported: Boolean, current: String): List<String> =
        if (usbConnectedOrSupported || current == "usb") modes else modes.dropLast(1)

    /** Caller should read and persist one atomic preferences snapshot, not combine partially updated mode flags. */
    fun resolve(mode: String? = null, existing: AudioModePreferences? = null): ResolvedAudioModePreferences {
        val saved = existing ?: AudioModePreferences()
        val pipeline = when (mode) {
            "processed" -> AudioPipelineMode.PROCESSED_PCM
            "offload" -> AudioPipelineMode.OFFLOAD_PREFERRED
            "pure" -> AudioPipelineMode.PURE_PCM
            "usb" -> AudioPipelineMode.USB_HIGH_FIDELITY
            else -> AudioPipelineMode.resolve(saved.audioOffload == true, saved.pureAudioMode == true,
                saved.usbHighFidelity == true)
        }
        val resolvedMode = when (pipeline) {
            AudioPipelineMode.PROCESSED_PCM -> "processed"
            AudioPipelineMode.OFFLOAD_PREFERRED -> "offload"
            AudioPipelineMode.PURE_PCM -> "pure"
            AudioPipelineMode.USB_HIGH_FIDELITY -> "usb"
        }
        return ResolvedAudioModePreferences(
            mode = resolvedMode,
            pipeline = pipeline,
            usbHighFidelity = pipeline == AudioPipelineMode.USB_HIGH_FIDELITY,
            pureAudioMode = pipeline == AudioPipelineMode.PURE_PCM,
            audioOffload = pipeline == AudioPipelineMode.OFFLOAD_PREFERRED,
            eqEnabled = saved.eqEnabled ?: false,
            eqPreset = saved.eqPreset ?: 0,
            fxBass = saved.fxBass ?: 0,
            fxLoudness = saved.fxLoudness ?: 0,
            callAutoResume = saved.callAutoResume ?: true,
            bluetoothAutoResume = saved.bluetoothAutoResume ?: false,
            dsdOutputRate = saved.dsdOutputRate ?: 44100,
            dsdBypassEq = saved.dsdBypassEq ?: true,
            truePeakEnabled = saved.truePeakEnabled ?: false,
            truePeakCeilingDb = saved.truePeakCeilingDb ?: -1f
        )
    }
}
