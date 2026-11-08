package com.kmusic.apkhd.playback

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi

/** Reads state only, never numbers. The service owns registration and permission is requested by UI. */
class CallStateMonitor(
    private val context: Context,
    private val onState: (CellularCallState) -> Unit,
    private val onStatus: (CallMonitorStatus) -> Unit
) : AutoCloseable {
    private val manager = context.getSystemService(TelephonyManager::class.java)
    private var enabled = false
    private var registered = false
    private var modern: Any? = null
    private var legacy: PhoneStateListener? = null
    private var closed = false

    init { owner = this }

    fun configure(enabled: Boolean) {
        this.enabled = enabled
        refresh()
    }

    fun refresh() {
        if (closed) return
        if (!enabled) { unregister(); onStatus(CallMonitorStatus.DISABLED); return }
        if (manager == null || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            unregister(); onStatus(CallMonitorStatus.UNAVAILABLE); return
        }
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            unregister(); onStatus(CallMonitorStatus.PERMISSION_REQUIRED); return
        }
        if (registered) return
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) registerModern(manager)
            else registerLegacy(manager)
            registered = true
        }.onSuccess { onStatus(CallMonitorStatus.MONITORING) }
            .onFailure { unregister(); onStatus(if (it is SecurityException) CallMonitorStatus.PERMISSION_REQUIRED else CallMonitorStatus.ERROR) }
    }

    @RequiresApi(31)
    private fun registerModern(manager: TelephonyManager) {
        val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) { if (!closed && enabled) onState(stateOf(state)) }
        }
        modern = callback
        manager.registerTelephonyCallback(context.mainExecutor, callback)
    }

    @Suppress("DEPRECATION")
    private fun registerLegacy(manager: TelephonyManager) {
        val listener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                if (!closed && enabled) onState(stateOf(state))
            }
        }
        legacy = listener
        manager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
    }

    @Suppress("DEPRECATION")
    private fun unregister() {
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) unregisterModern()
            legacy?.let { manager?.listen(it, PhoneStateListener.LISTEN_NONE) }
        }
        modern = null
        legacy = null
        registered = false
    }

    @RequiresApi(31)
    private fun unregisterModern() { (modern as? TelephonyCallback)?.let { manager?.unregisterTelephonyCallback(it) } }

    override fun close() {
        closed = true
        unregister()
        if (owner === this) owner = null
    }

    companion object {
        @Volatile private var owner: CallStateMonitor? = null
        /** UI calls after permission result/onResume; does not start a stopped service or playback. */
        fun requestRefresh() { owner?.let { android.os.Handler(android.os.Looper.getMainLooper()).post { it.refresh() } } }
        fun stateOf(state: Int): CellularCallState = when (state) {
            TelephonyManager.CALL_STATE_IDLE -> CellularCallState.IDLE
            TelephonyManager.CALL_STATE_RINGING -> CellularCallState.RINGING
            TelephonyManager.CALL_STATE_OFFHOOK -> CellularCallState.OFFHOOK
            else -> CellularCallState.UNKNOWN
        }
    }
}
