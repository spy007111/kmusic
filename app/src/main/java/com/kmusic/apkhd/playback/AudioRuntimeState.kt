package com.kmusic.apkhd.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AudioRuntimeSnapshot(
    val playbackEpoch: Long = 0,
    val desiredPlaying: Boolean = false,
    val pauseCause: PauseCause = PauseCause.NONE,
    val suppressionReason: Int = 0,
    val offloadRequested: Boolean = false,
    val pureAudioModeRequested: Boolean = false,
    val usbHighFidelityRequested: Boolean = false,
    val offloadActive: Boolean = false,
    val offloadSleeping: Boolean = false,
    val route: OutputRoute? = null,
    val applicationOutput: ApplicationAudioFormat? = null,
    val callState: CellularCallState = CellularCallState.UNKNOWN,
    val callMonitorStatus: CallMonitorStatus = CallMonitorStatus.DISABLED,
    val callResumePending: Boolean = false,
    val interruptionPending: Boolean = false,
    val interruptionStatus: String = "等待音频中断",
    val modeSwitching: Boolean = false,
    val modeSwitchStatus: String = "",
    val serviceAlive: Boolean = false
) {
    val dspBypassReason: String? get() = when {
        usbHighFidelityRequested -> "USB高保真请求"
        pureAudioModeRequested -> "纯净PCM模式"
        offloadRequested -> "offload偏好（含普通输出回退）"
        else -> null
    }
    val offloadStatus: String get() = when {
        offloadActive -> "已生效"
        !offloadRequested -> "已关闭"
        applicationOutput == null -> "等待输出确认"
        else -> "未生效（普通输出；具体原因未报告）"
    }
}

/** Process-local runtime evidence; never reconstruct a current route from a connected-device list. */
object AudioRuntimeState {
    private val policy = PlaybackIntentPolicy()
    private val mutableUpdates = MutableStateFlow(AudioRuntimeSnapshot())
    val updates: StateFlow<AudioRuntimeSnapshot> = mutableUpdates.asStateFlow()
    val snapshot: AudioRuntimeSnapshot get() = mutableUpdates.value

    private fun publish(intent: PlaybackIntent) {
        mutableUpdates.value = snapshot.copy(
            playbackEpoch = intent.playbackEpoch, desiredPlaying = intent.desiredPlaying,
            pauseCause = intent.pauseCause, suppressionReason = intent.suppressionReason
        )
    }

    @Synchronized fun recordPlayIntent(): Long { publish(policy.play()); return snapshot.playbackEpoch }
    @Synchronized fun recordPauseIntent(cause: PauseCause = PauseCause.USER): Long { publish(policy.pause(cause)); return snapshot.playbackEpoch }
    @Synchronized fun invalidatePlayback(): Long { publish(policy.invalidate()); return snapshot.playbackEpoch }
    @Synchronized fun reportError() { publish(policy.error()) }
    @Synchronized fun reportSuppression(reason: Int) { publish(policy.suppression(reason)) }
    @Synchronized fun reportDisconnect(route: OutputRoute, nowMs: Long, autoResume: Boolean) {
        publish(policy.disconnected(route, nowMs, autoResume))
    }
    @Synchronized fun canResume(confirmedRoute: OutputRoute?, nowMs: Long, autoResume: Boolean): Boolean =
        snapshot.serviceAlive && policy.canResume(confirmedRoute, nowMs, autoResume)

    @Synchronized fun serviceStarted(offloadRequested: Boolean, pureAudioModeRequested: Boolean = false, usbHighFidelityRequested: Boolean = false) {
        val mode = AudioPipelineMode.resolve(offloadRequested, pureAudioModeRequested, usbHighFidelityRequested)
        mutableUpdates.value = snapshot.copy(serviceAlive = true, offloadRequested = mode.offload,
            pureAudioModeRequested = pureAudioModeRequested && !usbHighFidelityRequested,
            usbHighFidelityRequested = usbHighFidelityRequested,
            offloadActive = false, offloadSleeping = false, route = null, applicationOutput = null)
    }
    @Synchronized fun serviceStopped() {
        publish(policy.pause(PauseCause.SERVICE_STOPPED))
        publish(policy.suppression(0))
        mutableUpdates.value = snapshot.copy(serviceAlive = false, route = null, applicationOutput = null,
            offloadActive = false, offloadSleeping = false, callState = CellularCallState.UNKNOWN,
            callMonitorStatus = CallMonitorStatus.DISABLED, callResumePending = false,
            interruptionPending = false, interruptionStatus = "播放服务已停止", modeSwitching = false, modeSwitchStatus = "")
    }
    @Synchronized fun reportOffloadActive(active: Boolean) {
        mutableUpdates.value = snapshot.copy(offloadActive = active, offloadSleeping = if (active) snapshot.offloadSleeping else false)
    }
    @Synchronized fun reportOffloadSleeping(sleeping: Boolean) {
        mutableUpdates.value = snapshot.copy(offloadSleeping = sleeping)
    }
    @Synchronized fun reportCall(state: CellularCallState, pending: Boolean) {
        mutableUpdates.value = snapshot.copy(callState = state, callResumePending = pending)
    }
    @Synchronized fun reportCallMonitor(status: CallMonitorStatus) {
        mutableUpdates.value = snapshot.copy(callMonitorStatus = status)
    }
    @Synchronized fun reportInterruption(pending: Boolean, status: String) {
        mutableUpdates.value = snapshot.copy(interruptionPending = pending, interruptionStatus = status)
    }
    @Synchronized fun reportModeSwitch(switching: Boolean, status: String) {
        mutableUpdates.value = snapshot.copy(modeSwitching = switching, modeSwitchStatus = status)
    }
    @Synchronized fun reportOutput(format: ApplicationAudioFormat?, route: OutputRoute?) {
        mutableUpdates.value = snapshot.copy(applicationOutput = format, route = route)
    }
}
