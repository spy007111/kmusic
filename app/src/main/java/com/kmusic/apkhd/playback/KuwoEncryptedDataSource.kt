package com.kmusic.apkhd.playback

import androidx.media3.common.util.UnstableApi
import android.net.Uri
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ContentDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.FileDataSource
import com.kmusic.apkhd.crypto.QmcV2Rc4
import com.kmusic.apkhd.data.DiagLog
import java.io.IOException

/**
 * 酷我加密流数据源：边下载边解密，供 ExoPlayer 播放
 *
 * 密钥**按本次打开的 URI**去 [KuwoCipherHolder] 里取，而不是全局单例 ——
 * 现在整个队列都在播放器播放列表里，多首加密曲目可能同时存在，
 * 全局单密钥会拿别人的密钥解密（表现为"串歌"/杂音）。
 *
 * 解密对播放器完全透明：ExoPlayer 看到的就是普通 FLAC/MP3 流。
 * 断点续传天然支持：decryptRange 按文件绝对偏移计算密钥流。
 */
@UnstableApi
class KuwoEncryptedDataSource(private val context: android.content.Context?,
                              private val connectTimeoutMs: Int = 15_000,
                              private val readTimeoutMs: Int = 60_000) : BaseDataSource(true) {

    private val httpFactory = DefaultHttpDataSource.Factory()
        .setUserAgent("okhttp/4.10.0")
        .setConnectTimeoutMs(connectTimeoutMs)
        .setReadTimeoutMs(readTimeoutMs)
        .setAllowCrossProtocolRedirects(true)

    private var upstream: DataSource? = null

    /** 本次 open 的文件起始偏移（DataSpec.position） */
    private var startPosition: Long = 0

    /** 本次 open 后已读取的字节数（BaseDataSource 无 getter，自维护） */
    private var readSoFar: Long = 0

    /** 本次 open 的曲目对应的解密器 */
    private var cipher: QmcV2Rc4? = null

    class Factory(private val context: android.content.Context? = null,
                  private val connectTimeoutMs: Int = 15_000,
                  private val readTimeoutMs: Int = 60_000) : DataSource.Factory {
        override fun createDataSource(): DataSource = KuwoEncryptedDataSource(context, connectTimeoutMs, readTimeoutMs)
    }

    override fun open(dataSpec: DataSpec): Long {
        startPosition = dataSpec.position
        readSoFar = 0
        // 播放地址可能带 `#k=...` 缓存键片段：HTTP 和解密器都只看片段前的部分。
        // （片段只参与 CacheDataSource 的 key，不会发给服务器）
        val rawUrl = dataSpec.uri.toString()
        val plainUrl = rawUrl.substringBefore('#')
        val scheme = dataSpec.uri.scheme?.lowercase()

        // 在线源（酷我/网易云 GD/咪咕）给的都是带签名的临时地址，player timeline 里
        // 的 URL 是「铺队列那一刻」的，播到后面早就过期了（ERROR_CODE_IO_BAD_HTTP_STATUS）。
        // 这里在真正 open 之前换成登记表里最新解析的那条 —— 播放器完全无感，
        // 缓存键仍是同一个 `#k=` 片段，不会重下。
        val fragKey = dataSpec.uri.fragment?.removePrefix("k=").orEmpty()
        val effectiveUrl = if (fragKey.isNotEmpty() && (scheme == "http" || scheme == "https"))
            PlaybackUrlRegistry.pick(fragKey, plainUrl) else plainUrl

        cipher = if (scheme == "content" || scheme == "file") null
        // 换了 URL 就按新 URL 找解密器；找不到再退回原 URL 的（老地址的解密器仍然有效）
        else KuwoCipherHolder.get(effectiveUrl) ?: KuwoCipherHolder.get(plainUrl)

        // ⚠️ 必须按 scheme 分派：本地文件是 content:// / file://，
        // 塞给 DefaultHttpDataSource 会直接 MalformedURLException: unknown protocol: content。
        // 这条路径既不加密也不带 WebDAV 头。
        val source: DataSource = when (scheme) {
            "content", "android.resource" -> ContentDataSource(context!!)
            "file" -> FileDataSource()
            else -> {
                // WebDAV 源要带 Basic 头；其余（酷我/直链）走原工厂，绝不互相污染。
                // 这里用 effectiveUrl：换过新签名的直链要按新地址发请求。
                val url = effectiveUrl
                if (DavAuth.applies(url)) {
                    DefaultHttpDataSource.Factory()
                        .setUserAgent("okhttp/4.10.0")
                        .setConnectTimeoutMs(connectTimeoutMs)
                        .setReadTimeoutMs(readTimeoutMs)
                        .setAllowCrossProtocolRedirects(true)
                        .setDefaultRequestProperties(mapOf("Authorization" to DavAuth.header!!))
                        .createDataSource()
                } else {
                    httpFactory.createDataSource()
                }
            }
        }
        upstream = source
        // 换过 URL（签名刷新）时，DataSpec 的 uri 也要跟着换，否则请求的还是老地址
        val sentSpec = when {
            effectiveUrl != plainUrl -> dataSpec.buildUpon().setUri(Uri.parse(effectiveUrl)).build()
            plainUrl != rawUrl -> dataSpec.buildUpon().setUri(Uri.parse(plainUrl)).build()
            else -> dataSpec
        }
        // failFast=false 之外再加一层诊断：真炸了要能从日志看出「打开的是哪条、
        // 换没换过地址」—— 线上排查 404 全靠这一行。
        runCatching {
            if (effectiveUrl != plainUrl) {
                DiagLog.log("PLAY_SWAP", "直链已刷新：${android.net.Uri.parse(plainUrl).host} → ${android.net.Uri.parse(effectiveUrl).host}")
            }
        }
        transferStarted(sentSpec)
        return source.open(sentSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val http = upstream ?: throw IOException("DataSource not opened")
        val read = http.read(buffer, offset, length)
        if (read > 0) {
            cipher?.decryptRange(buffer, offset, read, startPosition + readSoFar)
            readSoFar += read
            bytesTransferred(read)
        }
        return read
    }

    override fun getUri() = upstream?.uri

    override fun close() {
        upstream?.close()
        upstream = null
        transferEnded()
    }
}