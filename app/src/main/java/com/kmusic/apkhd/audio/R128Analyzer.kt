package com.kmusic.apkhd.audio

import android.content.Context
import android.net.Uri

/**
 * 本地文件离线响度分析（EBU R128 / BS.1770）。
 *
 * 走 [PcmDecoder]（MediaExtractor + MediaCodec）解成 PCM，喂给 [R128]。
 * 仅自然 EOS 时算完整结果（扫描上限 10 分钟），一首 4 分钟的歌在车机上大约 2~4 秒。
 *
 * 解不出来的格式（DSD、APE、厂商硬解诈胡的 ALAC）直接返回 null ——
 * 宁可不归一，也不要拿半首歌的数据算出一个错增益。
 */
object R128Analyzer {

    data class Result(val lufs: Double, val peak: Float)

    /** 目标响度：流媒体平台普遍用 -14 LUFS；车机环境吵，稍响一点更好听 */
    const val TARGET_LUFS = -14.0

    /** 最多分析 10 分钟：再长的现场版也没必要为它多等十几秒 */
    const val MAX_DURATION_MS = 600_000L
    private const val MAX_US = MAX_DURATION_MS * 1000L

    fun canScanDuration(durationMs: Long): Boolean = durationMs <= MAX_DURATION_MS
    fun isCompleteScan(reachedEndOfStream: Boolean): Boolean = reachedEndOfStream

    suspend fun analyze(context: Context, uri: Uri): Result? {
        var r128: R128? = null
        val decoded = PcmDecoder.decode(
            context, uri, 0L, MAX_US,
            onInfo = { info ->
                // 采样率只有打开容器后才知道，滤波器必须按它建
                r128 = if (R128.supportsChannels(info.channels)) R128(info.sampleRate, info.channels) else null
            },
            onPcm = { pcm, count, _ ->
                r128?.process(pcm, count)
            }
        ) ?: return null
        if (!isCompleteScan(decoded.reachedEndOfStream)) return null
        val r = r128 ?: return null
        val lufs = r.integratedLufs() ?: return null
        return Result(lufs, r.peak)
    }
}
