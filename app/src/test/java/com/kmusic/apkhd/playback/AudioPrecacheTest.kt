package com.kmusic.apkhd.playback

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AudioPrecacheTest {
    private fun target(id: String, epoch: Long = 1) = AudioPrecache.Target("https://cdn.example/$id.flac", id, epoch)
    private fun await(latch: CountDownLatch) = assertTrue("Worker timed out", latch.await(3, TimeUnit.SECONDS))

    @Test fun configuredLimitIsNonNegativeCappedAndResourceAware() {
        assertEquals(0L, AudioPrecache.limit(-1))
        assertEquals(0L, AudioPrecache.limit(0))
        assertEquals(1024L, AudioPrecache.limit(1024))
        assertEquals(AudioPrecache.HARD_MAX_BYTES, AudioPrecache.limit(Long.MAX_VALUE))
        assertEquals(123L, AudioPrecache.limit(AudioPrecache.DEFAULT_MAX_BYTES, 123))
        assertEquals(AudioPrecache.DEFAULT_MAX_BYTES, AudioPrecache.limit(AudioPrecache.DEFAULT_MAX_BYTES, -1))
    }

    @Test fun replacementCancelsOldAndKeepsOnlyLatestWithSingleWorker() {
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val latestDone = CountDownLatch(1)
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val tokens = Collections.synchronizedList(mutableListOf<AudioPrecache.Token>())
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val precache = AudioPrecache(AudioPrecache.Runner { target, _, token ->
            peak.updateAndGet { maxOf(it, active.incrementAndGet()) }
            calls.add(target.cacheKey!!)
            tokens.add(token)
            try {
                if (target.cacheKey == "a") {
                    firstStarted.countDown()
                    releaseFirst.await(3, TimeUnit.SECONDS)
                } else latestDone.countDown()
            } finally { active.decrementAndGet() }
        })
        try {
            precache.updateQueueEpoch(1)
            assertTrue(precache.replace(target("a"), true))
            await(firstStarted)
            assertTrue(precache.replace(target("b"), true))
            assertTrue(precache.replace(target("c"), true))
            assertTrue(tokens[0].isCancelled)
            releaseFirst.countDown()
            await(latestDone)
            assertEquals(listOf("a", "c"), calls.toList())
            assertTrue(tokens[1].generation > tokens[0].generation)
            assertEquals(1, peak.get())
        } finally {
            releaseFirst.countDown()
            precache.close()
            assertTrue(precache.awaitClosed(3000))
        }
    }

    @Test fun staleGenerationPolicyAndCurrentPlaybackRejectWork() {
        val precache = AudioPrecache(AudioPrecache.Runner { _, _, _ -> fail("Unexpected background work") })
        try {
            precache.updateQueueEpoch(2)
            assertFalse(precache.replace(target("stale", 1), true))
            assertFalse(precache.replace(target("no-policy", 2), false))
            precache.updatePlayback("current", false)
            assertFalse(precache.replace(target("current", 2), true))
            precache.updatePlayback("current", true)
            assertFalse(precache.replace(target("next", 2), true))
            precache.close()
            assertFalse(precache.replace(target("after-close", 2), true))
        } finally {
            precache.close()
            assertTrue(precache.awaitClosed(3000))
        }
    }

    @Test fun epochChangeAndPlaybackBufferingCancelRunningToken() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        var token: AudioPrecache.Token? = null
        val precache = AudioPrecache(AudioPrecache.Runner { _, _, received ->
            token = received
            started.countDown()
            release.await(3, TimeUnit.SECONDS)
        })
        try {
            precache.updateQueueEpoch(1)
            assertTrue(precache.replace(target("a"), true))
            await(started)
            precache.updateQueueEpoch(2)
            assertTrue(token!!.isCancelled)
            assertFalse(precache.replace(target("late", 1), true))
            precache.updatePlayback("playing", true)
            assertFalse(precache.replace(target("b", 2), true))
        } finally {
            release.countDown()
            precache.close()
            assertTrue(precache.awaitClosed(3000))
        }
    }

    @Test fun duplicateActiveTargetDoesNotCancelAndCloseWaitsForWorkerLease() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        var token: AudioPrecache.Token? = null
        val precache = AudioPrecache(AudioPrecache.Runner { _, _, received ->
            token = received
            started.countDown()
            release.await(3, TimeUnit.SECONDS)
        }, onClosed = { exited.countDown() })
        try {
            precache.updateQueueEpoch(1)
            assertTrue(precache.replace(target("a"), true))
            await(started)
            assertTrue(precache.replace(target("a"), true))
            assertFalse(token!!.isCancelled)
            precache.close()
            assertTrue(token!!.isCancelled)
            assertEquals(1L, exited.count)
        } finally {
            release.countDown()
            precache.close()
            assertTrue(precache.awaitClosed(3000))
            assertEquals(0L, exited.count)
        }
    }

    private class Head : AudioPrecache.HeadAccess {
        val opens = mutableListOf<Pair<Long, Long>>()
        var closed = 0
        var bytes = 0L
        var failOpen = false
        var failRead = false
        var cachedPrefix = 0L
        var afterRead: (() -> Unit)? = null
        override fun cachedLength(position: Long, length: Long): Long =
            if (position < cachedPrefix) minOf(cachedPrefix - position, length) else -length
        override fun open(position: Long, length: Long) {
            opens.add(position to length)
            if (failOpen) throw IOException("open failure")
        }
        override fun read(buffer: ByteArray, length: Int): Int {
            if (failRead) throw IOException("read failure")
            // Pretend the server ignores Range and could return unlimited bytes.
            bytes += length
            afterRead?.invoke()
            return length
        }
        override fun close() { closed++ }
    }

    @Test fun headReadIsBoundedEvenWhenServerDoesNotEndAtRange() {
        val head = Head()
        AudioPrecache.cacheHead(head, 33_000, AudioPrecache.Token(1))
        assertEquals(33_000L, head.bytes)
        assertEquals(listOf(0L to 33_000L), head.opens)
        assertEquals(1, head.closed)
    }

    @Test fun existingSpansAndFullyCachedHeadAreNotDownloadedTwice() {
        val partial = Head().apply { cachedPrefix = 30_000 }
        AudioPrecache.cacheHead(partial, 33_000, AudioPrecache.Token(1))
        assertEquals(3000L, partial.bytes)
        assertEquals(listOf(30_000L to 3000L), partial.opens)
        val full = Head().apply { cachedPrefix = 33_000 }
        AudioPrecache.cacheHead(full, 33_000, AudioPrecache.Token(1))
        assertTrue(full.opens.isEmpty())
    }

    @Test fun openOrReadFailureNeverReopensUnboundedResource() {
        listOf(true, false).forEach { failOnOpen ->
            val head = Head().apply { failOpen = failOnOpen; failRead = !failOnOpen }
            try {
                AudioPrecache.cacheHead(head, 1024, AudioPrecache.Token(1))
                fail("Expected IOException")
            } catch (_: IOException) { }
            assertEquals(listOf(0L to 1024L), head.opens)
            assertEquals(1, head.closed)
            assertEquals(0L, head.bytes)
        }
    }

    @Test fun cancellationStopsBeforeFurtherReadsOrOpens() {
        val token = AudioPrecache.Token(1)
        val head = Head().apply { afterRead = { token.cancel() } }
        AudioPrecache.cacheHead(head, AudioPrecache.HARD_MAX_BYTES, token)
        assertEquals(32_768L, head.bytes)
        assertEquals(1, head.closed)
        val alreadyCancelled = Head()
        AudioPrecache.cacheHead(alreadyCancelled, 100, token)
        assertTrue(alreadyCancelled.opens.isEmpty())
    }

    @Test fun zeroLimitOpensNothing() {
        val head = Head()
        AudioPrecache.cacheHead(head, 0, AudioPrecache.Token(1))
        assertTrue(head.opens.isEmpty())
    }
}
