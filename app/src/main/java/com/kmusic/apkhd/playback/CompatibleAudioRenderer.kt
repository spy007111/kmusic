package com.kmusic.apkhd.playback

import android.content.Context
import android.media.MediaFormat
import android.os.Handler
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.kmusic.apkhd.data.DiagLog

@UnstableApi
class CompatibleAudioRenderer(
    context: Context, adapterFactory: MediaCodecAdapter.Factory, selector: MediaCodecSelector, handler: Handler,
    events: AudioRendererEventListener, sink: AudioSink
) : MediaCodecAudioRenderer(context, adapterFactory, selector, true, handler, events, sink) {
    override fun getDecoderInfos(selector: MediaCodecSelector, format: Format,
                                 requiresSecureDecoder: Boolean): List<MediaCodecInfo> =
        super.getDecoderInfos(selector, format, requiresSecureDecoder).filter { codec ->
            val caps = codec.capabilities?.audioCapabilities
            val accepted = UnsupportedAudioPolicy.acceptsVorbis(format.sampleMimeType, format.channelCount,
                format.sampleRate, caps?.maxInputChannelCount,
                caps?.let { if (format.sampleRate > 0) it.isSampleRateSupported(format.sampleRate) else null })
            if (!accepted) DiagLog.log("CODEC_UNSUPPORTED", "${codec.name} 不支持源格式 ${format.sampleMimeType} ${format.sampleRate}Hz ${format.channelCount}ch")
            accepted
        }

    override fun onOutputFormatChanged(format: Format, mediaFormat: MediaFormat?) {
        val outputChannels = mediaFormat?.takeIf { it.containsKey(MediaFormat.KEY_CHANNEL_COUNT) }
            ?.getInteger(MediaFormat.KEY_CHANNEL_COUNT) ?: Format.NO_VALUE
        DiagLog.log("CODEC_OUTPUT", "${codecInfo?.name ?: "未知"} source=${format.sampleMimeType} ${format.sampleRate}Hz ${format.channelCount}ch output=${mediaFormat?.getString(MediaFormat.KEY_MIME)} ${outputChannels}ch")
        if (UnsupportedAudioPolicy.invalidVorbisOutput(format.sampleMimeType, format.channelCount, outputChannels)) {
            throw createRendererException(UnsupportedOperationException("Vorbis channel output mismatch: ${format.channelCount} -> $outputChannels"),
                format, PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES)
        }
        super.onOutputFormatChanged(format, mediaFormat)
    }
}
