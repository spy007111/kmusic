package com.kmusic.apkhd.playback

/** The service owns the worker; the UI supplies policy and queue identity only. */
object AudioPrecacheBridge {
    @Volatile private var worker: AudioPrecache? = null
    @Volatile private var queueEpoch = 0L
    @Volatile private var target: AudioPrecache.Target? = null
    @Volatile private var allowed = false

    @Synchronized fun bind(value: AudioPrecache?) {
        worker = value
        value?.updateQueueEpoch(queueEpoch)
    }

    @Synchronized fun unbind(value: AudioPrecache?) {
        if (worker === value) {
            worker = null
            target = null
            allowed = false
        }
    }

    @Synchronized fun updateQueueEpoch(epoch: Long) {
        if (epoch != queueEpoch) {
            queueEpoch = epoch
            target = null
            allowed = false
            worker?.updateQueueEpoch(epoch)
        }
    }

    @Synchronized fun replace(value: AudioPrecache.Target, shouldPrecache: Boolean) {
        if (value.queueEpoch != queueEpoch) return
        target = value
        allowed = shouldPrecache
        worker?.replace(value, shouldPrecache)
    }

    @Synchronized fun updatePlayback(currentKey: String?, needsData: Boolean) {
        worker?.updatePlayback(currentKey, needsData)
        if (!needsData) target?.let { worker?.replace(it, allowed) }
    }

    @Synchronized fun cancel() {
        target = null
        allowed = false
        worker?.cancel()
    }
}
