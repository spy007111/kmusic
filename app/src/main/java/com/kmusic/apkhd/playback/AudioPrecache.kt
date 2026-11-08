package com.kmusic.apkhd.playback

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** Single low-priority worker. The owner supplies queue/policy decisions, never settings here. */
class AudioPrecache(
    private val runner: Runner,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val onClosed: () -> Unit = {}
) : Closeable {
    data class Target(val url: String, val cacheKey: String?, val queueEpoch: Long, val sourceBytes: Long = 0)

    fun interface Runner {
        fun cache(target: Target, byteLimit: Long, token: Token)
    }

    class Token internal constructor(val generation: Long) {
        private val cancelled = AtomicBoolean(false)
        val isCancelled: Boolean get() = cancelled.get()

        internal fun cancel() { cancelled.set(true) }
    }

    private val lock = Object()
    private var epoch = Long.MIN_VALUE
    private var generation = 0L
    private var pending: Target? = null
    private var running: Target? = null
    private var activeToken: Token? = null
    private var playingKey: String? = null
    private var playbackNeedsData = false
    private var closed = false
    private val worker = Thread(::work, "KMusic-audio-precache").apply {
        isDaemon = true
        priority = Thread.MIN_PRIORITY
        start()
    }

    /** Call on every queue change, including stop; stale resolver completions are rejected. */
    fun updateQueueEpoch(queueEpoch: Long) = synchronized(lock) {
        if (queueEpoch != epoch) {
            epoch = queueEpoch
            invalidate()
        }
    }

    /** Call from the playback listener. Buffering/current-track requests always win. */
    fun updatePlayback(currentCacheKey: String?, needsData: Boolean) = synchronized(lock) {
        playingKey = currentCacheKey
        playbackNeedsData = needsData
        if (needsData || running?.cacheKey == currentCacheKey && currentCacheKey != null) invalidate()
        if (pending?.cacheKey == currentCacheKey && currentCacheKey != null) pending = null
        lock.notifyAll()
    }

    /** Replaces the previous target. shouldPrecache includes Wi-Fi/master/user policy. */
    fun replace(target: Target?, shouldPrecache: Boolean): Boolean = synchronized(lock) {
        if (closed || target != null && target.queueEpoch != epoch) return false
        if (!shouldPrecache || target == null || playbackNeedsData || target.cacheKey != null && target.cacheKey == playingKey) {
            invalidate()
            return false
        }
        if (target == pending || target == running && activeToken?.isCancelled == false) return true
        invalidate()
        if (limit(maxBytes, target.sourceBytes) == 0L || target.url.isBlank()) return false
        pending = target
        lock.notifyAll()
        true
    }

    fun cancel() = synchronized(lock) { invalidate() }

    override fun close() = synchronized(lock) {
        closed = true
        invalidate()
        lock.notifyAll()
    }

    /** For tests/background teardown only, never block the Android main thread. */
    fun awaitClosed(timeoutMs: Long): Boolean {
        worker.join(timeoutMs.coerceAtLeast(1))
        return !worker.isAlive
    }

    private fun invalidate() {
        generation++
        pending = null
        activeToken?.cancel()
    }

    private fun work() {
        try {
            while (true) {
                val task: Pair<Target, Token> = synchronized(lock) {
                    while (!closed && pending == null) lock.wait()
                    if (closed) return
                    val target = pending!!
                    pending = null
                    val token = Token(generation)
                    running = target
                    activeToken = token
                    target to token
                }
                try {
                    if (!task.second.isCancelled) runner.cache(task.first, limit(maxBytes, task.first.sourceBytes), task.second)
                } catch (_: Exception) {
                    // Best effort: playback owns retries. Never expose URLs/credentials in logs.
                } finally {
                    synchronized(lock) {
                        if (activeToken === task.second) {
                            activeToken = null
                            running = null
                        }
                    }
                }
            }
        } finally {
            onClosed()
        }
    }

    internal interface HeadAccess {
        fun cachedLength(position: Long, length: Long): Long
        fun open(position: Long, length: Long)
        fun read(buffer: ByteArray, length: Int): Int
        fun close()
    }

    companion object {
        internal fun cacheHead(access: HeadAccess, limit: Long, token: Token) {
            val buffer = ByteArray(32 * 1024)
            var position = 0L
            while (position < limit && !token.isCancelled) {
                val span = access.cachedLength(position, limit - position)
                if (span > 0) {
                    position += minOf(span, limit - position)
                    continue
                }
                val length = if (span == Long.MIN_VALUE) limit - position else minOf(-span, limit - position)
                if (length <= 0) break
                try {
                    if (token.isCancelled) break
                    access.open(position, length)
                    var remaining = length
                    while (remaining > 0 && !token.isCancelled) {
                        val read = access.read(buffer, minOf(buffer.size.toLong(), remaining).toInt())
                        if (read <= 0) break
                        check(read <= remaining && read <= buffer.size)
                        remaining -= read
                        position += read
                    }
                    if (remaining > 0) break
                } finally {
                    access.close()
                }
            }
        }

        const val DEFAULT_MAX_BYTES: Long = 2L * 1024 * 1024
        const val HARD_MAX_BYTES: Long = 4L * 1024 * 1024

        fun limit(configuredBytes: Long, sourceBytes: Long = 0): Long {
            val bounded = configuredBytes.coerceIn(0, HARD_MAX_BYTES)
            return if (sourceBytes > 0) minOf(bounded, sourceBytes) else bounded
        }
    }
}
