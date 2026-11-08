package com.kmusic.apkhd.playback

import android.content.Context
import androidx.media3.common.PriorityTaskManager
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** One process cache. Capacity changes are deferred until every service/worker lease is closed. */
@UnstableApi
object MediaCache {
    private const val PLAYBACK_PRIORITY = 0
    private const val PRECACHE_PRIORITY = -1000
    private val priorities = PriorityTaskManager()
    private var cache: SimpleCache? = null
    private var currentGb = -1
    private var leases = 0
    private var legacyAccess = false

    /** Compatibility access cannot prove the caller's lifetime; never release that instance. */
    @Synchronized
    fun get(context: Context, gb: Int): SimpleCache? {
        if (gb <= 0) return null
        legacyAccess = true
        return ensureCache(context, gb)
    }

    @Synchronized
    fun acquire(context: Context, gb: Int): Lease? {
        if (gb <= 0) return null
        val c = ensureCache(context, gb)
        leases++
        return Lease(context.applicationContext, c)
    }

    private fun ensureCache(context: Context, gb: Int): SimpleCache {
        val existing = cache
        if (existing != null && (currentGb == gb || leases > 0 || legacyAccess)) return existing
        existing?.release()
        return SimpleCache(File(context.cacheDir, "media"),
            LeastRecentlyUsedCacheEvictor(gb.toLong() * 1024 * 1024 * 1024),
            StandaloneDatabaseProvider(context.applicationContext)).also {
            cache = it
            currentGb = gb
        }
    }

    /** Service owns this lease until player release. Workers take independent leases. */
    class Lease internal constructor(private val context: Context, private val cache: SimpleCache) : Closeable {
        private val closed = AtomicBoolean(false)

        fun playbackDataSourceFactory(): DataSource.Factory {
            check(!closed.get())
            val factory = factory(PLAYBACK_PRIORITY)
            return DataSource.Factory { PlaybackPriorityDataSource(factory.createDataSource()) }
        }

        fun createPrecache(maxBytes: Long = AudioPrecache.DEFAULT_MAX_BYTES): AudioPrecache {
            synchronized(MediaCache) {
                check(!closed.get())
                leases++
            }
            val factory = factory(PRECACHE_PRIORITY)
            return AudioPrecache(runner = AudioPrecache.Runner { target, limit, token ->
                if (token.isCancelled || limit <= 0) return@Runner
                val spec = DataSpec.Builder().setUri(target.url).setKey(target.cacheKey).setPosition(0).setLength(limit).build()
                val key = CacheKeyFactory.DEFAULT.buildCacheKey(spec)
                AudioPrecache.cacheHead(object : AudioPrecache.HeadAccess {
                    private var source: CacheDataSource? = null
                    override fun cachedLength(position: Long, length: Long): Long = cache.getCachedLength(key, position, length)
                    override fun open(position: Long, length: Long) {
                        source = factory.createDataSource()
                        source!!.open(spec.buildUpon().setPosition(position).setLength(length).build())
                    }
                    override fun read(buffer: ByteArray, length: Int): Int = source!!.read(buffer, 0, length)
                    override fun close() {
                        source?.close()
                        source = null
                    }
                }, limit, token)
            }, maxBytes = maxBytes, onClosed = { releaseLease() })
        }

        private fun factory(priority: Int): CacheDataSource.Factory = CacheDataSource.Factory()
            .setCache(cache)
            // Store plaintext. Authentication, URL refresh and QMC decryption share the playback chain.
            .setUpstreamDataSourceFactory(if (priority == PRECACHE_PRIORITY)
                KuwoEncryptedDataSource.Factory(context, connectTimeoutMs = 3_000, readTimeoutMs = 5_000)
                else KuwoEncryptedDataSource.Factory(context))
            .setCacheKeyFactory(CacheKeyFactory.DEFAULT)
            .setUpstreamPriorityTaskManager(priorities)
            .setUpstreamPriority(priority)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        override fun close() {
            if (closed.compareAndSet(false, true)) releaseLease()
        }
    }

    private class PlaybackPriorityDataSource(private val delegate: DataSource) : DataSource {
        override fun addTransferListener(transferListener: TransferListener) = delegate.addTransferListener(transferListener)
        override fun open(dataSpec: DataSpec): Long = withPlaybackPriority { delegate.open(dataSpec) }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            withPlaybackPriority { delegate.read(buffer, offset, length) }
        override fun getUri() = delegate.uri
        override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders
        override fun close() = delegate.close()
        private inline fun <T> withPlaybackPriority(action: () -> T): T {
            priorities.add(PLAYBACK_PRIORITY)
            return try { action() } finally { priorities.remove(PLAYBACK_PRIORITY) }
        }
    }

    @Synchronized
    private fun releaseLease() { leases-- }

    /** Safe explicit teardown; never call while a player or a cancelling worker still owns a lease. */
    @Synchronized
    fun releaseIfIdle(): Boolean {
        if (leases > 0 || legacyAccess) return false
        cache?.release()
        cache = null
        currentGb = -1
        return true
    }

    data class ClearResult(val removed: Int, val failures: Int) {
        val successful: Boolean get() = failures == 0
    }

    @Synchronized
    fun clear(): ClearResult {
        val c = cache ?: return ClearResult(0, 0)
        val keys = runCatching { c.keys.toList() }.getOrElse { return ClearResult(0, 1) }
        var removed = 0
        var failures = 0
        keys.forEach { key ->
            try {
                c.removeResource(key)
                removed++
            } catch (_: Exception) {
                failures++
            }
        }
        return ClearResult(removed, failures)
    }

    fun sizeBytes(context: Context): Long {
        val dir = File(context.cacheDir, "media")
        if (!dir.exists()) return 0L
        return runCatching { dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)
    }
}
