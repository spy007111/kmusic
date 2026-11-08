package com.kmusic.apkhd.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.kmusic.apkhd.data.DiagLog
import com.kmusic.apkhd.data.LYRIC_OFFSET_DEFAULT_MS
import com.kmusic.apkhd.data.DownloadCenter
import com.kmusic.apkhd.model.DownloadTask
import com.kmusic.apkhd.audio.LoudnessNormalizer
import com.kmusic.apkhd.audio.SpectrumProbe
import com.kmusic.apkhd.data.LocalLibrary
import com.kmusic.apkhd.data.LoudnessStore
import com.kmusic.apkhd.data.LocalScanStore
import com.kmusic.apkhd.data.LocalScanCache
import com.kmusic.apkhd.data.CachedSafEntry
import com.kmusic.apkhd.data.ScanCacheData
import com.kmusic.apkhd.model.LocalFolder
import com.kmusic.apkhd.data.RecentStore
import com.kmusic.apkhd.data.PlaybackSnapshotStore
import com.kmusic.apkhd.model.PlaybackSnapshot
import com.kmusic.apkhd.data.SettingsStore
import com.kmusic.apkhd.data.SourceCatalog
import com.kmusic.apkhd.model.LyricLine
import com.kmusic.apkhd.model.ArtistBrief
import com.kmusic.apkhd.model.ChartSection
import com.kmusic.apkhd.model.PlaybackState
import com.kmusic.apkhd.model.PlaylistBrief
import com.kmusic.apkhd.model.Track
import com.kmusic.apkhd.net.AlbumInfo
import com.kmusic.apkhd.net.ArtistInfo
import com.kmusic.apkhd.net.GdStudioClient
import com.kmusic.apkhd.net.MiguClient
import com.kmusic.apkhd.net.DailyMix
import com.kmusic.apkhd.net.KuwoClient
import com.kmusic.apkhd.net.DavEntry
import com.kmusic.apkhd.net.WebDavClient
import com.kmusic.apkhd.playback.DavAuth
import com.kmusic.apkhd.playback.NetworkMonitor
import com.kmusic.apkhd.playback.PrefetchWindow
import com.kmusic.apkhd.playback.AudioRuntimeState
import com.kmusic.apkhd.playback.PlaybackRecovery
import com.kmusic.apkhd.playback.PlaybackRecoveryToken
import com.kmusic.apkhd.net.LyricRepository
import com.kmusic.apkhd.net.NavidromeClient
import com.kmusic.apkhd.net.LyricQuery
import com.kmusic.apkhd.net.NeteaseClient
import com.kmusic.apkhd.net.TrackMatcher
import com.kmusic.apkhd.net.PlaylistInfo
import com.kmusic.apkhd.net.SearchResult
import android.content.ComponentName
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.kmusic.apkhd.playback.KuwoCipherHolder
import com.kmusic.apkhd.playback.PlaybackUrlRegistry
import com.kmusic.apkhd.playback.MoyinPlaybackService
import com.kmusic.apkhd.ui.theme.ThemeMode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.media3.common.Player
import kotlinx.coroutines.Job
import com.kmusic.apkhd.model.PlayMode
import com.kmusic.apkhd.data.FavoriteStore
import androidx.media3.common.C
import androidx.media3.common.Format
import com.kmusic.apkhd.net.AiService
import android.media.AudioManager
import com.kmusic.apkhd.net.ResolvedSong
import com.kmusic.apkhd.net.QualityPrefs
import com.kmusic.apkhd.model.CollectionItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import com.kmusic.apkhd.playback.PlaylistOrder
import com.kmusic.apkhd.playback.ResumePlan
import com.kmusic.apkhd.data.UserPlaylistStore
import com.kmusic.apkhd.data.PlaylistFavStore
import com.kmusic.apkhd.model.UserPlaylist
import com.kmusic.apkhd.playback.EqProcessor
import com.kmusic.apkhd.playback.FxChain
import com.kmusic.apkhd.playback.EqPresets
import com.kmusic.apkhd.playback.UserEqPresets
import com.kmusic.apkhd.playback.DsdPlaybackState
import com.kmusic.apkhd.model.SearchTab
import com.kmusic.apkhd.model.AlbumBrief
import com.kmusic.apkhd.net.AlbumMatch

/**
 * KMusic 主 ViewModel —— 搜索、播放、歌词、媒体库、设置集中一处。
 * 数据源：酷我（在线，母带）+ 本地（MediaStore）+ Navidrome（Subsonic）。
 */
@UnstableApi
/** 音乐人每批展示数量 */
private const val ARTIST_BATCH = 20

/** 音乐人翻页时一次拉多少（多拉一些，本地切片，减少请求次数） */
private const val ARTIST_PAGE = 60

/**
 * 音乐人累计上限（v2.4.5 无限下拉）。
 *
 * `artist/list` 的 offset 翻页是稳定的（实测 offset 0/20/40/60 返回四批完全不同的人），
 * 理论上能一直翻下去；但首页是一行无限横滑，不设上限用户会一直往下拉，
 * 所以到 200 个就停止追加 —— 够 10 屏，远超实际浏览深度。
 */
private const val ARTIST_MAX = 200

/**
 * 推荐歌单累计上限。
 *
 * `personalized/playlist` **没有 offset**，每次都是随机一批，只能靠反复请求 + 去重累积。
 * 实测连调 3 次（limit=20）去重后共 51 个，第 2 批新增 19、第 3 批新增 12 —— 衰减很明显。
 * 连续两轮拿不到新歌单就停，另外硬性封顶 100 个，避免用户一直滑一直空转。
 */
private const val PLAYLIST_MAX = 100

/** 推荐歌单「下拉追加」最多尝试几轮空请求就认定服务端已经给不出新内容 */
private const val PLAYLIST_EMPTY_ROUNDS = 2

/** 取链连续失败几次才报错（含第一次；再多就当作整条队列都取不到链） */
private const val MAX_PLAY_TRY = 3

/**
 * 停播等网络的最长时间（v2.4.6）。
 *
 * 断网时不能像以前那样顺延（离线每首都取不到链，连跳三首只会把队列耗光并误报
 * 「三家都没有音源」）。但也不能无限期挂着 —— 用户可能已经出门、早就没网了，
 * 界面必须给出明确结果。8 分钟足够覆盖地铁过隧道、电梯、Wi-Fi 切换这些场景。
 */
private const val OFFLINE_WAIT_LIMIT_MS = 8L * 60 * 1000

/**
 * 已解析直链的复用时限。
 *
 * 三家在线源给的都是带签名的临时地址，放久了 403/404（ERROR_CODE_IO_BAD_HTTP_STATUS）。
 * 过期的缓存**不能**拿去起播 —— 宁可再打一次接口。
 *
 * 必须与 [PlaybackUrlRegistry.DEFAULT_TTL_MS] 保持一致：一个管「还能不能复用」，
 * 一个管「要不要在 open 时换成新地址」，两个数错开就会出现「缓存说能用、
 * 登记表说早过期」的夹缝。
 */
private const val STREAM_TTL_MS = 10L * 60 * 1000

class MoyinViewModel(app: Application) : AndroidViewModel(app) {

    private val client = KuwoClient()
    private val netease = NeteaseClient()
    private val gd = GdStudioClient()
    private val migu = MiguClient()
    private val localLib = LocalLibrary(app)

    /** 本地扫描范围设置（勾选了哪些目录 + 手动添加的目录） */
    private val localScanStore = LocalScanStore(app)

    /**
     * 扫描结果落盘缓存。
     *
     * 没有它：每次冷启动都要把 SAF 目录整个走一遍、逐文件读标签（几百首 = 几十秒），
     * 用户感受到的就是「每次打开都要重新扫」。
     * 有了它：启动先把缓存塞进列表（≈0.1s，能立刻看到歌、能立刻播），
     * 后台再按文件指纹增量校验，只有变过的文件才重读标签。
     */
    private val localScanCache = LocalScanCache(app)

    /** 「扫描设置」里的可选目录（MediaStore 目录 + 手动添加的目录） */
    var localFolders by mutableStateOf<List<LocalFolder>>(emptyList())
        private set

    /** 当前启用的目录集合。**空集合 = 不限制** */
    var enabledLocalFolders by mutableStateOf<Set<String>>(emptySet())
        private set
    private val recentStore = RecentStore(app)
    private val favoriteStore = FavoriteStore(app)
    private val playlistStore = UserPlaylistStore(app)
    private val playlistFavStore = PlaylistFavStore(app)
    private val lyricsRepo = LyricRepository()
    private val loudnessStore = LoudnessStore(app)
    val settings = SettingsStore(app)

    /**
     * 播放会话落盘（队列 + 位置）。
     *
     * 冷启动恢复要靠它 —— 队列只活在内存里的话，进程一死就全没了。
     * 位置单独一个文件，见 [PlaybackSnapshotStore] 的注释。
     */
    private val snapshotStore = PlaybackSnapshotStore(app)

    private var navidrome: NavidromeClient? = null

    // ---- 播放：连接 MoyinPlaybackService（MediaSession 前台服务） ----
    /** 之前是自持 ExoPlayer，没有 MediaSession/前台服务 → 系统媒体卡片空白 + 后台被掐 */
    private var controller: MediaController? = null
    private var controllerFuture:
        com.google.common.util.concurrent.ListenableFuture<MediaController>? = null


    /**
     * 等服务冷启动把控制器连上（通常几百毫秒），最多等 6 秒。
     *
     * 以前是 `repeat(60) { delay(100) }` 轮询：最长白等 6 秒，而且每 100ms 醒一次。
     * 直接等 inits 里那个 ListenableFuture 就行，连上的瞬间立刻返回。
     */
    private suspend fun readyController(): MediaController? {
        controller?.takeIf { it.isConnected }?.let { return it }
        if (controllerFuture == null && !com.kmusic.apkhd.playback.PlaybackSessionExit.isExitRequested) connectController()
        val f = controllerFuture ?: return null
        return withTimeoutOrNull(6_000) {
            suspendCancellableCoroutine { cont ->
                f.addListener({
                    val c = runCatching { f.get() }.getOrNull()
                    if (cont.isActive) cont.resume(c)
                }, ContextCompat.getMainExecutor(getApplication()))
            }
        }
    }

    // ---------------- 播放：队列进播放器播放列表 ----------------

    /** 一条已解析好的直链 + 解析时刻（酷我的签名地址会过期，见 [STREAM_TTL_MS]） */
    private class Stream(val track: Track, val at: Long)

    /**
     * 直链缓存（key = track.id）。
     *
     * 只负责「这首取过链没有、还能不能用」。**它不代表这首已经在播放器列表里** ——
     * 那个事实只问 controller（mediaItemCount / mediaId）。早先两者合用一个 Map，
     * 结果 prefetch 只解析不加入，被判成「已铺过」，整首歌从播放列表里掉了。
     */
    private val streams = java.util.concurrent.ConcurrentHashMap<String, Stream>()

    /** 同一首正在解析中：并发方共享同一个结果，别让 prefetch 和铺队列各打一次接口 */
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Track?>>()

    /** 铺队列的后台任务，切歌时取消上一个 */
    private var queueBuildJob: Job? = null

    /** 当前这次点播。新的点播直接取消旧的 —— 替代手写的 playSeq 版本号 */
    private var playJob: Job? = null
    private var queueEpoch = 0L
    private val queueEpochUpdates = kotlinx.coroutines.flow.MutableStateFlow(0L)
    private var loudnessJob: Job? = null
    private var loudnessEpoch = 0L

    private fun recoveryToken(t: Track): PlaybackRecoveryToken =
        PlaybackRecoveryToken(AudioRuntimeState.snapshot.playbackEpoch, queueEpoch, t.id)

    private fun canRecover(token: PlaybackRecoveryToken): Boolean {
        val runtime = AudioRuntimeState.snapshot
        return PlaybackRecovery.canCommit(token, runtime.playbackEpoch, queueEpoch,
            queue.getOrNull(queueIndex)?.id, runtime.desiredPlaying,
            runtime.suppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE)
    }

    private suspend fun recoveryReady(token: PlaybackRecoveryToken): Boolean {
        kotlinx.coroutines.flow.combine(AudioRuntimeState.updates, queueEpochUpdates) { runtime, _ -> runtime }.first { runtime ->
            val current = PlaybackRecovery.canCommit(token, runtime.playbackEpoch, queueEpoch,
                queue.getOrNull(queueIndex)?.id, runtime.desiredPlaying, suppressed = false)
            !current || runtime.suppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE
        }
        return canRecover(token)
    }

    private fun cancelPendingPlayback() {
        com.kmusic.apkhd.playback.AudioPrecacheBridge.cancel()
        playJob?.cancel()
        offlineWatchdog?.cancel()
        awaitingNetwork = false
        restoring = false
        restoreWatchdog?.cancel()
        playbackState = playbackState.copy(isPlaying = false, buffering = false, stalled = false)
    }

    /** 直链缓存用「歌曲身份」做键，换源不能把原队列条目挤掉。 */
    private fun streamKey(t: Track): String =
        if (t.source in SourceCatalog.ONLINE) "${t.name}|${t.artist}|${t.durationMs}" else t.id

    /** 这条 id 的直链还能用就返回它（没解析过 / 已过期 → null） */
    private fun peekStream(id: String): Track? = streams[id]
        ?.takeIf { android.os.SystemClock.elapsedRealtime() - it.at <= STREAM_TTL_MS }
        ?.track

    /**
     * 解析一首曲目的可播直链，**同一首只发一次请求**。
     *
     * [playLoop]、[buildQueueAround]、[prefetchNext] 会同时来要同一首 ——
     * 以前各要各的，起播瞬间同一首要打两次接口（酷我并发一高就限流）。
     * 这里用 in-flight 表合并：第一个来的人干活，后来的人等同一个结果。
     *
     * 取消要原样抛出：`runCatching` 会把 CancellationException 吞成 null，
     * 于是「取消了」和「取链失败」混为一谈，旧协程还会继续往下写状态。
     */
    private suspend fun resolveOnce(t: Track, force: Boolean = false): Track? {
        val key = streamKey(t)
        if (force) invalidateStream(t)
        else peekStream(key)?.let { return it }
        val mine = CompletableDeferred<Track?>()
        inFlight.putIfAbsent(key, mine)?.let { return it.await() }
        var out: Track? = null
        try {
            out = resolveForPlayback(t)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            out = null
        } finally {
            out?.let {
                streams[key] = Stream(it, android.os.SystemClock.elapsedRealtime())
                // 登记进「最新直链」表：播放器 timeline 里存的是旧 URL，
                // DataSource 在 open 时会用这里的新地址替换（签名过期自愈）。
                rememberPlayUrl(it)
            }
            mine.complete(out)                    // 等待方永远能拿到结果，不会挂死
            inFlight.remove(key, mine)
        }
        return out
    }

    /**
     * 构造播放项。**MediaMetadata 必须带** ——
     * 系统媒体卡片/锁屏/耳机/车机显示的曲名、歌手、封面全来自这里。
     */
    private fun mediaItemOf(t: Track): MediaItem {
        val raw = if (t.source == "local") localLib.trackUri(t).toString() else t.playUrl.orEmpty()
        val uri = Uri.parse(cacheKeyed(t, raw))
        val namespace = when (t.source) {
            "webdav" -> com.kmusic.apkhd.playback.PlaybackCacheKey.namespace(davUrl, davUser)
            "navidrome" -> null
            else -> null
        }
        return MediaItem.Builder()
            .setUri(uri)
            .setCustomCacheKey(com.kmusic.apkhd.playback.PlaybackCacheKey.of(t, namespaceHash = namespace))
            .setMediaId(t.id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(t.name)
                    .setArtist(t.artist.ifEmpty { null })
                    .setAlbumTitle(t.album.ifEmpty { null })
                    .setArtworkUri(t.coverUrl?.takeIf { it.isNotEmpty() }?.let(Uri::parse))
                    .build()
            )
            .build()
    }

    /**
     * 解析出一首可播的曲目（带 playUrl / qualityLabel）。
     *
     * 本地 / Navidrome / WebDAV 各走自己的协议；所有在线源统一交给 [resolveOnline]。
     */
    private suspend fun resolveForPlayback(t: Track): Track? = when (t.source) {
        "local" -> t.copy(playUrl = localLib.trackUri(t).toString())
        // WebDAV：占位文件（STRM）要先跟 302 拿到真直链，避免把 Basic 凭据带去第三方 CDN；
        // 解析不出来就原样播（原来的 302 链路本身也能播）
        "webdav" -> {
            val raw = t.playUrl.orEmpty()
            val direct = runCatching { davClient?.resolveStream(raw) }.getOrNull()
            t.copy(
                playUrl = direct ?: raw,
                qualityLabel = t.qualityLabel.ifEmpty { "WebDAV" }
            )
        }
        "navidrome" -> {
            val url = t.playUrl ?: navidrome?.streamUrl(t.sourceId) ?: return null
            t.copy(playUrl = url, qualityLabel = "无损")
        }
        else -> resolveOnline(t)
    }

    /**
     * 在线源统一取链：先用自己的源，失败后按 **酷我 → 网易云 → 咪咕** 依次换源。
     *
     * 以前每个源各写一段回退（网易云→酷我→GD、咪咕→酷我、酷我→咪咕），
     * 分支多、顺序还不一致。现在只有这一条链：
     * - 自己的源优先（元数据/版本最准）
     * - 换源一律走 [TrackMatcher] 严格匹配，宁可不放也不串歌
     * - 成功时打 `PLAY_CHAIN`，排障时能看出「点的是 A 源，实际放的是 B 源」
     */
    private suspend fun resolveOnline(t: Track): Track? {
        val own = when (t.source) {
            SourceCatalog.KUWO -> kuwoResolved(t.sourceId, t)
            SourceCatalog.NETEASE -> neteaseResolved(t)
            SourceCatalog.MIGU -> miguResolved(t)
            else -> null
        }
        if (own != null) return own
        DiagLog.log("PLAY_CHAIN_MISS", "'${t.name}'/${t.artist} 源=${t.source} 自身取链失败，开始换源")
        for (src in SourceCatalog.ONLINE) {
            if (src == t.source) continue
            val got = when (src) {
                SourceCatalog.KUWO -> matchOnKuwo(t)?.let { kuwoResolved(it.rid, t) }
                SourceCatalog.NETEASE -> matchNetease(t)
                SourceCatalog.MIGU -> matchMigu(t)
                else -> null
            }
            // 只升不降（v2.4.10）：酷我经常「请求母带、给 128k mp3」。
            // 换源本来是为了「能出声」，不是为了「换来一个更差的」——
            // 上一源是无损/母带时，低于无损的一律不要，宁可继续试下一个源。
            if (got != null && t.qualityRank >= KuwoClient.RANK_FLAC &&
                got.qualityRank < KuwoClient.RANK_FLAC
            ) {
                DiagLog.log(
                    "PLAY_CHAIN_SKIP",
                    "'${t.name}'/${t.artist} → $src 只有 ${got.qualityLabel}，比原源差，跳过"
                )
                continue
            }
            if (got != null) {
                DiagLog.log(
                    "PLAY_CHAIN",
                    "'${t.name}'/${t.artist} 源=${t.source} → 实际=${src}（${got.qualityLabel}）"
                )
                return got
            }
        }
        return null
    }

