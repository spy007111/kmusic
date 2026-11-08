package com.kmusic.apkhd.playback

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Looper
import androidx.media3.common.Format
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.common.C
import androidx.media3.common.AudioAttributes
import android.os.Handler
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.kmusic.apkhd.data.DiagLog

/**
 * ALAC / Vorbis 在 FFmpeg 确认支持当前 MIME 时走软件解码，其余格式保留系统选择。
 * 这是解码器兼容策略，不修复损坏容器、错误 URL 或分段下载；未知原曲故障不能归因于 codec。
 * setEnableDecoderFallback 仅涵盖平台初始化候选，不承诺运行中失败跨 renderer 自动恢复。
 */
@UnstableApi
class AudioRenderersFactory(
    context: Context,
    /** 请求硬件offload；不支持时普通输出回退，本工厂在该偏好下不挂软件音效。 */
    private val allowOffload: Boolean = false,
    private val pureAudioMode: Boolean = false,
    private val onRouteConfirmed: (OutputRoute?) -> Unit = {},
    private val usbHighFidelity: Boolean = false,
    private val onOutputConfirmed: (ApplicationAudioFormat?, OutputRoute?) -> Unit = { _, _ -> },
    private val onBeforeOutputCreated: (ApplicationAudioFormat) -> Unit = {}
) : DefaultRenderersFactory(context) {

    init {
        setEnableDecoderFallback(true)
    }

    private val pipelineMode = AudioPipelineMode.resolve(allowOffload, pureAudioMode, usbHighFidelity)
    private val routeHandler = Handler(Looper.getMainLooper())
    private val tracks = mutableListOf<TrackedOutput>()
    @Volatile private var routesClosed = false
    private var activeTrack: AudioTrack? = null
    private data class TrackedOutput(
        val track: AudioTrack,
        val format: ApplicationAudioFormat,
        val listener: AudioRouting.OnRoutingChangedListener
    )

    private val trackProvider = object : DefaultAudioSink.AudioTrackProvider {
        override fun getAudioTrack(
            config: AudioSink.AudioTrackConfig,
            attributes: AudioAttributes,
            sessionId: Int
        ): AudioTrack {
            onBeforeOutputCreated(ApplicationAudioFormat(config.sampleRate, Integer.bitCount(config.channelConfig), config.encoding,
                config.encoding == C.ENCODING_PCM_16BIT || config.encoding == C.ENCODING_PCM_FLOAT))
            val track = DefaultAudioSink.AudioTrackProvider.DEFAULT.getAudioTrack(config, attributes, sessionId)
            routeHandler.post {
                if (!routesClosed && track.state == AudioTrack.STATE_INITIALIZED) {
                    val format = ApplicationAudioFormat(track.sampleRate, track.channelCount, track.audioFormat,
                        track.audioFormat in setOf(C.ENCODING_PCM_8BIT, C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT,
                            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_16BIT_BIG_ENDIAN,
                            C.ENCODING_PCM_24BIT_BIG_ENDIAN, C.ENCODING_PCM_32BIT_BIG_ENDIAN))
                    val listener = AudioRouting.OnRoutingChangedListener {
                        if (!routesClosed && activeTrack === track) publishOutput(track, format)
                    }
                    pruneReleasedTracks()
                    tracks.add(TrackedOutput(track, format, listener))
                    track.addOnRoutingChangedListener(listener, routeHandler)
                    activeTrack = track
                    publishOutput(track, format)
                }
            }
            return track
        }
    }

    fun refreshOutputRoute() {
        routeHandler.post {
            if (!routesClosed) tracks.lastOrNull { it.track === activeTrack }?.let { publishOutput(it.track, it.format) }
        }
    }

    private fun publishOutput(track: AudioTrack, format: ApplicationAudioFormat) {
        val device = runCatching { track.routedDevice }.getOrNull()
        val route = device?.let {
            val type = it.type
            OutputRoute(it.id, type, routeTypeName(type), safeDeviceName(it),
                type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                    type == AudioDeviceInfo.TYPE_BLE_BROADCAST)
        }
        AudioRuntimeState.reportOutput(format, route)
        onOutputConfirmed(format, route)
        DiagLog.log("AUDIO", "应用AudioTrack ${format.sampleRate}Hz ch=${format.channelCount} encoding=${format.encoding} PCM=${format.isPcm}; routed=${route?.deviceTypeName ?: "未知"}; 不代表最终DAC/蓝牙codec")
        onRouteConfirmed(route)
    }

    private fun safeDeviceName(device: AudioDeviceInfo): String? {
        // Bluetooth productName can require BLUETOOTH_CONNECT. Unknown is an intentional fallback.
        return runCatching { device.productName?.toString()?.takeIf { it.isNotBlank() } }.getOrNull()
    }

    private fun routeTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "蓝牙A2DP"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙SCO（仅观察）"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "蓝牙LE耳机"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "蓝牙LE扬声器"
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> "蓝牙LE广播"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "内置扬声器"
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "有线耳机"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB音频"
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI"
        else -> "设备类型$type"
    }

    private fun pruneReleasedTracks() {
        val released = tracks.filter { runCatching { it.track.state == AudioTrack.STATE_UNINITIALIZED }.getOrDefault(true) }
        released.forEach { runCatching { it.track.removeOnRoutingChangedListener(it.listener) } }
        tracks.removeAll(released.toSet())
        if (released.any { it.track === activeTrack }) {
            activeTrack = null
            AudioRuntimeState.reportOutput(null, null)
            onOutputConfirmed(null, null)
            onRouteConfirmed(null)
        }
    }

    /** Called before releasing the service player. AudioTrack ownership remains with Media3. */
    fun releaseRouteCallbacks() {
        routesClosed = true
        routeHandler.removeCallbacksAndMessages(null)
        tracks.forEach { runCatching { it.track.removeOnRoutingChangedListener(it.listener) } }
        tracks.clear()
        activeTrack = null
        AudioRuntimeState.reportOutput(null, null)
        onOutputConfirmed(null, null)
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink {
        val builder = DefaultAudioSink.Builder(context)
            .setAudioTrackProvider(trackProvider)
            // Float bypasses Media3's processor chain, so only opt in when software DSP is absent.
            .setEnableFloatOutput(pipelineMode.floatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        if (pipelineMode.softwareDsp) {
            builder.setAudioProcessors(arrayOf(EqProcessor.instance))
        }
        if (!pipelineMode.offload) {
            builder.setAudioOffloadSupportProvider { _, _ -> AudioOffloadSupport.DEFAULT_UNSUPPORTED }
        }
        // Both pure PCM and offload preference omit software DSP, including ordinary-output fallback.
        return builder.build()
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>
    ) {
        val routedEvents = object : AudioRendererEventListener by eventListener {
            override fun onAudioDecoderInitialized(decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                DiagLog.log("CODEC_ACTUAL", "实际音频解码器：$decoderName")
                eventListener.onAudioDecoderInitialized(decoderName, initializedTimestampMs, initializationDurationMs)
            }
            override fun onAudioInputFormatChanged(format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
                DiagLog.log("CODEC_INPUT", "源格式：${format.sampleMimeType} ${format.sampleRate}Hz ${format.channelCount}ch")
                eventListener.onAudioInputFormatChanged(format, decoderReuseEvaluation)
            }
            override fun onAudioTrackReleased(audioTrackConfig: AudioSink.AudioTrackConfig) {
                routeHandler.post { if (!routesClosed) pruneReleasedTracks() }
                eventListener.onAudioTrackReleased(audioTrackConfig)
            }
        }
        val ffmpegAvailable = runCatching { FfmpegLibrary.isAvailable() }.getOrDefault(false)
        val alacSupported = ffmpegAvailable && runCatching {
            FfmpegLibrary.supportsFormat(MimeTypes.AUDIO_ALAC)
        }.getOrDefault(false)
        val vorbisSupported = ffmpegAvailable && runCatching {
            FfmpegLibrary.supportsFormat(MimeTypes.AUDIO_VORBIS)
        }.getOrDefault(false)
        val preferredSelector = MediaCodecSelector { mime, secure, tunneling ->
            val supported = when (mime) {
                MimeTypes.AUDIO_ALAC -> alacSupported
                MimeTypes.AUDIO_VORBIS -> vorbisSupported
                else -> false
            }
            if (CodecRoutingPolicy.useSoftwareDecoder(mime, ffmpegAvailable, supported, secure, tunneling)) {
                DiagLog.log("CODEC", "$mime → FFmpeg 已确认支持，平台解码器不抢占")
                emptyList()
            } else {
                val candidates = mediaCodecSelector.getDecoderInfos(mime, secure, tunneling)
                val ordered = CodecRoutingPolicy.platformSoftwareFirst(mime, candidates, name = { it.name }, softwareOnly = { it.softwareOnly }, secure = secure, tunneling = tunneling)
                if (mime == MimeTypes.AUDIO_VORBIS) {
                    DiagLog.log("CODEC", "Vorbis 平台候选（软件优先，全部保留）：${ordered.joinToString { it.name }}")
                }
                ordered
            }
        }
        val firstRenderer = out.size
        super.buildAudioRenderers(
            context,
            EXTENSION_RENDERER_MODE_OFF,   // 扩展渲染器由下面手动加，顺便控制顺序
            preferredSelector,
            true,
            audioSink,
            eventHandler,
            routedEvents,
            out
        )
        for (index in firstRenderer until out.size) {
            if (out[index] is MediaCodecAudioRenderer) {
                out[index] = CompatibleAudioRenderer(context, getCodecAdapterFactory(), preferredSelector, eventHandler, routedEvents, audioSink)
            }
        }
        if (ffmpegAvailable) {
            // FFmpeg 1.6.1 negotiates float only if this sink supports it directly (except AC3).
            // Otherwise it outputs 16-bit; the AudioTrack callback reports the actual application format.
            out.add(FfmpegAudioRenderer(eventHandler, routedEvents, audioSink))
            DiagLog.log("CODEC", "FFmpeg 音频渲染器已加载：ALAC=$alacSupported Vorbis=$vorbisSupported；仅确认支持的 MIME 优先软件解码")
        } else {
            DiagLog.log("CODEC", "FFmpeg 音频渲染器不可用：保留平台 ALAC/Vorbis 候选")
        }
    }

}
