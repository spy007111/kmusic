package com.kmusic.apkhd.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.kmusic.apkhd.data.DiagLog
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 弱网/断网下更耐等的加载重试策略（v2.4.6）。
 *
 * ## 为什么要自定义
 *
 * media3 默认策略是「3 次重试、退避 1s→2s→4s（单次上限 5s）」，一次加载总共撑不过十几秒。
 * 地铁进隧道、电梯、Wi-Fi 切 4G 这类场景动辄 20~40 秒，默认预算在恢复之前就耗尽了：
 * 错误抛给 ViewModel → 顺延下一首 → 下一首同样断网 → 连跳 3 首 → 整条队列停住。
 * 用户感受就是「断网一次，之后再也不自动续播」。
 *
 * ## 策略
 *
 * - 媒体数据 5 次重试（默认 3 次），单次退避上限 12 秒（默认 5 秒）。
 * - 非网络类错误（404 / 签名过期）不多给机会：这类错误重试也是白等。
 * - **不接管「换源」**：每条曲目只有一条直链，没有多 CDN 地址，
 *   换源由 ViewModel 的重新取链 + `PlaybackUrlRegistry` 负责。
 *
 * 与 `NetworkMonitor` 分工：这里负责「同一次加载能撑多久」，那边负责「网络回来后主动重取直链」。
 */
@UnstableApi
class ResilientLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {

    override fun getMinimumLoadableRetryCount(dataType: Int): Int =
        if (dataType == C.DATA_TYPE_MEDIA) NETWORK_RETRY_COUNT else super.getMinimumLoadableRetryCount(dataType)

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val e = loadErrorInfo.exception
        if (!isNetworkish(e)) return super.getRetryDelayMsFor(loadErrorInfo)

        val n = loadErrorInfo.errorCount.coerceIn(1, 6)
        val ms = (1_000L shl (n - 1)).coerceAtMost(MAX_DELAY_MS)
        DiagLog.log("NET", "加载失败第 $n 次（${e.javaClass.simpleName}），${ms}ms 后重试")
        return ms
    }

    /**
     * 只有单一直链，没有 location/track 备选，明确不参与 fallback。
     * 与默认实现结果一致，这里只是把意图写下来，避免以后有人以为我们支持多源切换。
     */
    override fun getFallbackSelectionFor(
        fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo
    ): LoadErrorHandlingPolicy.FallbackSelection? = null

    /**
     * 网络类错误的判定。
     *
     * 不能只看异常类型：`DataSourceException` / `HttpDataSourceException` 把真正的
     * `UnknownHostException` 包在 cause 里，靠 message + cause 兜两层，
     * 避免把「签名过期 403」也当成网络问题白重试 5 次。
     */
    private fun isNetworkish(e: IOException): Boolean {
        if (e is UnknownHostException || e is SocketTimeoutException) return true
        val msg = e.message.orEmpty()
        if (msg.contains("Unable to connect", true) ||
            msg.contains("Connection reset", true) ||
            msg.contains("Connection refused", true) ||
            msg.contains("timed out", true) ||
            msg.contains("Network is unreachable", true)
        ) return true
        val cause = e.cause
        return cause is UnknownHostException || cause is SocketTimeoutException
    }

    private companion object {
        const val NETWORK_RETRY_COUNT = 5
        const val MAX_DELAY_MS = 12_000L
    }
}
