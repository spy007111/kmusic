package com.kmusic.apkhd.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 把任意本地音频解成 float PCM 流。
 *
 * 响度分析和频谱检测都要「拿到真正的波形」，两处共用一份解码逻辑：
 * MediaExtractor 选轨 → MediaCodec 解码 → 统一转 float（16bit / float 两种输出都可能）。
 *
 * 全部在 IO 线程跑，回调是同步的 —— 调用方要自己保证回调里别做重活。
 */
object PcmDecoder {

    data class Info(val sampleRate: Int, val channels: Int, val reachedEndOfStream: Boolean = false)

    /**
     * @param startUs  从哪开始（seek 到最近的同步点）
     * @param maxUs    最多解多长；[Long.MAX_VALUE] 表示整首
     * @param onInfo   格式确定后立刻回调一次 —— 调用方要按采样率初始化滤波器，
     *                 而采样率只有打开容器之后才知道
     * @param onPcm    (交错 float 数据, 有效长度, 声道数)
     */
    suspend fun decode(
        context: Context,
        uri: Uri,
        startUs: Long = 0L,
        maxUs: Long = Long.MAX_VALUE,
        onInfo: (Info) -> Unit,
        onPcm: (FloatArray, Int, Int) -> Unit
    ): Info? = withContext(Dispatchers.IO) {
        runCatching { decodeBlocking(context, uri, startUs, maxUs, onInfo, onPcm) }.getOrNull()
    }

    private fun decodeBlocking(
        context: Context,
        uri: Uri,
        startUs: Long,
        maxUs: Long,
        onInfo: (Info) -> Unit,
        onPcm: (FloatArray, Int, Int) -> Unit
    ): Info? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) return null
            extractor.selectTrack(trackIndex)
            if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            if (sampleRate <= 0 || channels <= 0) return null
            onInfo(Info(sampleRate, channels))

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var reachedEndOfStream = false
            var pcmEncoding = runCatching { format.getInteger(MediaFormat.KEY_PCM_ENCODING) }
                .getOrDefault(android.media.AudioFormat.ENCODING_PCM_16BIT)
            val deadline = if (maxUs == Long.MAX_VALUE) Long.MAX_VALUE else startUs + maxUs

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)
                    if (outBuf != null && info.size > 0) {
                        val floats = toFloats(outBuf, info.offset, info.size, pcmEncoding)
                        onPcm(floats, floats.size, channels)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        reachedEndOfStream = true
                        outputDone = true
                    }
                    // PTS 用不上时（有些容器不给）只靠 maxUs 兜底；这里按采样数判断更稳
                    if (deadline != Long.MAX_VALUE && info.presentationTimeUs > deadline) outputDone = true
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    pcmEncoding = runCatching {
                        codec.outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }.getOrDefault(pcmEncoding)
                }
            }
            return Info(sampleRate, channels, reachedEndOfStream)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun toFloats(buf: ByteBuffer, offset: Int, size: Int, encoding: Int): FloatArray {
        val floatOut = encoding == android.media.AudioFormat.ENCODING_PCM_FLOAT
        val bytesPerSample = if (floatOut) 4 else 2
        val count = size / bytesPerSample
        val out = FloatArray(count)
        val dup = buf.duplicate().order(ByteOrder.nativeOrder())
        dup.position(offset)
        if (floatOut) {
            val fb = dup.asFloatBuffer()
            fb.get(out, 0, minOf(count, fb.remaining()))
        } else {
            val sb = dup.asShortBuffer()
            val n = minOf(count, sb.remaining())
            for (i in 0 until n) out[i] = sb.get(i) / 32768f
        }
        return out
    }
}
