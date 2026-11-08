package com.kmusic.apkhd.playback

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/** Process-local exit latch prevents late controllers or recovery jobs from reviving a TV session. */
object PlaybackSessionExit {
    @Volatile var isExitRequested: Boolean = false
        private set
    private var owner: Any? = null
    private var stop: (() -> Unit)? = null

    @Synchronized fun openSession() { isExitRequested = false }
    @Synchronized fun bind(ownerToken: Any, callback: () -> Unit) { owner = ownerToken; stop = callback }
    @Synchronized fun unbind(ownerToken: Any) { if (owner === ownerToken) { owner = null; stop = null } }

    fun requestStop(context: Context) {
        isExitRequested = true
        AudioRuntimeState.recordPauseIntent(PauseCause.SERVICE_STOPPED)
        AudioPrecacheBridge.cancel()
        val finish = {
            val callback = synchronized(this) { stop }
            callback?.invoke()
            context.applicationContext.stopService(Intent(context, MoyinPlaybackService::class.java))
            Unit
        }
        if (Looper.myLooper() == Looper.getMainLooper()) finish() else Handler(Looper.getMainLooper()).post { finish() }
    }
}
