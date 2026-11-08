package com.kmusic.apkhd.playback

import android.widget.Toast
import android.app.PendingIntent
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MimeTypes
import kotlinx.coroutines.flow.combine
import androidx.media3.common.PlaybackException
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DataSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.kmusic.apkhd.crypto.QmcV2Rc4
import com.kmusic.apkhd.audio.DsdExtractor
import com.kmusic.apkhd.data.DiagLog
import com.kmusic.apkhd.data.SettingsStore
import com.kmusic.apkhd.data.PlaybackSnapshotStore
import com.kmusic.apkhd.ui.tv.TvEnv
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import com.kmusic.apkhd.ui.MainActivity

/**
 * 进程内共享的当前解密器。
 *
 * 播放器由 [MoyinPlaybackService] 持有（同进程），而 ekey 是每首歌变的，
 * 所以用这个 holder 让 DataSource 动态读到当前密钥 —— 不能把 cipher 焊死进
 * MediaSourceFactory，否则换歌时会拿上一首的密钥解密。
 */
object KuwoCipherHolder {
    /**
     * 按**播放直链**索引解密器。
     *
     * 从「一个全局密钥」改成表：现在队列会整体进播放器的播放列表，
     * 多首加密曲目共存，一个全局槽位必然串密钥。每个直链唯一，用它做键最稳。
     */
    private val byUrl = java.util.concurrent.ConcurrentHashMap<String, QmcV2Rc4>()

    fun put(url: String, cipher: QmcV2Rc4?) {
        if (cipher != null && url.isNotEmpty()) byUrl[url] = cipher
    }

    fun get(url: String): QmcV2Rc4? = byUrl[url]

    fun clear() = byUrl.clear()
}

