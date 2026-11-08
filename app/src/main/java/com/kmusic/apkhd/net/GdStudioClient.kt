package com.kmusic.apkhd.net

import android.util.Log
import com.kmusic.apkhd.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * gdstudio 聚合 API 客户端 —— 洛雪音源生态在用的公开接口。
 *
 * ## 它解决的问题
 *
 * 网易云官方 `song/enhance/player/url` 不登录只给 **320k**，VIP 曲直接 `code=-110`。
 * 实测热歌榜前 30 首：官方出链 17 首且全是 320k；本接口出链 29 首、其中 28 首是**真 FLAC**
 * （24bit/44.1–88.2kHz，`ffprobe` 认，20kHz 以上能量比同曲 320k mp3 高 10–15dB，
 * 不是转码升频）。所以它是把网易云这条链路做到「无损」的唯一手段。
 *
 * ## 两个域名的真实分工（2026-11 实测，别再搞反）
 *
 * | | playlist | search | url | lyric | pic |
 * |---|---|---|---|---|---|
 * | `music-api.gdstudio.xyz` | ✅ | ✅ | ✅ | ✅ | ✅ |
 * | `music.gdstudio.org` | ✅ | ❌401 | ❌401 | ❌401 | ❌401 |
 *
 * 401 是 `Invalid request.`，带 Referer/Origin 也一样，不是防盗链、是那台没挂业务接口。
 * 所以**取链必须走 primary**；backup 只在 primary 彻底失败后兜一次（现在必然 401，
 * 但只花一次 300ms 的往返，且它哪天恢复就能自动接上）。
 *
 * 注意：这两个域名是**同一个服务**，不是两个音源。多一个域名不等于多一路内容。
 *
 * ## 支持的 source
 *
 * 只支持 `netease`。`tencent/qq/tx/kuwo/kugou/migu/baidu/qobuz/spotify` 一律
 * `400 Value of source is not supported`；`joox` 能过参数校验、`search` 也有结果，
 * 但 `types=url` 恒回 `{"url":"","br":-1}`（内容源已断），所以也不能用。
 *
 * ## 可靠性
 *
 * 第三方公开服务，会**偶发 503**（实测连发 70 次 3 次 503，都是瞬时）。
 * 所以 [get] 带 3 次退避重试；401/403/404 是确定性错误，立即放弃不重试。
 */
class GdStudioClient {

