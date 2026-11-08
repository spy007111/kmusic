package com.kmusic.apkhd.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import com.kmusic.apkhd.data.DiagLog

/**
 * 网络可用性监听（v2.4.6）。
 *
 * ## 为什么需要它
 *
 * media3 的 `LoadErrorHandlingPolicy` 只管「同一次加载重试几次」，**它不认识「网络没了」**。
 * 断网时一首歌的加载会被重试耗尽 → 报 `ERROR_CODE_IO_NETWORK_CONNECTION_FAILED` →
 * 走 `handlePlaybackError` → 顺延下一首 → 下一首同样断网 → 连跳 3 首 → `PLAY_GIVEUP` 停手。
 * 之后网络恢复了也没有任何人再试：**用户看到的就是「断网一次，之后再也不自动续播」**。
 *
 * 所以这里只做一件事：把「网络从不可用变成可用」这个**边沿**报出来，
 * 让 ViewModel 有机会把刚刚失败的当前曲重新取链、回到原位继续播。
 *
 * ## 为什么不在断开时立刻暂停
 *
 * 断开只是「可能失败」，不是「必然失败」：缓存里已有的字节还能继续放几秒到几十秒，
 * 地铁进出隧道的短暂断连经常不需要任何干预。真正判断失败交给播放器自己的错误回调。
 *
 * ## 为什么去抖
 *
 * 移动网络 / Wi-Fi 切换时系统会连续回调 `onLost` → `onAvailable`（几百毫秒内），
 * 不合并就会触发两次重连，第二次在第一次还没取到链时又作废一次直链。
 */
class NetworkMonitor(context: Context) {

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /** 上一次已知的「网络可用」状态，用来只报**边沿** */
    @Volatile
    private var lastAvailable: Boolean = cm?.let { isOnline(it) } ?: true

    private var callback: ConnectivityManager.NetworkCallback? = null

    /** 恢复回调（主线程派发，已去抖） */
    private var onRegained: (() -> Unit)? = null

    /** 丢失回调（主线程派发） */
    private var onLostCb: (() -> Unit)? = null
    private var onChangedCb: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    /** 当前是否真的没有可用网络（供 ViewModel 判断「这次失败要不要等恢复」） */
    fun isOffline(): Boolean = cm?.let { !isOnline(it) } ?: false

    fun start(onRegained: () -> Unit, onLost: () -> Unit = {}, onChanged: () -> Unit = {}) {
        if (cm == null || callback != null) return
        this.onRegained = onRegained
        this.onLostCb = onLost
        this.onChangedCb = onChanged
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                main.post { onChangedCb?.invoke() }
            }
            override fun onAvailable(network: Network) {
                val cmRef = cm ?: return
                val caps = cmRef.getNetworkCapabilities(network) ?: return
                // 只认真的能上网的网络：VPN 建立瞬间、仅本地的热点都会报 onAvailable
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return
                val was = lastAvailable
                lastAvailable = true
                if (!was) fireRegained()
            }

            override fun onLost(network: Network) {
                main.post { onChangedCb?.invoke() }
                // 系统同时可能还有另一条可用网络（Wi-Fi 掉线切 4G），以「整体」为准
                val cmRef = cm ?: return
                val stillOnline = isOnline(cmRef)
                val was = lastAvailable
                lastAvailable = stillOnline
                if (was && !stillOnline) {
                    DiagLog.log("NET", "网络已断开")
                    main.post { onLostCb?.invoke() }
                }
            }
        }
        callback = cb
        val cmRef = cm ?: return
        runCatching { cmRef.registerDefaultNetworkCallback(cb) }
            .onFailure {
                DiagLog.log("NET", "注册网络回调失败：${it.message}")
                callback = null
            }
    }

    fun stop() {
        pending?.let { main.removeCallbacks(it) }
        pending = null
        callback?.let { runCatching { cm?.unregisterNetworkCallback(it) } }
        callback = null
        onRegained = null
        onLostCb = null
        onChangedCb = null
    }

    /**
     * 去抖 1.2 秒：网络切换时 onAvailable 会连报，第一次就给 ViewModel 会白打一次取链。
     * 用固定延迟而不是「重置延迟」，避免持续抖动时永远等不到触发。
     */
    private fun fireRegained() {
        pending?.let { main.removeCallbacks(it) }
        val r = Runnable {
            pending = null
            DiagLog.log("NET", "网络已恢复")
            onRegained?.invoke()
        }
        pending = r
        main.postDelayed(r, 1_200L)
    }

    private fun isOnline(cm: ConnectivityManager): Boolean {
        val n = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(n) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