@UnstableApi
class MoyinPlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    /** 播放器实例（class 字段，供 DSD 状态同步使用） */
    private var player: ExoPlayer? = null

    /** 用户请求offload；实际是否生效须由AudioOffloadListener确认。 */
    private var offload: Boolean = false
    private var pureAudioMode: Boolean = false
    private var usbHighFidelity = false
    private var usbController: UsbAudioController? = null
    private var deviceProfileManager: DeviceEqProfileManager? = null
    private var loudnessMode = "track"
    private var currentOutputMode = "processed"
    private var playbackDataFactory: DataSource.Factory? = null
    private var configuredDsdOutRate = 44100
    private var modeGeneration = 0L
    private var controlledSwitch = false
    private var currentOffloadListener: ExoPlayer.AudioOffloadListener? = null

    /** DSD 播放时是否自动旁路均衡器（用户设置，默认开） */
    private var dsdBypassEq: Boolean = true

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val routeHandler = Handler(Looper.getMainLooper())
    private var renderersFactory: AudioRenderersFactory? = null
    private var audioManager: AudioManager? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallbackRegistered = false
    private var precacheWifiOnly = true
    private var precacheMasterWifiOnly = true
    private val precacheNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { routeHandler.post { updatePrecachePlayback() } }
        override fun onLost(network: Network) { routeHandler.post { updatePrecachePlayback() } }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            routeHandler.post { updatePrecachePlayback() }
        }
    }
    private var endingSession = false
    private var resourcesReleased = false
    private var audioInterruptionResume = true
    private var externalMonitor: ExternalPlaybackMonitor? = null
    private val interruptionPolicy = AudioInterruptionPolicy()
    private var attemptingInterruptionResume = false
    private var interruptionCheckScheduled = false
    private var lastExternalEvidence: ExternalPlaybackEvidence? = null
    private var lastInterruptionDiagnostic: String? = null
    private val interruptionCheck = object : Runnable {
        override fun run() {
            interruptionCheckScheduled = false
            if (endingSession || !interruptionPolicy.pending || callPolicy.active || callPolicy.pending) return
            externalMonitor?.refresh()
            evaluateInterruptionResume()
        }
    }
    private var callAutoResume = true
    private var callMonitor: CallStateMonitor? = null
    private val callPolicy = CallResumePolicy()
    private val callResumeTick = object : Runnable {
        override fun run() {
            if (!callPolicy.pending || player == null) return
            renderersFactory?.refreshOutputRoute()
            evaluateCallResume()
            if (callPolicy.pending) routeHandler.postDelayed(this, 250)
        }
    }
    private var bluetoothAutoResume = false
    private var lastConfirmedRoute: OutputRoute? = null
    private var lastMediaId: String? = null
    private val unsupportedAudioSkips = UnsupportedAudioSkipPolicy()
    private var cacheLease: MediaCache.Lease? = null
    private var precache: AudioPrecache? = null
    private val disconnectedDevices = mutableSetOf<Pair<Int, Int>>()
    private val playbackTick = object : Runnable {
        override fun run() {
            if (player == null) return
            updatePrecachePlayback()
            updateUsbConfiguration()
            routeHandler.postDelayed(this, 2_000)
        }
    }
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            val route = lastConfirmedRoute ?: return
            if (removedDevices.any { it.id == route.deviceId && it.type == route.deviceType }) {
                if (callPolicy.active || callPolicy.callTransportRoute(AudioRuntimeState.snapshot.route, SystemClock.elapsedRealtime())) {
                    // A2DP profile can disappear while SCO is used. Actual media-route return is required later.
                    return
                }
                pauseForDisconnectedRoute(route)
            }
        }
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            addedDevices.forEach { disconnectedDevices.remove(it.id to it.type) }
            // Query the live track; a connection list is not evidence of an active route.
            renderersFactory?.refreshOutputRoute()
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (TvEnv.isTv(this) && PlaybackSessionExit.isExitRequested) {
            endingSession = true
            stopSelf()
            return
        }

        // DSD 容器头解析出真实倍率后回报（比猜文件名可靠）
        DsdExtractor.DsdPlaybackReporter.onLabel = { label ->
            DsdPlaybackState.update(true, label)
        }

        // DSD 播放状态变化 → 处理器切透传 / 恢复。
        //
        // v2.3.5 起这只是一次 flag 写入（EqProcessor.isBypassed），不拆管线、不重建：
        // 旧的 AudioEffect 链在 DSD 上要 release 再 attach，切歌会有几百毫秒的空窗；
        // 现在处理器始终在链上，只是那几帧不做浮点运算。
        DsdPlaybackState.setListener {
            val bypass = dsdBypassEq && DsdPlaybackState.isDsdPlaying
            FxChain.setDsdBypass(bypass)
        }

        // 播放缓存：容量来自设置（0=关闭，默认 1GB）。
        // 服务 onCreate 在主线程，DataStore 首读通常几毫秒 —— 加 800ms 兜底，
        // 读不到就按默认 1GB，绝不因为配置读失败而阻塞起播。
        val cacheGb = runCatching {
            runBlocking {
                withTimeoutOrNull(800) { SettingsStore(this@MoyinPlaybackService).cacheSizeGb.first() }
            }
        }.getOrNull() ?: 1
        val initialMode = runCatching {
            runBlocking { withTimeoutOrNull(800) { SettingsStore(this@MoyinPlaybackService).audioOutputMode.first() } }
        }.getOrNull() ?: "processed"
        configureOutputMode(initialMode)
        serviceScope.launch {
            SettingsStore(this@MoyinPlaybackService).bluetoothAutoResume.collect { enabled ->
                if (!enabled && bluetoothAutoResume) AudioRuntimeState.invalidatePlayback()
                bluetoothAutoResume = enabled
            }
        }
        serviceScope.launch {
            SettingsStore(this@MoyinPlaybackService).wifiOnlyDownload.collect { enabled ->
                precacheWifiOnly = enabled
                updatePrecachePlayback()
            }
        }
        serviceScope.launch {
            SettingsStore(this@MoyinPlaybackService).masterWifiOnly.collect { enabled ->
                precacheMasterWifiOnly = enabled
                updatePrecachePlayback()
            }
        }
        FxChain.setOffload(offload)
        if (offload) {
            DiagLog.log("AUDIO", "请求offload：软件音效旁路，等待实际输出确认；不支持时普通PCM回退")
        }
        // DSD 输出采样率：44100（默认）/ 88200。
        // 读取方式和 offload 一样 —— 800ms 兜底，读不到就用默认值，绝不阻塞起播。
        configuredDsdOutRate = runCatching {
            runBlocking {
                withTimeoutOrNull(800) { SettingsStore(this@MoyinPlaybackService).dsdOutRate.first() }
            }
        }.getOrNull() ?: 44100
        DiagLog.log("DSD", "DSD 输出采样率：$configuredDsdOutRate Hz")

        // DSD 自动旁路 EQ：默认开。读了立刻同步到处理器（可能在 onCreate 之前就播过）
        dsdBypassEq = runCatching {
            runBlocking {
                withTimeoutOrNull(800) { SettingsStore(this@MoyinPlaybackService).dsdBypassEq.first() }
            }
        }.getOrNull() ?: true

        val lease = MediaCache.acquire(this, cacheGb)
        cacheLease = lease
        val dataFactory: DataSource.Factory = lease?.playbackDataSourceFactory() ?: KuwoEncryptedDataSource.Factory(this)
        playbackDataFactory = dataFactory
        precache = lease?.createPrecache()
        AudioPrecacheBridge.bind(precache)
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        networkCallbackRegistered = runCatching {
            connectivityManager?.registerDefaultNetworkCallback(precacheNetworkCallback)
            connectivityManager != null
        }.getOrDefault(false)

        usbController = UsbAudioController(this)
        val p = buildPlayer(dataFactory)
        player = p
        PlaybackSessionExit.bind(this, ::endPlaybackSession)
        externalMonitor = ExternalPlaybackMonitor(this, ::handleExternalPlayback).also { it.start() }
        serviceScope.launch {
            SettingsStore(this@MoyinPlaybackService).audioInterruptionResume.collect { enabled ->
                audioInterruptionResume = enabled
                if (!enabled) cancelInterruptionResume("中断自动恢复已关闭")
            }
        }
        deviceProfileManager = DeviceEqProfileManager(this).also { it.start() }
        serviceScope.launch {
            val settings = SettingsStore(this@MoyinPlaybackService)
            combine(settings.truePeakEnabled, settings.truePeakCeilingDb, DeviceEqProfiles.runtime) { enabled, ceiling, profile ->
                Triple(enabled, ceiling, profile.active)
            }.collect { (enabled, ceiling, profile) ->
                if (profile == null) FxChain.configureTruePeak(enabled, ceiling)
            }
        }
        serviceScope.launch {
            SettingsStore(this@MoyinPlaybackService).loudnessMode.collect { mode -> loudnessMode = mode; updateUsbConfiguration() }
        }
        audioManager = getSystemService(AudioManager::class.java)
        audioManager?.registerAudioDeviceCallback(deviceCallback, routeHandler)
        routeHandler.post(playbackTick)
        callMonitor = CallStateMonitor(this, ::handleCallState) { status ->
            AudioRuntimeState.reportCallMonitor(status)
            if (status != CallMonitorStatus.MONITORING) cancelCallResume()
        }
        serviceScope.launch {
            SettingsStore(this@MoyinPlaybackService).callAutoResume.collect { enabled ->
                callAutoResume = enabled
                if (!enabled) cancelCallResume()
                callMonitor?.configure(enabled)
            }
        }

        attachPlayerListeners(p)

        val intentPlayer = wrapPlayer(p)
        session = MediaSession.Builder(this, intentPlayer)
            // 点通知回到 App
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .build()
        serviceScope.launch {
            SettingsStore(this@MoyinPlaybackService).audioOutputMode.collect { mode -> applyOutputMode(mode) }
        }
    }

    private fun configureOutputMode(request: String) {
        currentOutputMode = AudioModeSwitchPolicy.normalize(request)
        offload = currentOutputMode == "offload"
        pureAudioMode = currentOutputMode == "pure"
        usbHighFidelity = currentOutputMode == "usb"
        FxChain.setOffload(offload)
        FxChain.setPureAudioMode(pureAudioMode || usbHighFidelity)
        AudioRuntimeState.serviceStarted(offload, pureAudioMode, usbHighFidelity)
    }

    private fun buildPlayer(dataFactory: DataSource.Factory): ExoPlayer {
        val generation = modeGeneration
        val factory = AudioRenderersFactory(this, allowOffload = offload, pureAudioMode = pureAudioMode,
            onRouteConfirmed = { route -> if (generation == modeGeneration && !endingSession) onRouteConfirmed(route) }, usbHighFidelity = usbHighFidelity,
            onOutputConfirmed = { format, route -> if (generation == modeGeneration && !endingSession) usbController?.onOutput(format, route) },
            onBeforeOutputCreated = { format -> if (generation == modeGeneration && !endingSession) usbController?.beforeOutputCreated(format) })
        renderersFactory = factory
        return ExoPlayer.Builder(this)
            // ALAC（.m4a 苹果无损）优先软解 + 解码器回退：厂商硬解会「自称支持但解不出来」
            .setRenderersFactory(factory)
            // cipher 通过 holder 动态获取，每次 open 都读当前值
            // DSD 用专用工厂（默认工厂不认识 DSF/DFF）
            //
            // 重试策略：默认「3 次 / 单次最多 5 秒」在断网、进隧道时撑不到网络恢复，
            // 错误会被直接抛出 → 顺延下一首 → 连跳 3 首停播。这里换成自定义策略，
            // 媒体数据给到 5 次、单次最长 12 秒，把「等网回来」的时间窗撑开。
            .setMediaSourceFactory(
                DsdMediaSourceFactory(dataFactory, configuredDsdOutRate).apply {
                    setLoadErrorHandlingPolicy(ResilientLoadErrorPolicy())
                }
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            // 拔耳机自动暂停（否则会外放）
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
    }

    private fun attachPlayerListeners(p: ExoPlayer) {
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setAudioOffloadPreferences(
                AudioOffloadPreferences.Builder()
                    .setAudioOffloadMode(if (offload) AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED else AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED)
                    .setIsGaplessSupportRequired(true)
                    .build()
            ).build()
        val listener = object : ExoPlayer.AudioOffloadListener {
            override fun onOffloadedPlayback(isOffloadedPlayback: Boolean) {
                if (player !== p || controlledSwitch || endingSession) return
                AudioRuntimeState.reportOffloadActive(isOffloadedPlayback)
                DiagLog.log("AUDIO", "offload 偏好=$offload 实际=$isOffloadedPlayback")
            }
            override fun onSleepingForOffloadChanged(isSleepingForOffload: Boolean) {
                if (player === p && !controlledSwitch && !endingSession) AudioRuntimeState.reportOffloadSleeping(isSleepingForOffload)
            }
        }
        currentOffloadListener = listener
        p.addAudioOffloadListener(listener)
        p.addListener(object : Player.Listener {
            /**
             * 切歌时更新「当前是不是 DSD」。
             *
             * DSD 播放要自动旁路均衡器（解码链已经吃满 CPU，再跑十个 biquad 会挤掉音频线程），
             * 所以处理器必须知道现在播的是不是 DSD。
             */
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                if (endingSession || controlledSwitch || player !== p) return
                val mediaId = mediaItem?.mediaId
                if (lastMediaId != null && (lastMediaId != mediaId || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT || reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)) {
                    cancelCallResume()
                    cancelInterruptionResume()
                    AudioRuntimeState.invalidatePlayback()
                }
                lastMediaId = mediaId
                updateDsdState(p)
                updatePrecachePlayback()
                updateUsbConfiguration()
            }

            /**
             * 处理器接进管线后采样率才知道（由它自己上报）。
             * 就绪时打一行日志当验收锚点 —— 有这行就说明 AudioProcessor 真的上链了。
             */
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (endingSession || controlledSwitch || player !== p) return
                updatePrecachePlayback()
                updateUsbConfiguration()
                if (playWhenReady) {
                    renderersFactory?.refreshOutputRoute()
                    // Internal focus return is not a fresh user play command.
                    if (!AudioRuntimeState.snapshot.desiredPlaying && reason != Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) AudioRuntimeState.recordPlayIntent()
                    return
                }
                when (reason) {
                    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> {
                        val before = callPlaybackSnapshot()
                        DiagLog.log("INTERRUPT", "focus-loss beforeDesired=${before.desiredPlaying} ownPlaying=${p.isPlaying} callActive=${callPolicy.active} callPending=${callPolicy.pending} enabled=$audioInterruptionResume")
                        val epoch = AudioRuntimeState.recordPauseIntent(if (callPolicy.active) PauseCause.CALL else PauseCause.FOCUS)
                        if (attemptingInterruptionResume) {
                            interruptionPolicy.adoptAttemptEpoch(epoch)
                        } else {
                            callPolicy.focusLost(before, epoch, SystemClock.elapsedRealtime())
                            if (!callPolicy.active && !callPolicy.pending) {
                                interruptionPolicy.focusLost(before, epoch, SystemClock.elapsedRealtime(), audioInterruptionResume)
                                publishInterruptionState()
                                clearInterruptionCheck()
                                if (interruptionPolicy.pending) scheduleInterruptionCheck()
                            }
                        }
                        publishCallState()
                    }
                    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> {
                        val route = lastConfirmedRoute
                        if (callPolicy.active || callPolicy.callTransportRoute(AudioRuntimeState.snapshot.route, SystemClock.elapsedRealtime())) {
                            publishCallState()
                        } else if (route != null) pauseForDisconnectedRoute(route)
                        else { cancelCallResume(); AudioRuntimeState.recordPauseIntent(PauseCause.ROUTE_DISCONNECT) }
                    }
                }
            }

            override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                if (endingSession || controlledSwitch || player !== p) return
                AudioRuntimeState.reportSuppression(playbackSuppressionReason)
                updateUsbConfiguration()
                if (playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE && p.playWhenReady) renderersFactory?.refreshOutputRoute()
            }

            override fun onPlayerError(error: PlaybackException) {
                if (endingSession || controlledSwitch || player !== p) return
                cancelCallResume()
                cancelInterruptionResume()
                AudioRuntimeState.reportError()
                if (UnsupportedAudioPolicy.isUnsupported(error.errorCode)) {
                    val epoch = AudioRuntimeState.snapshot.playbackEpoch
                    val index = p.currentMediaItemIndex
                    val item = p.currentMediaItem
                    routeHandler.postDelayed({
                        if (player === p && !endingSession && !controlledSwitch &&
                            epoch == AudioRuntimeState.snapshot.playbackEpoch && index == p.currentMediaItemIndex && item == p.currentMediaItem) {
                            skipUnsupportedAudio(p)
                        }
                    }, 250)
                }
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                if (endingSession || controlledSwitch || player !== p) return
                updateUsbConfiguration()
                renderersFactory?.refreshOutputRoute()
            }
            override fun onEvents(eventPlayer: Player, events: Player.Events) { if (this@MoyinPlaybackService.player === p && !controlledSwitch && !endingSession) updateUsbConfiguration() }
            override fun onIsLoadingChanged(isLoading: Boolean) { if (player === p && !controlledSwitch && !endingSession) updatePrecachePlayback() }
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (player === p && !controlledSwitch && !endingSession) updatePrecachePlayback() }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (endingSession || controlledSwitch || player !== p) return
                updatePrecachePlayback()
                if (playbackState != Player.STATE_READY) return
                unsupportedAudioSkips.reset()
                val proc = EqProcessor.instance
                DiagLog.log(
                    "EQ",
                    "播放就绪：处理器 ${proc.currentSampleRate}Hz 生效=${proc.working} 旁路=${proc.isBypassed}"
                )
            }
        })

    }

    private fun skipUnsupportedAudio(p: ExoPlayer) {
        val item = p.currentMediaItem ?: return
        val runtime = AudioRuntimeState.snapshot
        val ids = (0 until p.mediaItemCount).map { it.toString() }
        val nextIndex = p.nextMediaItemIndex.takeIf { it >= 0 }?.toString()
        val next = if (runtime.desiredPlaying && runtime.suppressionReason == 0) {
            unsupportedAudioSkips.next(p.currentMediaItemIndex.toString(), ids, nextIndex, p.repeatMode != Player.REPEAT_MODE_OFF)
        } else null
        val title = item.mediaMetadata.title?.toString() ?: "当前曲目"
        Toast.makeText(this, UnsupportedAudioPolicy.message(title, next != null), Toast.LENGTH_LONG).show()
        DiagLog.log("PLAY_UNSUPPORTED", "设备不支持 mediaId=${item.mediaId} next=$next")
        AudioRuntimeState.invalidatePlayback()
        if (next == null) {
            AudioRuntimeState.recordPauseIntent(PauseCause.ERROR)
            p.pause()
            p.stop()
        } else {
            p.seekTo(next.toInt(), 0)
            p.prepare()
        }
    }

    private fun wrapPlayer(p: ExoPlayer): Player {
        // onPlayerCommandRequest only exposes COMMAND_PLAY_PAUSE, not its Boolean value.
        // ForwardingPlayer sees both pause() and setPlayWhenReady(false), even when already paused.
        return object : ForwardingPlayer(p) {
            override fun play() = playerCommand(p, true) { p.play() }
            override fun pause() = playerCommand(p, false) { p.pause() }
            override fun setPlayWhenReady(playWhenReady: Boolean) = playerCommand(p, playWhenReady) { p.playWhenReady = playWhenReady }
            override fun stop() = playerCommand(p, false) { p.stop() }
        }
    }

    private inline fun playerCommand(p: ExoPlayer, playing: Boolean, command: () -> Unit) {
        if (player !== p || controlledSwitch) return
        if (playing) unsupportedAudioSkips.reset()
        externalCommand(playing, command)
    }

    fun applyOutputMode(request: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) { routeHandler.post { applyOutputMode(request) }; return }
        val mode = AudioModeSwitchPolicy.normalize(request)
        if (mode == currentOutputMode || endingSession || controlledSwitch || PlaybackSessionExit.isExitRequested && TvEnv.isTv(this)) return
        val oldPlayer = player ?: return
        val dataFactory = playbackDataFactory ?: return
        val snapshot = captureModeSwitch(oldPlayer)
        val expectedEpoch = AudioRuntimeState.invalidatePlayback()
        cancelCallResume()
        cancelInterruptionResume("输出模式切换取消旧恢复")
        AudioPrecacheBridge.cancel()
        val previousMode = currentOutputMode
        val oldFactory = renderersFactory
        val oldOffloadListener = currentOffloadListener
        var replacement: ExoPlayer? = null
        var oldReleased = false
        controlledSwitch = true
        AudioRuntimeState.reportModeSwitch(true, "正在切换输出模式，播放可能短暂中断")
        modeGeneration++
        try {
            configureOutputMode(mode)
            val newPlayer = buildPlayer(dataFactory)
            replacement = newPlayer
            oldPlayer.pause()
            oldOffloadListener?.let { oldPlayer.removeAudioOffloadListener(it) }
            oldFactory?.releaseRouteCallbacks()
            player = newPlayer
            lastMediaId = snapshot.mediaId
            lastConfirmedRoute = null
            newPlayer.repeatMode = snapshot.repeatMode
            newPlayer.shuffleModeEnabled = snapshot.shuffleEnabled
            newPlayer.playbackParameters = PlaybackParameters(snapshot.speed, snapshot.pitch)
            newPlayer.volume = snapshot.volume
            if (snapshot.items.isNotEmpty()) newPlayer.setMediaItems(snapshot.items, snapshot.index, snapshot.positionMs)
            attachPlayerListeners(newPlayer)
            session?.setPlayer(wrapPlayer(newPlayer))
            oldPlayer.release()
            oldReleased = true
            controlledSwitch = false
            if (snapshot.prepared && snapshot.items.isNotEmpty()) newPlayer.prepare()
            val runtime = AudioRuntimeState.snapshot
            if (AudioModeSwitchPolicy.shouldResume(snapshot, runtime.playbackEpoch, expectedEpoch,
                    newPlayer.currentMediaItem?.mediaId, runtime.desiredPlaying, endingSession || PlaybackSessionExit.isExitRequested && TvEnv.isTv(this))) newPlayer.play()
            updateDsdState(newPlayer)
            updateUsbConfiguration()
            AudioRuntimeState.reportModeSwitch(false, "输出模式已切换")
        } catch (error: Exception) {
            if (!oldReleased) {
                if (renderersFactory !== oldFactory) renderersFactory?.releaseRouteCallbacks()
                runCatching { replacement?.release() }
                player = oldPlayer
                configureOutputMode(previousMode)
                renderersFactory = oldFactory
                currentOffloadListener = oldOffloadListener
                oldOffloadListener?.let { oldPlayer.addAudioOffloadListener(it) }
                runCatching { session?.setPlayer(wrapPlayer(oldPlayer)) }
                oldPlayer.pause()
            } else player?.pause()
            controlledSwitch = false
            AudioRuntimeState.reportModeSwitch(false, "切换失败，保持暂停")
            DiagLog.log("AUDIO", "mode-switch failed=${error.javaClass.simpleName}")
        }
    }

    private fun captureModeSwitch(p: ExoPlayer): AudioModeSwitchSnapshot<MediaItem> {
        val runtime = AudioRuntimeState.snapshot
        return AudioModeSwitchSnapshot((0 until p.mediaItemCount).map { p.getMediaItemAt(it) },
            p.currentMediaItemIndex.coerceAtLeast(0), p.currentPosition, p.currentMediaItem?.mediaId,
            runtime.playbackEpoch, p.playWhenReady, runtime.desiredPlaying, p.playbackSuppressionReason,
            p.repeatMode, p.shuffleModeEnabled, p.playbackParameters.speed, p.playbackParameters.pitch,
            p.volume, p.playbackState != Player.STATE_IDLE)
    }

    private inline fun externalCommand(playing: Boolean, command: () -> Unit) {
        if (endingSession || TvEnv.isTv(this) && PlaybackSessionExit.isExitRequested) return
        cancelInterruptionResume()
        cancelCallResume()
        if (playing) {
            // VM records intent before async loading; do not invalidate its in-flight token on commit.
            if (!AudioRuntimeState.snapshot.desiredPlaying) AudioRuntimeState.recordPlayIntent()
        } else AudioRuntimeState.recordPauseIntent()
        command()
    }

    private fun pauseForDisconnectedRoute(route: OutputRoute) {
        cancelInterruptionResume()
        cancelCallResume()
        disconnectedDevices.add(route.deviceId to route.deviceType)
        val snapshot = AudioRuntimeState.snapshot
        if (snapshot.pauseCause == PauseCause.ROUTE_DISCONNECT && !snapshot.desiredPlaying) return
        AudioRuntimeState.reportDisconnect(route, SystemClock.elapsedRealtime(), bluetoothAutoResume)
        player?.pause()
    }

    private fun onRouteConfirmed(route: OutputRoute?) {
        val now = SystemClock.elapsedRealtime()
        if (callPolicy.callTransportRoute(route, now)) {
            // Observe SCO without changing the last confirmed media route or reconnect identity.
            return
        }
        val oldRoute = lastConfirmedRoute
        if (route != null && oldRoute != null && oldRoute.isBluetooth && !oldRoute.sameSessionDevice(route)) {
            if (!callPolicy.active && !callPolicy.pending) pauseForDisconnectedRoute(oldRoute)
        }
        if (route != null) lastConfirmedRoute = route
        val p = player ?: return
        if (!callPolicy.active && !callPolicy.pending && route != null && (route.deviceId to route.deviceType) !in disconnectedDevices &&
            p.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
            AudioRuntimeState.canResume(route, SystemClock.elapsedRealtime(), bluetoothAutoResume)) {
            AudioRuntimeState.recordPlayIntent()
            p.play()
        }
    }

    private fun callPlaybackSnapshot(): CallPlaybackSnapshot {
        val p = player
        val runtime = AudioRuntimeState.snapshot
        return CallPlaybackSnapshot(p?.currentMediaItem?.mediaId, p?.currentPosition ?: 0,
            runtime.playbackEpoch, runtime.desiredPlaying, runtime.route?.takeUnless { it.deviceType == AudioDeviceInfo.TYPE_BLUETOOTH_SCO } ?: lastConfirmedRoute)
    }

    private fun publishCallState() = AudioRuntimeState.reportCall(callPolicy.callState, callPolicy.pending)

    private fun cancelCallResume() {
        callPolicy.cancel()
        routeHandler.removeCallbacks(callResumeTick)
        publishCallState()
    }

    private fun handleCallState(state: CellularCallState) {
        val now = SystemClock.elapsedRealtime()
        if (endingSession) return
        val schedule = callPolicy.stateChanged(state, callPlaybackSnapshot(), now, callAutoResume)
        if (callPolicy.active) cancelInterruptionResume("通话恢复优先")
        if (callPolicy.active && callPolicy.pending) {
            val p = player
            if (AudioRuntimeState.snapshot.desiredPlaying) {
                callPolicy.adoptCallPauseEpoch(AudioRuntimeState.recordPauseIntent(PauseCause.CALL))
                p?.pause()
            }
        }
        publishCallState()
        DiagLog.log("CALL", "state=$state pending=${callPolicy.pending}")
        if (schedule) {
            routeHandler.removeCallbacks(callResumeTick)
            routeHandler.postDelayed(callResumeTick, 600)
        }
    }

    private fun evaluateCallResume() {
        if (endingSession) return
        val p = player ?: return
        val runtime = AudioRuntimeState.snapshot
        // Never substitute a connected-device list or the remembered old route for actual output here.
        val current = callPlaybackSnapshot().copy(route = runtime.route)
        if (callPolicy.ready(current, p.playbackSuppressionReason,
            SystemClock.elapsedRealtime(), callAutoResume) == null) { publishCallState(); return }
        if (p.playerError != null) { cancelCallResume(); return }
        callPolicy.attempted(SystemClock.elapsedRealtime())
        // Media3 is the only focus requester. A denied request is bounded, not an endless focus fight.
        p.play()
        if (p.playWhenReady && p.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE) {
            if (!AudioRuntimeState.snapshot.desiredPlaying) AudioRuntimeState.recordPlayIntent()
            callPolicy.completed()
            DiagLog.log("CALL", "resume=accepted cause=call-ended")
        } else {
            callPolicy.adoptCallPauseEpoch(AudioRuntimeState.snapshot.playbackEpoch)
            DiagLog.log("CALL", "resume=deferred cause=focus-or-route")
        }
        publishCallState()
    }

    private fun publishInterruptionState() {
        AudioRuntimeState.reportInterruption(interruptionPolicy.pending, interruptionPolicy.status)
        val runtime = AudioRuntimeState.snapshot
        val candidate = interruptionPolicy.candidate
        val evidence = lastExternalEvidence
        val diagnostic = "pending=${interruptionPolicy.pending} status=${interruptionPolicy.status} activeCount=${evidence?.activeCount} blockingCount=${evidence?.blockingCount} categories=${evidence?.categories} musicActive=${evidence?.musicActive} readable=${evidence?.readable} ownPlaying=${player?.isPlaying} ownReady=${player?.playWhenReady} epochMatches=${candidate?.expectedEpoch == runtime.playbackEpoch} mediaMatches=${candidate != null && candidate.before.mediaId == player?.currentMediaItem?.mediaId} routeKnown=${runtime.route != null} originalRouteKnown=${candidate?.before?.route != null} routeMatches=${runtime.route?.let { candidate?.before?.route?.sameSessionDevice(it) }} suppression=${player?.playbackSuppressionReason} scheduled=$interruptionCheckScheduled"
        if (diagnostic != lastInterruptionDiagnostic) {
            lastInterruptionDiagnostic = diagnostic
            DiagLog.log("INTERRUPT", diagnostic)
        }
    }

    private fun clearInterruptionCheck() {
        routeHandler.removeCallbacks(interruptionCheck)
        interruptionCheckScheduled = false
    }

    private fun scheduleInterruptionCheck() {
        if (!interruptionCheckScheduled && !endingSession) {
            interruptionCheckScheduled = true
            routeHandler.postDelayed(interruptionCheck, 800)
        }
    }

    private fun cancelInterruptionResume(reason: String = "恢复候选已取消") {
        interruptionPolicy.cancel(reason)
        clearInterruptionCheck()
        publishInterruptionState()
    }

    private fun handleExternalPlayback(evidence: ExternalPlaybackEvidence) {
        if (endingSession) return
        val p = player ?: return
        lastExternalEvidence = evidence
        interruptionPolicy.observe(evidence, p.isPlaying || p.playWhenReady, SystemClock.elapsedRealtime())
        publishInterruptionState()
        if (interruptionPolicy.requiresSettleCheck(SystemClock.elapsedRealtime())) scheduleInterruptionCheck()
        else clearInterruptionCheck()
    }

    private fun evaluateInterruptionResume() {
        if (endingSession) return
        val p = player ?: return
        externalMonitor?.refresh()
        if (!interruptionPolicy.ready(callPlaybackSnapshot().copy(route = AudioRuntimeState.snapshot.route),
                p.playbackSuppressionReason, callPolicy.active || callPolicy.pending,
                SystemClock.elapsedRealtime(), audioInterruptionResume)) { publishInterruptionState(); return }
        if (p.playerError != null) { cancelInterruptionResume(); return }
        interruptionPolicy.attempted(SystemClock.elapsedRealtime())
        attemptingInterruptionResume = true
        try { p.play() } finally { attemptingInterruptionResume = false }
        if (p.playWhenReady && p.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE) {
            if (!AudioRuntimeState.snapshot.desiredPlaying) AudioRuntimeState.recordPlayIntent()
            interruptionPolicy.completed()
            clearInterruptionCheck()
        } else interruptionPolicy.adoptAttemptEpoch(AudioRuntimeState.snapshot.playbackEpoch)
        publishInterruptionState()
    }

    private fun updateUsbConfiguration() {
        val p = player ?: return
        val format = p.audioFormat
        val dsd = DsdPlaybackState.isDsdPlaying
        val source = format?.takeIf { it.sampleRate > 0 && it.channelCount > 0 }?.let {
            UsbSourceFormat(UsbPcmFormat(it.sampleRate, it.channelCount, it.pcmEncoding),
                precisionKnown = it.sampleMimeType == MimeTypes.AUDIO_RAW &&
                    it.pcmEncoding in setOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT) && !dsd,
                convertedFromDsd = dsd)
        }
        usbController?.onFocus(p.playWhenReady && p.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
            !callPolicy.active && p.playerError == null)
        usbController?.configure(usbHighFidelity, source, UsbStrictConstraints(
            dspBypassed = pureAudioMode || usbHighFidelity || offload,
            normalizationDisabled = usbHighFidelity || loudnessMode == "off",
            truePeakDisabled = pureAudioMode || usbHighFidelity || offload,
            offloadDisabled = !offload,
            speed = p.playbackParameters.speed, pitch = p.playbackParameters.pitch, volume = p.volume))
    }

    private fun updatePrecachePlayback() {
        val p = player ?: return
        val wifi = runCatching {
            val manager = connectivityManager
            manager?.getNetworkCapabilities(manager.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }.getOrDefault(false)
        if ((precacheWifiOnly || precacheMasterWifiOnly) && !wifi) {
            // Conservative service-owned policy: no VM or source-quality inference is required.
            AudioPrecacheBridge.cancel()
            return
        }
        val needsData = p.playWhenReady && (p.playbackState != Player.STATE_READY || p.bufferedPosition - p.currentPosition < 15_000L)
        AudioPrecacheBridge.updatePlayback(p.currentMediaItem?.localConfiguration?.customCacheKey, needsData)
    }

    /**
     * 更新「当前是否在播 DSD」的状态。
     *
     * 只看扩展名 —— DSD 的容器头（`DSD ` / `FRM8`）在 MediaItem 里不可见，
     * 而扩展名是确定的。等真正起播后如果解码失败，日志里会看到具体原因。
     */
    private fun updateDsdState(p: ExoPlayer) {
        val item = p.currentMediaItem
        val uri = item?.localConfiguration?.uri
        val isDsd = uri != null && DsdMediaSourceFactory.isDsdUri(uri)
        // 倍率标签由 DsdExtractor 解析容器头后回报（这里只管「是不是 DSD」）
        if (!isDsd) DsdPlaybackState.update(false, null)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** 用户从最近任务划掉 App：没在播就收摊，在播就继续（后台听歌不该被划掉打断） */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (TvEnv.isTv(this)) { endPlaybackSession(); return }
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    fun endPlaybackSession() {
        if (endingSession) return
        endingSession = true
        val p = player
        if (p != null) runCatching {
            runBlocking { withTimeoutOrNull(500) { PlaybackSnapshotStore(this@MoyinPlaybackService).savePosition(p.currentPosition) } }
        }
        AudioRuntimeState.recordPauseIntent(PauseCause.SERVICE_STOPPED)
        cancelCallResume()
        cancelInterruptionResume("播放会话已退出")
        AudioPrecacheBridge.cancel()
        routeHandler.removeCallbacksAndMessages(null)
        p?.pause()
        p?.stop()
        p?.clearMediaItems()
        releasePlaybackResources()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releasePlaybackResources() {
        if (resourcesReleased) return
        resourcesReleased = true
        PlaybackSessionExit.unbind(this)
        externalMonitor?.close()
        externalMonitor = null
        cancelInterruptionResume("播放服务已停止")
        serviceScope.cancel()
        deviceProfileManager?.close()
        deviceProfileManager = null
        usbController?.close()
        usbController = null
        callMonitor?.close()
        callMonitor = null
        cancelCallResume()
        routeHandler.removeCallbacksAndMessages(null)
        AudioPrecacheBridge.unbind(precache)
        precache?.close()
        precache = null
        if (networkCallbackRegistered) connectivityManager?.unregisterNetworkCallback(precacheNetworkCallback)
        networkCallbackRegistered = false
        connectivityManager = null
        audioManager?.unregisterAudioDeviceCallback(deviceCallback)
        audioManager = null
        renderersFactory?.releaseRouteCallbacks()
        renderersFactory = null
        currentOffloadListener?.let { listener -> player?.removeAudioOffloadListener(listener) }
        currentOffloadListener = null
        player?.release()
        session?.release()
        session = null
        cacheLease?.close()
        cacheLease = null
        playbackDataFactory = null
        disconnectedDevices.clear()
        player = null
        lastConfirmedRoute = null
        AudioRuntimeState.serviceStopped()
        DsdExtractor.DsdPlaybackReporter.onLabel = null
        DsdPlaybackState.setListener(null)
        KuwoCipherHolder.clear()
        // 处理器不占任何系统资源，这里只是把参数清零，避免下次意外沿用
        FxChain.release()
    }

    override fun onDestroy() {
        endingSession = true
        releasePlaybackResources()
        super.onDestroy()
    }
}