    /**
     * 网易云直链（不换源）。
     *
     * 顺序：GD（`music-api.gdstudio.xyz`，能给真 FLAC）→ 网易云官方直连（只有 320k）。
     *
     * **标签按实际 `br` 写，不再一律挂「无损」** —— 以前 `url()` 只要非空就写「无损」，
     * 而当时域名指向的 `music.gdstudio.org` 恒 401，实际播的一直是 320k，
     * 界面上却显示无损。现在用 [GdLink.isLossless] 判定：
     * - `br ≥ 700` → 真 FLAC，标「无损」
     * - `br < 700` → 标实际码率（`320k` / `128k`），不撒谎
     *
     * 直连也有拿不到的：`code=-110` = 需 VIP/付费版权。这不是 bug，
     * 上层会走换源（酷我/咪咕）。
     */
    private suspend fun gdResolved(t: Track): Track? {
        val link = runCatching { gd.resolve(t.sourceId) }.getOrNull()
        if (link != null) {
            val label = GdStudioClient.labelOf(link.br)
            DiagLog.log(
                "PLAY_GD",
                "'${t.name}' id=${t.sourceId} br=${link.br} size=${link.size / 1024 / 1024}MB → $label"
            )
            return t.copy(
                playUrl = link.url,
                qualityLabel = label,
                qualityRank = GdStudioClient.rankOf(link.br),
                sourceKbps = link.br,
                sourceBytes = link.size
            )
        }
        // GD 拿不到才回官方直连：不登录只有 320k，VIP 曲直接 -110
        val direct = runCatching { netease.playUrl(t.sourceId) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: run {
                DiagLog.log("PLAY_GD_MISS", "'${t.name}' id=${t.sourceId} GD 无链，官方也拿不到")
                return null
            }
        DiagLog.log("PLAY_GD_FALLBACK", "'${t.name}' id=${t.sourceId} GD 无链 → 官方 320k")
        return t.copy(
            playUrl = direct,
            qualityLabel = "320k",
            qualityRank = KuwoClient.RANK_HIGH,
            sourceKbps = 320
        )
    }

    // ---------------- 网易歌单 → 酷我母带（v2.4.10，方案 A+B）----------------

    /**
     * 网易 id → 酷我 rid 的映射缓存（方案 B）。
     *
     * 值里的 `rank` 是**这首在酷我上实际能拿到的最高分级**：
     * - `>= RANK_FLAC`：值得再来一次
     * - `RANK_NONE`：酷我没有/只有低码率，**下次直接跳过搜索**（负缓存）
     *
     * 键用 `name|artist` 而不是网易 id：同一个 id 在不同歌单里是同一首，
     * 但跨源匹配的输入只有歌名+歌手，用 id 做键会在「同 id 不同版本」上错配。
     */
    private val neToKuwo = java.util.concurrent.ConcurrentHashMap<String, KuwoMapEntry>()

    private data class KuwoMapEntry(val rid: String, val rank: Int)

    private fun kuwoMapKey(t: Track) = "${t.name}|${t.artist}"

    /**
     * 网易源曲目：**先问酷我能不能给更好的，再回 GD**（v2.4.10）。
     *
     * 为什么顺序反了：以前 `resolveOnline` 里网易源只走 [gdResolved]，
     * GD 一出链就结束，酷我连一次搜索都没发过 —— 于是
     * 「臻品母带」这个设置对网易歌单完全无效，用户听着 CD 级无损却以为自己选的是母带。
     *
     * 现在的规则（**只升不降**）：
     * 1. 档位不是「母带」→ 不查酷我，直接 GD（省 0.4s）
     * 2. 「母带仅 Wi-Fi」开着且当前是流量 → 不查酷我，直接 GD 无损
     * 3. 查酷我并取链，**只有拿到 RANK_MASTER 才用**；给 mp3/aac 一律丢弃
     * 4. 酷我没有 / 只有低档 → 回落 GD 无损（最差也是现在的水平）
     *
     * 三重校验由 [matchOnKuwo] 完成（歌名 / 歌手 / 时长 ±3s），不放松。
     */
    private suspend fun neteaseResolved(t: Track): Track? {
        val wantMaster = defaultQuality == "master"
        val canProbeKuwo = wantMaster && !(masterWifiOnly && !onWifi())

        if (canProbeKuwo) {
            val key = kuwoMapKey(t)
            val cached = neToKuwo[key]

            // 负缓存：上次已确认酷我没母带（rank < MASTER）→ 不再白搜一次
            val rid = when {
                cached == null -> matchOnKuwo(t)?.rid
                cached.rank >= KuwoClient.RANK_MASTER && cached.rid.isNotEmpty() -> cached.rid
                else -> null
            }

            if (rid != null) {
                val song = runCatching {
                    client.resolveAtLeast(rid, KuwoClient.RANK_MASTER)
                }.getOrNull()

                if (song != null && song.rank >= KuwoClient.RANK_MASTER) {
                    val label = KuwoClient.labelOf(song.format, song.kbps) ?: "臻品母带"
                    neToKuwo[key] = KuwoMapEntry(rid, song.rank)
                    DiagLog.log(
                        "PLAY_MASTER",
                        "'${t.name}' 网易→酷我 rid=$rid ${song.format}/${song.kbps}kbps → $label"
                    )
                    val cipher = song.ekey?.let { client.createCipher(it) }
                    KuwoCipherHolder.put(song.url, cipher)
                    return t.copy(
                        source = "kuwo",
                        sourceId = rid,
                        playUrl = song.url,
                        ekey = song.ekey,
                        qualityLabel = label,
                        qualityRank = song.rank,
                        sourceKbps = song.kbps,
                        sourceBytes = song.sizeBytes,
                        isMaster = true,
                        durationMs = if (song.durationMs > 0) song.durationMs else t.durationMs
                    )
                }
                // 有 rid 但拿不到母带：记负缓存，省得每首都试
                neToKuwo[key] = KuwoMapEntry(rid, KuwoClient.RANK_NONE)
                DiagLog.log(
                    "PLAY_MASTER_MISS",
                    "'${t.name}' rid=$rid 酷我无母带（实际=${song?.format}/${song?.kbps}）→ 回落 GD"
                )
            } else {
                neToKuwo[key] = KuwoMapEntry("", KuwoClient.RANK_NONE)
            }
        }

        return gdResolved(t)
    }

    /** 当前是否在 Wi-Fi 上（无网络能力信息时按「不是 Wi-Fi」从严处理） */
    private fun onWifi(): Boolean {
        val cm = getApplication<Application>()
            .getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager ?: return false
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return false
        return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
    }

    /** 咪咕直链（不换源） */
    private suspend fun miguResolved(t: Track): Track? =
        runCatching { migu.resolve(t) }.getOrNull()

    /**
     * 按歌名 + 歌手在网易云找同一首。
     *
     * GD 的搜索结果**不带时长**，所以这里用不了三重校验，只能：
     * 归一化歌名**完全相等** + 歌手匹配。比酷我/咪咕的匹配更保守，
     * 宁可漏，也不拿翻唱/现场版冒充。
     */
    private suspend fun matchNetease(t: Track): Track? {
        val cands = runCatching {
            withTimeout(8_000) {
                netease.search("${t.name} ${t.artist}", limit = 10)
                    .ifEmpty { gd.search("${t.name} ${t.artist}", count = 10) }
            }
        }.getOrDefault(emptyList())
        val wantName = TrackMatcher.normalize(t.name)
        val hit = cands.firstOrNull {
            TrackMatcher.normalize(it.name) == wantName &&
                TrackMatcher.artistMatches(t.artist, it.artist)
        } ?: return null
        val resolved = gdResolved(hit) ?: return null
        return t.copy(
            playUrl = resolved.playUrl,
            qualityLabel = resolved.qualityLabel,
            qualityRank = resolved.qualityRank,
            sourceKbps = resolved.sourceKbps,
            sourceBytes = resolved.sourceBytes
        )
    }

    /**
     * 按名搜歌词（网易云直连）。
     *
     * 校验和 gdstudio/ lrclib 那条路同一套 [LyricQuery.verify] ——
     * 宁可返回 null（显示「暂无歌词」），也不拿同名别人的版本冒充。
     */
    private suspend fun lyricByNameDirect(t: Track): String? {
        if (t.name.isBlank()) return null
        val cands = runCatching { netease.search("${t.name} ${t.artist}", limit = 10) }
            .getOrDefault(emptyList())
        var best: String? = null
        var bestDist = Long.MAX_VALUE
        for (c in cands) {
            if (!LyricQuery.verify(c.name, c.artist, t.name, t.artist)) continue
            val dist = kotlin.math.abs(c.durationMs - t.durationMs)
            if (dist >= bestDist) continue
            val text = runCatching { netease.lyric(c.sourceId) }.getOrNull() ?: continue
            if (text.isNotBlank()) {
                best = text
                bestDist = dist
            }
        }
        return best
    }

    /**
     * 按「歌名 + 歌手 + 时长」在咪咕里找同一首并取链。
     *
     * 严格复用 [TrackMatcher] 的校验，不放松阈值 ——
     * 宁可这一首放不了，也不给用户放错歌。
     */
    private suspend fun matchMigu(t: Track): Track? {
        val cands = runCatching {
            withTimeout(8_000) { migu.search("${t.name} ${t.artist}", count = 10) }
        }.getOrDefault(emptyList())
        val target = TrackMatcher.Candidate(t.name, t.artist, t.durationMs)
        val best = cands
            .filter {
                TrackMatcher.isMatch(
                    target,
                    TrackMatcher.Candidate(it.name, it.artist, it.durationMs)
                )
            }
            .minByOrNull { kotlin.math.abs(it.durationMs - t.durationMs) }
            ?: return null
        val resolved = runCatching { migu.resolve(best) }.getOrNull() ?: return null
        return t.copy(
            playUrl = resolved.playUrl,
            qualityLabel = resolved.qualityLabel,
            qualityRank = KuwoClient.RANK_LOSSY
        )
    }

    /** 走酷我取链，并把这一首的解密器**按直链**登记进密钥表 */
    /**
     * 按「默认音质」设置排出可取链的档位。
     * 「无损降级」关掉就**只取首选那一档** —— 拿不到直接失败，不偷偷换音质。
     */
    private fun qualityTiers(): List<String> =
        QualityPrefs.tiers(defaultQuality, losslessFallback)

    /** 依次尝试各档位取链（顺序即优先级） */
    private suspend fun resolveByTier(rid: String): ResolvedSong? {
        for (br in qualityTiers()) {
            client.resolve(rid, br)?.let { return it }
        }
        return null
    }

    private suspend fun kuwoResolved(rid: String, display: Track): Track? {
        val r = resolveByTier(rid) ?: return null
        // ekey 解不开时绝不能继续：播放器会把加密字节当 PCM 播出去，用户听到的是满耳噪音。
        // 宁可这一源失败、走跨源回退，也不给一段「能播但是噪音」的音频。
        val cipher = r.ekey?.let { client.createCipher(it) }
        if (r.ekey != null && cipher == null) {
            DiagLog.log("PLAY", "ekey 解密失败 rid=$rid br=${r.br}，放弃该源")
            return null
        }
        KuwoCipherHolder.put(r.url, cipher)
        // 标签按**响应里的真实格式/码率**写（v2.4.10）。
        // 以前按请求的 br 常量写：请求 20900kmflac、服务端回 128k mp3，界面照样挂「臻品母带」。
        val realLabel = KuwoClient.labelOf(r.format, r.kbps)
        val label = realLabel ?: qualityLabel(r.br)
        DiagLog.log("PLAY_KUWO", "rid=$rid 请求=${qualityTiers().firstOrNull()} 实际=${r.format}/${r.kbps}kbps → $label")
        return display.copy(
            source = "kuwo",
            sourceId = rid,
            playUrl = r.url,
            ekey = r.ekey,
            qualityLabel = label,
            qualityRank = r.rank,
            sourceKbps = r.kbps,
            sourceBytes = r.sizeBytes,
            isMaster = r.rank >= KuwoClient.RANK_MASTER,
            durationMs = if (r.durationMs > 0) r.durationMs else display.durationMs
        )
    }

    /**
     * 给在线流加稳定的缓存 key 片段。
     *
     * 酷我/咪咕直链每次解析都带新签名，CacheDataSource 默认按 URL 做 key 会永远 miss。
     * `#k=` 不会发到服务器，只参与缓存键；同一首歌永远命中同一条缓存。
     */
    private fun cacheKeyed(t: Track, raw: String): String {
        if (raw.isEmpty()) return raw
        if (t.source !in setOf("kuwo", "netease", "migu", "webdav")) return raw
        if (raw.contains("#k=")) return raw
        return "$raw#k=${t.source}:${t.sourceId}:${t.qualityLabel}"
    }

    /** 播放器自己切歌（含放完自动下一首）时，把 UI 状态同步过来 */
    /**
     * 播放器报错兜底。
     *
     * 以前这里只「写状态 + toast」：不重取链、不跳过、也不重建播放器。
     * 而 `ERROR_CODE_IO_BAD_HTTP_STATUS` 是**拿到 URL 之后**才炸的 —— 取链阶段那道
     * 「失败就顺延」的闸门（[playLoop]）根本不经过，于是一首歌卡死整条队列。
     *
     * 现在分三级：
     * 1. WebDAV：直链 ↔ 原始 302 地址换一条路（每首一次）
     * 2. 在线源：作废这条直链 → 重新取链 → 原地重试（每首一次）
     * 3. 还不行：顺延下一首，连跳 [MAX_PLAY_TRY] 首都失败才停手
     */
    /** WebDAV 每条曲目只自动换路重试一次，避免死循环 */
    private val davRetried = mutableSetOf<String>()

    /** 在线源每条曲目只自动重取一次（用户重新点播时清空） */
    private val streamRetried = mutableSetOf<String>()

    /** 连续「播放期失败→顺延」的次数；成功起播即清零 */
    private var consecutivePlayErrors = 0
    private val unsupportedAudioSkips = com.kmusic.apkhd.playback.UnsupportedAudioSkipPolicy()

    /**
     * 网络边沿监听（v2.4.6）。
     *
     * 断网时播放器失败后**不能顺延**：离线状态下每一首取链都会失败，连跳 3 首只会
     * 把队列耗光，还把「没网」误报成「酷我/网易云/咪咕都没有音源」。正确做法是停在
     * 当前曲等网络回来，再由 [onNetworkRegained] 原地续播。
     */
    private val networkMonitor = NetworkMonitor(app)

    /** 因为断网而停在当前曲；网络恢复后由它触发续播 */
    private var awaitingNetwork = false

    /** 断网瞬间的播放位置（播放器报错后 currentPosition 可能已经归零，先记下来） */
    private var offlineResumeAt = 0L

    /**
     * 等网看门狗（v2.4.6）。
     *
     * 只靠 `ConnectivityManager.onAvailable` 是不够的：**Wi-Fi 连着但路由器断了 / 酒店门户 /
     * DNS 挂了** 时，网络接口一直是 UP，系统不会报「恢复」，回调永远不来。
     * 所以停住等网时同时起一个每 15 秒自己试一次取链的定时器 ——
     * 网一通就续播，无论系统怎么描述网络状态。最多守 8 分钟，之后放行顺延，避免无限期卡住。
     */
    private var offlineWatchdog: kotlinx.coroutines.Job? = null

    /**
     * 播放看门狗（v2.4.7）。每 30 秒体检「该在播却没在播」，兜住 STATE_ENDED 之外的静默停播。
     */
    private var playGuard: kotlinx.coroutines.Job? = null

    /**
     * 整条队列已经播完（v2.4.7）。
     *
     * 播完后 player.playWhenReady 仍是 true（ExoPlayer 播完不会自动清它），
     * 30 秒看门狗会因此判定「该播没播」并反复接管 —— 实测会每 30 秒重复提示「队列已播完」。
     * 用户只要重新播放（点歌/下一首/恢复网络）就清掉它。
     */
    private var queueFinished = false

    /**
     * 「整条队列播完、停在末尾」的标记（v2.4.8 替代 v2.4.7 里直接写 playWhenReady=false）。
     *
     * 与 [queueFinished] 的区别：queueFinished 表示「看门狗别管了」，
     * endedParked 表示「播放器被我们主动刹停在末尾，用户再按播放要回队首」。
     * 两者同时成立；分开是因为它们被清除的时机不同（用户按播放时都要清）。
     */
    private var endedParked = false

    /** onPlaybackEnded 重入保护（v2.4.8） */
    private var handlingEnded = false

    /** 正在执行「播完刹停」的 playWhenReady=false，挡住回调里的重入（v2.4.8） */
    private var parkingAtEnd = false

    /** 上一次看到的 playWhenReady，用来识别「假 → 真」边沿（见 onPlayWhenReadyChanged） */
    private var wasPlayWhenReady = false

    /**
     * 本轮缓冲的起点（SystemClock.elapsedRealtime）；不在缓冲时为 0（v2.4.8）。
     *
     * 只用来算 `stalled`（连续缓冲超过 3 秒）—— UI 只在真卡住时叠加载圈，
     * 「缓冲一下立刻好」这种网络抖动不该每次都闪一个转圈。
     */
    private var bufferingSince = 0L

    private companion object StalledHint {
        /** 连续缓冲超过这个时长才提示加载圈 */
        const val STALL_HINT_MS = 3_000L
    }

    /** 直链登记表用的键：与 `#k=` 缓存片段同源，保证一处失效处处失效 */
    private fun registryKey(t: Track): String =
        "${t.source}:${t.sourceId}:${t.qualityLabel}"

    private fun rememberPlayUrl(t: Track) {
        val url = t.playUrl.orEmpty()
        if (url.isNotEmpty()) PlaybackUrlRegistry.remember(registryKey(t), url)
    }

    /** 把这条曲目的直链从所有缓存里作废，下一次 resolve 必然重新打接口 */
    private fun invalidateStream(t: Track) {
        streams.remove(streamKey(t))
        PlaybackUrlRegistry.forget(registryKey(t))
    }

    /**
     * 播放器播到 timeline 末尾时接管（v2.4.7）。
     *
     * 这是「听了几首歌定住」的最后一道保险：即使补窗因为任何原因没补上，
     * 这里也能把队列里该播的下一首接上，而不是让用户对着一条走满的进度条发呆。
     *
     * 顺序模式走到队列末尾 = 真的播完了 → 明确提示，不再无声无息。
     * 循环/随机模式下 ExoPlayer 的 repeatMode 会自己回绕，根本不会进 ENDED；
     * 万一进了（repeatMode 没设上等异常），也按「接着往下播」处理。
     */
    private fun onPlaybackEnded() {
        val c = controller ?: return
        // 防重入：STATE_ENDED 回调与 30s 看门狗可能在同一毫秒双双命中
        // （实测日志里 02:58:12.003~.004 连出三条 PLAY_ENDED）。
        // 已经在处理就直接返回，避免重复 toast / 重复切歌。
        if (handlingEnded) return
        handlingEnded = true
        try {
            handlePlaybackEndedInner(c)
        } finally {
            handlingEnded = false
        }
    }

    private fun handlePlaybackEndedInner(c: Player) {
        val curId = runCatching { c.currentMediaItem?.mediaId }.getOrNull()
        val idx = curId?.let { id -> queue.indexOfFirst { it.id == id } } ?: queueIndex
        DiagLog.log("PLAY_ENDED", "播放器到达 timeline 末尾（当前逻辑第 ${idx + 1}/${queue.size} 首）")

        if (playMode == PlayMode.LOOP || playMode == PlayMode.SHUFFLE || playMode == PlayMode.SINGLE) {
            // repeatMode 本该拦住，走到这里说明状态没落上 —— 补一次并从头继续
            applyPlayMode(c)
            if (queue.isNotEmpty()) {
                DiagLog.log("PLAY_ENDED", "循环模式异常停在末尾，回到队首继续")
                queueIndex = 0
                playCurrent()
            }
            return
        }

        val next = com.kmusic.apkhd.playback.QueueContinuation.nextToPlayAfterEnd(idx, queue.size)
        if (next == null) {
            queueFinished = true
            // 落 playWhenReady=false 这一步必须保留（v2.4.7 结论）：
            // 遥控器/通知栏/耳机线的播放键是 MediaSession 直接调 player.play()，
            // 不经过 togglePlay；playWhenReady 若一直是 true 就没有「假→真」边沿，
            // 实测会退化成「重播最后一首」。
            //
            // v2.4.8 补充：这条赋值本身会在回调里再走一遍，用 [parkingAtEnd] 挡掉，
            // 免得起初那种「onPlaybackEnded ↔ onPlayWhenReadyChanged」来回触发。
            parkingAtEnd = true
            runCatching { c.playWhenReady = false }
            parkingAtEnd = false
            endedParked = true
            playbackState = playbackState.copy(isPlaying = false, buffering = false, stalled = false)
            DiagLog.log("PLAY_ENDED", "队列已播完（共 ${queue.size} 首）")
            toast("队列已播完")
            persistSession()
            return
        }
        queueFinished = false
        DiagLog.log("PLAY_ENDED", "自动接上队列第 ${next + 1} 首 '${queue[next].name}'")
        queueIndex = next
        playCurrent()
    }

    /**
     * 把播放器的 `playWhenReady`（用户意图）同步进 UI 状态（v2.4.8）。
     *
     * 这是 `playbackState.isPlaying` 的**唯一**权威写入点 —— 其余地方只在
     * 「切换意图的当下」乐观写一次（点播放/点暂停/起播/播完），最终都会被这里校正。
     * 这样「缓冲时图标乱翻」就不可能再发生：缓冲不改变 playWhenReady。
     */
    private fun syncIntentFromPlayer(c: Player, playWhenReady: Boolean) {
        if (playbackState.isPlaying == playWhenReady) return
        playbackState = playbackState.copy(isPlaying = playWhenReady)
    }

    /**
     * 播放看门狗（v2.4.7）：每 30 秒体检一次，只处理「该在播却没在播」。
     *
     * 为什么还要这一层：STATE_ENDED 能兜住「正常播完」，兜不住
     * 「timeline 被清空 / 播放器进了 IDLE / playWhenReady 被谁置回 false」这类异常，
     * 那些同样表现为「定住、不报错」。这条看门狗不猜原因，只看结果。
     *
     * 只在**用户明确处于播放态**时介入，避免打断用户自己按的暂停。
     */
    private fun startPlayGuard() {
        playGuard?.cancel()
        playGuard = viewModelScope.launch {
            while (true) {
                delay(30_000L)
                val c = controller ?: continue
                val st = runCatching { c.playbackState }.getOrNull() ?: continue
                val wantPlay = runCatching { c.playWhenReady }.getOrDefault(false)
                // 这里要的正是「此刻到底出没出声」——问 player.isPlaying 是对的
                val audible = runCatching { c.isPlaying }.getOrDefault(false)
                // 用户暂停 / 正在等网 / 正在缓冲 / 正在恢复中 / 整条队列已播完：都不该插手
                if (!wantPlay || awaitingNetwork || restoring || queueFinished) continue
                if (st == Player.STATE_BUFFERING) continue
                if (audible) continue
                if (playbackState.track == null) continue
                if (queue.isEmpty()) continue

                if (st == Player.STATE_ENDED) {
                    onPlaybackEnded()
                    continue
                }
                val suppressed = c.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE
                if (!PlaybackRecovery.needsRepair(st, Player.STATE_READY, Player.STATE_IDLE,
                        wantPlay, audible, suppressed, c.playerError != null)) continue
                val at = runCatching { c.currentPosition }.getOrDefault(0L)
                val t = queue.getOrNull(queueIndex) ?: continue
                val token = recoveryToken(t)
                DiagLog.log("PLAY_GUARD", "播放链异常（state=$st，${at}ms），原位恢复 '${t.name}'")
                invalidateStream(t)
                streamRetried.remove(streamKey(t))
                playbackState = playbackState.copy(buffering = true, error = null)
                val fresh = resolveOnce(t) ?: continue
                if (canRecover(token)) retryInPlace(fresh.copy(id = t.id), at, token)
            }
        }
    }

    private fun handlePlaybackError(error: androidx.media3.common.PlaybackException) {
        // ⚠️ 必须问播放器要「真正出错的那一首」，不能用 playbackState.track：
        // 自动连播/切歌的瞬间它还停在上一首，按它重取会重取错歌、跳错位置。
        val mediaId = runCatching { controller?.currentMediaItem?.mediaId }.getOrNull()
        val t = mediaId?.let { id -> queue.firstOrNull { it.id == id } } ?: playbackState.track
        val code = runCatching { error.errorCodeName }.getOrDefault("ERROR")
        val msg = error.message.orEmpty().take(180)
        // host 从「播放器里真正在放的那条 URI」取：队列表里存的 Track 往往没有 playUrl，
        // 拿它算出来永远是空，日志里就看不出到底炸在哪个 CDN 上。
        val host = runCatching {
            controller?.currentMediaItem?.localConfiguration?.uri?.host
                ?: android.net.Uri.parse(t?.playUrl.orEmpty()).host
        }.getOrNull().orEmpty()
        val detail = buildString {
            append(code)
            if (host.isNotEmpty()) append(" @ ").append(host)
            if (msg.isNotEmpty()) append(" · ").append(msg)
        }
        lastPlayError = "$detail（${t?.name ?: "?"}）"
        DiagLog.log("PLAY_ERR", "$detail track=${t?.name} source=${t?.source} urlHost=$host")

        if (com.kmusic.apkhd.playback.UnsupportedAudioPolicy.isUnsupported(error.errorCode)) {
            skipUnsupportedAudio(t)
            return
        }

        // ── 一级：WebDAV 换一条路（直链 ↔ 原始 302 地址） ──
        if (t != null && t.source == "webdav" && davRetried.add(t.id)) {
            val token = recoveryToken(t)
            viewModelScope.launch {
                val raw = t.sourceId.orEmpty()
                val alt = if (t.playUrl == raw) {
                    runCatching { davClient?.resolveStream(raw) }.getOrNull()
                } else raw
                if (!recoveryReady(token)) return@launch
                if (!alt.isNullOrEmpty() && alt != t.playUrl) {
                    toast("这条直链打不开，换一条路重试")
                    retryInPlace(t.copy(playUrl = alt), playbackState.positionMs, token)
                } else {
                    playbackState = playbackState.copy(isPlaying = false, buffering = false, error = detail)
                    toast("WebDAV 播放失败：$detail")
                }
            }
            return
        }

        // ── 断网：停止顺延，原地等网络回来（v2.4.6） ──
        //
        // 顺序很重要：这一段必须排在「在线源重取链」和「顺延下一首」之前。
        // 离线时重取必然失败、顺延必然连败 —— 以前正是这样把「没网」演成
        // 「酷我/网易云/咪咕都没有音源」，而且恢复后没有任何人再试。
        //
        // 注意条件里**不要求** networkMonitor.isOffline()：Wi-Fi 连着但路由器没网时，
        // 系统仍会报「有网」，可取链一样失败。凡是网络类错误，一律先进等网；
        // 只要网是通的，下一次探测（15s 内）就会自己续播，等于多等最多 15 秒。
        // 比起「连跳三首 + 误报无音源」，这个代价划算得多。
        if (t != null && isOfflineError(error)) {
            val at = runCatching { controller?.currentPosition ?: 0L }.getOrDefault(0L)
                .takeIf { it > 0L } ?: playbackState.positionMs
            // 不清 consecutivePlayErrors：等网这段不算「这首救不回来」，
            // 恢复后如果真失败还要靠它兜底顺延。
            enterAwaitingNetwork(t, at)
            return
        }

        // ── 二级：在线源作废旧链、重新取链、原地续播 ──
        // 这是 ERROR_CODE_IO_BAD_HTTP_STATUS 最常见的原因：签名过期。
        if (t != null && t.source in SourceCatalog.ONLINE && streamRetried.add(streamKey(t))) {
            val resumeAt = runCatching { controller?.currentPosition ?: 0L }.getOrDefault(0L)
            invalidateStream(t)
            val originalId = t.id
            val token = recoveryToken(t)
            playbackState = playbackState.copy(buffering = true, error = null)
            DiagLog.log("PLAY_RETRY", "重新取链 '${t.name}'（接着 ${resumeAt}ms 播）")
            viewModelScope.launch {
                val fresh = resolveOnce(t)
                if (!recoveryReady(token)) return@launch
                if (fresh == null) {
                    DiagLog.log("PLAY_RETRY", "重取失败 '${t.name}' → 顺延下一首")
                    skipAfterError(t, detail)
                    return@launch
                }
                toast("音频流断了，已重新连接")
                retryInPlace(fresh.copy(id = originalId), resumeAt, token)
            }
            return
        }

        // ── 三级：救不回来了，顺延下一首 ──
        skipAfterError(t, detail)
    }

    /**
     * 这个错误是不是「网络根本连不上」。
     *
     * 只看 `ERROR_CODE_IO_NETWORK_CONNECTION_FAILED` / `ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT`
     * 两个码：`BAD_HTTP_STATUS`（签名过期 403）虽然也常见，但那是服务端拒绝，
     * 不该被当成「等网回来」——它有自己的一级重取链兜底。
     */
    private fun isOfflineError(error: androidx.media3.common.PlaybackException): Boolean =
        error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT

    /**
     * 在 IO 线程上跑探针，避免 TCP 连接（最长 3 秒）卡住主线程。
     *
     * `playLoop` 本身跑在 `viewModelScope`（Main）里，取链已经切过 IO；
     * 这里是另一处需要显式切换的地方。
     */
    private suspend fun probeReachableOnIo(): Boolean =
        withContext(Dispatchers.IO) { com.kmusic.apkhd.playback.NetProbe.reachable() }

    private fun enterAwaitingNetwork(t: Track, resumeAt: Long) {
        awaitingNetwork = true
        offlineResumeAt = resumeAt.coerceAtLeast(0L)
        playbackState = playbackState.copy(isPlaying = false, buffering = true, error = null)
        DiagLog.log("NET", "等网络：停在 '${t.name}'（${offlineResumeAt}ms）")
        toast("网络已断开，恢复后自动续播")
        startOfflineWatchdog(t)
    }

    /**
     * 自主探测式等网：每 15 秒试一次取链，成功就续播。
     *
     * 不依赖系统网络回调 —— 「接口 UP 但真的不通」时那个回调不会来。
     * 8 分钟还没通就放弃等待、走正常顺延，免得用户以为 App 卡死。
     */
    private fun startOfflineWatchdog(t: Track) {
        offlineWatchdog?.cancel()
        val token = recoveryToken(t)
        offlineWatchdog = viewModelScope.launch {
            var waited = 0L
            while (awaitingNetwork && waited < OFFLINE_WAIT_LIMIT_MS) {
                delay(15_000L)
                waited += 15_000L
                if (!awaitingNetwork || !recoveryReady(token)) return@launch
                val fresh = resolveOnce(t, force = true)
                if (!recoveryReady(token)) return@launch
                if (fresh != null) {
                    DiagLog.log("NET", "探测到网络已通，续播 '${t.name}'（${offlineResumeAt}ms）")
                    awaitingNetwork = false
                    toast("网络已恢复，继续播放")
                    retryInPlace(fresh.copy(id = t.id), offlineResumeAt, token)
                    return@launch
                }
            }
            if (awaitingNetwork && canRecover(token)) {
                DiagLog.log("NET", "等网超过 ${OFFLINE_WAIT_LIMIT_MS / 60_000} 分钟，放弃等待")
                awaitingNetwork = false
                skipAfterError(t, "网络长时间不可用")
            }
        }
    }

    /**
     * 网络恢复：把断网时停下的那首重新取链、回到原位继续播（v2.4.6）。
     *
     * 三个必须做的动作，少一个都会「恢复后不动」：
     * 1. `invalidateStream` —— 断网期间解析失败的负缓存（其实没有负缓存，但旧链可能已过期）
     * 2. 清 `streamRetried` —— 断网时那次失败已经用掉这唯一的机会，不清就永远不再试
     * 3. `retryInPlace` —— 用 replaceMediaItem 保留队列与位置，不要 setMediaItems 重铺
     */
    private fun onNetworkRegained() {
        if (!awaitingNetwork) {
            // 没在等网：仍要清掉「已重试」标记，否则用户手动再播同一首不会重取链
            streamRetried.clear()
            return
        }
        offlineWatchdog?.cancel()
        offlineWatchdog = null
        val id = runCatching { controller?.currentMediaItem?.mediaId }.getOrNull()
        val t = id?.let { i -> queue.firstOrNull { it.id == i } } ?: playbackState.track
        if (t == null) {
            awaitingNetwork = false
            return
        }
        awaitingNetwork = false
        // 播放器若已因为错误把 timeline 清空，就不能再原地替换，退回普通起播
        streamRetried.remove(streamKey(t))
        invalidateStream(t)
        val resumeAt = offlineResumeAt
        val token = recoveryToken(t)
        DiagLog.log("NET", "网络恢复，续播 '${t.name}'（${resumeAt}ms）")
        playbackState = playbackState.copy(buffering = true, error = null)
        toast("网络已恢复，继续播放")
        viewModelScope.launch {
            val fresh = resolveOnce(t, force = true)
            if (!recoveryReady(token)) return@launch
            if (fresh == null) {
                DiagLog.log("NET", "恢复后重取链失败 '${t.name}' → 顺延")
                skipAfterError(t, "网络恢复后仍取不到音源")
                return@launch
            }
            retryInPlace(fresh.copy(id = t.id), resumeAt, token)
        }
    }

    /**
     * 一首救不回来时顺延到下一首；连跳 [MAX_PLAY_TRY] 首才真的停。
     *
     * 单曲为一首时直接停（跳过去还是它，死循环）。停下来时必须同时写状态 + toast：
     * 播放页不渲染 `playbackState.error`，只写状态等于什么都没发生。
     */
    private fun skipAfterError(t: Track?, detail: String) {
        consecutivePlayErrors++
        val canSkip = queue.size > 1 && consecutivePlayErrors < MAX_PLAY_TRY
        playbackState = playbackState.copy(isPlaying = false, buffering = false, error = detail)
        if (!canSkip) {
            val tail = if (consecutivePlayErrors > 1) "（已连试 $consecutivePlayErrors 首）" else ""
            DiagLog.log("PLAY_GIVEUP", "连试 $consecutivePlayErrors 首均失败，停手：$detail")
            toast("播放失败：$detail$tail")
            consecutivePlayErrors = 0
            return
        }
        val nextAt = run {
            val c = controller
            if (c != null && c.hasNextMediaItem()) {
                queue.indexOfFirst { it.id == c.getMediaItemAt(c.nextMediaItemIndex).mediaId }
            } else -1
        }.takeIf { it >= 0 } ?: ((queueIndex + 1) % queue.size.coerceAtLeast(1))
        DiagLog.log("PLAY_SKIP", "播放期失败，顺延到队列第 $nextAt 首（连续第 $consecutivePlayErrors 次）")
        queueIndex = nextAt
        playCurrent()
    }

    private fun skipUnsupportedAudio(t: Track?) {
        val c = controller ?: return
        val runtime = AudioRuntimeState.snapshot
        val ids = queue.map { it.id }
        val suggested = if (c.hasNextMediaItem()) c.getMediaItemAt(c.nextMediaItemIndex).mediaId else null
        val nextId = if (t != null && runtime.desiredPlaying && runtime.suppressionReason == 0) {
            unsupportedAudioSkips.next(t.id, ids, suggested, playMode != PlayMode.ORDER)
        } else null
        AudioRuntimeState.invalidatePlayback()
        cancelPendingPlayback()
        playbackState = playbackState.copy(isPlaying = false, buffering = false, error = "当前设备不支持此音频格式")
        toast(com.kmusic.apkhd.playback.UnsupportedAudioPolicy.message(t?.name ?: "当前曲目", nextId != null), long = true)
        DiagLog.log("PLAY_UNSUPPORTED", "设备不支持 track=${t?.name} next=$nextId")
        if (nextId == null) {
            AudioRuntimeState.recordPauseIntent(com.kmusic.apkhd.playback.PauseCause.ERROR)
            c.pause()
            c.stop()
        } else {
            queueIndex = ids.indexOf(nextId)
            playCurrent()
        }
    }

    private fun attachControllerListener(c: MediaController) {
        wasPlayWhenReady = runCatching { c.playWhenReady }.getOrDefault(false)
        c.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (controller !== c) return
                handlePlaybackError(error)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // ⚠️ 以前这里是 `prepared[it] ?: return` —— 拿不到就整段跳过，
                // 歌词永远不加载（用户看到「一直暂无歌词」）。
                // 直链缓存是播放时按需填充的，跨源回退 / 外部切歌都可能对不上，
                // 所以对不上要**退到队列里找**，不是放弃。
                val id = mediaItem?.mediaId ?: return
                val fromPrepared = queue.firstOrNull { it.id == id }?.let { t0 -> streams[streamKey(t0)]?.track }
                val t = fromPrepared
                    ?: queue.firstOrNull { it.id == id }
                    ?: run {
                        DiagLog.log("LYRIC_SKIP", "切歌时找不到曲目 mediaId=$id（缓存 ${streams.size} 项 / 队列 ${queue.size} 项）→ 歌词不加载")
                        return
                    }
                DiagLog.log(
                    "LYRIC_SWITCH",
                    "切歌 reason=$reason id=$id '${t.name}' 来源=${t.source} " +
                        "(取自${if (fromPrepared != null) "prepared" else "队列兜底"})"
                )
                // ⚠️ 不能在这里 `if (playbackState.track?.id == t.id) return`。
                // 同一首歌被再次点开（搜索里重点一次、回退到刚听过的歌）时
                // id 是相同的，早退会让歌词压根不加载 —— 用户看到的就是「没歌词」。
                // 重新点开一首歌本来就应该重新取词，所以这里只同步索引与状态。
                queue.indexOfFirst { it.id == t.id }.takeIf { it >= 0 }?.let { queueIndex = it }
                playbackState = playbackState.copy(
                    track = t,
                    isPlaying = c.playWhenReady,
                    buffering = false,
                    error = null,
                    durationMs = if (t.durationMs > 0) t.durationMs else playbackState.durationMs
                )
                // 自动连播 / 通知栏切歌也会走到这里 —— 队列没变、当前曲变了，照样要落盘
                persistSession()
                // 切歌清音质检测结果：不清的话，界面上还挂着上一首的「实测截止」，
                // 用户会以为这首也测过了（假数据比没数据更糟）。
                clearProbe()
                // 切歌清 AI 导赏。
                // 下一首/自动连播是播放器内部切的，根本不经过 playTrack，
                // 只在 playTrack 里清会出现「换歌了但还显示上一首赏析」。
                if (lastAiTrackId != t.id) {
                    lastAiTrackId = t.id
                    resetAiForTrackChange()
                    // 译文跟着歌走：上一首的译文挂到新歌下面比没有更糟
                    lyricTranslation = emptyMap()
                    translating = false
                }
                loadLyrics(t)
                viewModelScope.launch { recentStore.add(t) }
                // 响度归一：切歌就重新算增益。本地文件才有数据，在线流直接返回 0dB。
                applyLoudness(t)
                // 播放器自己往前走（自动连播/通知栏下一首）时也要把预取窗口往前滚，
                // 否则只有用户手点的那一次会预取，后面几首又回到「现取现等」。
                prefetchNext()
                ensureNextInTimeline(t.id)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // 「播完刹停」时我们自己置的 false：只同步边沿快照，不当成用户动作
                // （否则会把 queueFinished 之外的状态机再推一遍，产生重复的 PLAY_ENDED）。
                if (parkingAtEnd) {
                    wasPlayWhenReady = playWhenReady
                    return
                }
                // 意图的唯一权威来源（v2.4.8）：凡是 player.play()/pause() 引起的
                // playWhenReady 变化都会走到这里。缓冲不会改变它，所以图标不会再闪。
                syncIntentFromPlayer(c, playWhenReady)
                if (!playWhenReady && !AudioRuntimeState.snapshot.desiredPlaying) cancelPendingPlayback()

                // 队列已播完时用户要求播放（遥控器 OK / 通知栏 / 耳机线都走这里）：
                // 从队首重听，而不是把最后一首再放一遍。
                //
                // ⚠️ 只认「假 → 真」的真正边沿。播放完我们自己也把 playWhenReady 置成 false，
                // 若只看 `playWhenReady == true`，会和 onPlaybackEnded 里的赋值互相触发。
                if (playWhenReady == wasPlayWhenReady) return
                wasPlayWhenReady = playWhenReady
                if (playWhenReady && (queueFinished || endedParked)) {
                    queueFinished = false
                    endedParked = false
                    if (playMode == PlayMode.ORDER && queue.isNotEmpty()) {
                        DiagLog.log("PLAY_ENDED", "队列已播完后恢复播放 → 从队首重听")
                        queueIndex = 0
                        playCurrent()
                    }
                }
            }

            /**
             * ⚠️ 这里**不能**写 `playbackState.isPlaying`（v2.4.8）。
             *
             * ExoPlayer 的 `isPlaying` = 「正在播 且 没有缓冲」，缓冲一开始就是 false、
             * 缓冲完又 true。以前直接抄进 `isPlaying`，而按钮图标绑的正是它 ——
             * 于是弱网/切歌/seek 时每次缓冲，图标都「暂停↔播放」翻一次，
             * 用户看到的就是「播放一会儿 UI 不停播放暂停切换」。
             *
             * 现在 `isPlaying` 只表示**用户意图**，由 playWhenReady 维护；
             * 真实出声状态写到 `audible`，给确实需要它的地方用。
             */
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (playbackState.audible != isPlaying) {
                    playbackState = playbackState.copy(audible = isPlaying)
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                // ── 播放器走到 timeline 末尾（v2.4.7） ──
                //
                // 以前这里完全没有 STATE_ENDED 分支（全项目 grep 命中 0 次）。
                // 于是「补窗没补上」时播放器就静静停在末尾：进度条走满、按钮变回播放三角、
                // error=null、一行日志都没有 —— 用户看到的就是「听了几首歌定住不动了」。
                // 实测复现：state=STOPPED、position=230722/buffered=230713、诊断日志 0 条。
                if (state == Player.STATE_ENDED) {
                    onPlaybackEnded()
                    return
                }
                // 真的读出流了 = 这次恢复成功，连败计数清零
                if (state == Player.STATE_READY) {
                    consecutivePlayErrors = 0
                    unsupportedAudioSkips.reset()
                }
                // 切换音质会按新档位重取当前曲目，新流就绪后跳回原来的播放位置
                if (state == Player.STATE_READY && pendingSeekMs > 0L) {
                    val target = pendingSeekMs
                    pendingSeekMs = 0L
                    runCatching { c.seekTo(target) }
                }
                // 恢复流程走完：位置已落位，闸门放开，让进度条回到跟播放器走
                if (state == Player.STATE_READY && restoring) {
                    restoreWatchdog?.cancel()
                    restoreWatchdog = null
                    restoring = false
                }
            }
        })
    }

    // ---- 搜索 ----
    var searchKeyword by mutableStateOf("")
        private set
    var searchResults by mutableStateOf<List<Track>>(emptyList())
        private set
    var searching by mutableStateOf(false)
        private set
    var searchError by mutableStateOf<String?>(null)
        private set
    var searchHistory by mutableStateOf<List<String>>(emptyList())
        private set

    // ---- 播放 ----
    var playbackState by mutableStateOf(PlaybackState())
        private set
    private var _queue by mutableStateOf<List<Track>>(emptyList())

    /** 逻辑队列（播放器里的实际播放列表由 buildQueueAround 逐步铺出来） */
    var queue: List<Track>
        get() = _queue
        private set(value) {
            if (_queue != value) {
                queueEpoch++
                queueEpochUpdates.value = queueEpoch
                if (playJob?.isActive == true) {
                    playJob?.cancel()
                    playbackState = playbackState.copy(buffering = false, stalled = false)
                }
                com.kmusic.apkhd.playback.AudioPrecacheBridge.updateQueueEpoch(queueEpoch)
                queueBuildJob?.cancel()
            }
            _queue = value
            // 队列一变就落盘（防抖 400ms）。增删、排序、整体换歌单都会经过这里，
            // 集中一处比在每个调用点手写一行可靠得多。
            persistSession()
        }
    var queueIndex by mutableStateOf(-1)
        private set

    // ---- 发现页（首页推荐） ----
    /** 排行榜（含已拉取歌曲） */
    var charts by mutableStateOf<List<ChartSection>>(emptyList())
        private set
    var recommendedPlaylists by mutableStateOf<List<PlaylistBrief>>(emptyList())
        private set
    var topArtists by mutableStateOf<List<ArtistBrief>>(emptyList())
        private set
    var discoveryLoading by mutableStateOf(false)
    /** 推荐歌单「换一批」的独立忙碌态 */
    var playlistsRefreshing by mutableStateOf(false)
        private set
    /** 音乐人「换一批」的独立忙碌态 */
    var artistsRefreshing by mutableStateOf(false)
        private set
    /** 推荐歌单「下拉加载下一批」忙碌态（v2.4.5） */
    var playlistsLoadingMore by mutableStateOf(false)
        private set
    /** 音乐人「下拉加载下一批」忙碌态（v2.4.5） */
    var artistsLoadingMore by mutableStateOf(false)
        private set
    /** 推荐歌单是否已经拿不出新内容（服务端随机池见底 / 到达上限） */
    var playlistsExhausted by mutableStateOf(false)
        private set
    /** 音乐人是否已经到达累计上限 */
    var artistsExhausted by mutableStateOf(false)
        private set

    // ---- 子页面：榜单 / 歌单 / 歌手 ----
    var chartDetail by mutableStateOf<ChartSection?>(null)
        private set
    var chartDetailTracks by mutableStateOf<List<Track>>(emptyList())
        private set
    var chartCoverLoading by mutableStateOf(false)
        private set
    var playlistDetail by mutableStateOf<PlaylistBrief?>(null)
        private set
    var playlistTracks by mutableStateOf<List<Track>>(emptyList())

    /**
     * 歌单被源截断（声明 200 首只给 10 首）时为 true。
     *
     * 以前这种情况是**静默**的：列表页写「200 首」，点进去 10 首，用户以为 app 坏了。
     * 现在曲目列表底部会挂一行说明。
     */
    var playlistTruncated by mutableStateOf(false)
        private set
    var playlistLoading by mutableStateOf(false)
        private set
    var artistDetail by mutableStateOf<ArtistBrief?>(null)
        private set
    var artistTracks by mutableStateOf<List<Track>>(emptyList())
        private set
    var artistLoading by mutableStateOf(false)
        private set

    // ---- 歌词 ----
    var lyrics by mutableStateOf<List<LyricLine>?>(null)
        private set
    var loadingLyrics by mutableStateOf(false)

    /** 歌词加载序号，用来作废过期的协程（见 [loadLyrics]） */
    private var lyricLoadSeq = 0L

    /** 当前歌词是哪个源给的（kuwo / gdstudio / gdstudio_byname / lrclib），供「歌词不对」换源用 */
    private var lyricLastSource: String? = null

    /**
     * 歌词内存缓存（LRU，40 首）。
     *
     * 加它的原因：切回听过的歌时原来会重新走一遍完整取词链路（酷我 → gdstudio →
     * lrclib，最坏三个网络往返），表现就是「切歌后歌词要等一下才出来」。
     * 缓存命中后连「歌词加载中」都不会闪。
     *
     * 容量按 40 首估：每首约 50 行、每行几十字符，合计几百 KB，可以忽略。
     */
    private data class CachedLyric(val lines: List<LyricLine>, val source: String?)

    private val lyricCache = object : LinkedHashMap<String, CachedLyric>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedLyric>?) =
            size > 40
    }

    /** 这个曲目是否被用户否掉了某个源 */
    private fun isLyricSourceSkipped(t: Track, source: String): Boolean =
        lyricSkippedSources[t.id]?.contains(source) == true

    /** 已发起取词的曲目 id，避免同一首歌重复打接口 */
    private var lyricLoadedId: String? = null

    /**
     * 用户报告「歌词不对」后要跳过的源 —— **按曲目 id 隔离**。
     *
     * ⚠️ 这里踩过一次大坑：最初是 VM 级的一个全局 Set，用户否掉一首歌的 lrclib 之后，
     * 从此**所有歌**都跳过 lrclib，别人的歌词全没了。
     *
     * 所以必须按曲目存；而且每次新取词都清空，
     * 避免「否过一次，以后每次播放这首歌都少一个源」。
     */
    // ---- 音乐人刷新用的本地缓存（接口重复请求返回相同，只能翻页） ----
    private val artistCache = mutableListOf<ArtistBrief>()
    private var artistCursor = 0
    private var artistOffset = 0

    /**
     * 推荐歌单的去重集合（v2.4.5 下拉追加）。
     *
     * `personalized/playlist` 没有 offset，只能靠重复请求 + 去重累积。
     * 首屏 20 个进 [recommendedPlaylists]，之后每次追加都先过这个集合。
     */
    private val playlistSeen = mutableSetOf<String>()

    private val lyricSkippedSources = mutableMapOf<String, MutableSet<String>>()

    /** 当前曲目的否决集合是否有效（避免被「换歌清空」逻辑误清） */
    private var lyricSkippedForTrack: String? = null
    /** 歌词时间轴偏移（毫秒）。正值 = 歌词提前显示 */
    var lyricOffsetMs by mutableStateOf(LYRIC_OFFSET_DEFAULT_MS)
        private set

    fun nudgeLyricOffset(deltaMs: Long) {
        lyricOffsetMs = (lyricOffsetMs + deltaMs).coerceIn(-15_000L, 15_000L)
        viewModelScope.launch { settings.setLyricOffset(lyricOffsetMs) }
    }

    fun resetLyricOffset() {
        lyricOffsetMs = LYRIC_OFFSET_DEFAULT_MS
        viewModelScope.launch { settings.setLyricOffset(LYRIC_OFFSET_DEFAULT_MS) }
    }

    /** 歌词字号倍率。范围 0.8~1.8，与 SettingsStore 的 coerceIn 保持一致 */
    var lyricFontScale by mutableStateOf(1.0f)
        private set

    /** 逐档调节（不用滑块：滑块在遥控器/触屏上都要精细拖拽，档位一步到位） */
    fun nudgeLyricFontScale(delta: Float) {
        lyricFontScale = coerceLyricScale(lyricFontScale + delta)
        viewModelScope.launch { settings.setLyricFontScale(lyricFontScale) }
    }

    fun resetLyricFontScale() {
        lyricFontScale = 1.0f
        viewModelScope.launch { settings.setLyricFontScale(1.0f) }
    }

    /**
     * 当前高亮的歌词行。
     *
     * 以前是个 getter：每读一次就从第一行线性扫一遍；而 100ms 的进度轮询又让
     * 整个歌词列表每秒重组 10 次 —— 车机（1280×720、弱 SoC）上这是实打实的卡。
     * 现在由轮询算一次（二分），**行号真的变了才写状态**，列表只在换行时重组。
     */
    var currentLyricIndex by mutableStateOf(-1)
        private set

    /** 按当前进度算高亮行（歌词按时间升序，二分即可） */
    private fun computeLyricIndex(): Int {
        val list = lyrics ?: return -1
        if (list.isEmpty()) return -1
        val pos = playbackState.positionMs + lyricOffsetMs
        var lo = 0
        var hi = list.size - 1
        var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid].timeMs <= pos) {
                idx = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return idx
    }

    // ---- 媒体库 ----
    var localTracks by mutableStateOf<List<Track>>(emptyList())
        private set
    var localLoading by mutableStateOf(false)
        private set
    var recentTracks by mutableStateOf<List<Track>>(emptyList())
        private set
    var navidromeConnected by mutableStateOf(false)

    /**
     * 连接失败的原因（HTTP 码 / Subsonic 的报错文案 / 网络异常）。
     *
     * 之前这里没有它：`ping().isSuccess` 把原因**丢掉**，界面又靠 navidromeConnected
     * 整个隐藏 Navidrome 区块 —— 用户加完服务器看到的就是「区块没了」，
     * 完全不知道是地址错、账号错还是网络不通。
     */
    var navidromeError by mutableStateOf<String?>(null)
        private set
    var navidromeChecking by mutableStateOf(false)
        private set
    var navidromeAddress by mutableStateOf("")
        private set
    var navidromeUser by mutableStateOf("")
        private set
    var navidromePass by mutableStateOf("")
        private set
    var navidromeAlbums by mutableStateOf<List<AlbumInfo>>(emptyList())
        private set
    var navidromeArtists by mutableStateOf<List<ArtistInfo>>(emptyList())
        private set
    var navidromePlaylists by mutableStateOf<List<PlaylistInfo>>(emptyList())
        private set
    var navidromeTrackCount by mutableStateOf(0)
        private set

    // ── WebDAV ───────────────────────────────────────────────
    // 只做浏览 + 播放/下载；不建索引，目录即列表（车机上要的就是"点进去就能放"）
    var davUrl by mutableStateOf("")
        private set
    var davUser by mutableStateOf("")
        private set
    var davPass by mutableStateOf("")
        private set
    var davPath by mutableStateOf("")
        private set
    var davEntries by mutableStateOf<List<DavEntry>>(emptyList())
        private set
    var davLoading by mutableStateOf(false)
        private set
    var davError by mutableStateOf<String?>(null)
        private set
    var davChecking by mutableStateOf(false)
        private set
    var davTestResult by mutableStateOf<String?>(null)
        private set
    /** 诊断输出（列目录 + 逐跳 302），可复制发给开发者 */
    var davDiagText by mutableStateOf("")
        private set
    var davDiagRunning by mutableStateOf(false)
        private set
    /** 最近一次播放失败的原因：播放页看不到错误时，这里一定留一条 */
    var lastPlayError by mutableStateOf("")
        private set
    var davUa by mutableStateOf("")
        private set
    private var davClient: WebDavClient? = null

    val davConfigured: Boolean get() = davUrl.isNotBlank()

    /** 保存并立即生效（保存后自动连一次根目录） */
    fun saveDavConfig(url: String, user: String, pass: String) {
        viewModelScope.launch {
            settings.saveWebDav(url, user, pass)
            davTestResult = null
        }
    }

    fun testDav() {
        val c = davClient ?: run { davTestResult = "先保存地址"; return }
        davChecking = true
        davTestResult = null
        viewModelScope.launch {
            val r = c.test()
            davChecking = false
            davTestResult = r.getOrNull() ?: "失败：${r.exceptionOrNull()?.message}"
        }
    }

    fun updateDavUa(ua: String) {
        viewModelScope.launch { settings.saveDavUa(ua) }
    }

    fun davDiagnose() {
        val c = davClient ?: run { davDiagText = "先保存 WebDAV 地址"; return }
        if (davDiagRunning) return
        davDiagRunning = true
        davDiagText = ""
        viewModelScope.launch {
            davDiagText = runCatching { c.diagnose(davPath.ifBlank { davUrl }) }
                .getOrElse { "诊断异常：${it.javaClass.simpleName}: ${it.message}" }
            davDiagRunning = false
        }
    }

    fun davOpen(url: String) {
        val c = davClient ?: run { davError = "未配置 WebDAV"; return }
        davLoading = true
        davError = null
        viewModelScope.launch {
            val r = c.list(url)
            r.onSuccess { davEntries = it; davPath = url }
                .onFailure { davError = it.message ?: "加载失败" }
            davLoading = false
        }
    }

    fun davReload() { if (davPath.isNotEmpty()) davOpen(davPath) }

    /** 上一级；已在根目录则不动 */
    fun davUp() {
        val base = davClient?.base ?: return
        val cur = davPath.trimEnd('/')
        val root = base.trimEnd('/')
        if (cur.length <= root.length) return
        val parent = cur.substringBeforeLast('/', "")
        davOpen(if (parent.length < root.length) root else "$parent/")
    }

    fun davPlayAll() {
        val list = davTracksInCurrent()
        if (list.isEmpty()) { toast("当前目录没有音频文件"); return }
        play(list.first(), list)
    }

    fun davPlay(entry: DavEntry) {
        val list = davTracksInCurrent()
        val t = davTrack(entry)
        val queue = if (list.any { it.id == t.id }) list else listOf(t)
        play(t, queue)
    }

    fun davDownload(entry: DavEntry) {
        val c = davClient ?: return
        val t = davTrack(entry)
        if (DownloadCenter.has(t.id)) { toast("《${t.name}》已在下载"); return }
        DownloadCenter.prepare(t)
        DownloadCenter.enqueue(t, entry.url, null, "WebDAV", "", c.authHeader)
        toast("已加入下载：${t.name}")
    }

    fun davDownloadAll() {
        val list = davTracksInCurrent()
        if (list.isEmpty()) { toast("当前目录没有音频文件"); return }
        val c = davClient ?: return
        list.filter { !DownloadCenter.has(it.id) }.forEach {
            DownloadCenter.prepare(it)
            DownloadCenter.enqueue(it, it.playUrl.orEmpty(), null, "WebDAV", "", c.authHeader)
        }
        toast("已加入 ${list.size} 首到下载队列")
    }

    private fun davTracksInCurrent(): List<Track> =
        davEntries.filter { !it.isDir && it.isAudio }.map(::davTrack)

    private fun davTrack(e: DavEntry): Track {
        // 路径是 URL 编码的（%20 / %E6…），直接当专辑名会显示成一串百分号
        val folder = davPath.removePrefix(davUrl).trim('/')
            .let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
            .ifEmpty { "根目录" }
        // 去掉音轨号前缀（01. / 01 - / 01_ 这类）：留着会让歌词源搜不到（日志里一片 LYRIC_MISS）
        val noExt = e.name.substringBeforeLast('.', e.name)
        val clean = noExt.replace(Regex("^\\s*\\d{1,3}\\s*[.、_\\-]\\s*"), "").ifBlank { noExt }
        // 「歌手 - 歌名」才拆；「01 - 歌名」这种是音轨号，左边全是数字就别当歌手
        val dash = clean.indexOf(" - ")
        val left = if (dash > 0) clean.substring(0, dash).trim() else ""
        val looksLikeTrackNo = left.isEmpty() || left.all { it.isDigit() || it == '.' }
        val artist = if (dash > 0 && !looksLikeTrackNo) left else "WebDAV"
        val title = if (dash > 0) clean.substring(dash + 3).trim() else clean
        return Track(
            id = "dav:" + e.url.hashCode().toUInt().toString(16),
            name = title,
            artist = artist,
            album = folder,
            source = "webdav",
            sourceId = e.url,
            playUrl = e.url,
            qualityLabel = e.name.substringAfterLast('.', "").uppercase().ifEmpty { "WebDAV" }
        )
    }


    fun onKeywordChange(v: String) { searchKeyword = v }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settings.setThemeMode(mode) }
    }

    /** 马卡龙主题色：存枚举名，"DEFAULT" = 原生强调色 */
    fun setAccentPreset(name: String) {
        viewModelScope.launch { settings.setAccentPreset(name) }
    }

    fun setAccentAlpha(v: Float) {
        viewModelScope.launch { settings.setAccentAlpha(v) }
    }

    fun setSafeBottomDp(v: Int) {
        viewModelScope.launch { settings.setSafeBottomDp(v) }
    }

    fun setFrostLevel(v: String) {
        viewModelScope.launch { settings.setFrostLevel(v) }
    }

    /** 深色风格：藏蓝 / 纯黑 */
    var darkStyle by mutableStateOf("NAVY")
        private set

    fun updateDarkStyle(v: String) {
        viewModelScope.launch { settings.setDarkStyle(v) }
    }

    /**
     * 聚合搜索：本地 + Navidrome + 在线三源（酷我 / 网易云 / 咪咕）并发。
     *
     * 排序刻意是「**能离线播的排前面**」：本地 > 自建曲库 > 在线。
     * 每首歌带 source，UI 上标注来源，也让播放时知道该走原生还是跨源回退。
     */
    fun search(keyword: String? = null) {
        val kw = (keyword ?: searchKeyword).trim()
        if (kw.isEmpty()) return
        searchKeyword = kw
        searching = true
        searchError = null
        viewModelScope.launch {
            try {
                // 每个页签走**各自的接口**、填各自的列表。
                // 之前这排页签只换高亮色，选什么都返回同一份歌曲列表 —— 那是假的。
                when (searchTab) {
                    SearchTab.ARTIST -> {
                        artistResults = runCatching { client.searchArtists(kw) }
                            .getOrDefault(emptyList())
                        if (artistResults.isEmpty()) searchError = "没有找到相关歌手"
                        else settings.addSearchHistory(kw)
                        return@launch
                    }
                    SearchTab.ALBUM -> {
                        albumResults = runCatching { client.searchAlbums(kw) }
                            .getOrDefault(emptyList())
                        if (albumResults.isEmpty()) searchError = "没有找到相关专辑"
                        else settings.addSearchHistory(kw)
                        return@launch
                    }
                    SearchTab.PLAYLIST -> {
                        playlistResults = runCatching { client.searchPlaylists(kw) }
                            .getOrDefault(emptyList())
                        if (playlistResults.isEmpty()) searchError = "没有找到相关歌单"
                        else settings.addSearchHistory(kw)
                        return@launch
                    }
                    SearchTab.SONG -> Unit
                }
                val kwLower = kw.lowercase()
                // 单源超时：任何一个源卡住都不能拖死整个首屏。
                // 以前是裸 await，坏一个源就是 40 秒转圈；现在最坏 8 秒必出结果。
                val perSourceTimeout = 8_000L
                val localHit = localTracks.filter {
                    it.name.lowercase().contains(kwLower) ||
                        it.artist.lowercase().contains(kwLower) ||
                        it.album.lowercase().contains(kwLower)
                }
                val ndJob = async {
                    runCatching {
                        withTimeout(perSourceTimeout) {
                            navidrome?.searchSongs(kw) ?: emptyList()
                        }
                    }.getOrDefault(emptyList())
                }
                val kwJob = async {
                    runCatching {
                        withTimeout(perSourceTimeout) {
                            client.search(kw, size = 30).map { it.toTrack() }
                        }
                    }.getOrDefault(emptyList())
                }
                // 网易云（gdstudio）：酷我没有的内容靠它补，播放时走母带优先/直链兜底
                val neJob = async {
                    runCatching {
                        withTimeout(perSourceTimeout) {
                            // gd 的 types=search 在新域名上是 401，先试直连再回退 gd
                            netease.search(kw, limit = 15).ifEmpty { gd.search(kw, count = 15) }
                        }
                    }.getOrDefault(emptyList())
                }
                // 咪咕：官方接口、免登录 128k，用来补前两个源都没有的曲目
                val mgJob = async {
                    runCatching {
                        withTimeout(perSourceTimeout) { migu.search(kw, count = 15) }
                    }.getOrDefault(emptyList())
                }
                val bySource = mapOf(
                    SourceCatalog.LOCAL to localHit,
                    SourceCatalog.NAVIDROME to ndJob.await(),
                    SourceCatalog.KUWO to kwJob.await(),
                    SourceCatalog.NETEASE to neJob.await(),
                    SourceCatalog.MIGU to mgJob.await()
                )
                // 按音源优先级拼接，再去重：同一首歌三个源都命中时只留优先级最高的那条，
                // 否则《晴天》会在列表里出现三次，用户以为搜到了三个版本。
                val merged = dedupeTracks(
                    SourceCatalog.ALL.flatMap { bySource[it.id].orEmpty() }
                )
                searchResults = merged
                if (bySource[SourceCatalog.NETEASE].orEmpty().isNotEmpty()) {
                    // 网易云的封面要单独取，列出来之后再补，不阻塞首屏
                    enrichNeteaseCovers(bySource[SourceCatalog.NETEASE].orEmpty())
                }
                if (merged.isEmpty()) searchError = "没有找到相关歌曲"
                else settings.addSearchHistory(kw)
            } catch (e: Exception) {
                searchError = "搜索失败: ${e.message ?: "网络错误"}"
            } finally {
                searching = false
            }
        }
    }

    /**
     * 同曲去重：歌名 + 主歌手归一化后相同，且时长相差 ≤5 秒，视为同一首。
     *
     * 为什么带时长：同名不同版本（Live / 伴奏 / 重制）必须保留，
     * 只按歌名去重会把它们吞掉。5 秒是实测各家元数据的常见偏差上限。
     */
    private fun dedupeTracks(list: List<Track>): List<Track> {
        fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        val kept = ArrayList<Track>(list.size)
        val seen = HashMap<String, MutableList<Long>>()
        for (t in list) {
            val key = norm(t.name) + "|" + norm(t.artist.substringBefore('/'))
            val durs = seen.getOrPut(key) { mutableListOf() }
            val dup = durs.any { d ->
                t.durationMs > 0 && d > 0 && kotlin.math.abs(d - t.durationMs) <= 5_000
            }
            if (dup) continue
            durs.add(t.durationMs)
            kept.add(t)
        }
        return kept
    }

    /**
     * 给网易云搜索结果补封面。
     *
     * gdstudio 的搜索只返回 pic_id，真实封面 URL 带 hash 无法推导（实测拼出来的全 404），
     * 必须再调 types=pic。这里限并发 4、最多补 12 张，失败就保持占位不报错。
     */
    private fun enrichNeteaseCovers(tracks: List<Track>) {
        val targets = tracks.take(12)
        if (targets.isEmpty()) return
        viewModelScope.launch {
            targets.chunked(4).forEach { chunk ->
                val jobs = chunk.map { t ->
                    async { t.id to runCatching { gd.pic(t.sourceId) }.getOrNull() }
                }
                val got = jobs.mapNotNull { j ->
                    val (id, url) = j.await()
                    if (url.isNullOrEmpty()) null else id to url
                }.toMap()
                if (got.isNotEmpty()) {
                    searchResults = searchResults.map { r ->
                        got[r.id]?.let { r.copy(coverUrl = it) } ?: r
                    }
                }
            }
        }
    }

    fun removeSearchHistory(k: String) {
        viewModelScope.launch { settings.removeSearchHistory(k) }
    }

    fun clearSearchHistory() {
        viewModelScope.launch { settings.clearSearchHistory() }
    }

    // ---------------- 发现页（聚合多个源） ----------------

    /**
     * 首页推荐位。
     *
     * 聚合策略：**能播的优先**
     * - 排行榜走酷我（条目自带 rid，点了必定能播）
     * - 推荐歌单/歌手走网易云（元数据质量高），点进去播时按严格三重校验回退到酷我
     * 三路并发，任一路失败不影响其它两路。
     */
    fun loadDiscovery(force: Boolean = false) {
        if (discoveryLoading) return
        if (!force && (charts.isNotEmpty() || recommendedPlaylists.isNotEmpty())) return
        discoveryLoading = true
        viewModelScope.launch {
            try {
                val chartJob = async { client.chart(KuwoClient.BANG_HOT, rn = 20) }
                val risingJob = async { client.chart(KuwoClient.BANG_RISING, rn = 20) }
                val newJob = async { client.chart(KuwoClient.BANG_NEW, rn = 20) }
                // v2.4.5：首屏各 20 个，之后可以横向滚到底继续追加
                val plJob = async { netease.recommendPlaylists(ARTIST_BATCH) }
                val arJob = async { netease.topArtists(ARTIST_BATCH) }

                val hot = runCatching { chartJob.await() }.getOrNull()
                val rising = runCatching { risingJob.await() }.getOrNull()
                val fresh = runCatching { newJob.await() }.getOrNull()
                charts = listOfNotNull(hot, rising, fresh).filter { it.tracks.isNotEmpty() }
                val pls = runCatching { plJob.await() }.getOrDefault(emptyList())
                recommendedPlaylists = pls
                // 首屏的歌单要进去重集合，否则下拉第一轮会把它们再取回来一遍
                playlistSeen.clear()
                playlistSeen += pls.map { it.id }
                playlistsExhausted = false
                val artists = runCatching { arJob.await() }.getOrDefault(emptyList())
                topArtists = artists
                artistsExhausted = false
                // 首屏就把音乐人灌进本地缓存：否则「换一批」的 cursor 没有基准，
                // 第一次点击只会去请求同一页，看起来毫无变化。
                if (artistCache.isEmpty() && artists.isNotEmpty()) {
                    artistCache.addAll(artists)
                    artistOffset = artists.size
                }
            } finally {
                discoveryLoading = false
            }
        }
    }

    /**
     * 刷新「推荐歌单」和「音乐人」两个区块，换一批内容。
     *
     * 两个源的换批方式**不一样**，这是实测出来的：
     * - 推荐歌单：`/personalized/playlist` 每次请求都返回不同内容
     *   （连调 4 次首个全不一样），所以直接重调即可。
     * - 音乐人：`/artist/list` 重复请求返回**完全相同**（连调 4 次零变化），
     *   必须靠 offset 翻页。一次多拿 60 个缓存着，本地按 12 个一批往下切，
     *   切完再往后翻页，避免用户连点时每次都在请求。
     *
     * 刷新失败时保留旧内容，不清空 —— 否则网络一抖首页就少两块。
     */
    /** 只刷新「推荐歌单」——接口本身每次返回不同内容，直接重调即可 */
    fun refreshPlaylists() {
        if (playlistsRefreshing) return
        playlistsRefreshing = true
        viewModelScope.launch {
            try {
                runCatching { netease.recommendPlaylists(ARTIST_BATCH) }
                    .getOrNull()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let {
                        recommendedPlaylists = it
                        // 「换一批」= 重新洗牌：去重集合跟着换，否则下拉会一直撞旧内容
                        playlistSeen.clear()
                        playlistSeen += it.map { p -> p.id }
                        playlistsExhausted = false
                        DiagLog.log("HOME_MORE", "换一批(歌单) → ${it.size} 张，去重集重置")
                    }
            } finally {
                playlistsRefreshing = false
            }
        }
    }

    /**
     * 推荐歌单「横向滚到底，自动加载下一批」（v2.4.5）。
     *
     * `personalized/playlist` 没有 offset 参数，每次请求都是服务端随机挑一批，
     * 所以只能**发请求 → 去重 → 追加**。实测第 2 次能新增 19/20、第 3 次新增 12/20，
     * 之后越来越少 —— 连续 [PLAYLIST_EMPTY_ROUNDS] 轮拿不到新内容就标记
     * [playlistsExhausted]，UI 停止再触发，避免用户滑到底一直空转请求。
     *
     * 一次调用**最多发 2 轮请求**：单轮新内容太少（随机池重叠）时再试一次，
     * 免得用户每次滑到底只多出两三个卡片。
     */
    fun loadMorePlaylists() {
        if (playlistsLoadingMore || playlistsRefreshing) return
        if (playlistsExhausted) return
        if (recommendedPlaylists.size >= PLAYLIST_MAX) {
            playlistsExhausted = true
            return
        }
        playlistsLoadingMore = true
        DiagLog.log("HOME_MORE", "歌单追加开始，当前 ${recommendedPlaylists.size}")
        viewModelScope.launch {
            try {
                var emptyStreak = 0
                var added = 0
                var round = 0
                while (round < 2 && emptyStreak < PLAYLIST_EMPTY_ROUNDS) {
                    round++
                    val batch = runCatching { netease.recommendPlaylists(ARTIST_BATCH) }
                        .getOrDefault(emptyList())
                    if (batch.isEmpty()) break
                    val fresh = batch.filter { playlistSeen.add(it.id) }
                    if (fresh.isEmpty()) {
                        emptyStreak++
                        continue
                    }
                    emptyStreak = 0
                    added += fresh.size
                    recommendedPlaylists = recommendedPlaylists + fresh
                    if (recommendedPlaylists.size >= PLAYLIST_MAX) break
                }
                if (added == 0 && emptyStreak >= PLAYLIST_EMPTY_ROUNDS) {
                    playlistsExhausted = true
                }
                if (recommendedPlaylists.size >= PLAYLIST_MAX) playlistsExhausted = true
                DiagLog.log(
                    "HOME_MORE",
                    "歌单追加结束 +$added → ${recommendedPlaylists.size}，exhausted=$playlistsExhausted"
                )
            } finally {
                playlistsLoadingMore = false
            }
        }
    }

    /**
     * 只刷新「音乐人」——本地缓存翻批，翻到底再请求下一页。
     *
     * ⚠️ 顺序不能反：必须先尝试「cursor 前进」，前进后凑不够一批才去翻页。
     * 早期写成 `needFetch = cursor + BATCH > cache.size` 前置判断，
     * 在 cache=60 / cursor=48 时判为 false → cursor 变成 60 → drop(60) 空 →
     * ifEmpty 回退到第一批 → 用户会反复看到同一批，永远翻不到后面的。
     */
    fun refreshArtists() {
        if (artistsRefreshing) return
        artistsRefreshing = true
        viewModelScope.launch {
            try {
                var advanced = false
                if (artistCache.size > artistCursor + ARTIST_BATCH) {
                    artistCursor += ARTIST_BATCH
                    advanced = true
                }
                if (!advanced) {
                    // 本地缓存见底，请求下一页
                    runCatching { netease.topArtists(ARTIST_PAGE, offset = artistOffset) }
                        .getOrNull()
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { fresh ->
                            // 过滤掉已在缓存里的：服务端翻页边界可能重叠
                            val known = artistCache.map { it.id }.toSet()
                            val added = fresh.filter { it.id !in known }
                            if (added.isNotEmpty()) {
                                artistCache += added
                                artistOffset += added.size
                                // ⚠️ 关键：新数据追加后把 cursor 推到新数据的起点。
                                // 否则（尤其是首屏刚加载、cache 还是空的时候）用户点「换一批」
                                // 只是把缓存填上，屏幕上仍然是第一批 —— 看起来像「没反应」。
                                artistCursor += ARTIST_BATCH
                            } else {
                                // 服务端也翻到底了 → 回到缓存开头
                                artistOffset = 0
                                artistCursor = 0
                            }
                        }
                        ?: run {
                            // 请求失败：缓存还有剩就退回开头，别停在凑不齐的中间态
                            if (artistCursor >= artistCache.size) artistCursor = 0
                        }
                }
                if (artistCache.isNotEmpty()) {
                    topArtists = artistCache.drop(artistCursor).take(ARTIST_BATCH)
                }
            } finally {
                artistsRefreshing = false
            }
        }
    }

    /**
     * 音乐人「横向滚到底，自动加载下一批」（v2.4.5）。
     *
     * 与歌单不同，`artist/list` 的 offset 是**稳定**的（实测 0/20/40/60 四批人完全不同），
     * 所以这里老老实实按 offset 往后翻，不依赖随机去重。
     *
     * 复用 [artistCache]：`refreshArtists` 的「换一批」也读它，两条路径共享同一份数据，
     * 避免一边追加、一边换批时互相打架。追加只往 **列表尾部** 加，不动 cursor。
     *
     * 到 [ARTIST_MAX] 就标记 [artistsExhausted]，UI 停止再触发。
     */
    fun loadMoreArtists() {
        if (artistsLoadingMore || artistsRefreshing) return
        if (artistsExhausted) return
        if (topArtists.size >= ARTIST_MAX) {
            artistsExhausted = true
            return
        }
        artistsLoadingMore = true
        viewModelScope.launch {
            try {
                // 本地缓存里还有没展示过的人，先补本地 —— 省一次请求
                val known = artistCache.map { it.id }.toSet()
                val notShown = artistCache.filter { a -> topArtists.none { it.id == a.id } }
                if (notShown.isNotEmpty()) {
                    val room = ARTIST_MAX - topArtists.size
                    topArtists = topArtists + notShown.take(room)
                    if (topArtists.size >= ARTIST_MAX) artistsExhausted = true
                    return@launch
                }
                val fresh = runCatching { netease.topArtists(ARTIST_BATCH, offset = artistOffset) }
                    .getOrDefault(emptyList())
                // 服务端翻页边界可能重叠，去掉已在缓存里的
                val added = fresh.filter { it.id !in known }
                if (added.isEmpty()) {
                    artistsExhausted = true
                    return@launch
                }
                artistCache += added
                artistOffset += fresh.size
                val room = ARTIST_MAX - topArtists.size
                topArtists = topArtists + added.take(room)
                if (topArtists.size >= ARTIST_MAX) artistsExhausted = true
                DiagLog.log(
                    "HOME_MORE",
                    "音乐人追加 +${added.size} → ${topArtists.size}，offset=$artistOffset exhausted=$artistsExhausted"
                )
            } finally {
                artistsLoadingMore = false
            }
        }
    }

    // ---------------- 今日推荐 ----------------

    /** 今日推荐。同一天内固定，隔天自动换一批 */
    var dailyMix by mutableStateOf<List<Track>>(emptyList())
        private set
    var dailyLoading by mutableStateOf(false)
        private set

    /**
     * 拉取今日推荐。
     *
     * 池子来自两个**真实榜单**（酷我热歌榜 50 + 网易云推荐新歌 30），
     * 不是随机糊的；「今日」只体现在挑选的种子上（见 [DailyMix]）。
     * 同一天进来多少次都是同一批 —— 每次刷新都换的话这功能就是假的。
     */
    fun loadDailyMix(force: Boolean = false) {
        if (dailyLoading) return
        if (!force && dailyMix.isNotEmpty()) return
        dailyLoading = true
        viewModelScope.launch {
            try {
                val hotJob = async { client.chart(KuwoClient.BANG_HOT, rn = 50) }
                val newJob = async { netease.newSongs(30) }
                val hot = runCatching { hotJob.await() }.getOrNull()?.tracks ?: emptyList()
                val fresh = runCatching { newJob.await() }.getOrDefault(emptyList())
                dailyMix = DailyMix.build(hot + fresh, DailyMix.todaySeed())
            } catch (_: Exception) {
                dailyMix = emptyList()
            } finally {
                dailyLoading = false
            }
        }
    }

    /** 「今日推荐」标题里的日期，如 10月17日 */
    fun dailyMixDateLabel(): String {
        val d = java.time.LocalDate.now()
        return "${d.monthValue}月${d.dayOfMonth}日"
    }

    /**
     * 打开榜单。榜单接口不返回封面，这里并发补 ——
     * 用 rid 严格相等匹配搜索结果取封面，最多补 30 首、并发 4。
     */
    fun openChart(sec: ChartSection) {
        chartDetail = sec
        chartDetailTracks = sec.tracks
        // 全部已有封面才跳过；一首都没有时必须去补
        // （这里之前写反了：`none { 有封面 }` 为真时返回 → 榜单歌曲永远补不到封面）
        if (sec.tracks.isNotEmpty() && sec.tracks.all { !it.coverUrl.isNullOrEmpty() }) return
        chartCoverLoading = true
        viewModelScope.launch {
            val targets = sec.tracks.filter { it.coverUrl.isNullOrEmpty() }.take(30)
            targets.chunked(4).forEach { chunk ->
                val got = chunk.map { t ->
                    async { t.id to runCatching { client.coverByRid(t.sourceId, t.name, t.artist) }.getOrNull() }
                }.mapNotNull { j ->
                    val (id, url) = j.await()
                    if (url.isNullOrEmpty()) null else id to url
                }.toMap()
                if (got.isNotEmpty()) {
                    chartDetailTracks = chartDetailTracks.map { r ->
                        got[r.id]?.let { r.copy(coverUrl = it) } ?: r
                    }
                }
            }
            chartCoverLoading = false
        }
    }

    fun closeChart() {
        chartDetail = null
        chartDetailTracks = emptyList()
    }

    /** 打开歌单：酷我歌单走 pl.svc（全可播），网易云歌单走 playlist/detail（播放时回退） */
    fun openPlaylist(p: PlaylistBrief) {
        playlistDetail = p
        playlistLoading = true
        playlistTracks = emptyList()
        playlistTruncated = false
        viewModelScope.launch {
            // 三级取数。顺序是有实测依据的，别随手调：
            //   ① gdstudio  → 一次给全（1000 首以内），最快
            //   ② 网易云直连 → v6 的 trackIds + song/detail 分批，无上限
            //   ③ 官方 detail → 只给 10 首，**仅作最后兜底**，且要告诉用户
            var loaded = emptyList<Track>()
            var truncatedFrom = 0   // 声明数量；非 0 且 loaded 更少 = 源截断了
            if (p.source == "netease") {
                loaded = runCatching { gd.playlistTracks(p.id) }.getOrDefault(emptyList())
                if (loaded.isEmpty()) {
                    DiagLog.log("PLAYLIST_FETCH", "『${p.name}』gdstudio 空，改走网易云直连")
                    loaded = runCatching { netease.fullPlaylistTracks(p.id) }
                        .onFailure { DiagLog.log("PLAYLIST_FETCH", "直连失败：${it.message}") }
                        .getOrDefault(emptyList())
                }
                if (loaded.isEmpty()) {
                    DiagLog.log("PLAYLIST_FETCH", "『${p.name}』直连也空，退回官方 detail（只有 10 首）")
                    loaded = runCatching { netease.playlistTracks(p.id) }.getOrDefault(emptyList())
                    truncatedFrom = p.trackCount
                }
            } else {
                loaded = runCatching { client.playlistSongs(p.id) }.getOrDefault(emptyList())
            }
            playlistTracks = loaded
            // 只在「确实拿到东西、但明显少于声明」时提示；0 首走原来的空态文案
            playlistTruncated = truncatedFrom > 0 && loaded.isNotEmpty() && loaded.size < truncatedFrom

            // 把**实际取到**的数量回写到所有同名歌单 brief 上。
            //
            // ⚠️ 为什么必须回写：网易云 /playlist/detail 硬截断到 10 首
            // （实测：声明 367 首的歌单，接口只回 10 首，trackIds 也是空的，
            //  加 n=/s=/limit 参数全部无效）。酷我 rn 上限 100。
            // 所以 brief 上的 trackCount 是服务端元数据，不是我们能播的数量。
            // 不回写的话，列表页会一直显示「367 首」，点进去只有 10 首 —— 对用户是欺骗。
            if (loaded.isNotEmpty() && loaded.size != p.trackCount) {
                val real = loaded.size
                recommendedPlaylists = recommendedPlaylists.map {
                    if (it.id == p.id) it.copy(trackCount = real) else it
                }
                playlistResults = playlistResults.map {
                    if (it.id == p.id) it.copy(trackCount = real) else it
                }
                playlistDetail = p.copy(trackCount = real)
                // 收藏列表里也要跟着改，否则「我的歌单」永远显示服务端那个假数字
                favoritePlaylists = favoritePlaylists.map {
                    if (playlistKey(it) == playlistKey(p)) it.copy(trackCount = real) else it
                }
                if (isPlaylistFavorite(p)) {
                    runCatching { playlistFavStore.updateCount(p.source, p.id, real) }
                }
                DiagLog.log(
                    "PLAYLIST_COUNT",
                    "歌单『${p.name}』声明 ${p.trackCount} 首，实际取到 $real 首，已回写列表计数"
                )
            }
            playlistLoading = false
        }
    }

    fun closePlaylist() {
        playlistDetail = null
        playlistTracks = emptyList()
        playlistTruncated = false
    }

    /** 打开歌手：酷我没有"歌手歌曲"接口，用歌手名走搜索（结果同样带 rid 可直接播） */
    fun openArtist(a: ArtistBrief) {
        artistDetail = a
        artistLoading = true
        artistTracks = emptyList()
        viewModelScope.launch {
            artistTracks = runCatching {
                client.search(a.name, size = 50)
                    .filter { it.artist?.contains(a.name) == true || a.name.contains(it.artist.orEmpty()) }
                    .map { it.toTrack() }
                    .ifEmpty { client.search(a.name, size = 50).map { it.toTrack() } }
            }.getOrDefault(emptyList())
            artistLoading = false
        }
    }

    fun closeArtist() {
        artistDetail = null
        artistTracks = emptyList()
    }

    // ---------------- 媒体库 ----------------

    /**
     * SAF 目录读不到时的退避重试间隔（毫秒）。
     *
     * 为什么需要：冷启动时 DocumentsProvider 常常还没就绪，第一次 `listFiles()`
     * 会返回空数组而不是抛异常。不重试的话就会把「还没准备好」当成「目录是空的」，
     * 曲库直接显示 0 首。间隔取 0 / 700 / 1800ms —— 覆盖绝大多数 provider 冷启耗时，
     * 又不会让启动明显变慢（最多多等 2.5s，且只在真的读不到时才等）。
     */
    private val RETRY_DELAYS_MS = longArrayOf(0L, 400L)

    /**
     * 首屏扫描结束后，补扫「读不到」的 SAF 目录的时间表（毫秒，从扫描完成算起）。
     *
     * 递增间隔的道理：provider 未就绪是**很快就会好**的（多数 < 3s），
     * 但也可能是慢启动（刚开机、外置慢卡、云盘要联网）—— 那种往往要十几秒。
     * 头 2.5s 内补上就是最佳体验；拖到 30s+ 才好的，用户其实已经看过旧缓存列表了，
     * 只要别永久卡死就行，所以最后一轮放宽到 20s。
     */
    private val BACKGROUND_RETRY_PLAN = longArrayOf(2_500L, 8_000L, 20_000L)

    /**
     * 某棵 SAF 目录「读到 0 项」持续多久后，认定里面的歌**真的被删光了**，放弃旧缓存。
     *
     * 为什么要这个：`listFiles()` 返回空既可能是 provider 未就绪（要保住旧数据），
     * 也可能是用户真的把目录清空了（要跟着清）。两者只能靠**持续时间**区分 ——
     * provider 未就绪是瞬时的（几秒内必然恢复），真删了则一直空。
     * 用轮数（今天开 3 次就放弃）会误判「一天只开一次」的用户，所以按时间算。
     * 60s 足够覆盖任何 provider 冷启，也不会让已删的歌在列表里赖太久。
     */
    private val SAF_EMPTY_GIVE_UP_MS = 60_000L

    /** treeUri → 从什么时候开始「读到 0 项」（走缓存落盘，跨进程有效；见 ScanCacheData.emptySince） */
    private val safEmptySince = HashMap<String, Long>()

    fun hasAudioPermission(): Boolean = localLib.hasAudioPermission()

    fun scanLocal() {
        // ⚠️ **无权限时绝不能 `localTracks = emptyList()` 后 return**（v2.2.4 修）：
        // READ_MEDIA_AUDIO 只管 MediaStore，**手动添加的 SAF 目录根本不依赖它** ——
        // 用户可能正是靠在「扫描设置」里加文件夹才用起来的。
        // 旧代码在这里直接清空列表，于是「权限被系统回收 / 用户后来关掉」时，
        // 表现和「曲库真空」一模一样：本地音乐 0 首、已扫描。
        // 现在把权限判断交给 LocalLibrary.scan()（它只跳过 MediaStore 那一段），
        // SAF 照扫不误；同时留一条日志，省得下次又在「0 首」上瞎猜。
        if (!localLib.hasAudioPermission()) {
            DiagLog.log("LOCAL_SCAN", "缺少读取媒体权限：跳过 MediaStore，仅扫描手动添加的 SAF 目录")
        }
        rescanLocal()
    }

    /**
     * 按当前设置重扫本地库 = MediaStore（按勾选的目录过滤）+ 用户手动添加的目录。
     *
     * 手动添加的目录要递归遍历 + 逐个读标签，比较慢，所以整个放 IO 线程，
     * UI 靠 localLoading 显示「扫描中…」。
     */
    fun rescanLocal() {
        if (localLoading) return
        localLoading = true
        viewModelScope.launch {
            try {
                val enabled = localScanStore.enabledFolders.first()
                val trees = localScanStore.safTrees.first()
                enabledLocalFolders = enabled

                // ① 先吃缓存 + 一次 MediaStore 查询，列表立刻有内容。
                //    MediaStore 有系统索引，几十毫秒就回来；真正慢的是 SAF 逐文件读标签。
                //    用户不用盯着「扫描中…」等几十秒，甚至可以直接点歌播放 ——
                //    SAF 曲目的 sourceId 就是 content:// 直链，不依赖扫描结果是否最新。
                val cache = withContext(Dispatchers.IO) { localScanCache.load() }
                // 跨进程恢复「从什么时候开始读不到」——只放内存的话每次冷启归零，
                // 60s 阈值永远到不了，已删的歌就永远清不掉。
                safEmptySince.clear()
                safEmptySince.putAll(cache.emptySince)
                val ms = withContext(Dispatchers.IO) { localLib.scan(enabled) }
                val cachedSaf = cache.trees.filterKeys { it in trees }
                    .values.flatten().map { it.track }
                if (cachedSaf.isNotEmpty() || ms.isNotEmpty()) {
                    localTracks = (ms + cachedSaf)
                        .distinctBy { it.id }.sortedBy { it.name }
                }

                // ② 后台增量校验：只对指纹变过的文件重读标签。
                //
                // ⚠️ **失败/读不到必须保留旧数据**（v2.2.4 修的真 bug）：
                // 之前这里是 `trees.flatMap { ... }.getOrNull() ?: emptyList()` +
                // `localTracks = (ms + saf)` + 无条件 `save(newTreeEntries)`。
                // 只要某棵树这次读不到（目录改名、SD 卡重挂、刚开机 SAF 授权没就绪），
                // saf 就是空 → 列表被覆盖成 0 首，**缓存也被写成空表** →
                // 下次连兜底都没有，用户看到的就是「永久 0 首，必须点重扫」。
                var reused = 0
                var parsed = 0
                var failed = 0
                var retried = 0
                val newTreeEntries = HashMap<String, List<CachedSafEntry>>()
                // 本次读不到的树，交给后台稍后重试（不阻塞首屏）
                val pendingRetry = mutableListOf<String>()
                val saf = withContext(Dispatchers.IO) {
                    trees.flatMap { t ->
                        val prev = cache.trees[t]
                        // ⚠️ **读不到要重试**，不能一次失败就认命。
                        // 冷启动时 SAF 的 DocumentsProvider 往往还没就绪
                        // （尤其开机后第一次进 App、SD 卡刚挂载），
                        // 头一次 listFiles() 会拿到空数组而不是抛异常 →
                        // 上一版直接判「目录是空的」→ 曲库 0 首。
                        // 用户看到的就是「每次打开都 0 首，点进去重扫才出来」。
                        //
                        // 但**不能同步重试太多次**：正常机器第一次就成功（delay 0），
                        // 只有真的读不到才需要等；同步等 2.5s 会拖慢每一次冷启动。
                        // 所以这里同步只试 2 次（0 / 400ms，覆盖绝大多数 provider 冷启），
                        // 仍失败就交给下面的后台任务慢慢补，首屏先用旧缓存顶上。
                        var r: LocalLibrary.TreeScanResult? = null
                        for ((attempt, waitMs) in RETRY_DELAYS_MS.withIndex()) {
                            if (waitMs > 0) delay(waitMs)
                            val cur = runCatching {
                                localLib.scanTreeIncremental(t, prev?.associateBy { e -> e.docId })
                            }.getOrNull()
                            r = cur
                            if (cur != null && cur.ok) {
                                if (attempt > 0) retried++
                                break
                            }
                        }

                        if (r != null && r.ok) {
                            reused += r.reused
                            parsed += r.parsed
                            newTreeEntries[t] = r.entries.values.toList()
                            safEmptySince.remove(t)       // 读成功 → 清零「连续空」计时
                            r.tracks
                        } else {
                            // 读不到 → 先用旧缓存顶上
                            failed++
                            // 用**时间**判定「真删了」还是「provider 还没就绪」：
                            // provider 未就绪是瞬时的（几秒内恢复），真删则一直空。
                            // 用轮数判定会让「一天只开一次 App」的用户永远等不到阈值。
                            //
                            // ⚠️ **没有旧缓存时绝不能直接放弃**：那正是「第一次装 App /
                            // 换机 / 缓存被清」的场景，用户就是等着这次扫描出歌。
                            // 旧代码 `prev.isNullOrEmpty() -> giveUp` 会让这种首次扫描
                            // 一失败就把空结果写进缓存 → 又变成「永远 0 首」。
                            // 没缓存时一律先按「读不到」处理并重试，只有**根目录确实存在
                            // 且有子项**那种「真空」才由 scanTreeIncremental 返回 ok=true。
                            val since = safEmptySince.getOrPut(t) { System.currentTimeMillis() }
                            val emptyFor = System.currentTimeMillis() - since
                            val giveUp = !prev.isNullOrEmpty() && emptyFor >= SAF_EMPTY_GIVE_UP_MS
                            DiagLog.log(
                                "LOCAL_SCAN",
                                "SAF 目录读不到（先用旧缓存）：${safDisplayName(t)}" +
                                    "，已空 ${emptyFor / 1000}s" +
                                    (if (giveUp) " → 认定已清空" else "，稍后补扫")
                            )
                            if (giveUp) {
                                // 连续空了足够久 → 大概率是真删了，跟着清缓存，
                                // 否则用户会看到一堆点不开的幽灵歌。
                                safEmptySince.remove(t)
                                newTreeEntries[t] = emptyList()
                                emptyList()
                            } else {
                                if (prev != null) newTreeEntries[t] = prev
                                pendingRetry += t
                                prev?.map { it.track } ?: emptyList()
                            }
                        }
                    }
                }
                // ③ 只有「读到歌」或「本来就有缓存」时才覆盖列表 ——
                //    全空且失败的情况下保留现有列表，别把用户曲库清成 0 首。
                val merged = (ms + saf).distinctBy { it.id }.sortedBy { it.name }
                if (merged.isNotEmpty() || (ms.isEmpty() && saf.isEmpty() && failed == 0)) {
                    localTracks = merged
                }

                // ④ 落盘（读到几首就存几首；读不到的树保留旧条目，不删）
                withContext(Dispatchers.IO) {
                    localScanCache.save(
                        ScanCacheData(
                            trees = newTreeEntries,
                            counts = newTreeEntries.mapValues { it.value.size },
                            // 「从什么时候开始读不到」必须一起存，否则冷启归零、
                            // 60s 阈值永远到不了，已删的歌永远清不掉。
                            emptySince = safEmptySince.toMap()
                        )
                    )
                }
                DiagLog.log(
                    "LOCAL_SCAN",
                    "本地扫描完成：MediaStore ${ms.size} 首 + SAF ${saf.size} 首；" +
                        "SAF 复用 $reused 首、重读标签 $parsed 首" +
                        (if (retried > 0) "、重试后成功 $retried 棵" else "") +
                        (if (failed > 0) "、读不到 $failed 棵（已保留旧缓存）" else "")
                )

                // ⑤ 读不到的树交给后台补：首屏已经用旧缓存显示了，这里不必着急。
                //    ⚠️ 先把 localLoading 放掉再做补扫 —— 补扫最长会拖 30s，
                //    不能让用户在这段时间里点「重新扫描」被 `if (localLoading) return` 挡掉。
                localLoading = false
                //    补成功后**只更新曲目列表，不动缓存写盘竞争**（save 是原子的，
                //    但两个协程同时写会互相覆盖，这里串行地在同一个协程里做完）。
                //
                //    ⚠️ 补扫也要**多轮**：真机上 provider 慢起来能拖十几秒（刚开机、
                //    外置 SD 慢卡、云盘 provider 走网络）。只补一次等于没修 ——
                //    用户解锁手机那一刻目录还没好，App 就彻底放弃了。
                //    这里最多补 3 轮，间隔递增，全程不阻塞 UI。
                if (pendingRetry.isNotEmpty()) {
                    var pending = pendingRetry.toMutableList()
                    for ((round, delayMs) in BACKGROUND_RETRY_PLAN.withIndex()) {
                        if (pending.isEmpty()) break
                        delay(delayMs)
                        var recovered = 0
                        val mergedEntries = HashMap(newTreeEntries)
                        val extra = mutableListOf<Track>()
                        val stillFailing = mutableListOf<String>()
                        withContext(Dispatchers.IO) {
                            for (t in pending) {
                                val cur = runCatching {
                                    localLib.scanTreeIncremental(
                                        t, cache.trees[t]?.associateBy { e -> e.docId }
                                    )
                                }.getOrNull()
                                if (cur != null && cur.ok) {
                                    recovered++
                                    mergedEntries[t] = cur.entries.values.toList()
                                    extra += cur.tracks
                                    DiagLog.log(
                                        "LOCAL_SCAN",
                                        "SAF 后台补扫成功（第 ${round + 1} 轮）：" +
                                            "${safDisplayName(t)} ${cur.tracks.size} 首"
                                    )
                                } else {
                                    stillFailing += t
                                }
                            }
                            if (recovered > 0) {
                                localScanCache.save(
                                    ScanCacheData(
                                        trees = mergedEntries,
                                        counts = mergedEntries.mapValues { it.value.size },
                                        // 补扫成功的树要从 emptySince 里摘掉，
                                        // 否则下次会拿旧的时间戳误判「已清空」。
                                        emptySince = safEmptySince.toMap()
                                    )
                                )
                            }
                        }
                        if (recovered > 0) {
                            // 合并进当前列表（保留用户此刻可能的其它变更，不去覆盖整个列表）
                            localTracks = (localTracks + extra).distinctBy { it.id }.sortedBy { it.name }
                            newTreeEntries.putAll(mergedEntries)
                        }
                        if (stillFailing.isNotEmpty() && round == BACKGROUND_RETRY_PLAN.lastIndex) {
                            DiagLog.log(
                                "LOCAL_SCAN",
                                "SAF 后台补扫 ${BACKGROUND_RETRY_PLAN.size} 轮仍未就绪：" +
                                    stillFailing.joinToString { safDisplayName(it) } +
                                    "（旧缓存继续生效，下次冷启/重扫再试）"
                            )
                        }
                        pending = stillFailing.toMutableList()
                    }
                }
            } catch (e: Exception) {
                // 扫描失败不清空已有列表 —— 缓存里的歌还能播，清空反而像「库丢了」
                DiagLog.log("LOCAL_SCAN", "本地扫描失败：${e.message}")
            } finally {
                localLoading = false
            }
        }
    }

    /** 列出可选目录（给「扫描设置」页） */
    fun loadLocalFolders() {
        viewModelScope.launch {
            try {
                val ms = withContext(Dispatchers.IO) { localLib.folders() }
                val enabled = localScanStore.enabledFolders.first()
                val trees = localScanStore.safTrees.first()
                enabledLocalFolders = enabled
                // SAF 目录的曲目数取缓存里的真实值。
                // 以前写 -1（"真扫一遍才知道"），列表里就一直显示「扫描中」的味道 ——
                // 缓存落盘后这个数字是现成的，没必要再编一个占位值。
                val cache = withContext(Dispatchers.IO) { localScanCache.load() }
                localFolders = ms + trees.map {
                    LocalFolder(
                        path = it,
                        name = safDisplayName(it),
                        trackCount = cache.counts[it] ?: cache.trees[it]?.size ?: 0,
                        fromSaf = true
                    )
                }
            } catch (_: Exception) {
                localFolders = emptyList()
            }
        }
    }

    /** 勾选/取消目录。**全不勾 = 不限制**（不是「全不扫」），语义见 LocalScanStore */
    // 注意：不能叫 setEnabledLocalFolders —— 会和 enabledLocalFolders 的 setter 撞 JVM 签名
    fun applyLocalFolderSelection(paths: Set<String>) {
        viewModelScope.launch {
            localScanStore.setEnabled(paths)
            enabledLocalFolders = paths
            rescanLocal()
        }
    }

    /**
     * 用户用系统选择器挑了一个目录。
     *
     * ⚠️ 必须 takePersistableUriPermission —— 只拿一次性的读权限的话，
     * App 一重启这个 uri 就失效了，用户会看到「昨天加的歌今天又没了」。
     */
    fun addSafFolder(uri: Uri) {
        try {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
            // 少数提供方不支持长期授权，不阻断 —— 至少本次能用
        }
        viewModelScope.launch {
            localScanStore.addTree(uri.toString())
            toast("已添加目录，正在扫描…")
            rescanLocal()
            loadLocalFolders()
        }
    }

    fun removeSafFolder(path: String) {
        viewModelScope.launch {
            localScanStore.removeTree(path)
            toast("已移除该目录")
            rescanLocal()
            loadLocalFolders()
        }
    }

    /** 从 SAF tree uri 提取目录名（content://…/tree/primary%3AMusic → Music） */
    private fun safDisplayName(uri: String): String {
        val decoded = Uri.decode(uri)
        return decoded.substringAfterLast('/').substringAfterLast(':').ifBlank { decoded }
    }

    /** 「所有歌曲」页的数据与加载态 */
    var navidromeAllTracks by mutableStateOf<List<Track>>(emptyList())
        private set
    var navidromeAllLoading by mutableStateOf(false)
        private set

    /** 拉全库歌曲（进「所有歌曲」页时调用） */
    fun loadNavidromeAllSongs() {
        val c = navidrome ?: return
        viewModelScope.launch {
            navidromeAllLoading = true
            navidromeAllTracks = c.allSongs()
            navidromeAllLoading = false
        }
    }

    fun refreshNavidrome() {
        val c = navidrome ?: return
        viewModelScope.launch {
            navidromeAlbums = c.albums()
            navidromeArtists = c.artists()
            navidromePlaylists = c.playlists()
            // getScanStatus 拿不到（老服务器/权限问题）时，用刚拉到的专辑 songCount 兜底
            navidromeTrackCount = c.stats().first.let { n ->
                if (n > 0) n else navidromeAlbums.sumOf { a -> a.songCount }
            }
        }
    }

    // ---------------- 曲库浏览（歌手 → 专辑 → 歌曲） ----------------

    /** 详情页标题；专辑/歌单一条路走到底，歌手是两级 */
    var collectionTitle by mutableStateOf("")
        private set
    var collectionTracks by mutableStateOf<List<Track>>(emptyList())
        private set
    var collectionAlbums by mutableStateOf<List<CollectionItem>>(emptyList())
        private set
    var collectionLoading by mutableStateOf(false)
        private set

    fun openAlbum(albumId: String, name: String) {
        collectionTitle = name
        collectionTracks = emptyList()
        collectionAlbums = emptyList()
        collectionLoading = true
        viewModelScope.launch {
            collectionTracks = navidrome?.albumSongs(albumId) ?: emptyList()
            collectionLoading = false
        }
    }

    fun openPlaylist(playlistId: String, name: String) {
        collectionTitle = name
        collectionTracks = emptyList()
        collectionAlbums = emptyList()
        collectionLoading = true
        viewModelScope.launch {
            val (name, list) = navidrome?.playlistSongs(playlistId) ?: ("" to emptyList())
            if (name.isNotBlank()) collectionTitle = name
            collectionTracks = list
            collectionLoading = false
        }
    }

    /**
     * 歌手页先出专辑。
     * Subsonic 的 getArtist 只给专辑不给歌曲，硬凑一个歌曲列表要遍历每张专辑，又慢又容易漏。
     */
    fun openArtist(artistId: String, name: String) {
        collectionTitle = name
        collectionTracks = emptyList()
        collectionAlbums = emptyList()
        collectionLoading = true
        viewModelScope.launch {
            val (name, list) = navidrome?.artistAlbums(artistId) ?: ("" to emptyList())
            if (name.isNotBlank()) collectionTitle = name
            collectionAlbums = list.map { a ->
                CollectionItem(
                    id = a.id,
                    title = a.name,
                    subtitle = listOfNotNull(
                        a.year.takeIf { it > 0 }?.toString(),
                        "${a.songCount} 首"
                    ).joinToString(" · "),
                    coverUrl = a.coverUrl
                )
            }
            collectionLoading = false
        }
    }

    fun closeCollection() {
        collectionTitle = ""
        collectionTracks = emptyList()
        collectionAlbums = emptyList()
    }

    /** 曲库三种列表统一映射成「封面 + 标题 + 副标题」 */
    val albumItems: List<CollectionItem>
        get() = navidromeAlbums.map {
            CollectionItem(
                id = it.id, title = it.name,
                subtitle = listOfNotNull(
                    it.artist.takeIf { a -> a.isNotBlank() },
                    it.year.takeIf { y -> y > 0 }?.toString(),
                    "${it.songCount} 首"
                ).joinToString(" · "),
                coverUrl = it.coverUrl
            )
        }

    val artistItems: List<CollectionItem>
        get() = navidromeArtists.map {
            CollectionItem(
                id = it.id, title = it.name,
                subtitle = "${it.albumCount} 张专辑",
                coverUrl = it.coverUrl, round = true
            )
        }

    val playlistItems: List<CollectionItem>
        get() = navidromePlaylists.map {
            CollectionItem(
                id = it.id, title = it.name,
                subtitle = "${it.songCount} 首",
                coverUrl = it.coverUrl
            )
        }


    /** 重试连接（Navidrome 区块的「重新连接」按钮） */
    fun retryNavidrome() {
        val c = navidrome
        if (c == null) {
            toast("还没配置服务器地址")
            return
        }
        viewModelScope.launch {
            navidromeChecking = true
            navidromeError = null
            val r = c.ping()
            navidromeChecking = false
            navidromeConnected = r.isSuccess
            navidromeError = r.exceptionOrNull()?.message
            if (navidromeConnected) {
                refreshNavidrome()
                toast("已连上曲库")
            } else {
                toast("连接失败：${navidromeError ?: "未知原因"}")
            }
        }
    }

    fun saveNavidrome(url: String, user: String, pass: String) {
        viewModelScope.launch { settings.saveNavidrome(url, user, pass) }
    }

    /** 设置页的「测试」：把已保存的配置重新走一遍 ping（保存会触发上面的 collector） */
    fun recheckNavidrome() {
        viewModelScope.launch {
            val (u, user, pass) = settings.navidrome.first()
            if (u.isBlank() || user.isBlank() || pass.isBlank()) {
                toast("先填地址、用户名和密码")
                return@launch
            }
            saveNavidrome(u, user, pass)
        }
    }

    /** 断开曲库：清掉凭据，collector 会把连接状态与列表一并复位 */
    fun clearNavidrome() {
        viewModelScope.launch {
            settings.clearNavidrome()
            navidrome = null
            navidromeConnected = false
            navidromeError = null
            navidromeChecking = false
            navidromeAddress = ""
            navidromeUser = ""
            navidromePass = ""
            navidromeAlbums = emptyList()
            navidromeArtists = emptyList()
            navidromePlaylists = emptyList()
            navidromeTrackCount = 0
            toast("已断开曲库")
        }
    }

    // ---------------- 播放 ----------------

    fun play(track: Track, queueList: List<Track> = listOf(track)) {
        // 用户重新点播 = 允许再走一次换路/重取重试
        davRetried.clear()
        streamRetried.clear()
        consecutivePlayErrors = 0
        unsupportedAudioSkips.reset()
        // 用户主动点歌 = 不再等上一首的网络恢复，也不再是「已播完」状态
        awaitingNetwork = false
        queueFinished = false
        offlineWatchdog?.cancel()
        offlineWatchdog = null
        // 用户主动点歌 → 丢掉「上次没播完的位置」，从此按新歌走
        restoredPositionMs = -1L
        queue = queueList
        queueIndex = queueList.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        persistSession()
        playCurrent()
    }

    /** 播放整个列表（歌单/榜单/歌手的「播放全部」） */
    fun playAll(list: List<Track>) {
        if (list.isEmpty()) return
        play(list.first(), list)
    }

    /**
     * 播放当前队列项。
     *
     * 先把 UI 切到**点中的这一首**（曲名/封面立刻对，buffering 转起来），再去取链；
     * 解析成功才交给播放器，随后后台把队列逐步铺进播放器播放列表 ——
     * 队列真的在播放器里，`hasNextMediaItem()` 才是事实，「下一首/上一首」
     * 在通知栏、锁屏、QS 面板、耳机线控、车机上才都可用。
     *
     * 换歌 = 取消上一次点播（[playJob]）：旧协程在下一个挂起点直接退出，
     * 不会再把手写版本号之前那些脏状态写回来，也不需要 `playSeq` 那种自增判活。
     */
    private fun playCurrent() {
        if (queue.getOrNull(queueIndex) == null) return
        // 走普通起播 = 放弃「上次没播完的位置」，从 0 开始
        restoredPositionMs = -1L
        // 冷启动恢复后闸门是关着的（防止进度条被播放器的 0 覆盖）；用户这时点别的歌
        // 必须放开它，否则进度条再也不跟播放器走了
        restoring = false
        restoreWatchdog?.cancel()
        restoreWatchdog = null
        val start = queueIndex
        AudioRuntimeState.recordPlayIntent()
        val token = recoveryToken(queue[start])
        playJob?.cancel()
        playJob = viewModelScope.launch { playLoop(start, token) }
    }

    /**
     * 从 [start] 开始尝试播放，失败就顺着队列往下跳。
     *
     * 一张歌单/榜单里几乎必然夹着几首没版权、无母带的，撞上就停会让「播放全部」
     * 看起来像坏了；所以连续 [MAX_PLAY_TRY] 首都取不到链才报错。
     *
     * 屏幕上的曲目在跳过期间**不动** —— 连跳三首会让标题/封面狂闪，等真播了再切。
     */
    private suspend fun playLoop(start: Int, token: PlaybackRecoveryToken) {
        queue.getOrNull(start)?.let { tapped ->
            playbackState = playbackState.copy(track = tapped, buffering = true, error = null)
            // 切歌就掐掉上一首的导赏请求，否则它会把内容追加到新歌的文本框里。
            // resetAiForTrackChange 会先把上一首的半篇存档（归到旧歌名下）再清屏。
            resetAiForTrackChange()
            loadLyrics(tapped)
            queueBuildJob?.cancel()
        }

        var index = start
        var tried = 0
        while (true) {
            val t = queue.getOrNull(index) ?: return
            val resolved = resolveOnce(t)
            if (!recoveryReady(token)) return
            if (resolved != null) {
                startPlayback(resolved, index, tried, token)
                return
            }
            // 取链失败时先分辨「没网」还是「这首歌真没有」：
            // 离线状态下每首都会失败，继续跳只会耗光队列，还会把「没网」误报成
            // 「三家都没有音源」。这里用一个 3 秒的探针实测 —— 探针通不了就是网络问题，
            // 停在这一首等网（由 onNetworkRegained / 看门狗原地续播）；
            // 探针通了说明是真没这个音源，照旧顺延下一首。
            val reachable = probeReachableOnIo()
            if (!recoveryReady(token)) return
            if (!reachable) {
                DiagLog.log("NET", "取链失败且探针不通，判定为网络问题")
                enterAwaitingNetwork(t, 0L)
                return
            }
            tried++
            DiagLog.log("PLAY_SKIP", "取链失败 '${t.name}'（连续第 $tried 次）")
            // 两道闸：队列只有一首时跳过去还是它（死循环）；连败到头就别再试了
            if (queue.size <= 1 || tried >= MAX_PLAY_TRY) {
                // 现在是统一取链链：三个在线源都试过了
                val reason = "酷我 / 网易云 / 咪咕 均无可用音源"
                // ⚠️ 播放页目前并不渲染 playbackState.error，光写状态用户什么都看不到 ——
                // 必须同时 toast，否则连试三首失败就是「点了没反应」。
                val msg = if (tried > 1) "$reason（已连试 $tried 首）" else "$reason（${t.name}）"
                playbackState = playbackState.copy(buffering = false, error = msg)
                toast(msg)
                return
            }
            index = (index + 1) % queue.size
        }
    }

    /**
     * 用新直链**就地**替换播放器里的当前项。
     *
     * 为什么不复用 [startPlayback]：它是 `setMediaItems(listOf(...))`，会把整条 timeline
     * 清成一首 —— 正在随机播放的队列会被打乱、通知栏列表也跳一下。`replaceMediaItem`
     * 只换当前这一项，其余顺序、当前位置全部保留。
     */
    private suspend fun retryInPlace(fresh: Track, resumeAt: Long, token: PlaybackRecoveryToken) {
        if (!recoveryReady(token)) return
        val c = controller
        val idx = c?.currentMediaItemIndex ?: -1
        val sameItem = c != null && idx >= 0 && idx < c.mediaItemCount &&
            c.getMediaItemAt(idx).mediaId == fresh.id
        if (c == null) return
        if (!sameItem) {
            if (c.mediaItemCount != 0) return
            pendingSeekMs = resumeAt
            startPlayback(fresh, queueIndex, 0, token)
            return
        }
        // 解码状态跟着新流重置：不 clear 的话旧 error 还挂着，UI 会一直显示错误条
        playbackState = playbackState.copy(track = fresh, buffering = true, error = null, isPlaying = true)
        runCatching {
            c.replaceMediaItem(idx, mediaItemOf(fresh))
            c.prepare()
            // seek 必须在 READY 之后才有效；这里先用 pendingSeekMs 挂上，
            // 同时保留 seekTarget 让进度条别在 ready 前回跳
            if (resumeAt > 0L) {
                pendingSeekMs = resumeAt
                seekTarget = resumeAt
                seekGuardUntil = android.os.SystemClock.elapsedRealtime() + 3_000
            }
            c.play()
        }.onFailure {
            DiagLog.log("PLAY_RETRY", "就地替换失败，退回普通起播：${it.message}")
            pendingSeekMs = resumeAt
            startPlayback(fresh, queueIndex, 0, token)
        }
        queue.indexOfFirst { it.id == fresh.id }.takeIf { it >= 0 }?.let { queueIndex = it }
        applyLoudness(fresh)
    }

    /** 真的起播：交给播放器、更新状态、铺队列、预取下一首 */
    private suspend fun startPlayback(t: Track, index: Int, skipped: Int,
                                      token: PlaybackRecoveryToken = recoveryToken(queue.getOrNull(index) ?: t)) {
        if (!recoveryReady(token)) return
        // APE（Monkey's Audio）暂不支持 —— 识别并提示，不进播放器。
        // 看 playUrl（已解析的直链 / 本地路径 / content URI）的扩展名。
        val playPath = t.playUrl?.lowercase() ?: ""
        if (playPath.endsWith(".ape") || playPath.contains(".ape?")) {
            DiagLog.log("PLAY_SKIP", "APE 格式不支持：${t.name}")
            skipUnsupportedAudio(t)
            return
        }
        val c = readyController() ?: run {
            if (!recoveryReady(token)) return
            playbackState = playbackState.copy(buffering = false, error = "播放服务未就绪")
            return
        }
        if (!recoveryReady(token)) return
        queueFinished = false
        c.setMediaItems(listOf(mediaItemOf(t)), 0, 0L)
        c.prepare()
        c.play()
        queueIndex = index
        playbackState = playbackState.copy(
            track = t,
            isPlaying = true,
            durationMs = if (t.durationMs > 0) t.durationMs else playbackState.durationMs,
            buffering = false,
            error = null
        )
        // ⚠️ 不要在这里清 consecutivePlayErrors：起播只是「已交给播放器」，
        // 下一毫秒就可能因为同样的原因再炸（队列里全是被墙的地址时）。
        // 真正的恢复信号是 STATE_READY（见 onPlaybackStateChanged）。
        recentStore.add(t)
        if (skipped > 0) {
            toast("已跳过 $skipped 首无法播放的曲目")
            // 跳过来的这首之前没取过词（只有用户点的那首取了）
            loadLyrics(t)
        }
        buildQueueAround(t)
        // 首曲已经在出声了，顺手把下一首的直链在后台备好
        prefetchNext()
        // 首次起播不走 onMediaItemTransition（同一首重复点也一样），
        // 这里补一次，否则「点开第一首」永远没有响度归一
        applyLoudness(t)
        // 同上：第一次起播没有 transition，当前曲变了要自己落一次盘
        persistSession()
    }

    /**
     * 并发解析、**按序回调**。
     *
     * 并发是为了快（每首要单独取链，串行几十首要等很久），
     * 按序回调是为了准 —— 加入播放列表的顺序必须和队列一致，
     * 否则「下一首」会变成随机的某一首。
     * 并发只开 3 路：酷我接口并发太高会被限流。
     */
    private suspend fun resolveInOrder(
        tracks: List<Track>,
        concurrency: Int = 3,
        onResolved: (Track) -> Unit
    ) = coroutineScope {
        var next = 0
        while (next < tracks.size) {
            val batch = tracks.subList(next, (next + concurrency).coerceAtMost(tracks.size))
            val got = batch.map { t ->
                // resolveOnce 会先查缓存，再合并「同一首正在解析」的并发请求
                async(Dispatchers.IO) { resolveOnce(t) }
            }.awaitAll()
            got.forEach { if (it != null) onResolved(it) }
            next += batch.size
        }
    }

    /**
     * 后台把队列铺进播放器播放列表。
     * 先补当前之后（让「下一首」尽快可用），再往前插；解不出来的跳过，不阻塞播放。
     * 后面只铺 5 首（见下方注释），够了再由 prefetchNext 一首首续。
     */
    private fun buildQueueAround(anchor: Track) {
        val snapshot = queue
        val epoch = queueEpoch
        val mode = playMode
        queueBuildJob?.cancel()
        val start = snapshot.indexOfFirst { it.id == anchor.id }
        if (start < 0 || snapshot.size <= 1) return
        val shuffle = playMode == PlayMode.SHUFFLE
        queueBuildJob = viewModelScope.launch {
            // 「已经在播放器里」的唯一事实来源是 controller，不是直链缓存：
            // prefetch 只解析、不加入，缓存里有不代表播放器里有 —— 用缓存判断会漏歌。
            val laid = HashSet<String>()
            controller?.let { c ->
                for (i in 0 until c.mediaItemCount) laid += c.getMediaItemAt(i).mediaId
            }
            // ── 随机播放必须铺**整个队列** ──────────────────────────────
            //
            // ExoPlayer 的 shuffle 只在**播放器 timeline 已有**的 item 之间打乱，
            // 不是在全库里抽。顺序模式只铺 9 首（后 5 / 前 3）是为了省取链，
            // 但随机模式下这个策略会让「随机」永远只在附近那几首里跳 ——
            // 用户听到的就是「大概率都在随机附近那么几首」。
            // 所以 SHUFFLE 下宁可多打几次接口，也要把 timeline 铺成全集。
            if (shuffle) {
                val rest = snapshot.filterNot { laid.contains(it.id) }
                DiagLog.log("PLAY_QUEUE", "随机模式：全量铺队列 ${rest.size}/${snapshot.size} 首")
                resolveInOrder(rest) { r ->
                    // v2.4.7：判重必须问**当前**的 controller，不能用协程启动时拍的 `laid` 快照。
                    // prefetchNext / ensureNextInTimeline 会在 resolve 的这几秒里并发追加同一首，
                    // 快照不知道 → 同一首被加两遍 → timeline 21 项而队列只有 20 首（实测）。
                    laid.add(r.id)
                    addToTimelineIfAbsent(r, expectedEpoch = epoch)
                }
                val laidNow = controller?.mediaItemCount ?: 0
                DiagLog.log("PLAY_QUEUE", "随机模式：timeline 现有 $laidNow 首")
                return@launch
            }
            // 只铺**后面 5 首、前面 3 首**，不再全量铺。
            //
            // 歌单动辄几十首，全铺一遍就是几十次取链（每次 1~3s，酷我还会限流），
            // 而用户绝大多数听不到第 6 首，也很少连按三次「上一首」。
            // 更远的由 prefetchNext 一首首续上：点过去照样能播，只是那一下要等取链。
            val after = (start + 1 until snapshot.size).map { snapshot[it] }
                .filterNot { laid.contains(it.id) }
                .take(5)
            resolveInOrder(after) { r ->
                laid.add(r.id)
                addToTimelineIfAbsent(r, expectedEpoch = epoch)
            }
            // 倒序取最近 3 首：take(3) 作用在倒序列表上 = start-1、start-2、start-3
            DiagLog.log("PLAY_QUEUE", "顺序模式：铺后 ${after.size} 首 + 前 3 首")
            val before = (start - 1 downTo (start - 3)).filter { it >= 0 }.map { snapshot[it] }
                .filterNot { laid.contains(it.id) }
            resolveInOrder(before) { r ->
                laid.add(r.id)
                // 倒序迭代 + 固定插到 0 → 最终顺序才是 q[start-3..start-1], cur
                // （若插在 currentMediaItemIndex，顺序会反，上一首会跳到队列头部）
                addToTimelineIfAbsent(r, at = 0, expectedEpoch = epoch)
            }
        }
    }

    /**
     * 预取**下一首**的播放直链。
     *
     * 取链要 1~3s（酷我还要过音质档位、登记解密器），点「下一首」时才现取就是一段空档；
     * 而 [buildQueueAround] 只铺后面 5 首，第 6 首之后没人在手。
     * 这里提前解析好放进 [streams]（走 [resolveOnce]，和铺队列共用同一个在途请求），
     * [playCurrent] 命中缓存就不再打网络。
     *
     * 只是「顺手备一手」，不是播放：不动播放态、不动播放器、不动 queueIndex。
     * 失败也什么都不写，真正的播放流程会自己重试/报错。
     */
    private fun prefetchNext() {
        if (queue.size <= 1) return
        if (playMode == PlayMode.SINGLE) return       // 单曲循环，下一首永远不会播
        val nextIndex = nextQueueIndex()
        if (nextIndex !in queue.indices || nextIndex == queueIndex) return
        val next = queue.getOrNull(nextIndex) ?: return
        val epoch = queueEpoch
        val mode = playMode
        // v2.4.6：预取窗口从 +1 扩到 +2。
        //
        // 只提前一首时，遇到 3 分钟的短歌 + 取链 2 秒，切歌那一下仍会露出空档；
        // 而且 ensureNextInTimeline 在临近末尾时是**同步等取链**才追加的，
        // 一旦那一次接口慢，连播就会卡住。多备一首的代价只是多一次 1~3s 的接口调用，
        // 换来「+1 已经在缓存里、+2 也已经在路上」。
        //
        // +2 只写进 streams 缓存，**不进 timeline** —— timeline 由
        // ensureNextInTimeline 按需续窗，两处都追加会重（v2.4.4 踩过）。
        val targets = PrefetchWindow.targets(queue.size, queueIndex, nextIndex, loop = mode != PlayMode.ORDER) ?: return
        val following = queue.getOrNull(targets.following ?: -1)
        // 解析缓存不等于播放器队列有下一项：缓存只用于省请求，续窗在 ensureNextInTimeline。
        viewModelScope.launch(Dispatchers.IO) {
            val resolved = resolveOnce(next) ?: return@launch
            withContext(Dispatchers.Main) {
                if (queueEpoch != epoch || playMode != mode || nextQueueIndex() != nextIndex ||
                    queue.getOrNull(nextIndex)?.id != next.id) return@withContext
                if (controller == null) return@withContext
                // ⚠️ 必须在**追加这一刻**重新查重，不能用启动协程时算好的 `laid`：
                // ensureNextInTimeline 可能在这段时间里已经把同一首追加进 timeline，
                // 于是两处各加一次 → 队列里出现同一首歌两遍（实测日志同一毫秒两条
                // 「续窗追加 / 预取并追加」都是 '可能'）。
                if (addToTimelineIfAbsent(resolved.copy(id = next.id), expectedEpoch = epoch)) {
                    DiagLog.log("PLAY_QUEUE", "预取并追加下一首 '${next.name}' 到播放器队列")
                } else {
                    DiagLog.log("PLAY_PREFETCH", "已预取下一首 '${next.name}'")
                }
                val item = mediaItemOf(resolved.copy(id = next.id))
                val local = item.localConfiguration
                val allowed = cacheSizeGb > 0 && (!wifiOnlyDownload || onWifi()) &&
                    !(resolved.isMaster && masterWifiOnly && !onWifi()) && AudioRuntimeState.snapshot.desiredPlaying
                if (local != null) {
                    com.kmusic.apkhd.playback.AudioPrecacheBridge.replace(
                        com.kmusic.apkhd.playback.AudioPrecache.Target(local.uri.toString(), local.customCacheKey,
                            epoch, resolved.sourceBytes), allowed)
                }
            }
            // +2 只热缓存。放在 +1 之后做：万一酷我限流，先保证真正要播的那首拿到链。
            if (following != null && queueEpoch == epoch && playMode == mode) {
                val warmed = resolveOnce(following) ?: return@launch
                DiagLog.log("PLAY_PREFETCH", "预热 +2 '${warmed.name}'（不追加 timeline）")
            }
        }
    }

    /**
     * 顺序模式：保证**当前曲之后一定至少还有一首**铺在 timeline 里。
     *
     * v2.4.7 重写。旧实现有三个致命点，合起来就是用户看到的「听了几首歌定住」：
     *
     * 1. 判据用 `firstOrNull { mediaId == currentId }` —— timeline 里一旦有重复项
     *    （预取和续窗并发追加造成），拿到的是**靠前**那个，`currentAt` 偏小，
     *    判定「还没到末尾、不用补」，于是真的末尾没人补。
     * 2. 补的只算「当前+1」那一首，它已经在 timeline 里就直接放弃，不会往后看。
     * 3. 判据是 timeline 的下标差（`currentAt < count-2`），而不是逻辑队列还剩多少。
     *
     * 现在改成：**以逻辑队列为准**，向后扫描找第一个没铺过的，铺到「当前之后至少 1 首」为止。
     * 队列还剩多少由 [queueIndex] 说，跟 timeline 里有几个副本无关。
     */
    private fun ensureNextInTimeline(currentId: String) {
        if (playMode == PlayMode.SHUFFLE || playMode == PlayMode.SINGLE) return
        val snapshot = queue
        val epoch = queueEpoch
        val mode = playMode
        if (snapshot.size <= 1) return
        // 逻辑下标为准：currentId 只是校验它还是当前曲，不参与「铺到哪了」的判断
        val idx = snapshot.indexOfFirst { it.id == currentId }
        if (idx < 0) return
        val timelineIds = controller?.let { c -> (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId } }
            .orEmpty()
        // 缓冲够用就不动：这里只保证「当前之后至少还有 2 首」，
        // 不是把整条队列铺满。157 首的队列全铺 = 157 次取链，旧策略故意不做。
        val upcoming = snapshot.drop(idx + 1).mapTo(HashSet()) { it.id }
        if (timelineIds.count { it in upcoming } >= 2) return
        val next = com.kmusic.apkhd.playback.QueueContinuation.nextOutsideWindow(
            snapshot.size, idx, timelineIds, snapshot.map { it.id }
        ) ?: return   // 队尾：顺序模式播完即停，不绕回
        val original = snapshot[next]
        viewModelScope.launch(Dispatchers.IO) {
            val fresh = resolveOnce(original, force = true) ?: return@launch
            withContext(Dispatchers.Main) {
                if (queueEpoch != epoch || playMode != mode) return@withContext
                if (addToTimelineIfAbsent(fresh.copy(id = original.id), expectedEpoch = epoch)) {
                    DiagLog.log("PLAY_QUEUE", "续窗追加 '${original.name}'（逻辑第 ${next + 1}/${snapshot.size} 首）")
                }
            }
        }
    }

    /**
     * 安全追加：**在追加这一刻**问 controller 有没有这首，有就不加。
     *
     * 以前每个调用点各自写一遍 `(0 until count).any { mediaId == id }`，
     * buildQueueAround 用的还是协程启动时拍的旧快照 —— 与 prefetch / 续窗并发时
     * 必然漏判，实测 timeline 21 项而队列 20 首。这里统一收口。
     *
     * @return 真的追加了才返回 true
     */
    private fun addToTimelineIfAbsent(t: Track, at: Int? = null, expectedEpoch: Long = queueEpoch): Boolean {
        if (queueEpoch != expectedEpoch || queue.none { it.id == t.id }) return false
        val c = controller ?: return false
        val exists = (0 until c.mediaItemCount).any { c.getMediaItemAt(it).mediaId == t.id }
        if (exists) return false
        return runCatching {
            if (at == null) c.addMediaItem(mediaItemOf(t)) else c.addMediaItem(at, mediaItemOf(t))
            true
        }.getOrDefault(false)
    }

    /**
     * 队列里的「下一首」下标。
     *
     * 随机播放时播哪首由播放器决定，按 queue 顺序取会和实际播的那首对不上，
     * 预取就成了白打一次接口。所以 SHUFFLE 下问播放器要 [Player.nextMediaItemIndex]，
     * 再按 mediaId 反查队列；其余模式两者一致，直接用队列顺序（取模 = 循环播放）。
     */
    private fun nextQueueIndex(): Int {
        if (queueIndex !in queue.indices) return -1
        val c = controller
        if (playMode == PlayMode.SHUFFLE && c != null) {
            val n = c.nextMediaItemIndex
            if (n in 0 until c.mediaItemCount) {
                val id = c.getMediaItemAt(n).mediaId
                val at = queue.indexOfFirst { it.id == id }
                if (at >= 0) return at
            }
        }
        return PrefetchWindow.nextIndex(queue.size, queueIndex, loop = playMode != PlayMode.ORDER)
    }

    /** 在酷我按「歌名+歌手」找同一首歌，严格三重校验后返回最优候选 */
    private suspend fun matchOnKuwo(t: Track): SearchResult? {
        val candidates = runCatching { client.search("${t.name} ${t.artist}", size = 20) }
            .getOrDefault(emptyList())
        val target = TrackMatcher.Candidate(t.name, t.artist, t.durationMs)
        return candidates
            .filter {
                TrackMatcher.isMatch(
                    target,
                    TrackMatcher.Candidate(it.name.orEmpty(), it.artist.orEmpty(), it.durationMs)
                )
            }
            .minByOrNull { kotlin.math.abs(it.durationMs - t.durationMs) }
    }

    /**
     * 取歌词：按歌名+歌手查，与音源无关。
     *
     * 两个来源依次兜底（宁可多打一次接口，也别让能查到的词查不到）：
     *   1. 网易云曲目 → gdstudio 歌词（中文覆盖更好）
     *   2. lrclib（多候选归一化 + 严格校验，见 LyricQuery）
     *
     * 注：酷我自己的歌词接口实测三个端点全不通（mobi.s 空、kmusic 空、
     * nplserver 500），所以不接 —— 接了只会每次多等一次必败的请求。
     *
     * ⚠️ race condition：这个协程可能比当前歌曲活得更久。以前结尾是
     * `loadingLyrics = false`（无守卫）—— 上一首歌的收尾会把后一首的
     * 「加载中」抹成 false，UI 上歌词就「刚冒出来又变成暂无歌词」。
     * 所以**每次写状态前都要重新确认还是同一首**。
     */
    private fun loadLyrics(t: Track) {
        // 同一首歌正在加载或已加载过 → 不重复打接口。
        //
        // 实测日志：每首歌都被加载两次（playCurrent 一次、播放器 onMediaItemTransition
        // 再一次，间隔仅 1ms），等于每首要打 10 个请求，把自己往 lrclib 限流上推。
        if (lyricLoadedId == t.id) {
            DiagLog.log("LYRIC_SKIP_DUP", "同一首歌已在取词/已取过（id=${t.id}），跳过重复请求")
            return
        }
        lyricLoadedId = t.id
        // 换到**另一首**歌时才清掉它的否决记录。
        //
        // ⚠️ 这里以前是「每次取词都清」，正好把用户刚点的否决也清掉了 ——
        // reportWrongLyric 先加集合再调 loadLyrics，加完立刻被自己清掉，
        // 用户看到的就是「选了换源，歌词纹丝不动」。所以必须按曲目判断。
        if (lyricSkippedForTrack != t.id) {
            lyricSkippedSources.remove(t.id)
            lyricSkippedForTrack = null
        }

        // 缓存命中：听过的歌直接出词，不闪「加载中」也不打网络。
        // 有否决记录的曲目不走缓存 —— 那说明用户刚说这词不对，必须重新取。
        val cached = lyricCache[t.id]?.takeIf { lyricSkippedSources[t.id].isNullOrEmpty() }
        if (cached != null) {
            lyricLastSource = cached.source
            lyrics = cached.lines
            loadingLyrics = false
            DiagLog.log(
                "LYRIC_CACHE",
                "命中缓存（${cached.lines.size} 行，源=${cached.source ?: "未知"}），不再请求网络"
            )
            return
        }

        lyricLastSource = null

        // 用自增序号标记这次加载；被更新的歌一进来它就作废
        lyricLoadSeq += 1
        val mySeq = lyricLoadSeq
        viewModelScope.launch {
            // 这里**不要**用「当前曲目 id 相等」做守卫。
            //
            // 我在这里踩过一次：自动下一首 / 跨源回退时 queueIndex 与
            // playbackState.track 可能还没同步到这首，两个 id 都不相等，
            // 结果置空和赋值**双双被跳过** —— 表现为更多歌直接没歌词。
            //
            // 防串歌靠 [lyricLoadSeq] 就够了：只有最后一次加载能写状态。
            // 进入加载就先给出「加载中」，别让用户对着旧歌词或空面板。
            lyrics = null
            loadingLyrics = true
            var lines = emptyList<LyricLine>()
            DiagLog.log(
                "LYRIC_LOAD",
                "开始取词: '${t.name}' / '${t.artist}' 源=${t.source} " +
                    "sourceId=${t.sourceId} 时长=${t.durationMs}ms"
            )

            // 0. 咪咕：搜索响应自带 LRC 直链，与音频同源同版本 —— 有就先用
            if (lines.isEmpty() && t.source == "migu" &&
                !t.lyricUrl.isNullOrBlank() && !isLyricSourceSkipped(t, "migu")
            ) {
                val text = withTimeoutOrNull(3000) {
                    runCatching { migu.lyric(t.lyricUrl!!) }.getOrNull()
                }
                if (!text.isNullOrEmpty()) {
                    lines = lyricsRepo.parseLyrics(text, "")
                    if (lines.isNotEmpty()) lyricLastSource = "migu"
                }
                DiagLog.log(
                    "LYRIC_MIGU",
                    "咪咕歌词 url=${t.lyricUrl} → " +
                        if (text.isNullOrEmpty()) "无词" else "${text.length} 字符"
                )
            }

            // 1. 酷我自家歌词 —— 最优选，所以排最前
            //
            // 曲目就是酷我源的，歌词和音频是同一个 rid，同版本、零串歌风险。
            // 我们之前只查 lrclib / gdstudio，等于放着同源歌词不用、跑去跨源碰运气，
            // 结果热榜里 19/50 首明明有词却一直显示「暂无歌词」。
            // ⚠️ 不能只看 source：跨源回退后 source 仍是 "kuwo"，但 sourceId 已经
            // 换成 `ne:xxx`（那时歌词该走 gdstudio）。纯数字才是酷我 rid。
            val kwRid = t.sourceId.takeIf { it.isNotBlank() && it.all { c -> c.isDigit() } }
            if (lines.isEmpty() && t.source == "kuwo" && kwRid != null && !isLyricSourceSkipped(t, "kuwo")) {
                val k0 = System.currentTimeMillis()
                // 超时兜底：这接口正常 100~200ms。卡住时不能拖住整条链路 ——
                // 酷我无词还要降级到 gdstudio / lrclib，一步慢就步步慢。
                val text = withTimeoutOrNull(2500) {
                    runCatching { client.lyricByRid(kwRid) }.getOrNull()
                }
                DiagLog.log(
                    "LYRIC_KUWO",
                    "酷我歌词 rid=$kwRid → " +
                        if (text.isNullOrEmpty()) "无词" else "${text.length} 字符",
                    System.currentTimeMillis() - k0
                )
                if (!text.isNullOrEmpty()) {
                    lines = lyricsRepo.parseLyrics(text, "")
                    if (lines.isNotEmpty()) lyricLastSource = "kuwo"
                }
            } else {
                DiagLog.log(
                    "LYRIC_KUWO",
                    "跳过酷我歌词：源=${t.source} sourceId=${t.sourceId}" +
                        if (t.source == "kuwo" && isLyricSourceSkipped(t, "kuwo")) "（本曲已否决）" else ""
                )
            }

            // 2. gdstudio 取词（中文覆盖最好，且**与音源无关** —— 只看 id 是不是网易云的）
            //
            // ⚠️ 这里以前判的是 `t.source == "netease"`，是错的。
            // 实测日志：酷我源的歌，切歌后 mediaId 变成 `ne:3445947263`
            // （跨源回退时曲目被换成网易云 id，但 source 字段还是 kuwo），
            // 于是明明 gdstudio 有 1545 字符现成的词，却被打上"源不是 netease"跳过。
            // 5 首没歌词的歌里 3 首就是这么丢的。
            //
            // 判据改成看 sourceId 前缀：`ne:` 开头就是网易云 id，可以走 gdstudio。
            val canUseGd = lines.isEmpty() && t.sourceId.startsWith("ne:") &&
                !isLyricSourceSkipped(t, "gdstudio")
            if (canUseGd) {
                val gdId = t.sourceId.removePrefix("ne:")
                val g0 = System.currentTimeMillis()
                // 这个接口正常 1~2s，卡住时不能拖住后面几十秒
                // 先直连（新域名上 gd 的 types=lyric 是 401），拿不到再回退 gd
                val raw = withTimeoutOrNull(6000) {
                    runCatching { netease.lyric(gdId) }.getOrNull()
                        ?.takeIf { it.isNotBlank() }
                        ?: runCatching { gd.lyric(gdId) }.getOrNull()
                }
                DiagLog.log(
                    "LYRIC_GDSTUDIO",
                    "网易云/ gdstudio 取词 id=$gdId → " +
                        if (raw.isNullOrEmpty()) "空/失败" else "${raw.length} 字符",
                    System.currentTimeMillis() - g0
                )
                if (!raw.isNullOrEmpty()) {
                    lines = lyricsRepo.parseLyrics(raw, "")
                    if (lines.isNotEmpty()) lyricLastSource = "gdstudio"
                }
            } else {
                DiagLog.log("LYRIC_GDSTUDIO", "sourceId=${t.sourceId} 不是网易云 id，跳过 gdstudio")
            }

            // 2+3. gdstudio 按名 与 lrclib **并发**。
            //
            // 这两条都可能卡几十秒：实测日志里 gdstudio 按名搜最长 83s，
            // lrclib 整条最长 40s。串行等于把两边的超时相加，用户看到的就是
            // 「歌词一直加载中」。并发之后最坏只等最长的那一个。
            if (lines.isEmpty()) {
                val gdNameJob = async {
                    if (isLyricSourceSkipped(t, "gdstudio_byname")) null
                    else {
                        val s0 = System.currentTimeMillis()
                        // 8s 硬超时：日语歌名在 gdstudio 上会一直磨到 40s+，
                        // 那一刻用户早就切歌了，等下去没有意义
                        val text = withTimeoutOrNull(5000) {
                            // 按名搜歌词：先网易云直连（校验同样严格，看歌名+歌手+时长），
                            // 拿不到再回退 gdstudio
                            runCatching { lyricByNameDirect(t) }.getOrNull()
                                ?: runCatching {
                                    gd.lyricByName(t.name, t.artist, t.durationMs / 1000)
                                }.getOrNull()
                        }
                        DiagLog.log(
                            "LYRIC_GDSTUDIO_BYNAME",
                            "gdstudio 按名搜 '${t.name}' / '${t.artist}' → " +
                                if (text.isNullOrEmpty()) "无严格匹配" else "${text.length} 字符",
                            System.currentTimeMillis() - s0
                        )
                        text
                    }
                }
                val lrcJob = async {
                    if (isLyricSourceSkipped(t, "lrclib")) emptyList()
                    else {
                        val l0 = System.currentTimeMillis()
                        val r = withTimeoutOrNull(5000) {
                            runCatching { lyricsRepo.fetch(t.name, t.artist, t.durationMs) }
                                .getOrElse {
                                    DiagLog.log(
                                        "LYRIC_LRCLIB",
                                        "lrclib 抛异常: ${it.javaClass.simpleName}: ${it.message}"
                                    )
                                    emptyList()
                                }
                        } ?: emptyList()
                        DiagLog.log(
                            "LYRIC_LRCLIB",
                            "lrclib 返回 ${r.size} 行",
                            System.currentTimeMillis() - l0
                        )
                        r
                    }
                }

                // lrclib 先看：它通常 2~5s 回来，而且自带歌名/歌手/时长三重校验。
                // gdstudio 中文覆盖更好，但这条路上它动不动磨到 30s+（现已压到 5s），
                // 让用户干等一个「可能更好」的结果不划算 —— 快先拿到，宁可次优。
                // 两个都在并发跑，所以这段等待时间是重叠的，不是相加。
                lines = lrcJob.await()
                if (lines.isEmpty()) {
                    val gdText = gdNameJob.await()
                    if (!gdText.isNullOrEmpty()) {
                        lines = lyricsRepo.parseLyrics(gdText, "")
                        if (lines.isNotEmpty()) lyricLastSource = "gdstudio_byname"
                    }
                }
            }

            // 已经被更新的加载接手了 → 什么都不写，直接退场。
            // 这就是防串歌的唯一判据（切换的快时，旧歌的协程后跑完也不会覆盖新歌）。
            if (mySeq != lyricLoadSeq) {
                DiagLog.log("LYRIC_STALE", "取词结果被丢弃（seq=$mySeq 当前=${lyricLoadSeq}），可能是快速切歌")
                return@launch
            }

            if (lines.isNotEmpty() && lyricLastSource == null) lyricLastSource = "lrclib"
            // 只缓存**有词**的结果：空结果可能是网络抖动，缓存了会一直显示「暂无歌词」。
            if (lines.isNotEmpty()) {
                lyricCache[t.id] = CachedLyric(lines, lyricLastSource)
                prefetchNextLyric()
            }
            lyrics = lines
            loadingLyrics = false
            DiagLog.log(
                "LYRIC_RESULT",
                if (lines.isEmpty()) "最终结果: 无歌词（UI 显示暂无歌词）" else "最终结果: ${lines.size} 行歌词"
            )
        }
    }

    /**
     * 播放 / 暂停。
     *
     * ⚠️ 出错后 ExoPlayer 会落到 `STATE_IDLE`，此时 `play()` 只设置 playWhenReady，
     * **不会重新 prepare** —— 用户点播放键得到的是「图标变了一下、没有声音、也没有提示」。
     * 所以错误态 / IDLE 态必须先 prepare 再 play。
     */
    fun togglePlay() {
        val p = controller ?: return
        if (AudioRuntimeState.snapshot.desiredPlaying && (awaitingNetwork || playJob?.isActive == true)) {
            AudioRuntimeState.recordPauseIntent()
            cancelPendingPlayback()
            p.pause()
            return
        }
        // 冷启动恢复的这首歌还没交给播放器：按播放 = 从上次的位置接着播。
        // 必须先判这一条 —— 播放器此刻是 STATE_IDLE，落到下面的 broken 分支
        // 会走 play(t, queue) 从头开始，把恢复的位置浪费掉。
        if (restoredPositionMs >= 0L && queue.getOrNull(queueIndex) != null) {
            startRestoredPlayback(autoPlay = true)
            return
        }
        // 整条队列已经播完（进度条走满、按钮是三角）：按播放 = 从头重听队列。
        // 不处理的话 play() 只置 playWhenReady，播放器停在末尾一动不动 —— 又是「点了没反应」。
        // 单曲循环/循环/随机不该出现已播完，只按顺序模式处理。
        if (queueFinished || endedParked) {
            queueFinished = false
            endedParked = false
            if (queue.isNotEmpty() && playMode == PlayMode.ORDER) {
                DiagLog.log("PLAY_ENDED", "队列已播完，用户点播放 → 回到队首重听")
                queueIndex = 0
                playCurrent()
                return
            }
        }
        val broken = runCatching { p.playerError != null || p.playbackState == Player.STATE_IDLE }
            .getOrDefault(false)
        if (broken) {
            val t = queue.getOrNull(queueIndex)
            if (t != null) {
                // 有队列就按「重新点播这首」处理：清掉重试记录、重取链、从 0 开始
                play(t, queue)
            } else {
                runCatching { p.prepare() }
                runCatching { p.play() }
            }
            return
        }
        // ⚠️ 判「意图」而不是 p.isPlaying（v2.4.8）：
        // 缓冲时 p.isPlaying 是 false，用户在缓冲中按暂停会被当成「按播放」，
        // 结果点了没反应（甚至把想听的又拉起来）。playWhenReady 才是用户按下什么。
        val wantPlay = runCatching { p.playWhenReady }.getOrDefault(p.isPlaying)
        if (wantPlay) {
            AudioRuntimeState.recordPauseIntent()
            cancelPendingPlayback()
            p.pause()
            playbackState = playbackState.copy(isPlaying = false)
        } else {
            AudioRuntimeState.recordPlayIntent()
            p.play()
            playbackState = playbackState.copy(isPlaying = true)
        }
    }

    fun next() {
        AudioRuntimeState.recordPlayIntent()
        playJob?.cancel()
        awaitingNetwork = false
        offlineWatchdog?.cancel()
        val c = controller
        // 队列已铺进播放器 → 交给播放器切（系统按钮和 App 内按钮走同一条路）
        if (c != null && c.hasNextMediaItem()) {
            c.seekToNextMediaItem()
            return
        }
        if (queue.isEmpty()) return
        queueIndex = (queueIndex + 1) % queue.size
        playCurrent()
    }

    fun prev() {
        AudioRuntimeState.recordPlayIntent()
        playJob?.cancel()
        awaitingNetwork = false
        offlineWatchdog?.cancel()
        val c = controller
        if (c != null && c.hasPreviousMediaItem()) {
            c.seekToPreviousMediaItem()
            return
        }
        if (queue.isEmpty()) return
        queueIndex = (queueIndex - 1 + queue.size) % queue.size
        playCurrent()
    }

    /** 从队列面板点选某一条播放 */
    fun playAt(index: Int) {
        if (index !in queue.indices) return
        AudioRuntimeState.recordPlayIntent()
        playJob?.cancel()
        awaitingNetwork = false
        offlineWatchdog?.cancel()
        queueIndex = index
        val c = controller
        // 已经在播放器播放列表里就直接跳，不用重新解析
        val at = c?.let { cc ->
            (0 until cc.mediaItemCount).firstOrNull { cc.getMediaItemAt(it).mediaId == queue[index].id }
        }
        if (c != null && at != null) {
            c.seekTo(at, 0L)
            c.play()
            return
        }
        playCurrent()
    }

    // seek 保护：Media3 的 seekTo 是异步的，轮询可能读到旧位置导致进度条回退
    private var seekTarget = -1L
    private var seekGuardUntil = 0L

    /**
     * 拖动进度条 seek。
     * 用已有的 durationMs 换算目标位置；没有时长（还没解析出来）时忽略。
     */
    fun seekTo(fraction: Float) {
        val p = controller ?: return
        val dur = if (playbackState.durationMs > 0) playbackState.durationMs
        else runCatching { p.duration }.getOrDefault(0L)
        if (dur <= 0) return
        val target = (fraction.coerceIn(0f, 1f) * dur).toLong()
        // 恢复出来但还没起播：拖动只是改「待播位置」，等按播放时才真正 seek。
        // 直接 p.seekTo 到 IDLE 的播放器不会报错但也不会生效，进度条会立刻弹回。
        if (restoredPositionMs >= 0L) {
            restoredPositionMs = target
            playbackState = playbackState.copy(positionMs = target)
            // 拖完就退出 App 的话，这个新位置必须落盘
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { snapshotStore.savePosition(target) }
            }
            return
        }
        seekTarget = target
        seekGuardUntil = android.os.SystemClock.elapsedRealtime() + 800
        runCatching { p.seekTo(target) }
        playbackState = playbackState.copy(positionMs = target)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { snapshotStore.savePosition(target) }
        }
    }

    /** 重新进入搜索页时清空上次的搜索态，回到历史记录 */
    // ---------------- 搜索页签（单曲/专辑/歌手/歌单） ----------------

    var searchTab by mutableStateOf(SearchTab.SONG)
        private set

    var artistResults by mutableStateOf<List<ArtistBrief>>(emptyList())
        private set
    var albumResults by mutableStateOf<List<AlbumBrief>>(emptyList())
        private set
    var playlistResults by mutableStateOf<List<PlaylistBrief>>(emptyList())
        private set

    /**
     * 切页签。
     * 目标页签已有结果就直接展示（切回去不用重搜），没有才发请求。
     */
    fun changeSearchTab(tab: SearchTab) {
        if (tab == searchTab) return
        searchTab = tab
        searchError = null
        clearSuggestions()
        val hasCache = when (tab) {
            SearchTab.SONG -> searchResults.isNotEmpty()
            SearchTab.ARTIST -> artistResults.isNotEmpty()
            SearchTab.ALBUM -> albumResults.isNotEmpty()
            SearchTab.PLAYLIST -> playlistResults.isNotEmpty()
        }
        if (!hasCache && searchKeyword.isNotBlank()) search()
    }

    // ---- 专辑详情 ----

    var albumTitle by mutableStateOf("")
        private set
    var albumTracks by mutableStateOf<List<Track>>(emptyList())
        private set
    var albumLoading by mutableStateOf(false)
        private set

    /**
     * 专辑详情。
     *
     * ⚠️ 酷我**没有**「按专辑 id 取歌曲列表」的可用端点（实测 pl.svc 传专辑 id 返回空
     * musiclist，web 的 albumInfo 没带 kw_token 直接被拒）。所以走
     * 「按专辑名搜歌 + **严格比对 ALBUM 字段**」，比对不过就丢掉 ——
     * 宁可空着，也不把同名专辑或别的歌混进来。
     */
    fun openAlbum(a: AlbumBrief) {
        albumTitle = a.name
        albumTracks = emptyList()
        albumLoading = true
        viewModelScope.launch {
            val raw = runCatching { client.search(a.name, 1, 50) }.getOrDefault(emptyList())
            albumTracks = raw.map { it.toTrack() }.filter { t ->
                AlbumMatch.matches(t.album, t.artist, a.name, a.artist)
            }
            albumLoading = false
        }
    }

    fun closeAlbum() {
        albumTitle = ""
        albumTracks = emptyList()
    }

    fun resetSearch() {
        searchKeyword = ""
        searchResults = emptyList()
        searchError = null
        // 四个页签的缓存一起清，否则重进搜索页会看到上次的残留
        searchTab = SearchTab.SONG
        artistResults = emptyList()
        albumResults = emptyList()
        playlistResults = emptyList()
        clearSuggestions()
    }

    // ---------------- 收藏的歌单 ----------------

    /** 收藏的外部歌单（酷我 / 网易云），最近收藏在最前 */
    var favoritePlaylists by mutableStateOf<List<PlaylistBrief>>(emptyList())
        private set

    /** 已收藏歌单的 `source:id` 集合 —— 首页卡片角标 / 详情页按钮直接用，不必逐个 suspend 查询 */
    var favoritePlaylistKeys by mutableStateOf<Set<String>>(emptySet())
        private set

    fun playlistKey(p: PlaylistBrief): String = "${p.source}:${p.id}"

    fun isPlaylistFavorite(p: PlaylistBrief): Boolean = playlistKey(p) in favoritePlaylistKeys

    /** 收藏 / 取消收藏歌单，立即回写 UI 状态（不等 Flow 回来，否则点完卡片角标要延迟半秒才变） */
    fun togglePlaylistFavorite(p: PlaylistBrief) {
        val key = playlistKey(p)
        val wasFav = key in favoritePlaylistKeys
        // 乐观更新：先改本地，再落盘；失败时 Flow 会把真实状态刷回来
        favoritePlaylistKeys = if (wasFav) favoritePlaylistKeys - key else favoritePlaylistKeys + key
        favoritePlaylists = if (wasFav) {
            favoritePlaylists.filterNot { playlistKey(it) == key }
        } else {
            listOf(p) + favoritePlaylists.filterNot { playlistKey(it) == key }
        }
        viewModelScope.launch {
            runCatching { playlistFavStore.toggle(p) }
                .onFailure { DiagLog.log("PLAYLIST_FAV", "收藏歌单失败：${it.message}") }
        }
    }

    fun removeFavoritePlaylist(p: PlaylistBrief) {
        val key = playlistKey(p)
        favoritePlaylistKeys = favoritePlaylistKeys - key
        favoritePlaylists = favoritePlaylists.filterNot { playlistKey(it) == key }
        viewModelScope.launch { runCatching { playlistFavStore.remove(p.source, p.id) } }
    }

    // ---------------- 自建歌单 ----------------

    /** 用户建的歌单（存完整曲目 JSON） */
    var userPlaylists by mutableStateOf<List<UserPlaylist>>(emptyList())
        private set

    fun createPlaylist(name: String, onCreated: ((String) -> Unit)? = null) {
        viewModelScope.launch {
            // 新建失败必须留痕：DataStore 写不进去时 UI 会静默停在 0 个，
            // 没有这条日志就只能看到「点了没反应」。
            runCatching { playlistStore.create(name) }
                .onFailure { DiagLog.log("PLAYLIST", "新建歌单失败：${it.message}") }
                .getOrNull()?.let { onCreated?.invoke(it) }
        }
    }

    fun renamePlaylist(id: String, name: String) {
        viewModelScope.launch { runCatching { playlistStore.rename(id, name) } }
    }

    fun deletePlaylist(id: String) {
        viewModelScope.launch { runCatching { playlistStore.delete(id) } }
    }

    /** 加一首进歌单（已在该歌单里就置顶，不重复） */
    fun addToPlaylist(id: String, track: Track) {
        viewModelScope.launch { runCatching { playlistStore.addTrack(id, track) } }
    }

    /** 整个列表加进歌单（TrackListScreen 的「全部加入歌单」） */
    fun addAllToPlaylist(id: String, tracks: List<Track>) {
        viewModelScope.launch { runCatching { playlistStore.addTracks(id, tracks) } }
    }

    fun removeFromPlaylist(id: String, trackId: String) {
        viewModelScope.launch { runCatching { playlistStore.removeTrack(id, trackId) } }
    }

    // ---------------- AI 导赏 ----------------
    //
    // 走用户自己的 OpenAI 兼容接口（默认 DeepSeek）。
    // 不做「内置 key」：那是拿我们的钱替所有人付账，而且 key 泄漏就废了。

    private val aiService = AiService()
    /**
     * trackId -> 导赏全文。
     *
     * 内存 + 磁盘两层：内存管本次进程内的来回切歌，磁盘管杀进程重进 ——
     * 导赏是按 token 计费的，用户切走再回来不该重付一次。
     * 落盘在 [GuideStore]，只在**流正常结束**或**用户主动停止**时写，
     * 请求失败（网络/额度）不缓存，否则重试会直接拿到半截空文。
     */
    private val aiCache = HashMap<String, String>()
    private val aiStore by lazy { com.kmusic.apkhd.data.GuideStore(getApplication()) }

    var aiEndpoint by mutableStateOf("https://api.deepseek.com/v1")
        private set
    var aiApiKey by mutableStateOf("")
        private set
    var aiModel by mutableStateOf("deepseek-chat")
        private set

    /** 配了 key 才在界面露出入口 —— 没配就点的话只能看到报错，不如不给按钮 */
    val aiConfigured: Boolean get() = aiApiKey.isNotBlank()

    var aiText by mutableStateOf("")
        private set
    var aiLoading by mutableStateOf(false)
        private set
    var aiError by mutableStateOf<String?>(null)
        private set

    /**
     * 当前这篇导赏是否**已经写完**。
     *
     * 用户中途点「停止」或关掉面板时也是 [aiText] 非空、[aiLoading] 为 false，
     * 光看这两个状态分不出「写完了」和「只写了一半」。界面据此决定
     * 显示「已生成」还是「已停止（可继续）」。
     */
    var aiComplete by mutableStateOf(false)
        private set

    private var aiJob: Job? = null
    /** 当前 [aiText] 属于哪首歌 —— 切歌那一刻 playbackState.track 已经是新歌了，不能拿它当归属 */
    private var aiTrackId: String? = null
    /** 上一次触发过导赏清零的曲目 id，避免同一首重播时反复清空 */
    private var lastAiTrackId: String? = null

    fun changeAiEndpoint(v: String) { aiEndpoint = v; viewModelScope.launch { settings.setAiEndpoint(v) } }
    fun changeAiApiKey(v: String) { aiApiKey = v; viewModelScope.launch { settings.setAiApiKey(v) } }
    fun changeAiModel(v: String) { aiModel = v; viewModelScope.launch { settings.setAiModel(v) } }

    /**
     * 请求当前曲目的 AI 导赏。
     *
     * 同一首播过就不再花钱：结果按 trackId 缓存在内存里，切回来直接给。
     * 切歌时旧请求会被取消，避免「A 歌的分析跳到 B 歌上」。
     */
    fun requestAiGuide() {
        val t = playbackState.track ?: return
        aiJob?.cancel()
        aiJob = viewModelScope.launch { runAiGuide(t) }
    }

    /**
     * 打开详情面板时调一次：把已经生成过的导赏读出来，**不发起任何网络请求**。
     *
     * 不这么做的话，用户生成完、关掉面板、再打开，看到的是空态和「生成导赏」按钮 ——
     * 他会以为内容丢了，而且不敢再点（怕又扣一次钱）。
     */
    fun loadCachedAiGuide() {
        val t = playbackState.track ?: return
        if (aiText.isNotBlank() && aiTrackId == t.id) return   // 已在屏上
        if (aiLoading) return                                   // 正在生成，别抢
        viewModelScope.launch {
            val cached = aiCache[t.id] ?: aiStore.read(t.id)?.also { aiCache[t.id] = it }
            if (cached == null) return@launch
            // 期间可能已经切歌/开始新生成，落盘读是挂起的，回来要二次确认
            if (playbackState.track?.id != t.id || aiLoading) return@launch
            aiText = cached
            aiError = null
            aiComplete = aiStore.isComplete(t.id)
            aiTrackId = t.id
        }
    }

    private suspend fun runAiGuide(t: Track) {
        // 先看内存，再看磁盘（磁盘命中顺手回填内存）——
        // 同一首歌重复点「生成」不该二次计费。
        //
        // 但**只有写完过的那篇才算命中**：半篇（用户上次点过停止）如果也短路返回，
        // 点「继续生成」就会一直卡在原地毫无反应 —— 那比丢内容更让人困惑。
        val cached = aiCache[t.id] ?: aiStore.read(t.id)?.also { aiCache[t.id] = it }
        if (cached != null && aiStore.isComplete(t.id)) {
            aiText = cached
            aiError = null
            aiLoading = false
            aiComplete = true
            aiTrackId = t.id
            return
        }
        // 注意：这里**不能**再 cancel aiJob —— runAiGuide 本身就跑在 aiJob 里，
        // 取消它等于取消自己：请求还没发出就被 CancellationException 打断，
        // 界面表现为「点了生成完全没反应」。上一次请求的取消在 requestAiGuide 里做。
        aiText = ""
        aiError = null
        aiComplete = false
        aiLoading = true
        aiTrackId = t.id
        try {
            val lyricText = lyrics?.joinToString("\n") { it.text }.orEmpty()
            aiService.analyze(
                AiService.Config(aiEndpoint, aiApiKey, aiModel),
                t.name, t.artist, lyricText
            ).collect { delta -> aiText += delta }
            aiComplete = true
            if (aiText.isNotBlank()) {
                aiCache[t.id] = aiText
                aiStore.write(t.id, aiText)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 停止/关面板：**已经生成的部分要保住**，否则用户花了 token 却什么都没留下。
            // 存成「未完成」，下次打开能直接续读，而不是从头再买一遍。
            savePartialAi()
            throw e
        } catch (e: Exception) {
            aiError = e.message ?: "请求失败"
            // 失败不缓存：半截或空内容存下来，重试会直接命中缓存拿到坏结果
        } finally {
            aiLoading = false
        }
    }

    /**
     * 用户主动停止生成。
     *
     * 和切歌时的取消不同：这里要**保留**已经生成的内容（用户看得见它，突然清空像丢数据）。
     * 真正的清空发生在切到别的歌时（见 [onTrackChanged] 附近的 aiText = ""）。
     */
    fun cancelAiGuide() {
        aiJob?.cancel()
        aiJob = null
        aiLoading = false
        aiComplete = false
        savePartialAi()
    }

    /**
     * 把当前已生成的部分存到**它所属的那首歌**名下（不是当前播放的歌）。
     *
     * 同步进内存、异步落盘：调用点在主线程（点停止、切歌），DataStore 写要几十毫秒，
     * 不能卡住 UI。先取快照再 launch，避免协程跑起来时 [aiText] 已经被清空。
     */
    private fun savePartialAi() {
        val id = aiTrackId ?: return
        val partial = aiText.trim()
        if (partial.isEmpty()) return
        aiCache[id] = partial
        viewModelScope.launch { aiStore.write(id, partial, complete = false) }
    }

    /**
     * 切歌：先把上一首已生成的部分存到**它自己名下**，再清屏。
     *
     * 用户在 A 歌看到一半换到 B，A 的半篇不该白费 —— 回到 A 时还能接着读。
     * 这里必须用 [aiTrackId]（A）而不是 playbackState.track（已经是 B 了）。
     */
    private fun resetAiForTrackChange() {
        savePartialAi()
        aiJob?.cancel()
        aiJob = null
        aiLoading = false
        aiText = ""
        aiError = null
        aiComplete = false
        aiTrackId = null
    }

    // ---------------- AI 建歌单 ----------------

    var aiGenLoading by mutableStateOf(false)
        private set
    var aiGenError by mutableStateOf<String?>(null)
        private set

    /**
     * 刚由 AI 建好的歌单 id。
     *
     * 生成的歌单会自动出现在「创建的」列表里，但用户不会知道是哪一条 ——
     * 界面读到这个 id 后自动滚到它 / 提示一句，然后调 [consumeAiCreated] 清掉，
     * 避免每次进页面都重复提示。
     */
    var aiCreatedId by mutableStateOf<String?>(null)
        private set

    fun consumeAiCreated() { aiCreatedId = null }

    /**
     * AI 建歌单：**先真实搜索拿候选，再让模型从候选里挑序号**。
     *
     * 不能让模型直接「想」歌名 —— 它什么歌名都能编得出来，而我们要的是真能播的曲目。
     * 模型只负责挑，不负责造；挑出来的序号还要再跟候选池对一遍，对不上就丢。
     */
    fun aiGeneratePlaylist(description: String) {
        val desc = description.trim()
        if (desc.isEmpty() || aiGenLoading) return
        if (!aiConfigured) {
            aiGenError = "先去「我的 → AI 导赏」填入 API Key"
            return
        }
        aiGenLoading = true
        aiGenError = null
        viewModelScope.launch {
            try {
                // 1) 先让模型给出「这个歌单该有哪些歌」
                //
                // 以前的写法是「拿描述去曲库搜，再从搜到的结果里挑」——
                // 那样出来的其实是**搜索相关性**，不是歌单。
                // 实测「适合开车听的粤语老歌」整句搜索返回 0 首，
                // 改成关键词也只能搜到一堆 DJ 版和翻唱。
                // 想让歌单像样，得先知道该有哪些歌，再去验证能不能播。
                // 用户在话里点了几首就出几首（「来20首」「10 首」），
                // 没写才用默认值。之前硬编码 12，人家要 20 也只给 12。
                val want = wantedCount(desc)
                val sb = StringBuilder()
                aiService.proposeTracks(
                    AiService.Config(aiEndpoint, aiApiKey, aiModel),
                    desc,
                    want
                ).collect { sb.append(it) }

                val picks = sb.toString().lines().mapNotNull { raw ->
                    val line = raw.trim().removePrefix("-").trim()
                    val sep = line.indexOf('|')
                    if (sep <= 0) return@mapNotNull null
                    val n = line.take(sep).trim().trim('《', '》', '"', '“', '”')
                    val a = line.substring(sep + 1).trim().trim('《', '》')
                    if (n.isEmpty()) null else n to a
                }
                    .distinctBy { it.first.lowercase() }
                    .take(want)

                if (picks.isEmpty()) {
                    aiGenError = "AI 没给出可用曲目，换个说法试试"
                    return@launch
                }

                // 2) 逐首去酷我验证。搜不到、或歌名/歌手对不上的一律丢弃 ——
                //    模型给的是「应该有哪些歌」，能不能播由曲库说了算。
                val found = mutableListOf<Track>()
                for (batch in picks.chunked(4)) {
                    val r = batch.map { (n, a) -> async { findTrackByName(n, a) } }.awaitAll()
                    found += r.filterNotNull()
                }
                if (found.size < 3) {
                    // 说清楚卡在哪一步：是模型给的数量少，还是曲库没收录。
                    // 老文案「只有 0 首能播」用户无从下手 —— 既像 AI 坏了，又像网络坏了。
                    aiGenError = if (found.isEmpty()) {
                        "曲库没搜到 AI 推荐的这 ${picks.size} 首（多是版本/译名对不上），换个说法再试"
                    } else {
                        "AI 给了 ${picks.size} 首，曲库只收录了 ${found.size} 首（至少要 3 首才建）"
                    }
                    return@launch
                }

                // 3) 落到现有歌单体系（userPlaylists 由 store 的 Flow 自动刷新）
                val newId = playlistStore.create(desc.take(18))
                playlistStore.addTracks(newId, found)
                // 让界面知道「新歌单是这条」，而不是让用户自己在列表里找
                aiCreatedId = newId
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                aiGenError = friendlyAiError(e.message)
            } finally {
                aiGenLoading = false
            }
        }
    }

    /**
     * 按「歌名 + 歌手」在酷我找一首真能播的。
     *
     * 复用跨源回退用的 TrackMatcher（歌名归一化 + 歌手 token 比对），
     * 宁可返回 null 也不肯凑一首同名不同人的歌 —— 歌单里串歌比少一首糟糕得多。
     */
    /**
     * 把 HTTP 错误翻成用户能看懂、且**能据此行动**的一句话。
     *
     * 原始异常长这样（DeepSeek 401）：
     * `HTTP 401: {"error":{"message":"Authentication Fails, Your api key: ****0000 is invalid
     *  (request_id: 138d763c-...)"}}`
     * 整段塞进卡片会把两行布局撑爆，而且 90% 是 request_id 这种用户不需要的东西。
     */
    private fun friendlyAiError(raw: String?): String {
        val m = raw.orEmpty()
        return when {
            m.isBlank() -> "生成失败：未知错误"
            m.contains("401") || m.contains("Authentication", true) || m.contains("invalid", true) ->
                "API Key 无效 —— 去「我的 → AI 导赏」改成正确的"
            m.contains("402") || m.contains("insufficient", true) || m.contains("balance", true) ->
                "账户余额不足，去服务商充值后再试"
            m.contains("429") || m.contains("rate limit", true) ->
                "请求太频繁，等 1 分钟再试"
            m.contains("timeout", true) || m.contains("timed out", true) ->
                "请求超时，检查网络或换更快的模型"
            m.contains("Unable to resolve host", true) || m.contains("UnknownHost", true) ->
                "连不上接口地址，检查网络或「接口地址」是否写错"
            m.contains("404") -> "接口地址找不到（404），检查「接口地址」"
            m.contains("500") || m.contains("502") || m.contains("503") ->
                "服务端暂时不可用（${m.substringBefore(':').take(20)}），稍后再试"
            else -> "生成失败：" + m.substringAfter("HTTP ", "").ifBlank { m }
                .replace(Regex("[\\n\\r]+"), " ")
                .take(60)
        }
    }

    /**
     * 从用户描述里读「要几首」。
     *
     * 「来20首」「给我 10 首」「二十首」都认。没写就默认 12。
     * 上限 40：每首都要真去曲库验一遍，再多就等太久了。
     */
    private fun wantedCount(desc: String): Int {
        Regex("(\\d{1,2})\\s*首").find(desc)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
            return it.coerceIn(3, 40)
        }
        val cn = mapOf(
            "十" to 10, "二十" to 20, "三十" to 30, "四十" to 40,
            "十五" to 15, "二十五" to 25
        )
        val m = Regex("([一二三四五六七八九十]{1,3})\\s*首").find(desc)
        val raw = m?.groupValues?.getOrNull(1)
        if (raw != null) {
            cn[raw]?.let { return it.coerceIn(3, 40) }
            val d = raw.singleOrNull()?.let { ch ->
                "一二三四五六七八九十".indexOf(ch).takeIf { it >= 0 }?.plus(1)
            }
            if (d != null) return d.coerceIn(3, 40)
        }
        return 12
    }

    /**
     * 按「歌名 + 歌手」在酷我找一首真能播的。
     *
     * 复用跨源回退用的 TrackMatcher（歌名归一化 + 歌手 token 比对，含繁简互转），
     * 宁可返回 null 也不肯凑一首同名不同人的歌 —— 歌单里串歌比少一首糟糕得多。
     *
     * 以前用 `runCatching{}.getOrDefault(emptyList())` 把异常全吞了：曲库挂了、被限流、
     * DNS 失败，界面一律显示「0 首能播」，把网络问题伪装成模型问题。现在照旧不抛出
     * （单首失败不该毁掉整个歌单），但**一定打日志**，方便下次直接从 logcat 定位。
     */
    private suspend fun findTrackByName(name: String, artist: String): Track? {
        val kw = "$name $artist".trim()
        val hits = try {
            client.search(kw, size = 5)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagLog.log("AI_PLAYLIST", "搜索失败「$kw」 ${e.javaClass.simpleName}: ${e.message}")
            return null
        }
        if (hits.isEmpty()) {
            DiagLog.log("AI_PLAYLIST", "搜索 0 条「$kw」")
            return null
        }
        val hit = hits.firstOrNull { r ->
            TrackMatcher.nameMatches(name, r.name ?: "") &&
                (artist.isBlank() || TrackMatcher.artistMatches(artist, r.artist ?: ""))
        }
        if (hit == null) {
            val sim = com.kmusic.apkhd.net.T2S.convert(name)
            DiagLog.log(
                "AI_PLAYLIST",
                "曲库没收录「$kw」" +
                    (if (sim != name) "（原文含繁体，简体为「$sim」）" else "") +
                    "，前 3 候选：" + hits.take(3).joinToString(" / ") {
                        "${it.name ?: "?"}·${it.artist ?: "?"}"
                    }
            )
            return null
        }
        return hit.toTrack()
    }

    // ---------------- 歌词翻译 ----------------

    /** 行索引 → 译文。空表示还没翻 */
    var lyricTranslation by mutableStateOf<Map<Int, String>>(emptyMap())
        private set
    var translating by mutableStateOf(false)
        private set

    /**
     * 翻译当前歌词。
     *
     * 只翻一次、结果留在内存里：同一首歌反复切回来不重复花钱。
     * 切歌时由 onMediaItemTransition 统一清空。
     */
    fun translateLyrics() {
        val ls = lyrics ?: return
        if (ls.isEmpty() || translating || !aiConfigured) return
        translating = true
        viewModelScope.launch {
            try {
                val sb = StringBuilder()
                aiService.translateLyrics(
                    AiService.Config(aiEndpoint, aiApiKey, aiModel),
                    ls.map { it.text }
                ).collect { sb.append(it) }

                val out = mutableMapOf<Int, String>()
                sb.toString().lines().forEach { raw ->
                    val line = raw.trim()
                    val sep = line.indexOf('|')
                    if (sep <= 0) return@forEach
                    val n = line.take(sep).trim().trimEnd('.', '、', '．').toIntOrNull()
                        ?: return@forEach
                    val tr = line.substring(sep + 1).trim()
                    // 只接受落在歌词范围内的行号，防止模型多写/错位
                    if (n in 1..ls.size && tr.isNotEmpty()) out[n - 1] = tr
                }
                if (out.isEmpty()) {
                    aiError = "翻译没有返回有效内容"
                } else {
                    lyricTranslation = out
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                aiError = "翻译失败：${e.message ?: "未知错误"}"
            } finally {
                translating = false
            }
        }
    }

    // ---------------- AI 查询改写 ----------------

    var aiRewriting by mutableStateOf(false)
        private set
    /** 改写结果提示，显示在搜索页（「AI 理解为：…」/ 失败原因） */
    var aiRewriteHint by mutableStateOf<String?>(null)
        private set

    /**
     * 把口语化的需求改写成检索词再搜。
     *
     * 只在用户主动点 AI 时才走模型：每次搜索都过一遍既慢又烧钱，
     * 而且大部分搜索本来就是「歌名 + 歌手」，不劳模型。
     */
    fun aiRewriteAndSearch() {
        val raw = searchKeyword.trim()
        if (raw.isEmpty() || aiRewriting || !aiConfigured) return
        aiRewriting = true
        aiRewriteHint = null
        viewModelScope.launch {
            try {
                val sb = StringBuilder()
                aiService.rewriteQuery(AiService.Config(aiEndpoint, aiApiKey, aiModel), raw)
                    .collect { sb.append(it) }
                val kw = sb.toString()
                    .replace(Regex("[\r\n]+"), " ")
                    .trim()
                    .trim('"', '“', '”', '「', '」', '，', ',', '。', '.')
                    .replace(Regex("\\s+"), " ")
                    .take(60)
                if (kw.isBlank()) {
                    aiRewriteHint = "AI 没给出有效检索词，已按原词搜索"
                    search()
                } else {
                    aiRewriteHint = if (kw == raw) null else "AI 理解为：$kw"
                    search(kw)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                aiRewriteHint = "改写失败：${e.message ?: "未知错误"}"
            } finally {
                aiRewriting = false
            }
        }
    }

    // ---------------- 音频信息 ----------------

    /**
     * 当前播放链的真实参数。
     *
     * 只报能核实的东西：源格式、源采样率、声道、码率、设备输出采样率、缓冲余量。
     * **不报位深** —— Media3 的 Format 不暴露 bitDepth，那是解码器内部的，
     * 在线流更无从得知，写死一个「24bit」就是骗人。
     */
    fun readAudioInfo(): AudioInfo? {
        val c = controller ?: return null
        var fmt: Format? = null
        runCatching {
            c.currentTracks.groups.forEach { g ->
                if (g.type == C.TRACK_TYPE_AUDIO && g.isSelected) fmt = g.getTrackFormat(0)
            }
        }
        val f = fmt ?: return null

        val am = getApplication<android.app.Application>()
            .getSystemService(AudioManager::class.java)
        val runtime = AudioRuntimeState.snapshot
        val outRate = runtime.applicationOutput?.takeIf { it.isPcm }?.sampleRate
        val outFrames = am?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()

        // 「请求的档位」与「实际拿到的」分开显示：
        // 用户点的是「臻品母带」，源站给的可能是 320k —— 不告诉他，等于骗他。
        val cur = playbackState.track
        val reqLabel = cur?.qualityLabel?.takeIf { it.isNotBlank() }
        val actualKbps = f.bitrate.takeIf { it > 0 }?.div(1000)
        // 标了「母带」实际不到 500k、或标了「无损」实际不到 400k，就是源站在偷偷降档。
        // 现在按 [Track.qualityRank] 判更准：声称无损/母带、实际 rank 不到 FLAC 就是降档。
        val downgraded = reqLabel != null && actualKbps != null && (
            (cur?.qualityRank ?: 0) >= KuwoClient.RANK_FLAC && actualKbps < 400 ||
                (reqLabel.contains("母带") && actualKbps < 500) ||
                (reqLabel.contains("无损") && actualKbps < 400)
            )
        val codec = f.codecs?.uppercase()
            ?: f.sampleMimeType?.substringAfter("/")?.uppercase()
            ?: "未知"
        // 码率 × 时长估体积：详情面板要能回答「这一首占多少流量」。
        // 母带 5.4Mbps × 3:30 ≈ 140MB，这个数字比「臻品母带」四个字有说服力。
        // ExoPlayer 读不到 mflac 容器里的码率（它只认 fmtp/bitrate 元数据），
        // 所以优先用播放器报的；报不出来就用「源站实测码率」（见 Track.sourceKbps）。
        val durMs = playbackState.durationMs.takeIf { it > 0 } ?: cur?.durationMs ?: 0L
        val effectiveKbps = actualKbps ?: cur?.sourceKbps?.takeIf { it > 0 }
        val estBytes = cur?.sourceBytes?.takeIf { it > 0 }
            ?: if (effectiveKbps != null && effectiveKbps > 0 && durMs > 0) {
                effectiveKbps.toLong() * 1000L / 8L * durMs / 1000L
            } else 0L
        return AudioInfo(
            codec = codec,
            requestedLabel = reqLabel,
            downgraded = downgraded,
            sampleRate = f.sampleRate.takeIf { it > 0 },
            channels = f.channelCount.takeIf { it > 0 },
            bitrateKbps = effectiveKbps,
            bitrateFromSource = actualKbps == null && effectiveKbps != null,
            outSampleRate = outRate,
            outFramesPerBuffer = outFrames,
            outputDescription = runtime.applicationOutput?.let {
                if (it.isPcm) "PCM ${it.sampleRate / 1000f} kHz · ${it.channelCount}ch · ${it.encoding}" else "编码输出（非PCM）"
            },
            outputDevice = runtime.route?.let { it.deviceTypeName + (it.displayName?.let { n -> " · $n" } ?: "") },
            offloadStatus = runtime.offloadStatus,
            usbOutputStatus = usbOutput.statusText + " · " + usbOutput.reasonText,
            interruptionStatus = runtime.interruptionStatus,
            truePeakStatus = if (EqProcessor.instance.currentTruePeakActive) "已生效 · 延迟${EqProcessor.instance.currentTruePeakLatencyFrames}帧（有限插值估计）" else "关闭 / 当前旁路",
            pureAudioMode = runtime.pureAudioModeRequested || runtime.usbHighFidelityRequested,
            bufferedMs = (c.bufferedPosition - c.currentPosition).coerceAtLeast(0),
            sourceLabel = when (cur?.source) {
                "local" -> "本地文件"
                "navidrome" -> "Navidrome 直连"
                "netease" -> "网易云（GD 无损通道）"
                "migu" -> "咪咕 128k（官方）"
                "kuwo" -> if ((cur.qualityRank) >= KuwoClient.RANK_MASTER) "酷我（母带通道）" else "酷我在线流"
                else -> "在线流"
            },
            qualityRank = cur?.qualityRank ?: 0,
            estimatedBytes = estBytes
        )
    }

    // ---------------- 响度归一 ----------------

    /** off / track / album */
    var loudnessMode by mutableStateOf("track")
        private set

    /** 当前曲目的增益（dB），界面可显示；0 表示没归一 */
    var loudnessGainDb by mutableStateOf(0f)
        private set
    var loudnessSource by mutableStateOf("none")
        private set

    fun changeLoudnessMode(mode: String) {
        loudnessMode = if (mode in setOf("off", "track", "album")) mode else "track"
        viewModelScope.launch { settings.setLoudnessMode(loudnessMode) }
        // 立刻对当前曲目生效，不用等下一首
        playbackState.track?.let { applyLoudness(it) }
    }

    /**
     * 给当前曲目算增益并施加到播放器音量。
     *
     * 为什么用 player.volume 而不是 AudioEffect：
     * 音量是线性增益，不影响频响、不额外吃 CPU，也不需要 audio session；
     * LoudnessEnhancer 只能往上加、不能减，做归一方向是错的。
     */
    private fun applyLoudness(track: Track) {
        loudnessJob?.cancel()
        val epoch = ++loudnessEpoch
        val mode = loudnessMode
        if (mode == "off" || audioRuntime.usbHighFidelityRequested) {
            loudnessGainDb = 0f
            loudnessSource = "off"
            runCatching { controller?.volume = 1f }
            return
        }
        if (track.source != "local") {
            loudnessGainDb = 0f
            loudnessSource = "none"
            runCatching { controller?.volume = 1f }
            return
        }
        loudnessJob = viewModelScope.launch {
            val uri = localLib.trackUri(track)
            val applied = LoudnessNormalizer.resolve(
                getApplication(), track, uri, mode, loudnessStore
            )
            // 用户可能已经切歌了 —— 只对仍是当前曲目的结果生效
            if (playbackState.track?.id != track.id || epoch != loudnessEpoch || mode != loudnessMode) return@launch
            loudnessGainDb = applied.gainDb
            loudnessSource = applied.source + ":" + applied.basis
            val c = controller ?: return@launch
            val initialVolume = c.volume
            for (step in 1..10) {
                if (playbackState.track?.id != track.id || epoch != loudnessEpoch || mode != loudnessMode) return@launch
                c.volume = initialVolume + (applied.volume - initialVolume) * step / 10f
                delay(10)
            }
            DiagLog.log(
                "LOUD",
                "归一 '${track.name}' mode=$loudnessMode gain=${"%.2f".format(applied.gainDb)}dB " +
                    "source=${applied.source} lufs=${applied.lufs}"
            )
        }
    }

    // ---------------- 音质检测 ----------------

    /** 频谱检测结果（仅本地曲目，用户主动触发） */
    var probeResult by mutableStateOf<SpectrumProbe.Result?>(null)
        private set
    var probeRunning by mutableStateOf(false)
        private set
    var probeError by mutableStateOf<String?>(null)
        private set

    fun probeCurrentTrack() {
        val t = playbackState.track ?: return
        if (probeRunning) return
        if (t.source != "local") {
            probeError = "在线流拿不到解码后的音频，只能检测本地文件"
            return
        }
        probeRunning = true
        probeError = null
        probeResult = null
        viewModelScope.launch {
            val uri = localLib.trackUri(t)
            val r = SpectrumProbe.probe(getApplication(), uri, t.durationMs)
            probeRunning = false
            if (r == null) {
                probeError = "这个文件解不开（可能是 DSD/APE 等不支持的格式）"
            } else {
                probeResult = r
                DiagLog.log("PROBE", "'${t.name}' 截止 ${r.cutoffHz}Hz → ${r.verdict.label}")
            }
        }
    }

    fun clearProbe() {
        probeResult = null
        probeError = null
    }

    // ---------------- 均衡器 ----------------

    var eqEnabled by mutableStateOf(false)
        private set
    var eqPreset by mutableStateOf(0)
        private set

    /**
     * 均衡器预设名。
     *
     * v2.3.5 起是**内置十段表**（12 套），不读设备 —— 均衡器已经不走系统音效了，
     * 自然也没有「芯片原厂预设」这回事。列表一开机就有内容，不用等起播。
     */
    var eqPresets by mutableStateOf(FxChain.presetNames())
        private set

    /** 本进程是否已做过「设备预设 → 内置预设」的迁移（只做一次） */
    private var presetMigrated = false

    /**
     * 均衡器是否可用。
     *
     * v2.3.5 起恒为 true：均衡器跑在自己的 PCM 管线上，不需要向系统申请任何音效资源，
     * 于是「本机不支持」「池位被占」两种状态一起消失了。字段留着是因为界面签名还在用，
     * 将来真有平台限制时也仍然从同一个地方读。
     */
    var eqAvailable by mutableStateOf(true)
        private set

    /** 各频段增益（mB），下标即 band —— 恒为十段 */
    var eqBands by mutableStateOf<List<Int>>(emptyList())
        private set

    /** 各频段中心频率（Hz） */
    var eqFreqs by mutableStateOf<List<Int>>(emptyList())
        private set

    /** 可调范围，[最小 mB, 最大 mB] */
    var eqRange by mutableStateOf(intArrayOf(EqProcessor.LEVEL_MIN, EqProcessor.LEVEL_MAX))
        private set

    /** 是否处在自定义曲线（用户拖过圆点） */
    var eqCustom by mutableStateOf(false)
        private set

    /** 刷新频段与曲线 */
    fun refreshEqBands() {
        eqFreqs = FxChain.bandFreqs().toList()
        eqBands = FxChain.bandLevels().toList()
        eqRange = FxChain.bandRange()
        eqCustom = FxChain.customBands
    }

    /** 兼容旧调用点：预设表不再依赖「挂载」，刷新频段即可 */
    fun refreshEqPresets() {
        eqPresets = FxChain.presetNames()
        refreshEqBands()
    }

    fun changeEqEnabled(on: Boolean) {
        eqEnabled = on
        FxChain.configure(on, eqPreset)
        refreshEqBands()
        if (!persistActiveProfile()) viewModelScope.launch { settings.setEqEnabled(on) }
    }

    fun changeEqPreset(index: Int) {
        eqPreset = index
        eqCustom = false
        // 选预设隐含「我要用均衡器」：关着选预设没有任何听感变化，用户会以为坏了。
        eqEnabled = true
        FxChain.usePresetMode(index)
        refreshEqBands()
        if (persistActiveProfile()) return
        viewModelScope.launch {
            settings.setEqPreset(index)
            settings.setEqEnabled(true)
        }
    }

    /**
     * 拖动某一段频段。
     *
     * 会顺手打开均衡器并切到自定义 —— 用户拖了却听不到变化，是最容易被当成「坏了」的情况。
     */
    fun changeEqBand(band: Int, levelMiliBel: Int) {
        // 频段拖动是唯一没法靠 uiautomator 读数的地方（自绘圆点没有语义节点，
        // 且这个页面 dump 会因 U+0 崩），打一行日志当验收锚点。
        DiagLog.log("EQ", "频段 $band（${eqFreqs.getOrNull(band) ?: "?"}Hz）→ ${levelMiliBel / 100} dB")
        FxChain.setBandLevel(band, levelMiliBel)
        eqEnabled = true
        eqCustom = true
        eqBands = FxChain.bandLevels().toList()
        persistActiveProfile()
    }

    // ---------------- 用户自定义曲线 ----------------
    //
    // 只存「十段增益 + 名字」。存整个音效 profile 会让「保存曲线」变成
    // 「顺手把低音也存进去」，语义不彻底 —— 用户点的是保存这条曲线。

    /** 用户存的曲线（上限 20 条，SharedPreferences，不占系统资源） */
    var userEqPresets by mutableStateOf<List<UserEqPresets.Preset>>(emptyList())
        private set

    private fun reloadUserPresets() {
        userEqPresets = UserEqPresets.load(getApplication())
    }

    /** 把当前十段曲线存成一条命名预设 */
    fun saveCurrentEqCurve(name: String) {
        val gains = FxChain.bandLevels().toList()
        if (gains.isEmpty()) return
        userEqPresets = UserEqPresets.savePreset(getApplication(), name, gains)
        DiagLog.log("EQ", "保存曲线「$name」= ${gains.joinToString(",")}")
    }

    fun deleteEqCurve(name: String) {
        userEqPresets = UserEqPresets.delete(getApplication(), name)
    }

    /**
     * 套用一条用户曲线。
     *
     * 走 [FxChain.applyCurve]：十段全写进 manualLevels 并标记 custom ——
     * 曲线是用户自己的，不该再被任何内置预设覆盖。
     */
    fun applyEqCurve(preset: UserEqPresets.Preset) {
        FxChain.applyCurve(preset.gains.toIntArray())
        eqEnabled = true
        eqCustom = true
        eqBands = FxChain.bandLevels().toList()
        eqPreset = FxChain.presetIndex
        viewModelScope.launch { settings.setEqEnabled(true) }
    }

    // ---------------- 睡眠定时 ----------------

    private var sleepJob: kotlinx.coroutines.Job? = null

    /** 剩余秒数，0 = 未开启 */
    var sleepRemainingSec by mutableStateOf(0)
        private set
    /** 设定的总时长（分钟），0 = 未开启 */
    var sleepTotalMin by mutableStateOf(0)
        private set

    /**
     * 睡眠定时。
     *
     * 注意：计时跑在 ViewModel 协程里，App 进程被系统杀掉就失效 ——
     * 要跨进程可靠得把定时写进播放服务。目前这个实现对「听着睡着」够用，
     * 但不做「杀了 App 还能定时」的假承诺。
     */
    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        sleepJob = null
        if (minutes <= 0) {
            sleepTotalMin = 0
            sleepRemainingSec = 0
            return
        }
        sleepTotalMin = minutes
        sleepRemainingSec = minutes * 60
        sleepJob = viewModelScope.launch {
            var left = minutes * 60
            while (left > 0 && isActive) {
                delay(1000)
                left--
                sleepRemainingSec = left
            }
            if (isActive) {
                controller?.pause()
                playbackState = playbackState.copy(isPlaying = false)
                sleepTotalMin = 0
                sleepRemainingSec = 0
            }
        }
    }

    // ---------------- 增强音效（低音/响度） ----------------
    //
    // v2.3.2 删掉环绕／混响／动态范围；v2.3.5 起三项（均衡器/低音/响度）全部搬进
    // 自研的 EqProcessor，不再占用任何系统音效池位 —— 于是这一整块里
    // 「池位」「重挂」「饿死」「资源占用」的概念全部退场。

    /** 0..1000，0 = 关 */
    var fxBass by mutableStateOf(0)
        private set
    /** 0..1500 mB，0 = 关 */
    var fxLoudness by mutableStateOf(0)
        private set

    /** 省电播放（offload）：开了 PCM 直通 DSP，音效不可用 */
    var audioOutputMode by mutableStateOf("processed")
        private set
    var usbHighFidelity by mutableStateOf(false)
        private set
    var usbOutput by mutableStateOf(com.kmusic.apkhd.playback.UsbOutputSnapshot())
        private set
    var callAutoResume by mutableStateOf(true)
        private set
    var truePeakEnabled by mutableStateOf(false)
        private set
    var truePeakCeilingDb by mutableStateOf(-1f)
        private set
    var deviceProfiles by mutableStateOf<List<com.kmusic.apkhd.data.DeviceEqProfile>>(emptyList())
        private set
    var deviceProfileAuto by mutableStateOf(false)
        private set
    var deviceProfileRuntime by mutableStateOf(com.kmusic.apkhd.playback.DeviceEqProfiles.ProfileRuntime())
        private set
    private val deviceProfileStore = com.kmusic.apkhd.playback.DeviceEqProfiles.store(getApplication())

    fun changeAudioOutputMode(mode: String) {
        audioOutputMode = mode
        audioOffload = mode == "offload"
        pureAudioMode = mode == "pure"
        usbHighFidelity = mode == "usb"
        viewModelScope.launch {
            settings.setAudioOutputMode(mode)
            if (mode != "usb") playbackState.track?.let { applyLoudness(it) }
        }
        toast("正在切换播放模式，将保留当前歌曲和进度")
    }
    fun exitTvPlayback() {
        if (!com.kmusic.apkhd.ui.tv.TvEnv.isTv(getApplication())) return
        val current = queue.getOrNull(queueIndex)
        val position = controller?.currentPosition?.takeIf { it > 0 } ?: restoredPositionMs.takeIf { it >= 0 } ?: playbackState.positionMs
        AudioRuntimeState.recordPauseIntent(com.kmusic.apkhd.playback.PauseCause.SERVICE_STOPPED)
        cancelPendingPlayback()
        persistJob?.cancel()
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(600) {
                if (current != null && queue.isNotEmpty()) {
                    snapshotStore.saveQueue(PlaybackSnapshot(queue, current.id, queueIndex, System.currentTimeMillis()))
                    snapshotStore.savePosition(position)
                }
            }
        }
        com.kmusic.apkhd.playback.PlaybackSessionExit.requestStop(getApplication())
    }
    var audioInterruptionResume by mutableStateOf(true)
        private set
    fun changeAudioInterruptionResume(on: Boolean) {
        audioInterruptionResume = on
        viewModelScope.launch { settings.setAudioInterruptionResume(on) }
    }

    fun changeCallAutoResume(on: Boolean) {
        callAutoResume = on
        viewModelScope.launch { settings.setCallAutoResume(on) }
        com.kmusic.apkhd.playback.CallStateMonitor.requestRefresh()
    }
    fun changeTruePeakEnabled(on: Boolean) {
        truePeakEnabled = on
        FxChain.configureTruePeak(on, truePeakCeilingDb)
        val profile = deviceProfileRuntime.active
        viewModelScope.launch {
            if (profile != null) deviceProfileStore.updateExisting(profile.id) { it.copy(truePeakEnabled = on) } else settings.setTruePeakEnabled(on)
        }
    }
    fun changeTruePeakCeiling(value: Float) {
        truePeakCeilingDb = value.coerceIn(-6f, -0.5f)
        FxChain.configureTruePeak(truePeakEnabled, truePeakCeilingDb)
        val profile = deviceProfileRuntime.active
        viewModelScope.launch {
            if (profile != null) deviceProfileStore.updateExisting(profile.id) { it.copy(ceilingDbTp = truePeakCeilingDb) } else settings.setTruePeakCeilingDb(truePeakCeilingDb)
        }
    }
    fun changeDevicePreGain(value: Float) {
        FxChain.configureDevicePreGain(value)
        persistActiveProfile()
    }

    fun changeDeviceProfileAuto(on: Boolean) {
        viewModelScope.launch { runCatching { deviceProfileStore.setAutoEnabled(on) }.onFailure { toast("档案写入失败，请检查文件或重新授权") } }
    }
    fun saveDeviceProfile(name: String, bind: Boolean) {
        if (name.isBlank()) { toast("请填写档案名称"); return }
        val evidence = com.kmusic.apkhd.playback.DeviceEqProfiles.identify(getApplication(), audioRuntime.route)
        if (bind && evidence.bindingKey == null) { toast("尚未确认输出路由，不能绑定设备"); return }
        val profile = com.kmusic.apkhd.data.DeviceEqProfile(name = name.take(80), bindingKey = if(bind) evidence.bindingKey else null,
            eqEnabled = FxChain.enabled, bandsMb = FxChain.bandLevels().toList(), bass = FxChain.bassStrength,
            fxLoudness = FxChain.loudnessGain, preGainDb = FxChain.devicePreGainDb,
            truePeakEnabled = truePeakEnabled, ceilingDbTp = truePeakCeilingDb)
        viewModelScope.launch {
            runCatching { deviceProfileStore.update(profile) }
                .onSuccess { toast(if(bind && !evidence.perDeviceIdentity) "已保存类别档案，不是单设备身份" else "音效档案已保存") }
                .onFailure { toast("档案保存失败：不覆盖损坏或未知版本文件") }
        }
    }
    var usbModeAvailable by mutableStateOf(false)
        private set

    fun refreshOutputCapabilities() {
        usbModeAvailable = runCatching {
            getApplication<Application>().getSystemService(android.media.AudioManager::class.java)
                ?.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
                ?.any { com.kmusic.apkhd.playback.UsbOutputPolicy.isUsb(it.type) } == true
        }.getOrDefault(false)
    }
    fun bindDeviceProfile(id: String) {
        val evidence = com.kmusic.apkhd.playback.DeviceEqProfiles.identify(getApplication(), audioRuntime.route)
        val key = evidence.bindingKey ?: run { toast("请在播放时连接要使用的设备"); return }
        viewModelScope.launch {
            runCatching { deviceProfileStore.updateExisting(id) { it.copy(bindingKey = key) } }
                .onSuccess { toast(if(evidence.perDeviceIdentity) "已用于当前设备" else "已用于当前输出类别") }
                .onFailure { toast("方案绑定失败，原方案保留") }
        }
    }
    fun applyDeviceProfile(id: String?) {
        if (id != null && audioOutputMode != "processed") changeAudioOutputMode("processed")
        com.kmusic.apkhd.playback.DeviceEqProfiles.selectManual(id)
        if (id == null) changeDeviceProfileAuto(false)
    }
    fun deleteDeviceProfile(id: String) {
        viewModelScope.launch { runCatching { deviceProfileStore.remove(id) }.onFailure { toast("档案删除失败") } }
    }
    fun refreshDeviceIdentity() {
        com.kmusic.apkhd.playback.DeviceEqProfiles.runtime.value = deviceProfileRuntime.copy(
            bindingKey = com.kmusic.apkhd.playback.DeviceEqProfiles.identify(getApplication(), audioRuntime.route).bindingKey)
    }
    private fun persistActiveProfile(): Boolean {
        val profile = deviceProfileRuntime.active ?: return false
        val updated = profile.copy(eqEnabled = FxChain.enabled, bandsMb = FxChain.bandLevels().toList(),
            bass = FxChain.bassStrength, fxLoudness = FxChain.loudnessGain,
            preGainDb = FxChain.devicePreGainDb, truePeakEnabled = truePeakEnabled, ceilingDbTp = truePeakCeilingDb)
        viewModelScope.launch { runCatching { deviceProfileStore.updateExisting(profile.id) { it.copy(
            eqEnabled = updated.eqEnabled, bandsMb = updated.bandsMb, bass = updated.bass, fxLoudness = updated.fxLoudness,
            preGainDb = updated.preGainDb, truePeakEnabled = updated.truePeakEnabled, ceilingDbTp = updated.ceilingDbTp)
        } }.onFailure { toast("音效已修改，但档案保存失败") } }
        return true
    }

    var pureAudioMode by mutableStateOf(false)
        private set

    fun changePureAudioMode(on: Boolean) {
        pureAudioMode = on
        if (on) audioOffload = false
        viewModelScope.launch { settings.setPureAudioMode(on) }
        toast("纯净PCM设置已保存，重启播放服务后生效；不保证设备最终格式")
    }

    var bluetoothAutoResume by mutableStateOf(false)
        private set
    var audioRuntime by mutableStateOf(AudioRuntimeState.snapshot)
        private set

    fun changeBluetoothAutoResume(on: Boolean) {
        bluetoothAutoResume = on
        viewModelScope.launch { settings.setBluetoothAutoResume(on) }
    }

    var audioOffload by mutableStateOf(false)
        private set

    /** DSD 输出采样率：44100 / 88200 */
    var dsdOutRate by mutableStateOf(44100)
        private set

    /** DSD 播放时自动旁路均衡器 */
    var dsdBypassEq by mutableStateOf(true)
        private set

    /** 当前正在播放 DSD（界面据此提示「均衡器已自动旁路」） */
    var dsdPlaying by mutableStateOf(false)
        private set

    /** 当前 DSD 倍率标签（DSD64 / DSD128 / DSD256） */
    var dsdLabel by mutableStateOf<String?>(null)
        private set

    /**
     * 低音 / 响度是否可用。
     *
     * v2.3.5 起恒为 true —— 它们和均衡器共用同一条自研处理器，不向系统申请资源，
     * 也就没有「本机不支持」。字段留在签名里，省得界面到处改。
     */
    val fxBassAvailable: Boolean get() = true
    val fxLoudnessAvailable: Boolean get() = true

    /** 用户配置里开着的音效项数（0..3）—— 界面用它区分「全关」和「还没起播」 */
    var fxWantedCount by mutableStateOf(0)
        private set

    /** 处理器是否已接进 PCM 管线。0 = 还没起播（不是「不可用」） */
    var fxSampleRate by mutableStateOf(0)
        private set

    /**
     * 让给 EQ 的前级余量（dB）。
     *
     * 整条曲线的**解析峰值**，不是「十段里最大的那个」—— 相邻段在中间频率会叠加，
     * 取最大值会低估（31/62/125 全拉满时峰值比单段高 4dB 以上），
     * 余量扣少了，响度就会提前撞限幅器。
     */
    var fxHeadroomDb by mutableStateOf(0f)
        private set

    /** 把处理器报上来的运行态同步到界面（状态文案要写「48 kHz 生效中」） */
    fun refreshFxRuntime() {
        fxWantedCount = FxChain.wantedCount
        fxSampleRate = FxChain.activeSampleRate
        fxHeadroomDb = FxChain.headroomDb()
    }

    /**
     * offload 开关：只写设置，**重启播放服务后生效**。
     *
     * 为什么不能热切：renderers factory 是构建 ExoPlayer 时固定的，
     * 运行中换不了；硬要换只能整个播放器重建，正在放歌会被打断。
     * 所以做成「下次起播生效」，跟缓存容量同一个语义。
     */
    fun changeAudioOffload(on: Boolean) {
        audioOffload = on
        if (on) pureAudioMode = false
        viewModelScope.launch { settings.setAudioOffload(on) }
        if (on) {
            toast("省电播放已开启，音效将在下次起播时失效")
        } else {
            toast("省电播放已关闭，音效将在下次起播时恢复")
        }
    }

    /**
     * 切换 DSD 输出采样率。
     *
     * 和 offload 一样**下次起播生效** —— 采样率是 ExoPlayer 构建时定死的
     * （Extractor 构造参数），运行中换要重建整个播放器，正在放的歌会被打断。
     */
    fun changeDsdOutRate(rate: Int) {
        if (rate != 44100 && rate != 88200) return
        if (dsdOutRate == rate) return
        dsdOutRate = rate
        viewModelScope.launch { settings.setDsdOutRate(rate) }
        toast("DSD 输出已设为 ${rate / 1000}.1 kHz，下次起播生效")
    }

    /** DSD 自动旁路均衡器开关。热生效 —— 处理器切透传，不拆管线。 */
    fun changeDsdBypassEq(on: Boolean) {
        if (dsdBypassEq == on) return
        dsdBypassEq = on
        viewModelScope.launch { settings.setDsdBypassEq(on) }
        FxChain.setDsdBypass(on && dsdPlaying)
    }

    private fun pushFx() {
        FxChain.configureEnhanced(fxBass, fxLoudness)
        refreshFxRuntime()
    }

    fun changeFxBass(v: Int) {
        fxBass = v.coerceIn(0, 1000)
        pushFx()
        if (!persistActiveProfile()) viewModelScope.launch { settings.setFxBass(fxBass) }
    }

    fun changeFxLoudness(v: Int) {
        fxLoudness = v.coerceIn(0, 1500)
        pushFx()
        if (!persistActiveProfile()) viewModelScope.launch { settings.setFxLoudness(fxLoudness) }
    }

    // ---------------- 搜索增强 ----------------

    /** 热门搜索：取酷我热歌榜前 10 首的歌名 —— 真实数据，不编 */
    var hotKeywords by mutableStateOf<List<String>>(emptyList())
        private set
    private var hotLoading = false

    fun loadHotKeywords() {
        if (hotKeywords.isNotEmpty() || hotLoading) return
        hotLoading = true
        viewModelScope.launch {
            val sec = runCatching { client.chart(KuwoClient.BANG_HOT, 0, 10) }.getOrNull()
            hotKeywords = sec?.tracks.orEmpty()
                .map { it.name.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(10)
            hotLoading = false
        }
    }

    /**
     * 搜索联想。用真实搜索的前几条结果做建议，不另接一个来路不明的联想接口。
     * 防抖 280ms —— 否则每敲一个字就打一次接口。
     */
    var suggestions by mutableStateOf<List<String>>(emptyList())
        private set
    private var suggestJob: Job? = null

    fun loadSuggestions(keyword: String) {
        suggestJob?.cancel()
        val k = keyword.trim()
        if (k.isEmpty()) {
            suggestions = emptyList()
            return
        }
        suggestJob = viewModelScope.launch {
            delay(280)
            val list = runCatching { client.search(k, 1, 8) }.getOrDefault(emptyList())
            suggestions = list.map { r ->
                val n = r.name.orEmpty().trim()
                val a = r.artist.orEmpty().trim()
                if (a.isEmpty()) n else "$n - $a"
            }.filter { it.isNotEmpty() }.distinct().take(8)
        }
    }

    fun clearSuggestions() {
        suggestJob?.cancel()
        suggestions = emptyList()
    }

    // ---------------- 队列操作 ----------------

    /** 加到队列末尾。队列空时直接作为当前曲目（否则点"加入队列"像没反应） */
    private fun restartPendingSelection(wasPending: Boolean) {
        if (!wasPending || !AudioRuntimeState.snapshot.desiredPlaying || queue.getOrNull(queueIndex) == null) return
        if (restoredPositionMs >= 0L) startRestoredPlayback(autoPlay = true) else playCurrent()
    }

    fun addToQueue(track: Track) {
        val wasPending = playJob?.isActive == true
        if (queue.isEmpty()) {
            play(track)
            return
        }
        queue = queue + track
        restartPendingSelection(wasPending)
    }

    /** 插到当前曲目后面，下一首就播 */
    fun playNext(track: Track) {
        val wasPending = playJob?.isActive == true
        if (queue.isEmpty()) {
            play(track)
            return
        }
        val at = (queueIndex + 1).coerceIn(0, queue.size)
        val m = queue.toMutableList()
        m.add(at, track)
        queue = m
        restartPendingSelection(wasPending)
    }

    /** 该曲目在**播放器播放列表**里的位置（未解析则 -1） */
    private fun playlistIndexOf(trackId: String): Int {
        val c = controller ?: return -1
        for (i in 0 until c.mediaItemCount) {
            if (c.getMediaItemAt(i).mediaId == trackId) return i
        }
        return -1
    }

    /**
     * 从队列里移除一条。
     * 逻辑队列和播放器播放列表都要动 —— 只动一个的话，界面删了但系统面板还在播。
     */
    fun removeFromQueue(index: Int) {
        if (index !in queue.indices) return
        val wasPending = playJob?.isActive == true
        val t = queue[index]
        val at = playlistIndexOf(t.id)
        if (at >= 0) runCatching { controller?.removeMediaItem(at) }
        if (index == queueIndex && wasPending) {
            restoredPositionMs = 0L
            pendingSeekMs = 0L
            seekTarget = -1L
        }
        val m = queue.toMutableList().also { it.removeAt(index) }
        queue = m
        when {
            m.isEmpty() -> queueIndex = -1
            index < queueIndex -> queueIndex -= 1
            index == queueIndex -> queueIndex = index.coerceAtMost(m.size - 1)
        }
        restartPendingSelection(wasPending)
    }

    /**
     * 队列面板拖动排序。
     * 播放列表侧用 moveMediaItem（不打断播放、不重新缓冲），下标换算见 PlaylistOrder。
     */
    fun moveInQueue(from: Int, to: Int) {
        if (from == to || from !in queue.indices || to !in queue.indices) return
        val wasPending = playJob?.isActive == true
        val curId = queue.getOrNull(queueIndex)?.id
        val m = queue.toMutableList()
        val moved = m.removeAt(from)
        m.add(to, moved)
        queue = m
        queueIndex = m.indexOfFirst { it.id == curId }
        restartPendingSelection(wasPending)

        val c = controller ?: return
        val fromAt = playlistIndexOf(moved.id)
        if (fromAt < 0) return
        val afterAt = m.getOrNull(to + 1)?.id?.let { playlistIndexOf(it) } ?: -1
        val dest = PlaylistOrder.moveTarget(fromAt, afterAt, c.mediaItemCount)
        if (dest != fromAt) runCatching { c.moveMediaItem(fromAt, dest) }
    }

    // ---------------- 播放会话：落盘与恢复 ----------------

    /**
     * 恢复期闸门。
     *
     * 从「刚 setMediaItems 还没 prepare」到「READY 并 seek 到位」这段时间，播放器报的位置
     * 是 0 —— 不拦住，进度条会先跳到 0 再跳回来，看起来像「恢复失败」。
     * 同时它也是落盘闸门：恢复过程中不该把半成品状态写回文件。
     */
    private var restoring = false

    /** 恢复待播的位置（毫秒）；<0 = 没有待恢复的会话 */
    private var restoredPositionMs = -1L

    /** 恢复看门狗：READY 迟迟不来（无网/源挂了）也要把闸门放开，否则进度条永远冻住 */
    private var restoreWatchdog: Job? = null

    /** 队列/当前曲变化后的落盘任务（防抖：一次拖动排序会连着改好几次） */
    private var persistJob: Job? = null

    /** 冷启动后自动接着播（设置项，默认关：停在原位等用户按播放） */
    var autoResumePlayback by mutableStateOf(false)
        private set

    fun changeAutoResumePlayback(on: Boolean) {
        autoResumePlayback = on
        viewModelScope.launch { settings.setAutoResumePlayback(on) }
    }

    /**
     * 把当前队列 + 当前曲目落盘。
     *
     * 防抖 400ms：拖动排序、批量加歌会连续改好几次 `queue`，每次都写几百 KB 太浪费。
     * 恢复过程中直接跳过（见 [restoring]）。
     */
    private fun persistSession(delayMs: Long = 400, force: Boolean = false) {
        // 恢复过程中一般不该写（怕把半成品写回去），但恢复**结束**时要用 force 存一次，
        // 否则「恢复后拖了进度条就退出」这种情况下新位置会丢。
        if (restoring && !force) return
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            if (delayMs > 0) delay(delayMs)
            if (restoring && !force) return@launch
            val curId = queue.getOrNull(queueIndex)?.id ?: ""
            if (queue.isEmpty() || curId.isEmpty()) {
                runCatching { snapshotStore.clearQueue() }
                return@launch
            }
            val snap = PlaybackSnapshot(
                tracks = queue,
                currentId = curId,
                index = queueIndex.coerceAtLeast(0),
                savedAt = System.currentTimeMillis()
            )
            runCatching { snapshotStore.saveQueue(snap) }
        }
    }

    /**
     * 冷启动（或重进 App）后接上上次的播放。
     *
     * 两条路必须分开：
     *
     * **A. 播放器还活着**（划掉任务卡片但没停播）—— 不覆盖它，反而以它为真相：
     *    按 mediaId 从快照里找回曲目、把队列补进 ViewModel、同步进度和播放态。
     *    以前这条路上 `playbackState.track` 一直是 null，迷你播放条要等自动切下一首
     *    才冒出来 —— 现在顺手修掉。
     *
     * **B. 真的冷启动** —— 队列和位置从文件恢复，**但不立刻起播**：
     *    直链是几分钟就过期的签名地址，起播必须重新取链。所以先把界面摆好
     *    （曲名/封面/进度条都对），位置记在 [restoredPositionMs]，
     *    等用户按播放（或设置里开了自动接着播）才真正交给播放器。
     */
    private suspend fun restoreLastSession(c: MediaController) {
        if (queue.isNotEmpty()) return
        val restoreQueueEpoch = queueEpoch
        val restorePlaybackEpoch = AudioRuntimeState.snapshot.playbackEpoch
        val snap = runCatching { snapshotStore.loadQueue() }.getOrNull() ?: return
        val savedPos = runCatching { snapshotStore.loadPosition() }.getOrDefault(0L)
        // 设置项此刻可能还没被 init 里的 collect 收上来，直接读一次最准
        val autoPlay = runCatching { settings.autoResumePlayback.first() }
            .getOrDefault(autoResumePlayback)
        if (queue.isNotEmpty() || queueEpoch != restoreQueueEpoch ||
            AudioRuntimeState.snapshot.playbackEpoch != restorePlaybackEpoch) return

        // ── 路径 A：播放器里还有东西（服务没死） ──
        val aliveId = runCatching { c.currentMediaItem?.mediaId }.getOrNull()
        if (c.mediaItemCount > 0 && !aliveId.isNullOrEmpty()) {
            val inQueue = snap.tracks.indexOfFirst { it.id == aliveId }
            // 快照里没有这首（快照太旧/被清过）→ 用播放器自带的元数据补一条，
            // 至少迷你播放条和播放页能显示正确的曲名封面，而不是空着
            val list = if (inQueue >= 0) snap.tracks else listOfNotNull(trackFromController(c, aliveId))
            if (list.isEmpty()) return
            restoring = true
            queue = list
            queueIndex = list.indexOfFirst { it.id == aliveId }.coerceAtLeast(0)
            val cur = list[queueIndex]
            val dur = runCatching { c.duration }.getOrDefault(0L).takeIf { it > 0 } ?: cur.durationMs
            // isPlaying 是「用户意图」——必须取 playWhenReady。
            // 取 c.isPlaying 的话，恢复时正好在缓冲就会把图标画成「播放」三角，
            // 用户明明没暂停却看到播放键，接着缓冲完又跳回暂停，也是「乱翻」的一种。
            playbackState = PlaybackState(
                track = cur,
                isPlaying = runCatching { c.playWhenReady }.getOrDefault(false),
                audible = runCatching { c.isPlaying }.getOrDefault(false),
                positionMs = runCatching { c.currentPosition }.getOrDefault(0L),
                durationMs = dur,
                buffering = runCatching { c.playbackState == Player.STATE_BUFFERING }.getOrDefault(false),
                error = null
            )
            restoring = false
            restoredPositionMs = -1L
            loadLyrics(cur)
            applyLoudness(cur)
            // 播放器里的 timeline 只有窗口内的几首，补齐后续（幂等，已有的不会重复加）
            buildQueueAround(cur)
            persistSession(delayMs = 1_000)
            DiagLog.log("RESUME", "采纳存活播放器：'${cur.name}' pos=${playbackState.positionMs}ms 队列 ${list.size} 首")
            return
        }

        // ── 路径 B：冷启动恢复 ──
        val idx = ResumePlan.index(snap.tracks, snap.currentId, snap.index)
        if (idx < 0) return
        val target = snap.tracks[idx]
        val dur = target.durationMs
        // 剩不到 3 秒当作听完了：这首从头开始（否则一播放就结束，看起来像没恢复）
        val pos = ResumePlan.positionMs(savedPos, dur)

        restoring = true
        queue = snap.tracks
        queueIndex = idx
        playbackState = PlaybackState(
            track = target,
            isPlaying = false,
            positionMs = pos,
            durationMs = dur,
            buffering = false,
            error = null
        )
        restoredPositionMs = pos
        loadLyrics(target)
        persistSession(delayMs = 1_000, force = true)
        DiagLog.log("RESUME", "冷启动恢复队列：${snap.tracks.size} 首，当前第 ${idx + 1} 首 '${target.name}' pos=${pos}ms autoPlay=$autoPlay")

        // 预热直链：不碰播放器（无网也不会弹错），只为用户按下播放时少等那 1~3 秒
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { resolveOnce(target) }
        }
        if (autoPlay) {
            val restoredQueueEpoch = queueEpoch
            delay(60)
            if (queueEpoch != restoredQueueEpoch || AudioRuntimeState.snapshot.playbackEpoch != restorePlaybackEpoch ||
                queue.getOrNull(queueIndex)?.id != target.id || restoredPositionMs != pos) return
            startRestoredPlayback(autoPlay = true)
        }
    }

    /**
     * 从播放器当前项反推一个 Track。
     *
     * 只在「快照里找不到这首」时用（快照被清/太旧）。曲名、歌手、封面都在
     * MediaItem 的 metadata 里；`source` 猜不出来，按 mediaId 前缀判
     * （`kw:` / `ne:` / `nd:` / `dav:` / `local:`），猜不出就留空 —— 只影响
     * 「重新取链」时的分支，不影响当前这首继续播。
     */
    private fun trackFromController(c: MediaController, mediaId: String): Track? {
        val mi = runCatching { c.currentMediaItem }.getOrNull() ?: return null
        val md = mi.mediaMetadata
        val source = when {
            mediaId.startsWith("kw:") -> "kuwo"
            mediaId.startsWith("ne:") -> "netease"
            mediaId.startsWith("nd:") -> "navidrome"
            mediaId.startsWith("dav:") -> "webdav"
            mediaId.startsWith("local:") -> "local"
            else -> ""
        }
        val sourceId = mediaId.substringAfter(':', "")
        return Track(
            id = mediaId,
            name = md.title?.toString().orEmpty().ifEmpty { "未知曲目" },
            artist = md.artist?.toString().orEmpty(),
            album = md.albumTitle?.toString().orEmpty(),
            coverUrl = md.artworkUri?.toString(),
            durationMs = runCatching { c.duration }.getOrDefault(0L).coerceAtLeast(0L),
            source = source,
            sourceId = sourceId
        )
    }

    /**
     * 把 [restoredPositionMs] 这一首真正交给播放器。
     *
     * 只在两处调用：用户按了播放（[togglePlay]），或设置里开了自动接着播。
     * 结束后清掉待恢复位置 —— 之后就是普通播放流程。
     */
    private fun startRestoredPlayback(autoPlay: Boolean) {
        val t = queue.getOrNull(queueIndex) ?: return
        val startAt = restoredPositionMs.coerceAtLeast(0L)
        if (autoPlay) AudioRuntimeState.recordPlayIntent()
        val token = recoveryToken(t)
        playJob?.cancel()
        playJob = viewModelScope.launch {
            playbackState = playbackState.copy(track = t, buffering = true, error = null)
            val resolved = resolveOnce(t)
            if (!recoveryReady(token)) return@launch
            if (resolved == null) {
                playbackState = playbackState.copy(
                    buffering = false,
                    isPlaying = false,
                    error = "上次的歌曲暂时取不到音源"
                )
                toast("上次的歌曲暂时取不到音源")
                // 取不到就退回「把位置存着」，下次还能再试
                if (restoredPositionMs < 0L) restoredPositionMs = startAt
                return@launch
            }
            val c = readyController() ?: run {
                if (!recoveryReady(token)) return@launch
                playbackState = playbackState.copy(buffering = false, error = "播放服务未就绪")
                if (restoredPositionMs < 0L) restoredPositionMs = startAt
                return@launch
            }
            if (!recoveryReady(token)) return@launch
            val resumeAt = restoredPositionMs.takeIf { it >= 0L } ?: startAt
            restoredPositionMs = -1L
            watchRestore()
            c.setMediaItems(listOf(mediaItemOf(resolved)), 0, resumeAt)
            c.prepare()
            if (autoPlay) c.play()
            // seek 交给 pendingSeekMs：READY 之后才真正落位（setMediaItems 的起始位置
            // 在部分机型上会被 prepare 过程重置，显式 seek 一次最稳）
            if (resumeAt > 0L) {
                pendingSeekMs = resumeAt
                seekTarget = resumeAt
                seekGuardUntil = android.os.SystemClock.elapsedRealtime() + 3_000
            }
            playbackState = playbackState.copy(
                track = resolved,
                isPlaying = autoPlay,
                buffering = true,
                error = null,
                positionMs = resumeAt,
                durationMs = if (resolved.durationMs > 0) resolved.durationMs else playbackState.durationMs
            )
            applyLoudness(resolved)
            loadLyrics(resolved)
            buildQueueAround(resolved)
            prefetchNext()
            DiagLog.log("RESUME", "从 ${resumeAt}ms 接着播 '${resolved.name}' autoPlay=$autoPlay")
        }
    }

    /** 恢复看门狗：READY 一直不来也要放开闸门，别让进度条冻在原位 */
    private fun watchRestore() {
        restoring = true
        restoreWatchdog?.cancel()
        restoreWatchdog = viewModelScope.launch {
            delay(10_000)
            if (restoring) {
                restoring = false
                DiagLog.log("RESUME", "恢复超时（10s 未 READY），放开位置闸门")
            }
        }
    }

    // ---------------- 收藏 / 播放模式 / 音质偏好 ----------------

    /** 我喜欢的音乐（存完整曲目 JSON，不用回查） */
    var favorites by mutableStateOf<List<Track>>(emptyList())
        private set

    fun isFavorite(id: String): Boolean = favorites.any { it.id == id }

    fun toggleFavorite(track: Track) {
        viewModelScope.launch { favoriteStore.toggle(track) }
    }

    /** 播放模式：顺序 / 列表循环 / 单曲循环 / 随机 */
    var playMode by mutableStateOf(PlayMode.ORDER)
        private set

    fun changePlayMode(mode: PlayMode) {
        val wasShuffle = playMode == PlayMode.SHUFFLE
        val wasPending = playJob?.isActive == true
        if (mode != playMode) {
            queueEpoch++
            queueEpochUpdates.value = queueEpoch
            playJob?.cancel()
            com.kmusic.apkhd.playback.AudioPrecacheBridge.updateQueueEpoch(queueEpoch)
            queueBuildJob?.cancel()
        }
        playMode = mode
        controller?.let { applyPlayMode(it) }
        viewModelScope.launch { settings.setPlayMode(mode) }
        restartPendingSelection(wasPending)
        // 顺序模式下播放器里只铺了 9 首；切到随机时若不补齐，shuffle 仍只在这 9 首里打转。
        // 垫一首当前曲目触发 buildQueueAround 全量铺（幂等：已在 timeline 的不会重复加）。
        if (mode == PlayMode.SHUFFLE && !wasShuffle) {
            queue.getOrNull(queueIndex)?.let { buildQueueAround(it) }
        }
    }

    /** 把播放模式落到播放器 —— 重复/随机是 ExoPlayer 的原生状态，不是 UI 假开关 */
    private fun applyPlayMode(c: Player) {
        c.repeatMode = when (playMode) {
            PlayMode.ORDER -> Player.REPEAT_MODE_OFF
            PlayMode.LOOP, PlayMode.SHUFFLE -> Player.REPEAT_MODE_ALL
            PlayMode.SINGLE -> Player.REPEAT_MODE_ONE
        }
        c.shuffleModeEnabled = playMode == PlayMode.SHUFFLE
    }

    /** 默认音质：master / hires / high / standard */
    var defaultQuality by mutableStateOf("master")
        private set

    /** 播放缓存容量（GB），0 = 关闭；默认 1GB */
    var cacheSizeGb by mutableStateOf(1)
        private set
    /** 当前缓存占用（字节），进设置页时刷新 */
    var cacheBytes by mutableStateOf(0L)
        private set

    fun changeCacheSize(v: Int) {
        cacheSizeGb = v
        viewModelScope.launch { settings.setCacheSizeGb(v) }
        toast(if (v == 0) "播放缓存已关闭（下次起播生效）" else "缓存上限 ${v}GB（下次起播生效）")
    }

    fun refreshCacheSize() {
        refreshOutputCapabilities()
        cacheBytes = com.kmusic.apkhd.playback.MediaCache.sizeBytes(getApplication())
    }

    fun clearMediaCache() {
        com.kmusic.apkhd.playback.AudioPrecacheBridge.cancel()
        val result = com.kmusic.apkhd.playback.MediaCache.clear()
        refreshCacheSize()
        toast(if (result.successful) "缓存已清空（正在播放的歌曲会继续缓存）"
            else "部分缓存未清除：${result.failures}项；稍后重试")
    }

    /** 切音质后要跳回的位置（毫秒），新流 READY 时消费掉 */
    private var pendingSeekMs = 0L

    /**
     * 切换默认音质。
     *
     * 只改设置是不够的：正在播放的这一首还挂在旧码率的流上，用户点完听到的还是原来的声音，
     * 会以为「切换无效」。所以这里顺手把当前曲目按新档位重取一遍，并保留播放位置。
     * 非酷我源（本地/Navidrome/网易云直链）没有档位概念，不动。
     */
    fun changeQuality(q: String) {
        if (defaultQuality == q) return
        defaultQuality = q
        viewModelScope.launch { settings.setDefaultQuality(q) }

        val t = queue.getOrNull(queueIndex) ?: return
        // 只有本地文件和 Navidrome 流没有「档位」概念；
        // 网易云源的曲目会回退到酷我取流，同样要按新音质重取
        if (t.source == "local" || t.source == "navidrome") return
        // 用户主动换档，之前的「这档拿不到」记录可能已经不成立，重新探测
        client.resetQualityProbe()
        // ⚠️ 必须把这首的缓存删掉：playCurrent 现在优先用预取/已解析的结果，
        // 留着旧条目就会拿**旧码率**的直链「重播」一遍，用户听到的还是原来的音质。
        streams.remove(streamKey(t))
        pendingSeekMs = controller?.currentPosition ?: 0L
        playCurrent()
    }

    var losslessFallback by mutableStateOf(true)
        private set

    fun changeLosslessFallback(on: Boolean) {
        losslessFallback = on
        viewModelScope.launch { settings.setLosslessFallback(on) }
    }

    var wifiOnlyDownload by mutableStateOf(false)
        private set

    fun changeWifiOnlyDownload(on: Boolean) {
        wifiOnlyDownload = on
        viewModelScope.launch { settings.setWifiOnlyDownload(on) }
    }

    /** 母带仅在 Wi-Fi 下播放（默认关：流量也照播母带）。开了就在布局里少一行负缓存探测。 */
    var masterWifiOnly by mutableStateOf(false)
        private set

    fun changeMasterWifiOnly(on: Boolean) {
        masterWifiOnly = on
        viewModelScope.launch { settings.setMasterWifiOnly(on) }
        // 换成「不限流量」时，之前因流量被封存的母带探测要能重新来一遍
        if (!on) neToKuwo.clear()
        // 当前这首如果正在放 GD 无损、而现在是 Wi-Fi 了，按新设置重取一次
        val t = queue.getOrNull(queueIndex) ?: return
        if (t.source == "netease" && defaultQuality == "master") {
            streams.remove(streamKey(t))
            pendingSeekMs = controller?.currentPosition ?: 0L
            playCurrent()
        }
    }

    // ---------------- 下载 ----------------

    // ---------------- 一次性用户提示（Toast） ----------------

    /**
     * 需要弹一次就丢的提示。
     *
     * 用自增 seq 而不是裸字符串：同一句话连点两次也要能再弹一次（内容相同否则会被
     * Compose 当成没变化）。UI 读完调 [consumeUserMessage] 清掉。
     */
    data class UserMessage(val seq: Long, val text: String, val long: Boolean = false)

    var userMessage by mutableStateOf<UserMessage?>(null)
        private set
    private var msgSeq = 0L

    private fun toast(text: String, long: Boolean = false) {
        msgSeq += 1
        userMessage = UserMessage(msgSeq, text, long)
    }

    fun consumeUserMessage() {
        userMessage = null
    }

    /** 下载任务的**镜像**（真身在 DownloadCenter，由前台服务驱动） */
    var downloads by mutableStateOf<List<DownloadTask>>(emptyList())
        private set

    /**
     * 下载一首。取链在这里做（要用到搜索/跨源匹配），字节搬运交给 DownloadCenter ——
     * 那样退出 App 也不会断。
     */
    /**
     * 「仅 Wi-Fi 下载」开启且当前不是 Wi-Fi → 直接拒绝并写清原因。
     * 不静默排队：点了没反应才是最糟的。
     */
    private fun blockedByWifiOnly(): Boolean {
        if (!wifiOnlyDownload) return false
        val cm = getApplication<Application>()
            .getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            ?: return false
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return true
        return !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun download(track: Track) {
        if (DownloadCenter.has(track.id)) {
            // 之前这里是静默 return —— 用户点了没任何反应，以为按钮坏了
            toast("《${track.name}》已在下载或已下载过")
            return
        }
        if (blockedByWifiOnly()) {
            DownloadCenter.fail(track, "已开启「仅 Wi-Fi 下载」，当前不是 Wi-Fi")
            toast("已开启「仅 Wi-Fi 下载」，当前不是 Wi-Fi")
            return
        }
        // 取链要打搜索 / 跨源匹配，可能好几秒。先上屏占位，别让用户对着没反应的界面猜
        DownloadCenter.prepare(track)
        toast("已加入下载：${track.name}")
        viewModelScope.launch { resolveAndEnqueue(track) }
    }

    /** 批量下载（歌单/榜单/歌手的「下载全部」）。**串行取链**，避免并发打死酷我接口 */
    fun downloadAll(list: List<Track>) {
        val pending = list.filter { !DownloadCenter.has(it.id) }
        if (pending.isEmpty()) {
            toast("这些歌都已经在下载或已下载过了")
            return
        }
        if (blockedByWifiOnly()) {
            DownloadCenter.fail(list.first(), "已开启「仅 Wi-Fi 下载」，当前不是 Wi-Fi")
            toast("已开启「仅 Wi-Fi 下载」，当前不是 Wi-Fi")
            return
        }
        pending.forEach { DownloadCenter.prepare(it) }
        toast("已加入 ${pending.size} 首到下载队列")
        viewModelScope.launch {
            pending.forEach { resolveAndEnqueue(it) }
        }
    }

    /** 解析出直链并入队；失败立刻给出原因，不静默 */
    private suspend fun resolveAndEnqueue(track: Track) {
        try {
            when (track.source) {
                "local" -> DownloadCenter.markLocal(track, localLib.trackUri(track).toString())
                "webdav" -> {
                    val url = track.playUrl ?: throw IllegalStateException("WebDAV 地址缺失")
                    DownloadCenter.enqueue(track, url, null, "WebDAV", "", davClient?.authHeader)
                }
                "navidrome" -> {
                    val url = track.playUrl ?: navidrome?.streamUrl(track.sourceId)
                        ?: throw IllegalStateException("取不到曲库播放地址")
                    DownloadCenter.enqueue(track, url, null, "无损", "")
                }
                "netease" -> {
                    // 与播放**同一条策略**（v2.4.10）：走 neteaseResolved，母带优先、只升不降。
                    // 以前这里是手写的一份相反逻辑（先酷我、不看音质档位），
                    // 导致「同一首歌，播放是无损、下载却是母带」。
                    val got = neteaseResolved(track)
                        ?: throw IllegalStateException("酷我无匹配、GD 取链失败")
                    val ekey = got.ekey
                    if (ekey != null) {
                        DownloadCenter.enqueue(got, got.playUrl!!, ekey, got.qualityLabel, client.userTag)
                    } else {
                        DownloadCenter.enqueue(got, got.playUrl!!, null, got.qualityLabel, "")
                    }
                }
                else -> enqueueFromKuwo(track.sourceId, track)
            }
        } catch (e: Exception) {
            DownloadCenter.fail(track, e.message ?: "未知错误")
        }
    }

    private suspend fun enqueueFromKuwo(rid: String, track: Track) {
        val r = resolveByTier(rid)
            ?: throw IllegalStateException("没有符合当前音质设置的可下载资源")
        // 标签与播放一致：按响应里的真实格式/码率写（v2.4.10）
        val label = KuwoClient.labelOf(r.format, r.kbps) ?: qualityLabel(r.br)
        DownloadCenter.enqueue(track, r.url, r.ekey, label, client.userTag)
    }

    fun removeDownload(id: String) = DownloadCenter.remove(id)

    fun stop() {
        controller?.stop()
        playbackState = PlaybackState()
        lyrics = null
    }

    fun localUri(track: Track): Uri = localLib.trackUri(track)

    // ---------------- 诊断日志 ----------------

    /** 导出日志文件；失败时抛异常，由调用方提示用户 */
    fun exportDiagLog(context: android.content.Context): java.io.File {
        DiagLog.log("SYS", "用户导出诊断日志，缓冲内共 ${DiagLog.snapshot().lines().size} 行")
        return DiagLog.export(context)
    }

    fun clearDiagLog() = DiagLog.clear()

    /**
     * 用户报告「歌词不对」。
     *
     * 为什么只能这么做：歌词配错**没法自动检测**。实测过两个判据都不成立 ——
     *   1. 时长差：正确的词末尾常有大段纯音乐（实测差 75s 仍是对的），不能否决
     *   2. 歌名出现在正文里：'再次重逢的世界'、'Oh!' 这些**正确**的词也不含歌名，会误杀
     * 所以只能让用户说话，然后按他的反馈换源重试。
     *
     * @param reason 用户选的原因，进日志便于回溯
     */
    /** 用户点开「歌词不对？」面板。记一笔，免得反馈没生效时查不到线索 */
    fun openWrongLyricPanel() {
        val t = playbackState.track
        DiagLog.log(
            "LYRIC_REPORT_OPEN",
            "用户点开「歌词不对」面板：当前曲目=" +
                if (t == null) "无" else "'${t.name}' / '${t.artist}'"
        )
    }

    fun reportWrongLyric(reason: String) {
        val t = playbackState.track
        if (t == null) {
            DiagLog.log("LYRIC_REPORT", "用户报告歌词不对，但当前没有播放曲目 → 未做任何事")
            return
        }
        // 判断当前歌词是哪个源给的，把那个源加进跳过集合。
        // 顺序与 loadLyrics 里的取数顺序一致 —— 后加的源优先级更高，
        // 所以要从后往前判：lrclib 是最后一环，它给了词就说明前面都没给。
        val fromLrclib = lyrics?.isNotEmpty() == true && lyricLastSource == "lrclib"
        val skip = when {
            fromLrclib -> "lrclib"
            lyricLastSource == "gdstudio_byname" -> "gdstudio_byname"
            lyricLastSource == "gdstudio" -> "gdstudio"
            lyricLastSource == "kuwo" -> "kuwo"
            else -> null
        }
        if (skip != null) {
            lyricSkippedSources.getOrPut(t.id) { mutableSetOf() } += skip
            // 标记「这首歌的否决是有效的」，防止 loadLyrics 开头按曲目清掉时误伤
            lyricSkippedForTrack = t.id
            DiagLog.log(
                "LYRIC_REPORT",
                "已把源 '$skip' 加入『${t.name}』的跳过集合（仅这首，不影响别的歌）"
            )
        } else {
            DiagLog.log("LYRIC_REPORT", "无法判定当前歌词来自哪个源，只能原样重试")
        }

        DiagLog.log(
            "LYRIC_REPORT",
            "用户报告歌词不对: '${t.name}' / '${t.artist}' 源=${t.source} 原因=$reason"
        )
        // 强制重取：清掉已加载标记和缓存，否则会直接命中旧词、看起来像没生效
        lyricLoadedId = null
        lyricCache.remove(t.id)
        toast("正在换源重新查找歌词…")
        loadLyrics(t)
    }

    /**
     * 预取**下一首**的歌词 —— 专门治「切歌后歌词要等一下才出来」。
     *
     * 只预取酷我源：同源、最快（实测 ~120ms），且占全部曲目的大头。
     * 取不到就交给真正的 [loadLyrics] 走全链路，这里不留任何副作用。
     * 写入统一回主线程做 —— [lyricCache] 是普通 LinkedHashMap，不是线程安全的。
     */
    private fun prefetchNextLyric() {
        val next = queue.getOrNull(queueIndex + 1) ?: return
        if (lyricCache.containsKey(next.id)) return
        val rid = next.sourceId.takeIf {
            next.source == "kuwo" && it.isNotBlank() && it.all { c -> c.isDigit() }
        } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val text = runCatching {
                withTimeoutOrNull(3000) { client.lyricByRid(rid) }
            }.getOrNull() ?: return@launch
            val lines = lyricsRepo.parseLyrics(text, "")
            if (lines.isEmpty()) return@launch
            withContext(Dispatchers.Main) {
                lyricCache[next.id] = CachedLyric(lines, "kuwo")
                DiagLog.log("LYRIC_PREFETCH", "已预取下一首歌词 '${next.name}'（${lines.size} 行）")
            }
        }
    }

    /**
     * 音质文案。
     *
     * [br] 有两个来源：请求档位常量（BR_MASTER 等），以及酷我响应里的实际 bitrate 数字
     * （"128" / "1"）。降级取流后拿到的是后者，必须显示真实值，不能挂「臻品母带」。
     */
    private fun qualityLabel(br: String): String = when (br) {
        KuwoClient.BR_MASTER -> "臻品母带"
        KuwoClient.BR_HIRES -> "无损 FLAC"
        KuwoClient.BR_HIGH -> "极高品"
        else -> when (val n = br.toIntOrNull()) {
            null -> "标准"
            in 0..1 -> "试听"
            in 2..999 -> "${n}kbps"
            in 1000..9999 -> "无损 FLAC"   // 2000kflac 返回的 bitrate
            else -> "臻品母带"              // 20900kmflac
        }
    }

    private fun SearchResult.toTrack() = Track(
        id = "kw:$rid",
        name = name ?: "未知曲目",
        artist = artist ?: "",
        album = album ?: "",
        coverUrl = cover,
        source = "kuwo",
        sourceId = rid
    )

    // ⚠️ 这两个 init 块**必须留在类末尾**，不能挪到属性声明之前。
    //
    // 原因：init 里的 `viewModelScope.launch { flow.collect { state = it } }` 会
    // **同步执行** —— viewModelScope 用的是 Dispatchers.Main.immediate，且裸 StateFlow
    // 首次 emit 不挂起。若被赋值的 `by mutableStateOf` 属性声明在 init 之后，
    // 那一刻委托字段还是 null → NPE（在协程里被吞掉，真机上抛到主线程 = 闪退）。
    //
    // ViewModelInitTest / InitOrderingProbeTest 钉住了这条规则。

    init {
        val ctx = getApplication<Application>()
        // 网络边沿监听：断网时停在当前曲，恢复后自动续播（v2.4.6）
        networkMonitor.start(
            onRegained = { onNetworkRegained() },
            onLost = {
                // 只是记一笔：真正的「停播等网」由播放器报错时判定，
                // 缓存里还剩几十秒音频的场景不该被我们提前打断。
            },
            onChanged = {
                com.kmusic.apkhd.playback.AudioPrecacheBridge.cancel()
                if (AudioRuntimeState.snapshot.desiredPlaying && !networkMonitor.isOffline()) prefetchNext()
            }
        )
        startPlayGuard()
        connectController()

    }

    init {
        // 搜索历史
        viewModelScope.launch {
            settings.searchHistory.collect { searchHistory = it }
        }
        // 最近播放（存的是完整曲目信息，不用回查）
        viewModelScope.launch {
            recentStore.recentTracks.collect { recentTracks = it }
        }
        // 我喜欢的音乐
        viewModelScope.launch {
            favoriteStore.favorites.collect { favorites = it }
        }
        // 自建歌单
        viewModelScope.launch {
            playlistStore.playlists.collect { userPlaylists = it }
        }
        // 收藏的歌单：列表与 key 集合同步（key 集合用于首页角标/详情页按钮的 O(1) 判定）
        viewModelScope.launch {
            playlistFavStore.favorites.collect {
                favoritePlaylists = it
                favoritePlaylistKeys = it.map { p -> playlistKey(p) }.toSet()
            }
        }
        // 均衡器偏好
        reloadUserPresets()
        viewModelScope.launch { settings.aiEndpoint.collect { aiEndpoint = it } }
        viewModelScope.launch { settings.aiApiKey.collect { aiApiKey = it } }
        viewModelScope.launch { settings.aiModel.collect { aiModel = it } }
        viewModelScope.launch { settings.eqEnabled.collect { if (deviceProfileRuntime.active == null) { eqEnabled = it; FxChain.configure(it, eqPreset) } } }
        viewModelScope.launch {
            settings.eqPreset.collect { raw ->
                // 老版本存的是**设备预设下标**（0..9），现在换成了内置表。
                // 只在本次进程第一次读到旧值时迁移一次，之后不再动用户的选择。
                val idx = if (presetMigrated) raw else {
                    presetMigrated = true
                    FxChain.migratePresetIndex(raw).also { m ->
                        eqPreset = m
                        if (m != raw) viewModelScope.launch { settings.setEqPreset(m) }
                    }
                }
                if (deviceProfileRuntime.active == null) {
                    eqPreset = idx
                    FxChain.configure(eqEnabled, idx)
                }
                refreshEqBands()
            }
        }
        reloadUserPresets()
        viewModelScope.launch { settings.fxBass.collect { if (deviceProfileRuntime.active == null) { fxBass = it; pushFx() } } }
        viewModelScope.launch { settings.fxLoudness.collect { if (deviceProfileRuntime.active == null) { fxLoudness = it; pushFx() } } }
        // 处理器是普通对象（不是 Compose State），靠版本号把它报上来的运行态
        // （采样率 / 开了几项）同步到界面。没有「重建链」这回事，所以这里只读不推。
        viewModelScope.launch {
            snapshotFlow { FxChain.version }.collect { v ->
                if (v > 0) {
                    refreshEqBands()
                    refreshFxRuntime()
                }
            }
        }
        // 下载任务（真身在 DownloadCenter，前台服务驱动，退出 App 也继续）
        DownloadCenter.attach(getApplication())
        viewModelScope.launch {
            DownloadCenter.tasks.collect { downloads = it }
        }
        // 播放模式 + 音质偏好
        viewModelScope.launch {
            settings.playMode.collect { playMode = it; controller?.let(::applyPlayMode) }
        }
        // 自动接着播（冷启动后是否直接起播）
        viewModelScope.launch { settings.autoResumePlayback.collect { autoResumePlayback = it } }
        viewModelScope.launch { settings.defaultQuality.collect { defaultQuality = it } }
        viewModelScope.launch { settings.cacheSizeGb.collect { cacheSizeGb = it } }
        viewModelScope.launch { settings.losslessFallback.collect { losslessFallback = it } }
        viewModelScope.launch { settings.masterWifiOnly.collect {
            masterWifiOnly = it
            if (it) com.kmusic.apkhd.playback.AudioPrecacheBridge.cancel()
        } }
        viewModelScope.launch {
            settings.loudnessMode.collect { m ->
                if (m != loudnessMode) {
                    loudnessMode = m
                    playbackState.track?.let { applyLoudness(it) }
                }
            }
        }
        viewModelScope.launch { settings.audioOffload.collect { audioOffload = it } }
        viewModelScope.launch { settings.pureAudioMode.collect { pureAudioMode = it } }
        viewModelScope.launch { settings.audioOutputMode.collect { audioOutputMode = it } }
        viewModelScope.launch { settings.usbHighFidelity.collect { usbHighFidelity = it } }
        viewModelScope.launch { settings.callAutoResume.collect { callAutoResume = it } }
        viewModelScope.launch { settings.audioInterruptionResume.collect { audioInterruptionResume = it } }
        viewModelScope.launch { settings.truePeakEnabled.collect { if(deviceProfileRuntime.active == null) { truePeakEnabled = it; FxChain.configureTruePeak(it, truePeakCeilingDb) } } }
        viewModelScope.launch { settings.truePeakCeilingDb.collect { if(deviceProfileRuntime.active == null) { truePeakCeilingDb = it; FxChain.configureTruePeak(truePeakEnabled, it) } } }
        viewModelScope.launch { com.kmusic.apkhd.playback.UsbAudioRuntime.state.collect { usbOutput = it } }
        viewModelScope.launch { deviceProfileStore.profiles.collect { deviceProfiles = it } }
        viewModelScope.launch { deviceProfileStore.autoEnabled.collect { deviceProfileAuto = it } }
        viewModelScope.launch {
            com.kmusic.apkhd.playback.DeviceEqProfiles.runtime.collect {
                deviceProfileRuntime = it
                eqEnabled = FxChain.enabled
                fxBass = FxChain.bassStrength
                fxLoudness = FxChain.loudnessGain
                it.active?.let { profile -> truePeakEnabled = profile.truePeakEnabled; truePeakCeilingDb = profile.ceilingDbTp }
                refreshEqBands()
            }
        }
        viewModelScope.launch { settings.bluetoothAutoResume.collect { bluetoothAutoResume = it } }
        viewModelScope.launch { settings.dsdOutRate.collect { dsdOutRate = it } }
        viewModelScope.launch { settings.dsdBypassEq.collect { dsdBypassEq = it } }
        // DSD 播放状态：音效链旁路 EQ 时也要同步界面
        DsdPlaybackState.setListener {
            dsdPlaying = DsdPlaybackState.isDsdPlaying
            dsdLabel = DsdPlaybackState.currentLabel
            FxChain.setDsdBypass(dsdBypassEq && dsdPlaying)
        }
        viewModelScope.launch { settings.wifiOnlyDownload.collect {
            wifiOnlyDownload = it
            if (it) com.kmusic.apkhd.playback.AudioPrecacheBridge.cancel()
        } }
        viewModelScope.launch { settings.lyricOffsetMs.collect { lyricOffsetMs = it } }
        viewModelScope.launch { settings.lyricFontScale.collect { lyricFontScale = it } }
        // WebDAV 凭据：变化就重建客户端，顺手把鉴权上下文交给播放器数据源
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(settings.webdav, settings.davUa) { t, ua -> t to ua }
                .collect { (creds, ua) ->
                val (u, usr, pw) = creds
                davUrl = u
                davUser = usr
                davPass = pw
                davUa = ua
                val c = if (u.isBlank()) null else WebDavClient(
                    u, usr, pw, ua.ifBlank { "KMusic/1.5.0 (Android)" }
                )
                davClient = c
                DavAuth.prefix = c?.base.orEmpty()
                DavAuth.header = c?.authHeader
                if (c == null) {
                    davEntries = emptyList(); davPath = ""; davError = null
                } else if (davPath.isBlank() || !davPath.startsWith(c.base)) {
                    davOpen(c.base)
                }
            }
        }
        // Navidrome 凭据恢复 + 测连通
        viewModelScope.launch {
            settings.navidrome.collect { cfg ->
                val (url, user, pass) = cfg
                if (url.isBlank() || user.isBlank() || pass.isBlank()) {
                    navidrome = null
                    navidromeConnected = false
                    navidromeError = null
                    navidromeAddress = ""
                    navidromeUser = ""
                    navidromePass = ""
                    return@collect
                }
                navidromeAddress = url
                navidromeUser = user
                navidromePass = pass
                val c = NavidromeClient(url, user, pass).also { navidrome = it }
                navidromeChecking = true
                navidromeError = null
                val r = c.ping()
                navidromeChecking = false
                navidromeConnected = r.isSuccess
                navidromeError = r.exceptionOrNull()?.message
                if (navidromeConnected) refreshNavidrome()
            }
        }
        viewModelScope.launch {
            AudioRuntimeState.updates.collect { runtime ->
                if (runtime.usbHighFidelityRequested && !audioRuntime.usbHighFidelityRequested) {
                    loudnessJob?.cancel()
                    loudnessEpoch++
                    runCatching { controller?.volume = 1f }
                    loudnessGainDb = 0f
                    loudnessSource = "usb-bypassed"
                }
                audioRuntime = runtime
                refreshOutputCapabilities()
                if (!runtime.desiredPlaying && !runtime.modeSwitching) cancelPendingPlayback()
                if (!runtime.serviceAlive && com.kmusic.apkhd.ui.tv.TvEnv.isTv(getApplication()) && com.kmusic.apkhd.playback.PlaybackSessionExit.isExitRequested) {
                    controllerFuture?.let { MediaController.releaseFuture(it) }
                    controllerFuture = null
                    controller = null
                }
            }
        }
        // 播放进度轮询（驱动进度条和歌词高亮）
        var lastPersistAt = 0L
        viewModelScope.launch {
            while (isActive) {
                val p = controller
                if (p != null) {
                    // 恢复期：播放器还没 prepare/seek 到位，它报的位置是 0 ——
                    // 直接用会把进度条一到 0，看起来像「没恢复成功」
                    if (restoring) {
                        delay(100)
                        continue
                    }
                    val rawPos = runCatching { p.currentPosition }.getOrDefault(0L)
                    // seek 后 800ms 内强制用目标位置：Media3 异步 seek 未落地时读到的是旧值，
                    // 直接写回会让进度条「拖完弹回原处」
                    val pos = if (seekTarget >= 0) {
                        if (android.os.SystemClock.elapsedRealtime() < seekGuardUntil) {
                            seekTarget
                        } else {
                            seekTarget = -1L
                            rawPos
                        }
                    } else rawPos
                    val dur = runCatching { p.duration }.getOrDefault(0L).let {
                        if (it > 0) it else playbackState.durationMs
                    }
                    if (playbackState.track != null) {
                        // ⚠️ 这里只同步「真实出声」和「缓冲」两个物理事实（v2.4.8）。
                        // 绝不能拿 p.isPlaying 去写 playbackState.isPlaying ——
                        // 那个字段现在是「用户意图」（= playWhenReady），
                        // 被缓冲状态覆盖的话图标就会跟着网络抖动闪。
                        val audible = runCatching { p.isPlaying }.getOrDefault(false)
                        val buffering = runCatching { p.playbackState == Player.STATE_BUFFERING }
                            .getOrDefault(false)
                        val nowElapsed = android.os.SystemClock.elapsedRealtime()
                        if (buffering) {
                            if (bufferingSince == 0L) bufferingSince = nowElapsed
                        } else {
                            bufferingSince = 0L
                        }
                        val stalled = bufferingSince != 0L && nowElapsed - bufferingSince >= STALL_HINT_MS
                        if (stalled && !playbackState.stalled) {
                            DiagLog.log(
                                "PLAY_BUF",
                                "缓冲已持续 ${(nowElapsed - bufferingSince) / 1000}s，叠加载圈（图标保持「暂停」）"
                            )
                        }
                        // mutableStateOf 的结构相等策略会挡住无变化的写入；这里显式比一次
                        // 只是省掉 10Hz 的 copy 分配（暂停时尤其明显）
                        if (pos != playbackState.positionMs || dur != playbackState.durationMs ||
                            audible != playbackState.audible || buffering != playbackState.buffering ||
                            stalled != playbackState.stalled
                        ) {
                            playbackState = playbackState.copy(
                                positionMs = pos,
                                durationMs = dur,
                                audible = audible,
                                buffering = buffering,
                                stalled = stalled
                            )
                        }
                        // 歌词高亮：只在换行时更新状态，别让整个列表跟着 100ms 抖
                        val line = computeLyricIndex()
                        if (line != currentLyricIndex) currentLyricIndex = line
                        // 位置落盘：每 5 秒一次。App 被划掉/杀掉时，最多丢 5 秒进度
                        val nowAt = android.os.SystemClock.elapsedRealtime()
                        if (pos > 0L && nowAt - lastPersistAt >= 5_000L) {
                            lastPersistAt = nowAt
                            viewModelScope.launch(Dispatchers.IO) {
                                runCatching { snapshotStore.savePosition(pos) }
                            }
                        }
                    }
                }
                delay(100)
            }
        }
    }

    private fun connectController() {
        if (controller?.isConnected == true || controllerFuture?.isDone == false) return
        if (com.kmusic.apkhd.ui.tv.TvEnv.isTv(getApplication()) && com.kmusic.apkhd.playback.PlaybackSessionExit.isExitRequested) return
        val ctx = getApplication<Application>()
        val token = SessionToken(ctx, ComponentName(ctx, MoyinPlaybackService::class.java))
        controllerFuture = MediaController.Builder(ctx, token).setListener(object : MediaController.Listener {
            override fun onDisconnected(disconnected: MediaController) {
                if (controller === disconnected) controller = null
                controllerFuture = null
            }
        }).buildAsync().also { f ->
            f.addListener({
                runCatching { f.get() }
                    .onSuccess {
                        controller = it
                        attachControllerListener(it)
                        applyPlayMode(it)
                        // 接上上次的播放：队列 + 当前曲 + 进度。
                        // 播放器还活着就以它为准，真冷启动则只摆 UI、等用户按播放
                        // （或设置里开了「自动接着播」）。
                        viewModelScope.launch {
                            if (queue.isNotEmpty() && com.kmusic.apkhd.ui.tv.TvEnv.isTv(ctx)) return@launch
                            runCatching { restoreLastSession(it) }
                                .onFailure { e -> Log.w("MoyinVM", "恢复上次播放失败: ${e.message}") }
                        }
                    }
                    .onFailure { Log.w("MoyinVM", "控制器连接失败: ${it.message}") }
            }, ContextCompat.getMainExecutor(ctx))
        }
    }

    fun onUiForeground() {
        if (!com.kmusic.apkhd.ui.tv.TvEnv.isTv(getApplication())) return
        com.kmusic.apkhd.playback.PlaybackSessionExit.openSession()
        refreshOutputCapabilities()
        connectController()
    }

    override fun onCleared() {
        networkMonitor.stop()
        offlineWatchdog?.cancel()
        offlineWatchdog = null
        playGuard?.cancel()
        playGuard = null
        // 只断开与服务的连接；播放继续（前台服务活着），重进 App 会自动重连
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onCleared()
    }
}

