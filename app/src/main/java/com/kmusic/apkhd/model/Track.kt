package com.kmusic.apkhd.model

import kotlinx.serialization.Serializable

/** 统一曲目模型：聚合酷我 / Navidrome / 本地三源 */
@Serializable
data class Track(
    val id: String,
    val name: String,
    val artist: String,
    val album: String,
    val coverUrl: String? = null,
    val durationMs: Long = 0,
    /** 来源：kuwo / navidrome / local */
    val source: String = "kuwo",
    /** 原始 rid（酷我）或 Subsonic id */
    val sourceId: String = "",
    /** 音质标签，如「母带」「无损」「FLAC」 */
    val qualityLabel: String = "",
    /** 是否臻品母带 24bit/192kHz */
    val isMaster: Boolean = false,
    /**
     * 实际音质分级（[com.kmusic.apkhd.net.KuwoClient.RANK_*]）。
     *
     * **跨源比价必须用它，不能比 [qualityLabel] 的字符串** ——
     * 「无损 FLAC」和「无损」肉眼一样，但一个是 16bit/44.1k、一个是 24bit/88.2k。
     * 0 = 未知（老数据 / 本地文件），比较时按「不知道就别换」处理。
     */
    val qualityRank: Int = 0,
    /**
     * 源站报的**实际**码率（kbps）。0 = 未知。
     *
     * 为什么不能只靠播放器：ExoPlayer 从 mflac/mgg 容器里读不出码率（返回 -1），
     * 详情面板就会显示「未知」—— 用户想知道「母带到底多大」反而看不到。
     * 这个值来自取链响应（酷我 `bitrate`、GD `br`），播放器读不到时用它兜底。
     */
    val sourceKbps: Int = 0,
    /** 源站报的实际字节数。0 = 未知。有它就不必用「码率 × 时长」估算。 */
    val sourceBytes: Long = 0L,
    /** 播放直链（已解析） */
    val playUrl: String? = null,
    /** 酷我 ekey，用于流式解密 */
    val ekey: String? = null,
    /** 歌词直链（咪咕搜索响应自带 LRC 地址；其它源为空） */
    val lyricUrl: String? = null
)

/**
 * 播放状态快照，UI 直接观察。
 *
 * ## 三个「在播」相关字段必须分清（v2.4.8）
 *
 * 以前只有一个 `isPlaying`，被两种语义同时写：`player.playWhenReady`（我想播）
 * 和 `player.isPlaying`（此刻真在出声）。ExoPlayer 的 `isPlaying` 定义是
 * **「正在播 且 没有缓冲」** —— 于是它在一缓冲时就变 false、缓冲完又变 true。
 * 按钮图标直接绑这个值，弱网/切歌/seek 时就会反复「暂停↔播放」地闪。
 * 现在拆开：
 *
 * - [isPlaying]：**用户意图**。等于 `playWhenReady`，只在用户/自动流程切歌时变，
 *   缓冲期间**不抖**。按钮图标只看它。
 * - [audible]：**真的在出声**。等于 `player.isPlaying`，会随缓冲抖动 ——
 *   只给需要「确实有声音」的逻辑用，别拿去画图标。
 * - [buffering] / [stalled]：播放器是否在缓冲、以及**已连续缓冲超过 3 秒**。
 *   只有 [stalled] 才让 UI 叠加载圈，避免一秒一次的网络抖动也闪一下转圈。
 */
data class PlaybackState(
    val track: Track? = null,
    /** 用户意图：播放器被要求播放（= playWhenReady）。缓冲时保持 true，不要用 player.isPlaying 写它 */
    val isPlaying: Boolean = false,
    /** 真的在出声（= ExoPlayer.isPlaying：正在播且没在缓冲）。会随缓冲抖动，只给「有无声音」判断用 */
    val audible: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** 播放器当前处于 STATE_BUFFERING */
    val buffering: Boolean = false,
    /** 缓冲已持续超过 STALL_HINT_MS（3 秒）—— 只有它才让 UI 显示加载圈 */
    val stalled: Boolean = false,
    val error: String? = null
) {
    val progress: Float
        get() = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f

    /** 图标该画「暂停」还是「播放」—— 只看意图，不受缓冲影响 */
    val showPause: Boolean
        get() = isPlaying
}

/** 首页快捷入口 */
data class QuickEntry(
    val title: String,
    val subtitle: String,
    val highlighted: Boolean = false
)

/** 一行歌词：时间轴 + 文本 */
data class LyricLine(
    val timeMs: Long,
    val text: String
)

/** 下载任务 */
data class DownloadTask(
    val id: String,
    val name: String,
    val artist: String,
    val qualityLabel: String,
    val progress: Float,
    val status: String,
    val path: String?
)