    companion object {
        private const val TAG = "GdStudioClient"

        /**
         * 主域名：唯一挂齐 `search/url/lyric/pic` 的那台。
         *
         * ⚠️ 别改回 `music.gdstudio.org` —— 那台只有 `types=playlist` 能用，
         * 其余四个接口恒 `401 Invalid request.`。2026-10 因为「xyz 返 503」把域名
         * 换成了 org，结果连带把取链也换死了：`url()` 永远拿不到值，网易云一路降到 320k。
         */
        private const val API_PRIMARY = "https://music-api.gdstudio.xyz/api.php"

        /** 兜底域名：当前只有 playlist 可用，纯当「哪天修好了自动接上」的保险 */
        private const val API_BACKUP = "https://music.gdstudio.org/api.php"

        /**
         * 重试次数（含首次）。503 是这台服务的常态抖动，不是故障：
         * 实测连发 70 次里有 3 次 503，隔 300ms 重发就成。
         */
        private const val RETRY_TIMES = 3

        /** 音质档位：999=flac24bit / 740=flac / 320 / 128 */
        const val BR_FLAC24 = 999
        const val BR_FLAC = 740
        const val BR_320 = 320
        const val BR_128 = 128

        /**
         * 无损下限（kbps）。
         *
         * 实测真实 FLAC 的 `br` 落在 **786–2832**（曲目不同、位深/采样率不同），
         * 320k mp3 的 `br` 恒为 320。所以 700 是干净的分界，用它判「这条是不是无损」。
         */
        const val LOSSLESS_MIN_BR = 700

        /**
         * 把服务端回的 `br` 翻成界面标签。
         *
         * 为什么要按 `br` 而不是「请求了什么档位」：服务端会自作主张降档 ——
         * 请求 `999` 可能拿回 `br=320`，请求 `320` 也可能回 `128`。
         * 只有响应里的 `br` 才是「这一刻真正播的是什么」。
         */
        fun labelOf(br: Int): String = when {
            br >= LOSSLESS_MIN_BR -> "无损"
            br >= 256 -> "320k"
            br >= 96 -> "128k"
            else -> "低音质"
        }

        /**
         * GD 的 `br` 换算成**统一分级**（[KuwoClient.RANK_*]），供跨源比价（v2.4.10）。
         *
         * GD 回的是**实际编码码率**（901 / 1546 / 2793…），不是「档位」，
         * 所以能直接按数值判：≥700 无损，再按有损码率分档。
         * 注意：GD 的 2793kbps 是 24bit/88.2k，比酷我 `2000kflac`（16bit/44.1k / 895kbps）高，
         * 但比酷我母带（mflac，5.4Mbps）低 —— 用同一个分级才能正确排序。
         */
        fun rankOf(br: Int): Int = when {
            br >= LOSSLESS_MIN_BR -> KuwoClient.RANK_FLAC
            br >= 256 -> KuwoClient.RANK_HIGH
            br > 0 -> KuwoClient.RANK_LOSSY
            else -> KuwoClient.RANK_NONE
        }

        /** 这些状态码代表「再试也没用」，只换主机不重试 */
        private val DETERMINISTIC_FAIL = setOf(401, 403, 404)
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun search(keyword: String, count: Int = 10): List<Track> = withContext(Dispatchers.IO) {
        val body = get("types=search&source=netease&name=${enc(keyword)}&count=$count")
            ?: return@withContext emptyList()
        runCatching {
            json.decodeFromString<List<GdTrack>>(body).map { it.toTrack() }
        }.onFailure { Log.w(TAG, "search parse: ${it.message}") }.getOrDefault(emptyList())
    }

    /**
     * 取歌单全部歌曲（网易云歌单 id）。
     *
     * ⚠️ 为什么走 gdstudio 而不是 music.163.com：实测 `/api/playlist/detail` 与
     * `/api/v6/playlist/detail`（GET 和 POST 都试过）都硬锁 `tracks` 到 10 首，
     * 即使 `trackIds` 是完整的；`s` 偏移参数完全无效（试过 0/1/2/3/4/5/10/20/50/
     * 100/200/300，永远是同样 10 首）；`song/detail` 批量取元数据返回 code=400
     * （要 weapi 加密）。而 gdstudio 代理这个接口返回**完整** tracks。
     *
     * 实测：367 首的歌单拿到 367 首，带歌名/歌手/时长/封面，抽样 11/12 可播。
     *
     * 注意返回的是**网易云原生结构**（ar 数组 / al 对象 / dt 毫秒），
     * 不是 search 接口那种扁平结构，所以用单独的模型。
     */
    /** 网易云图片 http→https，避免明文流量被拦 */
    private fun https(u: String?): String? =
        u?.takeIf { it.isNotEmpty() }?.replace("http://", "https://")

    suspend fun playlistTracks(id: String): List<Track> = withContext(Dispatchers.IO) {
        val body = get("types=playlist&source=netease&id=$id") ?: return@withContext emptyList()
        runCatching {
            json.decodeFromString<GdPlaylistResp>(body)
                .let { it.playlist ?: it.result }
                ?.tracks.orEmpty()
                .map { t ->
                    Track(
                        id = "ne:${t.id}",
                        name = t.name,
                        artist = t.ar.orEmpty().joinToString("/") { it.name.orEmpty() },
                        album = t.al?.name.orEmpty(),
                        coverUrl = https(t.al?.picUrl),
                        durationMs = t.dt,
                        source = "netease",
                        sourceId = t.id.toString()
                    )
                }
        }.onFailure { Log.w(TAG, "playlistTracks parse: ${it.message}") }.getOrDefault(emptyList())
    }

    /**
     * 取播放直链，按音质从高到低自动降级。
     *
     * @return `null` = 该曲目在本接口拿不到（无版权/彻底失败），上层应回退网易云直连或换源。
     *
     * 档位链说明（实测）：
     * - `999` 与 `740` 对同一批 10 首返回**完全一样**的 `br/size`（都是 999 语义的
     *   flac24bit，服务端不区分），所以 740 不进链，省一次往返。
     * - `999` 拿不到时**必须先试 320**：有些歌（如 `186016` 晴天周杰伦）四档全空，
     *   但也有部分曲子只有普通档位，跳过 320 会白白判死。
     * - 返回的是 `GdLink` 而不是裸字符串：`br` 决定这首到底算不算无损，
     *   标签要按它写，不能像以前那样一律挂「无损」。
     */
    suspend fun resolve(
        id: String,
        brs: List<Int> = listOf(BR_FLAC24, BR_320, BR_128)
    ): GdLink? = withContext(Dispatchers.IO) {
        // 取链是播放前置步骤，用户就在等这一个 await；给整条链 12 秒上限，
        // 超了宁可回退网易云直连（320k 但秒出），也别让人盯着转圈。
        val deadline = System.currentTimeMillis() + 12_000L
        for (br in brs) {
            val body = get("types=url&source=netease&id=$id&br=$br", deadline) ?: continue
            val d = runCatching { json.decodeFromString<GdUrl>(body) }.getOrNull() ?: continue
            val u = d.url
            if (u.isNullOrEmpty()) continue
            // 服务端偶尔回 200 + url 但 br=0（编码中/索引缺失），这种链多半 404，跳过
            if (d.br <= 0) {
                Log.w(TAG, "id=$id br=$br 回链但 br=0，跳过")
                continue
            }
            return@withContext GdLink(url = u, br = d.br, size = d.size)
        }
        null
    }

    /** 只要直链的旧接口，等价于 [resolve] 取 `url`。 */
    suspend fun url(id: String, brs: List<Int> = listOf(BR_FLAC24, BR_320, BR_128)): String? =
        resolve(id, brs)?.url

    /** 封面（传入网易云的 pic_id） */
    suspend fun pic(picId: String, size: Int = 500): String? = withContext(Dispatchers.IO) {
        val body = get("types=pic&source=netease&id=$picId&size=$size") ?: return@withContext null
        runCatching { json.decodeFromString<GdUrl>(body).url }.getOrNull()?.ifEmpty { null }
    }

    /** 歌词（LRC 文本），网易云歌词覆盖比 lrclib 好 */
    suspend fun lyric(songId: String): String? = withContext(Dispatchers.IO) {
        val body = get("types=lyric&source=netease&id=$songId") ?: return@withContext null
        runCatching { json.decodeFromString<GdLyric>(body).lyric }.getOrNull()?.ifEmpty { null }
    }

    /**
     * 按「歌名 + 歌手」搜索并取词。
     *
     * 为什么需要它：酷我源的歌**没有网易云 id**，直接拿 sourceId 调 [lyric] 无从下手。
     * 而这类歌在网易云往往有（实测 9 首里 8 首能严格匹配到，含 Live 版）。
     *
     * ⚠️ 校验必须严格，用的是和 lrclib 那条路同一套 [LyricQuery.verify] ——
     * 否则会把「同名但别人的版本」的词配上来，那比「暂无歌词」更糟。
     *
     * @return 命中的歌词文本；没有严格匹配的结果就返回 null
     */
    suspend fun lyricByName(
        title: String,
        artist: String,
        wantSec: Long = 0
    ): String? = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext null
        val body = runCatching {
            get("types=search&source=netease&name=${enc(title)}&count=10&pages=1")
        }.getOrNull() ?: return@withContext null

        val list = runCatching {
            json.decodeFromString<List<GdTrack>>(body)
        }.getOrNull() ?: return@withContext null

        // 严格匹配的候选，按时长接近度挑最像的
        var bestText: String? = null
        var bestDist = Long.MAX_VALUE
        for (x in list) {
            val ar = x.artist.orEmpty().joinToString("/")
            if (!LyricQuery.verify(x.name, ar, title, artist)) continue
            val id = (x.urlId ?: x.id).ifEmpty { null } ?: continue
            val text = runCatching { lyric(id) }.getOrNull()
            if (text.isNullOrEmpty()) continue
            val dist = LyricQuery.durationDistance(wantSec, 0)   // gdstudio 不返回时长
            if (dist < bestDist) {
                bestDist = dist
                bestText = text
            }
        }
        bestText
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    /**
     * 发一次 GET，`q` 是 `api.php?` 之后的查询串（不含域名）。
     *
     * 两级兜底 + 抖动重试：
     * 1. `API_PRIMARY` 最多试 [RETRY_TIMES] 次（间隔 300/600ms），吃掉 503 抖动；
     * 2. primary 彻底失败再给 `API_BACKUP` 一次机会 —— 它现在只有 playlist 能用，
     *    其余接口必然 401，但拿一次 401 只花几百毫秒，换取「哪天修好自动复活」。
     *
     * 401/403/404 属**确定性错误**（这台没挂该接口 / 无此版权），不重试，直接换下一台。
     *
     * @param deadline 绝对时间戳（`System.currentTimeMillis()` 口径）。传 >0 时，
     *   一旦超时就立即放弃 —— 播放取链不能为了重试把用户干等一分钟。
     */
    private fun get(q: String, deadline: Long = 0): String? {
        for (host in listOf(API_PRIMARY, API_BACKUP)) {
            var i = 0
            while (i < RETRY_TIMES) {
                if (deadline > 0 && System.currentTimeMillis() >= deadline) {
                    Log.w(TAG, "取链超时放弃: $q")
                    return null
                }
                val r = once("$host?$q")
                if (r != null) return r
                // 401/403/404 是确定性错误，别在这台上耗，直接换下一台
                if (lastCode in DETERMINISTIC_FAIL) break
                if (i < RETRY_TIMES - 1) Thread.sleep(300L * (i + 1))
                i++
            }
        }
        return null
    }

    /** 最近一次 HTTP 状态码，供 [get] 判断该不该重试 */
    @Volatile
    private var lastCode: Int = 0

    private fun once(url: String): String? = try {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "LX-Music-Mobile")
            .build()
        client.newCall(req).execute().use { resp ->
            lastCode = resp.code
            if (!resp.isSuccessful) {
                Log.w(TAG, "HTTP ${resp.code} for $url")
                null
            } else {
                resp.body?.string()?.takeIf { it.isNotBlank() }
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "GET failed: ${e.message}")
        lastCode = -1
        null
    }
}

@Serializable
data class GdTrack(
    val id: String = "",
    // picId 保留：补封面时要用它调 types=pic
    val name: String = "",
    /** 该接口返回的是数组，不是字符串 */
    val artist: List<String>? = null,
    val album: String? = null,
    @SerialName("pic_id") val picId: String? = null,
    @SerialName("url_id") val urlId: String? = null,
    @SerialName("lyric_id") val lyricId: String? = null,
    val source: String? = null
)

@Serializable
data class GdUrl(val url: String? = null, val br: Int = 0, val size: Long = 0)

/** 取链结果：`br` 决定这条到底算不算无损，不要只看 `url` 非空就写「无损」 */
data class GdLink(val url: String, val br: Int, val size: Long) {
    val isLossless: Boolean get() = br >= GdStudioClient.LOSSLESS_MIN_BR
}

@Serializable
data class GdLyric(val lyric: String? = null)

// ---------------- 歌单详情（网易云原生结构，非扁平） ----------------

@Serializable
data class GdPlaylistResp(
    val playlist: GdPlaylistNode? = null,
    val result: GdPlaylistNode? = null
)

@Serializable
data class GdPlaylistNode(
    val id: Long = 0,
    val name: String? = null,
    val trackCount: Int = 0,
    val coverImgUrl: String? = null,
    val description: String? = null,
    val tracks: List<GdPlTrack>? = null
)

@Serializable
data class GdPlTrack(
    val id: Long = 0,
    val name: String = "",
    val ar: List<GdPlArtist>? = null,
    val al: GdPlAlbum? = null,
    /** 时长，毫秒 */
    val dt: Long = 0
)

@Serializable
data class GdPlArtist(val id: Long = 0, val name: String? = null)

@Serializable
data class GdPlAlbum(val id: Long = 0, val name: String? = null, val picUrl: String? = null)

/** 把 gdstudio 的搜索结果转成统一 Track（source = netease） */
fun GdTrack.toTrack(): Track = Track(
    id = "gd:${id}",
    name = name,
    artist = artist.orEmpty().joinToString("/"),
    album = album.orEmpty(),
    // 封面**不能**由 pic_id 直接拼：实测 pN.music.126.net/<id>.jpg 一律 404，
    // 真实 URL 带一段不可推导的 hash，只能再调 types=pic 取。
    // 这里留空，由上层并发补封面（见 enrichNeteaseCovers）。
    coverUrl = null,
    source = "netease",
    // url_id 才是取链用的 id，个别条目和 id 不同
    sourceId = (urlId ?: id).ifEmpty { id }
)