/**
 * 当前播放链的可核实参数。
 *
 * 刻意没有 bitDepth：Media3 的 Format 不暴露它，在线流更无从得知，
 * 硬填一个「24bit/192kHz」只会变成装饰性的假数据。
 */
data class AudioInfo(
    val codec: String,
    /** 用户请求的音质档位标签（「臻品母带」等）；为空表示不知道 */
    val requestedLabel: String? = null,
    /** 请求母带但实际码率远低于无损 → 源站没给真货 */
    val downgraded: Boolean = false,
    val sampleRate: Int?,
    val channels: Int?,
    val bitrateKbps: Int?,
    val outSampleRate: Int?,
    val outFramesPerBuffer: Int?,
    val outputDescription: String? = null,
    val outputDevice: String? = null,
    val offloadStatus: String = "未知",
    val usbOutputStatus: String = "关闭",
    val interruptionStatus: String = "等待音频中断",
    val truePeakStatus: String = "关闭",
    val pureAudioMode: Boolean = false,
    val bufferedMs: Long,
    val sourceLabel: String,
    /** 实际音质分级，用于在详情里显示「母带 / 无损 / 320k」的真实档位 */
    val qualityRank: Int = 0,
    /** 单曲体积（字节，按码率 × 时长估算），用来显示「这首多大」 */
    val estimatedBytes: Long = 0L,
    /**
     * [bitrateKbps] 是源站报的，不是解码器实测的。
     *
     * mflac / mgg 这些加密容器，ExoPlayer 读不到码率（Format.bitrate = -1），
     * 只能拿取链响应里的 `bitrate` 兜底。界面上要标出来 ——
     * 「5426 kbps（源站标称）」和「5426 kbps」的可信度不是一回事。
     */
    val bitrateFromSource: Boolean = false
)
