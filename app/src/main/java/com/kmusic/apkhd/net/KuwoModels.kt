package com.kmusic.apkhd.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class KuwoSearchResponse(
    val abslist: List<KuwoSong>? = null,
    val total: String? = null
)

@Serializable
data class KuwoSong(
    @SerialName("MUSICRID") val musicRid: String? = null,
    val NAME: String? = null,
    val ARTIST: String? = null,
    val ALBUM: String? = null,
    @SerialName("SONGNAME") val songName: String? = null,
    val ARTISTNAME: String? = null,
    val ALBUMNAME: String? = null,
    /** 搜索接口的时长字段（大写，单位秒） */
    @SerialName("DURATION") val durationSec: String? = null,
    val web_albumpic_short: String? = null,
    val htrain_albumpic_short: String? = null,
    val dc_targetid: String? = null,
    val fpay: String? = null,
    val isbatch: String? = null
)

@Serializable
data class KuwoLinkResponse(
    val code: Int = 0,
    val data: KuwoLinkData? = null
)

@Serializable
data class KuwoLinkData(
    val url: String? = null,
    @SerialName("bitrate") val bitrate: String? = null,
    @SerialName("ekey") val ekey: String? = null,
    val duration: Int = 0,
    val format: String? = null,
    /** 整首字节数。母带才有（20900 这档的 bitrate 是代号，不能当 kbps 用） */
    val size: String? = null,
    @SerialName("br") val br: String? = null
)

// ---------------- 排行榜（kbangserver.kuwo.cn/ksong.s） ----------------

@Serializable
data class KuwoChartResponse(
    val name: String? = null,
    val info: String? = null,
    /** 不可靠：飙升榜给的是**没有文件名的目录**（.../BangPic/），热歌榜才给完整路径 */
    val pic: String? = null,
    /** 可靠：始终是完整 URL，形如 .../albumcover/120/s4s6/89/4105923142.jpg */
    @SerialName("v9_pic2") val v9Pic2: String? = null,
    val num: String? = null,
    val pub: String? = null,
    val musiclist: List<KuwoPlainSong>? = null
)

/** 排行榜 / 歌单里的小写字段歌曲条目 */
@Serializable
data class KuwoPlainSong(
    val id: String? = null,
    val name: String? = null,
    val artist: String? = null,
    val album: String? = null,
    /** 不可靠：榜单里是垃圾值（实测 2/6/9/78，同曲真实 208），**绝不可用** */
    val duration: String? = null,
    /** 可靠的真实时长（秒）。榜单条目必须用这个 */
    @SerialName("song_duration") val songDuration: String? = null,
    @SerialName("albumid") val albumId: String? = null
)

/** 取可信时长（毫秒）：优先 song_duration，其次 duration */
fun KuwoPlainSong.durationMsSafe(): Long {
    val sec = songDuration?.toLongOrNull()?.takeIf { it > 0 }
        ?: duration?.toLongOrNull()?.takeIf { it > 0 }
        ?: 0L
    return sec * 1000L
}

// ---------------- 歌单详情（nplserver.kuwo.cn/pl.svc） ----------------

@Serializable
data class KuwoPlaylistDetail(
    val id: Long = 0,
    val title: String? = null,
    val info: String? = null,
    val pic: String? = null,
    val total: Int = 0,
    val musiclist: List<KuwoPlainSong>? = null
)

// ---------------- 歌单搜索（r.s?ft=playlist） ----------------

@Serializable
data class KuwoPlaylistSearchResponse(val abslist: List<KuwoPlaylistItem>? = null)

@Serializable
data class KuwoPlaylistItem(
    @SerialName("DC_TARGETID") val targetId: String? = null,
    @SerialName("playlistid") val playlistId: String? = null,
    val name: String? = null,
    val nickname: String? = null,
    val pic: String? = null,
    val hts_pic: String? = null,
    val songnum: String? = null,
    val playcnt: String? = null,
    val intro: String? = null
)

// ---------------- 歌手搜索（r.s?ft=artist） ----------------

@Serializable
data class KuwoArtistSearchResponse(val abslist: List<KuwoArtistItem>? = null)

@Serializable
data class KuwoArtistItem(
    @SerialName("ARTISTID") val artistId: String? = null,
    @SerialName("ARTIST") val artist: String? = null,
    @SerialName("PICPATH") val picPath: String? = null,
    @SerialName("SONGNUM") val songNum: String? = null,
    // 之前漏了这个字段 → 歌手行永远显示「0 张专辑」
    @SerialName("ALBUMNUM") val albumNum: String? = null,
    val desc: String? = null
)

object KuwoJson {
    val instance: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }
}

// ---------------- 专辑搜索（r.s?ft=album） ----------------
//
// 字段名实测确认：专辑名在 `name`（**不是** title，title 是空串），
// 曲目数是 `musiccnt`（字符串）。封面 `img` 与 `hts_img` 都是完整 URL。

@Serializable
data class KuwoAlbumSearchResponse(val albumlist: List<KuwoAlbumItem>? = null)

@Serializable
data class KuwoAlbumItem(
    val albumid: String? = null,
    val id: String? = null,
    val name: String? = null,
    val artist: String? = null,
    val img: String? = null,
    val hts_img: String? = null,
    val musiccnt: String? = null
)
