package com.kmusic.apkhd.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Credentials
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Navidrome / Subsonic API 客户端。
 *
 * 认证：Subsonic 标准的用户名 + 密码（token 模式：md5(password) + salt）。
 * 用户要求「仅账号密码授权」，所以不存 token、不做 OAuth。
 *
 * 只实现本应用需要的几个端点：ping（测连通）/ 搜索 / 专辑列表 / 播放地址。
 */
class NavidromeClient(
    private var baseUrl: String,
    private var username: String,
    private var password: String
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    var isConfigured: Boolean
        get() = baseUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()
        private set(_) {}

    fun update(base: String, user: String, pass: String) {
        baseUrl = base.trimEnd('/')
        username = user.trim()
        password = pass
    }

    /** 测试连通性 + 凭据是否有效 */
    suspend fun ping(): Result<String> = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext Result.failure(IllegalStateException("未配置"))
        try {
            val j = get("ping", emptyMap())
            Result.success(j.optString("version", "unknown"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 搜索歌曲（返回统一的 Track 列表） */
    suspend fun searchSongs(query: String, limit: Int = 30): List<com.kmusic.apkhd.model.Track> =
        withContext(Dispatchers.IO) {
            if (!isConfigured) return@withContext emptyList()
            try {
                val j = get("search3", mapOf("query" to query, "songCount" to limit.toString()))
                val songs = j.optJSONObject("searchResult3")?.optJSONArray("song") ?: return@withContext emptyList()
                (0 until songs.length()).mapNotNull { i ->
                    songs.optJSONObject(i)?.let { songJsonToTrack(it) }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

    /**
     * 曲库全部歌曲 —— search3 空查询 + songOffset 翻页。
     * Navidrome 对空 query 返回全库；页大小 500，5000 封顶防失控（单曲库 5k 以上的人
     * 一般也不会「整库随机播放」）。
     */
    suspend fun allSongs(pageSize: Int = 500, maxTotal: Int = 5000): List<com.kmusic.apkhd.model.Track> =
        withContext(Dispatchers.IO) {
            if (!isConfigured) return@withContext emptyList()
            val out = ArrayList<com.kmusic.apkhd.model.Track>(pageSize)
            var offset = 0
            try {
                while (out.size < maxTotal) {
                    val j = get("search3", mapOf(
                        "query" to "",
                        "songCount" to pageSize.toString(),
                        "songOffset" to offset.toString()
                    ))
                    val songs = j.optJSONObject("searchResult3")?.optJSONArray("song") ?: break
                    if (songs.length() == 0) break
                    for (i in 0 until songs.length()) {
                        songs.optJSONObject(i)?.let { out.add(songJsonToTrack(it)) }
                    }
                    if (songs.length() < pageSize) break
                    offset += songs.length()
                }
            } catch (_: Exception) { /* 拿到多少算多少 */ }
            out
        }

    private fun songJsonToTrack(s: org.json.JSONObject) = com.kmusic.apkhd.model.Track(
        id = "nd:${s.optString("id")}",
        name = s.optString("title").ifBlank { "未知曲目" },
        artist = s.optString("artist").ifBlank { "未知歌手" },
        album = s.optString("album").ifBlank { "" },
        coverUrl = coverUrl(s.optString("id")),
        durationMs = (s.optDouble("duration", 0.0) * 1000).toLong(),
        source = "navidrome",
        sourceId = s.optString("id"),
        qualityLabel = suffixToQuality(s.optString("suffix")),
        playUrl = streamUrl(s.optString("id"))
    )

    /** 专辑列表 */
    suspend fun albums(type: String = "newest", size: Int = 30): List<AlbumInfo> =
        withContext(Dispatchers.IO) {
            if (!isConfigured) return@withContext emptyList()
            try {
                val j = get("getAlbumList2", mapOf("type" to type, "size" to size.toString()))
                val arr = j.optJSONObject("albumList2")?.optJSONArray("album") ?: return@withContext emptyList()
                (0 until arr.length()).mapNotNull { i ->
                    val a = arr.optJSONObject(i) ?: return@mapNotNull null
                    AlbumInfo(
                        id = a.optString("id"),
                        name = a.optString("name").ifBlank { "未知专辑" },
                        artist = a.optString("artist").ifBlank { "" },
                        coverUrl = coverUrl(a.optString("id")),
                        songCount = a.optInt("songCount", 0),
                        year = a.optInt("year", 0)
                    )
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

    /** 专辑内歌曲 */
    suspend fun albumSongs(albumId: String): List<com.kmusic.apkhd.model.Track> =
        withContext(Dispatchers.IO) {
            if (!isConfigured) return@withContext emptyList()
            try {
                val j = get("getAlbum", mapOf("id" to albumId))
                val arr = j.optJSONObject("album")?.optJSONArray("song") ?: return@withContext emptyList()
                (0 until arr.length()).mapNotNull { i ->
                    val s = arr.optJSONObject(i) ?: return@mapNotNull null
                    com.kmusic.apkhd.model.Track(
                        id = "nd:${s.optString("id")}",
                        name = s.optString("title").ifBlank { "未知曲目" },
                        artist = s.optString("artist").ifBlank { "未知歌手" },
                        album = s.optString("album").ifBlank { "" },
                        coverUrl = coverUrl(s.optString("id")),
                        durationMs = (s.optDouble("duration", 0.0) * 1000).toLong(),
                        source = "navidrome",
                        sourceId = s.optString("id"),
                        qualityLabel = suffixToQuality(s.optString("suffix")),
                        playUrl = streamUrl(s.optString("id"))
                    )
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

    /**
     * 某歌手的专辑列表。
     *
     * Subsonic 的 getArtist 只返回「专辑」不返回歌曲 —— 所以歌手页做成
     * 歌手 → 专辑 → 歌曲 两级，而不是硬塞一个歌曲列表（那样只能靠
     * 遍历每张专辑去拼，既慢又容易漏）。
     */
    suspend fun artistAlbums(artistId: String): Pair<String, List<AlbumInfo>> =
        withContext(Dispatchers.IO) {
            if (!isConfigured) return@withContext "" to emptyList()
            try {
                val a = get("getArtist", mapOf("id" to artistId)).optJSONObject("artist")
                    ?: return@withContext "" to emptyList()
                val name = a.optString("name")
                val arr = a.optJSONArray("album") ?: return@withContext name to emptyList()
                val list = (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    AlbumInfo(
                        id = o.optString("id"),
                        name = o.optString("name").ifBlank { "未命名专辑" },
                        artist = o.optString("artist").ifBlank { name },
                        coverUrl = o.optString("coverArt").takeIf { it.isNotBlank() }?.let { coverUrl(it) },
                        songCount = o.optInt("songCount", 0),
                        year = o.optInt("year", 0)
                    )
                }
                name to list
            } catch (_: Exception) {
                "" to emptyList()
            }
        }

    /** 歌单内的歌曲（getPlaylist 直接返回 entry 数组） */
    suspend fun playlistSongs(playlistId: String): Pair<String, List<com.kmusic.apkhd.model.Track>> =
        withContext(Dispatchers.IO) {
            if (!isConfigured) return@withContext "" to emptyList()
            try {
                val p = get("getPlaylist", mapOf("id" to playlistId)).optJSONObject("playlist")
                    ?: return@withContext "" to emptyList()
                val name = p.optString("name")
                val arr = p.optJSONArray("entry") ?: return@withContext name to emptyList()
                val list = (0 until arr.length()).mapNotNull { i ->
                    val s = arr.optJSONObject(i) ?: return@mapNotNull null
                    com.kmusic.apkhd.model.Track(
                        id = "nd:${s.optString("id")}",
                        name = s.optString("title").ifBlank { "未知曲目" },
                        artist = s.optString("artist").ifBlank { "未知歌手" },
                        album = s.optString("album").ifBlank { "" },
                        coverUrl = coverUrl(s.optString("id")),
                        durationMs = (s.optDouble("duration", 0.0) * 1000).toLong(),
                        source = "navidrome",
                        sourceId = s.optString("id"),
                        qualityLabel = suffixToQuality(s.optString("suffix")),
                        playUrl = streamUrl(s.optString("id"))
                    )
                }
                name to list
            } catch (_: Exception) {
                "" to emptyList()
            }
        }

    /** 歌手列表 */
    suspend fun artists(): List<ArtistInfo> = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext emptyList()
        try {
            val j = get("getArtists", emptyMap())
            val arr = j.optJSONObject("artists")?.optJSONArray("index") ?: return@withContext emptyList()
            val out = mutableListOf<ArtistInfo>()
            for (i in 0 until arr.length()) {
                val idx = arr.optJSONObject(i) ?: continue
                val inner = idx.optJSONArray("artist") ?: continue
                for (k in 0 until inner.length()) {
                    val a = inner.optJSONObject(k) ?: continue
                    out += ArtistInfo(
                        id = a.optString("id"),
                        name = a.optString("name").ifBlank { "未知歌手" },
                        coverUrl = coverUrl(a.optString("id")),
                        albumCount = a.optInt("albumCount", 0)
                    )
                }
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 播放列表 */
    suspend fun playlists(): List<PlaylistInfo> = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext emptyList()
        try {
            val j = get("getPlaylists", emptyMap())
            val arr = j.optJSONObject("playlists")?.optJSONArray("playlist") ?: return@withContext emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val p = arr.optJSONObject(i) ?: return@mapNotNull null
                PlaylistInfo(
                    id = p.optString("id"),
                    name = p.optString("name").ifBlank { "未命名歌单" },
                    coverUrl = p.optString("coverArt").takeIf { it.isNotBlank() }?.let { coverUrl(it) },
                    songCount = p.optInt("songCount", 0)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ---------------- Subsonic 协议细节 ----------------

    /** 封面图地址 —— 不经过鉴权头，用 query 参数带 token */
    fun coverUrl(id: String, size: Int = 300): String? {
        if (id.isBlank()) return null
        return buildUrl("getCoverArt", mapOf("id" to id, "size" to size.toString()))
    }

    /** 音频流地址 */
    fun streamUrl(id: String): String? {
        if (id.isBlank()) return null
        return buildUrl("stream", mapOf("id" to id))
    }

    /** 统计：曲库总量（用于媒体库页显示「N 首」） */
    suspend fun stats(): Triple<Int, Int, Int> = withContext(Dispatchers.IO) {
        // (曲目, 专辑, 歌手) —— 曲库总量用 getScanStatus 的 count。
        // 旧写法读 searchResult3.total：Subsonic/OpenSubsonic 都**没有**这个字段，
        // 结果恒为 0，界面就一直是「已连接 · 0 首」。
        val songs = try {
            val j = get("getScanStatus", emptyMap())
            j.optJSONObject("scanStatus")?.optInt("count", 0) ?: 0
        } catch (_: Exception) { 0 }
        Triple(songs, 0, 0)
    }

    private fun buildUrl(endpoint: String, params: Map<String, String>): String {
        val salt = randomSalt()
        val token = md5(password + salt)
        val sb = StringBuilder("$baseUrl/rest/$endpoint.view?")
        sb.append("u=").append(username.urlEnc())
        sb.append("&t=").append(token)
        sb.append("&s=").append(salt)
        sb.append("&v=").append(API_VERSION)
        sb.append("&c=").append(CLIENT_NAME)
        sb.append("&f=").append("json")
        params.forEach { (k, v) -> sb.append("&").append(k).append("=").append(v.urlEnc()) }
        return sb.toString()
    }

    private suspend fun get(endpoint: String, params: Map<String, String>): JSONObject {
        val url = buildUrl(endpoint, params)
        val req = Request.Builder().url(url).get().build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            val j = JSONObject(body)
            val sub = j.optJSONObject("subsonic-response")
                ?: throw java.io.IOException("非 Subsonic 响应")
            if (sub.optString("status") != "ok") {
                val err = sub.optJSONObject("error")
                throw java.io.IOException(err?.optString("message") ?: "Subsonic error")
            }
            return sub
        }
    }

    private fun md5(s: String): String {
        val md = java.security.MessageDigest.getInstance("MD5")
        val digest = md.digest(s.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun randomSalt(): String = (1..12).map { SALT_CHARS.random() }.joinToString("")

    private fun String.urlEnc(): String = java.net.URLEncoder.encode(this, "UTF-8")

    private fun suffixToQuality(suffix: String): String = when (suffix.lowercase()) {
        "flac" -> "无损 FLAC"
        "dsf", "dff" -> "DSD"
        "mp3" -> "MP3"
        "m4a", "aac" -> "AAC"
        "ogg" -> "OGG"
        "wav" -> "WAV"
        else -> suffix.uppercase().ifBlank { "未知格式" }
    }

    companion object {
        private const val API_VERSION = "1.16.1"
        private const val CLIENT_NAME = "KMusic"
        private const val SALT_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"
    }
}

data class AlbumInfo(
    val id: String, val name: String, val artist: String,
    val coverUrl: String?, val songCount: Int, val year: Int
)

data class ArtistInfo(
    val id: String, val name: String, val coverUrl: String?, val albumCount: Int
)

data class PlaylistInfo(
    val id: String, val name: String, val coverUrl: String?, val songCount: Int
)
