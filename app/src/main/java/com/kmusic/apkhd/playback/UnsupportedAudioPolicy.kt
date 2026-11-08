package com.kmusic.apkhd.playback

import androidx.media3.common.PlaybackException

class UnsupportedAudioSkipPolicy {
    private val failed = mutableSetOf<String>()

    fun reset() = failed.clear()

    fun next(current: String, queue: List<String>, suggested: String?, loop: Boolean): String? {
        failed.add(current)
        if (suggested != null && suggested != current && suggested !in failed && suggested in queue) return suggested
        val index = queue.indexOf(current)
        if (index < 0) return null
        val following = queue.drop(index + 1)
        val candidates = if (loop) following + queue.take(index) else following
        return candidates.firstOrNull { it != current && it !in failed }
    }
}

object UnsupportedAudioPolicy {
    fun isUnsupported(errorCode: Int): Boolean = errorCode in setOf(
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED
    )

    fun acceptsVorbis(mime: String?, channels: Int, sampleRate: Int,
                      maxChannels: Int?, sampleRateSupported: Boolean?): Boolean {
        if (mime != "audio/vorbis") return true
        return !(channels > 0 && maxChannels != null && maxChannels > 0 && channels > maxChannels ||
            sampleRate > 0 && sampleRateSupported == false)
    }

    fun invalidVorbisOutput(mime: String?, sourceChannels: Int, outputChannels: Int): Boolean =
        mime == "audio/vorbis" && sourceChannels > 2 && outputChannels > 0 && outputChannels != sourceChannels

    fun message(title: String, next: Boolean): String =
        "当前设备无法解码或输出《$title》，" + if (next) "已跳过，尝试下一首" else "没有可播放的下一首，已停止"
}
