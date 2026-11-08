package com.kmusic.apkhd.net

import android.util.Log
import com.kmusic.apkhd.model.Track
import com.kmusic.apkhd.model.PlaylistBrief
import com.kmusic.apkhd.model.AlbumBrief
import com.kmusic.apkhd.model.ArtistBrief
import com.kmusic.apkhd.model.ChartBrief
import com.kmusic.apkhd.model.ChartSection
import com.kmusic.apkhd.crypto.KuwoCipher
import com.kmusic.apkhd.crypto.QmcV2Rc4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 酷我音源客户端（母带 / 无损 / 高品 / 标准）
 *
 * 接口链（参考 HotDownloader Rust 源码复刻）：
 * 1. search.kuwo.cn/r.s 搜索
 * 2. mobi.kuwo.cn/mobi.s 取直链 + ekey
 * 3. ekey → QMC key → 流式解密
 */
class KuwoClient(
    private val user: String = "C_APK_guanwang_${System.nanoTime()}${(0..999999).random()}",
    private val androidId: String = DEFAULT_ANDROID_ID,
    private val source: String = DEFAULT_SOURCE
) {

    companion object {
        private const val TAG = "KuwoClient"
        private const val SEARCH_URL = "http://search.kuwo.cn/r.s"
        private const val MOBI_URL = "http://mobi.kuwo.cn/mobi.s"
        private const val CHART_URL = "http://kbangserver.kuwo.cn/ksong.s"
        private const val PLAYLIST_URL = "http://nplserver.kuwo.cn/pl.svc"
        private const val LYRIC_URL = "https://m.kuwo.cn/newh5/singles/songinfoandlrc"

        /** 常用榜单 id：16 热歌榜 / 93 飙升榜 / 17 新歌榜 / 18 经典老歌 / 6 华语榜 */
        const val BANG_HOT = "16"
        const val BANG_RISING = "93"
        const val BANG_NEW = "17"

        private const val DEFAULT_USER = "1234567890"
        private const val DEFAULT_ANDROID_ID = "abcdef0123456789"

        /**
         * **车载播放器** source。
         *
         * 2026-10 起手机端 kwplayer 源对未授权音质只返回 11 秒试听（响应 duration=11），
         * 320/无损/母带全部失效；伪装官方车载播放器则能拿到完整音频。
         * 沿用手机端 source 会让每一首歌都变成 11 秒的「酷我音乐已为你开通」提示音。
         */
        private const val DEFAULT_SOURCE = "kwplayercar_ar_6.0.0.9_B_jiakong_vh.apk"

        /**
         * 试听片段阈值。
         *
         * 酷我会把「没授权的音质」返回成 11 秒的试听片段（响应里的 duration 字段就是 11），
         * 播放器照样能播 —— 用户听到的是「酷我音乐已为你开通…」的提示音。
         * 所以**不能相信请求的音质参数生效了**，必须按实际时长剔除。
         */
        private const val MIN_VALID_DURATION_MS = 60_000L

        /** 音质档位，br 值为酷我接口标准参数 */
        const val BR_MASTER = "20900kmflac"   // 臻品母带 24bit/192kHz（需 VIP）
        const val BR_HIRES = "2000kflac"      // 无损 FLAC
        const val BR_HIGH = "320kmp3"         // 极高品 MP3
        const val BR_STANDARD = "128kmp3"     // 标准

        // ── 实际音质分级（v2.4.10）──
        //
        // **必须按响应里的 `format` + `bitrate` 判，不能按请求的参数判。**
        // 实测：请求 `20900kmflac`，酷我会老老实实返回
        // `{"format":"mp3","bitrate":128,"duration":211}` —— 即一首完整的 128k mp3。
        // 它既不报错、也不是 11 秒试听片段，只看「成功/失败」根本发现不了，
        // 结果是界面挂「臻品母带」、耳朵听到 128k。
        const val RANK_NONE = 0     // 拿不到 / 未知
        const val RANK_LOSSY = 1    // mp3/aac/ogg，且 < 256kbps
        const val RANK_HIGH = 2     // ≥256kbps 有损（320k）
        const val RANK_FLAC = 3     // 无损（flac）
        const val RANK_MASTER = 4   // 母带（mflac / mgg）

        /** 响应里的格式 + 码率 → 分级。未知格式一律 RANK_NONE，宁可当没拿到。 */
        fun rankOf(format: String?, kbps: Int): Int {
            val f = format?.lowercase()?.trim().orEmpty()
            return when {
                f.startsWith("mflac") || f.startsWith("mgg") -> RANK_MASTER
                f == "flac" || f == "ape" -> RANK_FLAC
                f == "mp3" || f == "aac" || f == "ogg" || f == "opus" || f == "wma" ->
                    if (kbps >= 256) RANK_HIGH else RANK_LOSSY
                else -> RANK_NONE
            }
        }

        /** 分级 → 界面标签。与 [rankOf] 成对使用，保证「标签 = 实际」 */
        fun labelOf(format: String?, kbps: Int): String? {
            val f = format?.lowercase()?.trim().orEmpty()
            if (f.isEmpty()) return null
            return when (rankOf(f, kbps)) {
                RANK_MASTER -> "臻品母带"
                RANK_FLAC -> "无损 FLAC"
                RANK_HIGH -> "320k"
                RANK_LOSSY -> if (kbps > 0) "${kbps}kbps" else "低音质"
                else -> null
            }
        }
    }

    /**
     * 音质降级链：从请求档位开始逐级往下退。
     *
     * 2026-10 起酷我对未授权的高音质统一返回 11 秒试听，母带/无损/320 全部失效，
     * 只有 128k 是完整的。所以取流必须能自动退档，否则整首歌都是提示音。
     */
    private fun qualityChain(br: String): List<String> = when (br) {
        BR_MASTER -> listOf(BR_MASTER, BR_HIRES, BR_HIGH, BR_STANDARD)
        BR_HIRES -> listOf(BR_HIRES, BR_HIGH, BR_STANDARD)
        BR_HIGH -> listOf(BR_HIGH, BR_STANDARD)
        else -> listOf(BR_STANDARD)
    }

    /**
     * 本进程内**已知只给试听片段**的档位。
     *
     * 只用来跳过确定拿不到的档位，避免每首歌都白发几个请求。
     * 注意这里记的是「失败」而不是「上次成功」——
     * 如果记上次成功并把它提到队首，用户从 320 切回无损时会一直命中 320，永远回不去。
     */
    @Volatile
    private var failedBrs: Set<String> = emptySet()

    /** 用户主动切档位时清空记录：他想重新试一遍，不能被历史失败挡住 */
    fun resetQualityProbe() {
        failedBrs = emptySet()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private fun msisdnParam() = "&msisdn=$user"

    /** 搜索歌曲，返回 (rid, 名称, 歌手, 专辑, 封面) 列表 */
    suspend fun search(keyword: String, page: Int = 1, size: Int = 30): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val url = "$SEARCH_URL?all=${urlEncode(keyword)}&ft=music&client=kt&encoding=utf8" +
                "&rformat=json&pcjson=1&itemset=web_2013&vipver=1&mobi=1&newver=1" +
                "&pn=${page - 1}&rn=$size"
            val text = get(url)
            val resp = try {
                KuwoJson.instance.decodeFromString<KuwoSearchResponse>(normalizeJson(text))
            } catch (e: Exception) {
                Log.w(TAG, "search parse failed: ${e.message}")
                return@withContext emptyList()
            }
            resp.abslist?.mapNotNull { s ->
                val rid = s.musicRid?.removePrefix("MUSIC_")?.ifEmpty { null } ?: return@mapNotNull null
                SearchResult(
                    rid = rid,
                    name = (s.songName ?: s.NAME)?.cleanHtml(),
                    artist = (s.ARTISTNAME ?: s.ARTIST)?.cleanHtml(),
                    album = (s.ALBUMNAME ?: s.ALBUM)?.cleanHtml(),
                    cover = coverUrl(s.web_albumpic_short),
                    durationMs = (s.durationSec?.toLongOrNull() ?: 0L) * 1000L
                )
            } ?: emptyList()
        }

    /** 获取母带/无损/高清直链 + ekey（同样按降级链剔除试听片段） */
    suspend fun getLink(rid: String, br: String = BR_MASTER): SongLink? =
        withContext(Dispatchers.IO) {
            for (tryBr in orderedChain(br)) {
                val l = fetchLink(rid, tryBr) ?: continue
                if (l.durationMs <= 0L || l.durationMs >= MIN_VALID_DURATION_MS) return@withContext l
                failedBrs = failedBrs + tryBr
                Log.w(TAG, "音质 $tryBr 只拿到 ${l.durationMs / 1000}s 试听片段，降级")
            }
            null
        }

    private suspend fun fetchLink(rid: String, br: String): SongLink? {
        val url = "$MOBI_URL?f=web&user=$user&android_id=$androidId&source=$source" +
            "&type=convert_url_with_sign&from=PC&rid=$rid&br=$br&format=${formatOf(br)}" + msisdnParam()
        val text = get(url)
        val resp = try {
            KuwoJson.instance.decodeFromString<KuwoLinkResponse>(text)
        } catch (e: Exception) {
            Log.w(TAG, "link parse failed: ${e.message}")
            return null
        }
        val data = resp.data ?: return null
        val u = data.url?.ifEmpty { null } ?: return null
        return SongLink(url = u, ekey = null, br = data.br ?: br, durationMs = data.duration * 1000L)
    }

    /**
     * 完整取链：拿直链 + ekey，返回可直接播放的加密流。
     *
     * 按降级链逐档尝试，**把试听片段挡在外面** —— 详见 [MIN_VALID_DURATION_MS]。
     */
    suspend fun resolve(rid: String, br: String = BR_MASTER): ResolvedSong? =
        withContext(Dispatchers.IO) {
            for (tryBr in orderedChain(br)) {
                val r = fetchResolved(rid, tryBr) ?: continue
                // duration <= 0 表示接口没给时长，不能据此判死，放行
                if (r.durationMs > 0L && r.durationMs < MIN_VALID_DURATION_MS) {
                    failedBrs = failedBrs + tryBr
                    Log.w(TAG, "音质 $tryBr 只拿到 ${r.durationMs / 1000}s 试听片段，降级")
                    continue
                }
                return@withContext r
            }
            null
        }

    /**
     * 取链，并且**只接受达到 [minRank] 的结果**（v2.4.10）。
     *
     * 存在的理由：酷我对「没有该音质授权」的处理是**静默降档** ——
     * 请求 `20900kmflac`，它回 `format=mp3&bitrate=128`，时长完整、URL 可用，
     * 一切「成功」。只看 null / 非 null 会被它骗。
     *
     * 与 [resolve] 的区别：这里每一档都要校验实际格式，达不到就继续往下一档试，
     * 全部达不到返回 null —— 由调用方决定「宁可放别的源」还是「就在这一档凑合」。
     *
     * @param minRank 最低可接受分级（[RANK_LOSSY] / [RANK_HIGH] / [RANK_FLAC] / [RANK_MASTER]）
     */
    suspend fun resolveAtLeast(rid: String, minRank: Int, br: String = BR_MASTER): ResolvedSong? =
        withContext(Dispatchers.IO) {
            // 只有母带档可能给出母带，低档位不必白试三次
            val chain = if (minRank >= RANK_MASTER) listOf(BR_MASTER) else orderedChain(br)
            for (tryBr in chain) {
                val r = fetchResolved(rid, tryBr) ?: continue
                if (r.durationMs > 0L && r.durationMs < MIN_VALID_DURATION_MS) {
                    failedBrs = failedBrs + tryBr
                    continue
                }
                if (r.rank < minRank) {
                    // 降档发生在服务端，跟请求档位无关；记下来少发几次请求
                    Log.w(TAG, "音质 $tryBr 实际只有 ${r.format}/${r.kbps}kbps（rank=${r.rank}），不够 $minRank")
                    continue
                }
                return@withContext r
            }
            null
        }

    /** 跳过已知拿不到完整音频的档位，保持原有优先级顺序 */
    private fun orderedChain(br: String): List<String> {
        val chain = qualityChain(br)
        val filtered = chain.filter { it !in failedBrs }
        return filtered.ifEmpty { chain }
    }

    private suspend fun fetchResolved(rid: String, br: String): ResolvedSong? {
        val url = "$MOBI_URL?f=web&user=$user&android_id=$androidId&source=$source" +
            "&type=convert_url_with_sign&from=PC&rid=$rid&br=$br&format=${formatOf(br)}" + msisdnParam()
        val text = get(url)
        val resp = try {
            KuwoJson.instance.decodeFromString<KuwoLinkResponse>(text)
        } catch (e: Exception) {
            return null
        }
        val data = resp.data ?: return null
        val u = data.url?.ifEmpty { null } ?: return null
        val rawBr = data.bitrate?.toIntOrNull() ?: 0
        // ⚠️ 酷我的 `bitrate` 字段对母带/无损给的是**档位代号**（20900 / 2000），
        // 不是真码率：20900 当成 kbps 会算出 547MB 的单曲体积（真实约 140MB）。
        // 只有 <= 1000 的值才可信（320/128 这些 mp3 档确实报的是 kbps）。
        // 母带真实码率靠 Content-Length / 时长反算，见 probeRealKbps。
        val kbps = rawBr.takeIf { it in 1..1000 } ?: 0
        val size = data.size?.toLongOrNull()?.takeIf { it > 0 }
            ?: probeContentLength(u)
        val realKbps = when {
            kbps > 0 -> kbps
            size != null && size > 0 && data.duration > 0 ->
                (size * 8L / data.duration / 1000L).toInt()
            else -> 0
        }
        return ResolvedSong(
            rid = rid,
            url = u,
            br = data.br ?: br,
            durationMs = data.duration * 1000L,
            ekey = data.ekey?.ifEmpty { null },
            format = data.format,
            kbps = realKbps,
            sizeBytes = size ?: 0L
        )
    }

    /**
     * 用 Range 请求读 `Content-Range` 里的总字节数。
     *
     * 只取 1 字节，不下载整首；CDN 支持 Range 时一次往返就够。
     * 拿不到就返回 null —— 宁可不显示体积，也不显示错的。
     */
    private fun probeContentLength(url: String): Long? = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "okhttp/4.10.0")
            .header("Referer", "http://www.kuwo.cn/")
            .header("Range", "bytes=0-0")
            .build()
        client.newCall(req).execute().use { resp ->
            // Content-Range: bytes 0-0/141123456
            resp.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                ?: resp.header("Content-Length")?.toLongOrNull()
        }
    }.getOrNull()

    /**
     * 榜单封面归一化。v9_pic2 形如 `.../albumcover/120/s4s6/89/4105923142.jpg`，
     * 把尺寸段换成 500 取大图（实测 /500/ 同样 200，35KB vs 4KB）。
     * 路径里还有别的数字，所以必须锚定 `/albumcover/<数字>/` 这一段替换。
     */
    private fun v9Pic(raw: String?): String? {
        val u = raw?.trim()?.ifEmpty { null } ?: return null
        return Regex("(/albumcover/)\\d+(/)").replaceFirst(u, "$1500$2")
    }

    /**
     * 封面 URL。
     *
     * web_albumpic_short 形如 `120/s4s81/95/2497366108.jpg`（**自带尺寸前缀**）。
     * 早先直接拼 `albumcover/500$short` 会得到 `500120/...`（实测返回 2048px 大图，浪费流量），
     * 这里先剥掉前缀里的尺寸段再按需指定。
     */
    internal fun coverUrl(short: String?, size: Int = 500): String? {
        val v = short?.ifEmpty { null } ?: return null
        val path = v.replace(Regex("^\\d+/"), "")
        return "https://img1.kuwo.cn/star/albumcover/$size/$path"
    }

    /**
     * 排行榜歌曲。
     *
     * @return 榜单名 to 歌曲列表
     *
     * 两处实测坑（都已按真实响应修）：
     * 1. 时长：`duration` 是垃圾值（实测 2/6/9/78，同曲真实 208），必须读 `song_duration`
     * 2. 封面：`pic` 在飙升榜返回的是**没有文件名的目录**（.../BangPic/），取出来是坏图；
     *    必须用 `v9_pic2`（始终是完整 URL）
     */
    suspend fun chart(bangId: String = BANG_HOT, pn: Int = 0, rn: Int = 50): ChartSection? =
        withContext(Dispatchers.IO) {
            val url = "$CHART_URL?from=pc&fmt=json&pn=$pn&rn=$rn&type=bang&data=content" +
                "&id=$bangId&show_copyright_off=1"
            val resp = runCatching {
                KuwoJson.instance.decodeFromString<KuwoChartResponse>(get(url))
            }.onFailure { Log.w(TAG, "chart parse: ${it.message}") }.getOrNull() ?: return@withContext null
            val list = resp.musiclist.orEmpty()
                .filter { !it.id.isNullOrEmpty() && !it.name.isNullOrEmpty() }
                .map { it.toTrack(it.durationMsSafe()) }
            if (list.isEmpty()) return@withContext null
            ChartSection(
                chart = ChartBrief(
                    id = bangId,
                    name = resp.name ?: "排行榜",
                    coverUrl = v9Pic(resp.v9Pic2),
                    songCount = resp.num?.toIntOrNull() ?: list.size,
                    source = "kuwo"
                ),
                tracks = list
            )
        }

    /** 歌单详情歌曲（nplserver pl.svc，返回的 duration 是可信的真实时长） */
    /**
     * 歌单全部歌曲。
     *
     * ⚠️ 必须翻页：`rn` 上限 100（实测传 1000 仍只回 100），超出部分要靠 `pn` 继续取。
     * 只取第一页的话，界面显示「200 首」点进去只有 100 首。
     * 实测翻页正常：35 首的歌单 pn=0 拿满，pn=1 起为空。
     */
    suspend fun playlistSongs(pid: String): List<Track> = withContext(Dispatchers.IO) {
        val out = mutableListOf<Track>()
        val seen = mutableSetOf<String>()
        var pn = 0
        while (pn < 50) {   // 上限 50 页 ×100 = 5000 首，够任何歌单
            val url = "$PLAYLIST_URL?op=getlistinfo&pid=$pid&pn=$pn&rn=100&encode=utf8" +
                "&keyset=pl2012&identity=kuwo&pcmp4=1&vipver=MUSIC_9.1.1.2&newver=1"
            val resp = runCatching {
                KuwoJson.instance.decodeFromString<KuwoPlaylistDetail>(get(url))
            }.onFailure { Log.w(TAG, "playlistSongs parse pn=$pn: ${it.message}") }.getOrNull()
                ?: break
            val batch = resp.musiclist.orEmpty()
                .filter { !it.id.isNullOrEmpty() && !it.name.isNullOrEmpty() }
                .filter { seen.add(it.id!!) }        // 去重，防止服务端重复返回
                .map { it.toTrack(it.durationMsSafe()) }
            if (batch.isEmpty()) break              // 空页 = 到底了
            out += batch
            if (batch.size < 100) break             // 不满页 = 最后一页
            pn++
        }
        out
    }

    /** 歌单搜索（r.s?ft=playlist） */
    suspend fun searchPlaylists(keyword: String, rn: Int = 20): List<PlaylistBrief> =
        withContext(Dispatchers.IO) {
            val url = "$SEARCH_URL?all=${urlEncode(keyword)}&ft=playlist&client=kt&encoding=utf8" +
                "&rformat=json&vipver=1&mobi=1&newver=1&pn=0&rn=$rn"
            val resp = runCatching {
                KuwoJson.instance.decodeFromString<KuwoPlaylistSearchResponse>(normalizeJson(get(url)))
            }.onFailure { Log.w(TAG, "searchPlaylists parse: ${it.message}") }.getOrNull()
                ?: return@withContext emptyList()
            resp.abslist.orEmpty().mapNotNull { it.toBrief() }
        }

    /** 歌手搜索（r.s?ft=artist） */
    suspend fun searchArtists(keyword: String, rn: Int = 20): List<ArtistBrief> =
        withContext(Dispatchers.IO) {
            val url = "$SEARCH_URL?all=${urlEncode(keyword)}&ft=artist&client=kt&encoding=utf8" +
                "&rformat=json&vipver=1&mobi=1&newver=1&pn=0&rn=$rn"
            val resp = runCatching {
                KuwoJson.instance.decodeFromString<KuwoArtistSearchResponse>(normalizeJson(get(url)))
            }.onFailure { Log.w(TAG, "searchArtists parse: ${it.message}") }.getOrNull()
                ?: return@withContext emptyList()
            resp.abslist.orEmpty().mapNotNull { a ->
                val id = a.artistId?.ifEmpty { null } ?: return@mapNotNull null
                ArtistBrief(
                    id = id,
                    name = a.artist?.cleanHtml().orEmpty(),
                    picUrl = a.picPath?.ifEmpty { null }
                        ?.replace(Regex("^\\d+/"), "")
                        ?.let { "https://img1.kuwo.cn/star/starheads/500/$it" },
                    songCount = a.songNum?.toIntOrNull() ?: 0,
                    albumCount = a.albumNum?.toIntOrNull() ?: 0,
                    source = "kuwo"
                )
            }
        }

    /**
     * 专辑搜索（r.s?ft=album）。
     *
     * ⚠️ 酷我**没有**「按专辑 id 取歌曲列表」的可用端点（实测 pl.svc 传专辑 id 返回空
     * musiclist，web 的 albumInfo 未带 kw_token 直接被拒）。所以专辑详情走
     * 「按专辑名搜歌 + 严格比对 ALBUM 字段」，具体见 ViewModel。
     */
    suspend fun searchAlbums(keyword: String, rn: Int = 50): List<AlbumBrief> =
        withContext(Dispatchers.IO) {
            // 关键词如果是**歌手名**，必须走 artistid 取专辑。
            // 按专辑名搜「周杰伦」只会搜到一堆同名杂碟（别人的专辑恰好叫《周杰伦》），
            // 而 `ft=album&artistid=336` 直接给出他名下 39 张真专辑。
            val artistId = runCatching { searchArtists(keyword, 5) }
                .getOrDefault(emptyList())
                .let { list ->
                    ArtistMatch.pickArtistId(
                        keyword,
                        list.map { a -> a.name },
                        list.map { a -> a.id }
                    )
                }
            val url = if (artistId != null) {
                "$SEARCH_URL?ft=album&artistid=$artistId&client=kt&encoding=utf8" +
                    "&rformat=json&pcjson=1&itemset=web_2013&vipver=1&mobi=1&newver=1&pn=0&rn=$rn"
            } else {
                "$SEARCH_URL?all=${urlEncode(keyword)}&ft=album&client=kt&encoding=utf8" +
                    "&rformat=json&pcjson=1&itemset=web_2013&vipver=1&mobi=1&newver=1&pn=0&rn=$rn"
            }
            val resp = runCatching {
                KuwoJson.instance.decodeFromString<KuwoAlbumSearchResponse>(normalizeJson(get(url)))
            }.onFailure { Log.w(TAG, "searchAlbums parse: ${it.message}") }.getOrNull()
                ?: return@withContext emptyList()
            resp.albumlist.orEmpty().mapNotNull { a ->
                val id = a.albumid?.ifEmpty { null } ?: a.id?.ifEmpty { null } ?: return@mapNotNull null
                val n = a.name?.cleanHtml()?.ifEmpty { null } ?: return@mapNotNull null
                AlbumBrief(
                    id = id,
                    name = n,
                    artist = a.artist?.cleanHtml().orEmpty(),
                    coverUrl = a.img?.ifEmpty { null } ?: a.hts_img?.ifEmpty { null },
                    songCount = a.musiccnt?.toIntOrNull() ?: 0,
                    source = "kuwo"
                )
            }
        }

    /**
     * 按 rid 精确反查封面。
     *
     * 榜单/歌单接口不返回封面，且 pN.kuwo 的 `albumcover/<rid>.jpg` 一律 404，
     * 唯一可靠途径是搜一次歌名，再从结果里**按 rid 严格相等**取封面。
     * 用 rid 相等而非歌名相似来判定，所以不存在错配（取到别人的封面）。
     */
    suspend fun coverByRid(rid: String, name: String, artist: String): String? {
        if (rid.isEmpty() || name.isEmpty()) return null
        val hits = runCatching { search("$name $artist", page = 1, size = 20) }
            .getOrDefault(emptyList())
        return hits.firstOrNull { it.rid == rid }?.cover
    }

    private fun KuwoPlainSong.toTrack(durationMs: Long) = Track(
        id = "kw:$id",
        name = name?.cleanHtml().orEmpty(),
        artist = artist?.cleanHtml().orEmpty(),
        album = album?.cleanHtml().orEmpty(),
        durationMs = durationMs,
        source = "kuwo",
        sourceId = id.orEmpty()
    )

    private fun KuwoPlaylistItem.toBrief(): PlaylistBrief? {
        val pid = (targetId ?: playlistId)?.ifEmpty { null } ?: return null
        return PlaylistBrief(
            id = pid,
            name = name?.cleanHtml().orEmpty(),
            coverUrl = pic?.ifEmpty { null } ?: hts_pic?.ifEmpty { null },
            trackCount = songnum?.toIntOrNull() ?: 0,
            playCount = playcnt?.toLongOrNull() ?: 0L,
            source = "kuwo",
            creator = nickname?.cleanHtml().orEmpty(),
            description = intro?.cleanHtml().orEmpty()
        )
    }

    /** 用 ekey 创建解密器，为播放器提供解密后的音频流 */
    /**
     * 取流用的 user 标识。
     *
     * ekey 解密要求「解密后的前缀 == 取流时的 user」（QmcV2Rc4.createFromEkey），
     * 所以下载链路必须拿到这个值，不能自己写死一个。
     */
    val userTag: String get() = user

    fun createCipher(ekey: String): QmcV2Rc4? = KuwoCipher.createFromEkey(ekey, user)

    /**
     * 仅取歌曲元数据（不取播放链）—— 用于「最近播放」回查显示名称/封面。
     * 走 mobi.s 但只需要 dur 与封面，不解析 ekey。
     */
    suspend fun resolveMeta(rid: String): SongLink? = getLink(rid, BR_HIGH)

    private fun formatOf(br: String): String = when {
        br.endsWith("kmflac") -> "mflac"
        br.endsWith("kflac") -> "flac"
        br.endsWith("kape") -> "ape"
        br.endsWith("kwma") -> "wma"
        else -> "mp3"
    }

    /**
     * 酷我 r.s 接口返回的是单引号伪 JSON（Python 字典字面量风格），
     * kotlinx.serialization 无法直接解析，这里把它转成标准 JSON。
     * 该接口返回格式稳定：键与字符串值用单引号，无嵌套引号转义问题。
     */
    internal fun normalizeJson(text: String): String {
        if (!text.startsWith("{")) return text
        val sb = StringBuilder(text.length + 16)
        var inStr = false
        var quote = ' '
        var escape = false
        for (ch in text) {
            when {
                escape -> { sb.append(ch); escape = false }
                ch == '\\' && inStr -> { sb.append(ch); escape = true }
                inStr && ch == quote -> { inStr = false; sb.append('"') }
                !inStr && (ch == '\'' || ch == '"') -> { inStr = true; quote = ch; sb.append('"') }
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun String.cleanHtml(): String =
        replace("&nbsp;", " ").replace("&amp;", "&")
            .replace("&quot;", "\"").replace("&#39;", "'")
            .replace("\\u0026", "&").trim()

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")


    /**
     * 酷我官方歌词（m.kuwo.cn newh5 接口）。
     *
     * 为什么排在所有歌词源的最前面：曲目本身来自酷我，歌词和音频是**同一个 rid**，
     * 天然同版本、不会串歌 —— 我们之前两个自动校验判据（时长差、歌名入正文）
     * 都是因为「跨源」才对不上，这里根本不存在跨源。
     *
     * 实测（2016 热歌榜 50 首）：19 首是酷我独有（lrclib 完全没有），
     * 而这些歌以前一律显示「暂无歌词」，因为只查了 lrclib / gdstudio。
     *
     * @return 标准 LRC 文本，交给 [LyricRepository.parseLyrics] 统一解析；无词返回 null
     */
    suspend fun lyricByRid(rid: String): String? = withContext(Dispatchers.IO) {
        // 只接纯数字 rid：跨源回退后 sourceId 会变成 `ne:xxx`，那不是酷我的 id
        if (rid.isBlank() || !rid.all { it.isDigit() }) return@withContext null
        val raw = runCatching { get("$LYRIC_URL?musicId=$rid") }.getOrNull()
            ?: return@withContext null
        lrclistToLrc(raw)
    }

    /** 酷我歌词 JSON → 标准 LRC 文本。抽成 internal 纯函数是为了能单测（不碰网络）。 */
    internal fun lrclistToLrc(raw: String): String? {
        val arr = runCatching {
            org.json.JSONObject(raw).optJSONObject("data")?.optJSONArray("lrclist")
        }.getOrNull() ?: return null
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val text = o.optString("lineLyric").trim()
            if (text.isEmpty()) continue
            val sec = o.optString("time").toDoubleOrNull() ?: continue
            sb.append('[').append(lrcTag(sec)).append(']').append(text).append('\n')
        }
        return if (sb.isEmpty()) null else sb.toString()
    }

    /** 秒 → `mm:ss.cc`（百分秒四舍五入，用整数运算避免 59.995 这类进位误差） */
    private fun lrcTag(sec: Double): String {
        val cs = Math.round(sec * 100)
        val mm = cs / 6000
        val ss = (cs % 6000) / 100
        return String.format(java.util.Locale.ROOT, "%02d:%02d.%02d", mm, ss, cs % 100)
    }

    private fun get(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "okhttp/4.10.0")
            .header("Referer", "http://www.kuwo.cn/")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code} for $url")
            return resp.body?.string() ?: throw java.io.IOException("empty body")
        }
    }
}

data class SearchResult(
    val rid: String,
    val name: String?,
    val artist: String?,
    val album: String?,
    val cover: String?,
    val durationMs: Long = 0
)

data class SongLink(
    val url: String,
    val ekey: String?,
    val br: String,
    val durationMs: Long
)

data class ResolvedSong(
    val rid: String,
    val url: String,
    val br: String,
    val durationMs: Long,
    val ekey: String?,
    /** 响应里的真实容器格式（mp3 / flac / mflac / mgg / aac / ogg…）。空 = 老响应没给 */
    val format: String? = null,
    /** 响应里的真实码率（kbps）。0 = 没给。母带按体积÷时长反算，不取档位代号 */
    val kbps: Int = 0,
    /** 整首字节数（来自响应 size 或 Content-Range）。0 = 不知道 */
    val sizeBytes: Long = 0L
) {
    /** 实际音质分级，见 [KuwoClient.rankOf]。**这是「只升不降」唯一的判据**。 */
    val rank: Int get() = KuwoClient.rankOf(format, kbps)
}
