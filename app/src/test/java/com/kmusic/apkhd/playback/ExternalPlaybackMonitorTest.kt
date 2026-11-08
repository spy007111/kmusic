package com.kmusic.apkhd.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ExternalPlaybackMonitorTest {
    @Test
    fun startRegistersOnceAndImmediatelyPublishesSnapshot() {
        val platform = FakePlatform(ExternalPlaybackEvidence(2, true))
        val received = mutableListOf<ExternalPlaybackEvidence>()
        val monitor = ExternalPlaybackMonitor(platform, received::add)
        monitor.start()
        monitor.start()

        assertEquals(1, platform.registrations)
        assertEquals(listOf(ExternalPlaybackEvidence(2, true)), received)
        monitor.close()
    }

    @Test
    fun callbacksReadFreshAnonymousEvidenceRatherThanCachedActiveCount() {
        val platform = FakePlatform(ExternalPlaybackEvidence(1, true))
        val received = mutableListOf<ExternalPlaybackEvidence>()
        val monitor = ExternalPlaybackMonitor(platform, received::add)
        monitor.start()
        platform.evidence = ExternalPlaybackEvidence(0, false)
        platform.changed!!()

        assertEquals(listOf(ExternalPlaybackEvidence(1, true), ExternalPlaybackEvidence(0, false)), received)
        monitor.close()
    }

    @Test
    fun snapshotFailurePublishesUnreadableInsteadOfFalseStopEvidence() {
        val platform = FakePlatform(ExternalPlaybackEvidence(1, true))
        val received = mutableListOf<ExternalPlaybackEvidence>()
        val monitor = ExternalPlaybackMonitor(platform, received::add)
        monitor.start()
        platform.failSnapshot = true
        platform.changed!!()

        assertEquals(ExternalPlaybackEvidence(0, false, false), received.last())
        assertFalse(received.last().allInactive)
        monitor.close()
    }

    @Test
    fun registrationFailureIsReportedAndLaterSnapshotCanStillBeRead() {
        val platform = FakePlatform(ExternalPlaybackEvidence(1, true)).apply { failRegistration = true }
        val received = mutableListOf<ExternalPlaybackEvidence>()
        val monitor = ExternalPlaybackMonitor(platform, received::add)
        monitor.start()

        assertEquals(listOf(ExternalPlaybackEvidence(0, false, false), ExternalPlaybackEvidence(1, true)), received)
        monitor.close()
        assertEquals(1, platform.closes)
    }

    @Test
    fun closeIsIdempotentAndLateCallbackCannotPublishOrRead() {
        val platform = FakePlatform(ExternalPlaybackEvidence(1, true))
        val received = mutableListOf<ExternalPlaybackEvidence>()
        val monitor = ExternalPlaybackMonitor(platform, received::add)
        monitor.start()
        val callback = platform.changed!!
        val reads = platform.reads
        monitor.close()
        monitor.close()
        callback()
        monitor.refresh()
        monitor.start()

        assertEquals(1, platform.closes)
        assertEquals(reads, platform.reads)
        assertEquals(1, received.size)
        assertEquals(1, platform.registrations)
    }

    @Test
    fun closedBeforeStartNeverRegistersOrPublishes() {
        val platform = FakePlatform(ExternalPlaybackEvidence(0, false))
        val received = mutableListOf<ExternalPlaybackEvidence>()
        val monitor = ExternalPlaybackMonitor(platform, received::add)
        monitor.close()
        monitor.start()
        monitor.refresh()

        assertEquals(0, platform.registrations)
        assertEquals(0, platform.reads)
        assertEquals(0, received.size)
        assertEquals(1, platform.closes)
    }

    private class FakePlatform(var evidence: ExternalPlaybackEvidence) : ExternalPlaybackPlatform {
        var changed: (() -> Unit)? = null
        var registrations = 0
        var reads = 0
        var closes = 0
        var failRegistration = false
        var failSnapshot = false

        override fun observe(changed: () -> Unit) {
            registrations++
            if (failRegistration) error("test registration failure")
            this.changed = changed
        }

        override fun snapshot(): ExternalPlaybackEvidence {
            reads++
            if (failSnapshot) error("test snapshot failure")
            return evidence
        }

        override fun close() { closes++ }
    }
}
