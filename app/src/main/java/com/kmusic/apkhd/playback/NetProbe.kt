package com.kmusic.apkhd.playback

import android.os.SystemClock
import com.kmusic.apkhd.data.DiagLog
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 「网络到底通不通」的实测探针（v2.4.6）。
 *
 * ## 为什么不能只信 ConnectivityManager
 *
 * 真实世界里有大量「网络接口是 UP、系统说有网、但实际一个包都出不去」的情况：
 * Wi-Fi 连着一个没插网线的路由器、酒店/校园门户还没登录、DNS 被劫持、
 * VPN 半连不连。这些场景系统给的 `NET_CAPABILITY_INTERNET` 全都是 true，
 * 于是靠 `isOffline()` 做判定的代码会走进「顺延下一首」，连跳三首后
 * 报「酷我/网易云/咪咕都没有音源」—— 用户看到的就是莫名其妙的错误。
 *
 * ## 做法
 *
 * 直接对一个稳定目标的 443 端口做 TCP 连接（3 秒超时）。不解析域名（避开 DNS 的锅），
 * 不做 HTTP（不需要证书与响应体）。通了 = 网络可用；不通 = 当作断网处理。
 *
 * ## 成本
 *
 * 只在「取链已经失败」的时候调用，不是每次点歌都跑。结果缓存 [OK_TTL_MS]，
 * 队列里连续几首失败不会每首都打一次探针。
 */
object NetProbe {

    /** 通的时候缓存 30 秒：网络刚恢复时的连续几次取样不该重复打 */
    private const val OK_TTL_MS = 30_000L

    /** 不通的时候只缓存 3 秒：用户很可能正在救网络，恢复后要尽快放行 */
    private const val BAD_TTL_MS = 3_000L

    private const val TIMEOUT_MS = 3_000

    /** 目标：阿里公共 DNS 的 TCP 443。国内可达性最好，且不涉及任何用户数据 */
    private val targets = listOf(
        "223.5.5.5" to 443,
        "120.53.53.53" to 443
    )

    private var lastAt = 0L
    private var lastOk = false

    /**
     * 网络是否真的能出去。
     *
     * 依次试 [targets]，任一通即返回 true。全部不通返回 false。
     */
    fun reachable(): Boolean {
        val now = SystemClock.elapsedRealtime()
        val ttl = if (lastOk) OK_TTL_MS else BAD_TTL_MS
        if (lastAt != 0L && now - lastAt < ttl) return lastOk

        var ok = false
        for ((host, port) in targets) {
            ok = try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(host, port), TIMEOUT_MS)
                    true
                }
            } catch (e: Exception) {
                false
            }
            if (ok) break
        }
        lastAt = now
        lastOk = ok
        DiagLog.log("NET", if (ok) "探针：网络可达" else "探针：网络不可达")
        return ok
    }

    /** 单测/排障用：清掉缓存强制下次重测 */
    internal fun reset() {
        lastAt = 0L
        lastOk = false
    }
}
