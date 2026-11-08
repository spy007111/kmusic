package com.kmusic.apkhd.playback

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AudioRuntimeStateTest {
    @Before fun reset() { AudioRuntimeState.serviceStopped() }
    @After fun cleanup() { AudioRuntimeState.serviceStopped() }

    @Test fun serviceStartupPreservesPendingVmPlayIntent() {
        val epoch = AudioRuntimeState.recordPlayIntent()
        AudioRuntimeState.serviceStarted(false)
        assertTrue(AudioRuntimeState.snapshot.desiredPlaying)
        assertEquals(epoch, AudioRuntimeState.snapshot.playbackEpoch)
    }

    @Test fun updatesPublishRepeatedPauseEvenWhenAlreadyPaused() {
        AudioRuntimeState.recordPauseIntent()
        val previous = AudioRuntimeState.updates.value
        AudioRuntimeState.recordPauseIntent()
        val current = AudioRuntimeState.updates.value
        assertFalse(current.desiredPlaying)
        assertEquals(previous.playbackEpoch + 1, current.playbackEpoch)
        assertNotEquals(previous, current)
    }

    @Test fun requestedOffloadDoesNotClaimActiveOffload() {
        AudioRuntimeState.serviceStarted(true)
        assertTrue(AudioRuntimeState.snapshot.offloadRequested)
        assertFalse(AudioRuntimeState.snapshot.offloadActive)
        assertEquals("等待输出确认", AudioRuntimeState.snapshot.offloadStatus)
        AudioRuntimeState.reportOutput(ApplicationAudioFormat(48_000, 2, 2, true), null)
        assertFalse(AudioRuntimeState.snapshot.offloadActive)
        assertTrue(AudioRuntimeState.snapshot.offloadStatus.contains("具体原因未报告"))
    }

    @Test fun offloadListenerStateIsIndependentOfPreference() {
        AudioRuntimeState.serviceStarted(true)
        AudioRuntimeState.reportOffloadActive(true)
        AudioRuntimeState.reportOffloadSleeping(true)
        assertTrue(AudioRuntimeState.snapshot.offloadActive)
        assertTrue(AudioRuntimeState.snapshot.offloadSleeping)
        AudioRuntimeState.reportOffloadActive(false)
        assertTrue(AudioRuntimeState.snapshot.offloadRequested)
        assertFalse(AudioRuntimeState.snapshot.offloadSleeping)
    }

    @Test fun serviceDestructionClearsOutputAndReconnectCandidate() {
        val route = OutputRoute(12, 8, "蓝牙A2DP", isBluetooth = true)
        AudioRuntimeState.serviceStarted(true)
        AudioRuntimeState.recordPlayIntent()
        AudioRuntimeState.reportOutput(ApplicationAudioFormat(44_100, 2, 2, true), route)
        AudioRuntimeState.reportDisconnect(route, 1_000, true)
        assertTrue(AudioRuntimeState.canResume(route, 2_000, true))
        AudioRuntimeState.serviceStopped()
        assertFalse(AudioRuntimeState.canResume(route, 2_000, true))
        assertFalse(AudioRuntimeState.snapshot.serviceAlive)
        assertFalse(AudioRuntimeState.snapshot.desiredPlaying)
        assertNull(AudioRuntimeState.snapshot.route)
        assertNull(AudioRuntimeState.snapshot.applicationOutput)
    }

    @Test fun purePcmRequestHasItsOwnBypassReasonAndDisablesOffload() {
        AudioRuntimeState.serviceStarted(true, true)
        assertTrue(AudioRuntimeState.snapshot.pureAudioModeRequested)
        assertFalse(AudioRuntimeState.snapshot.offloadRequested)
        assertFalse(AudioRuntimeState.snapshot.offloadActive)
        assertEquals("纯净PCM模式", AudioRuntimeState.snapshot.dspBypassReason)
    }

    @Test fun purePcmRequestDoesNotInventFloatOutputForSixteenBitFallback() {
        AudioRuntimeState.serviceStarted(false, true)
        AudioRuntimeState.reportOutput(ApplicationAudioFormat(44_100, 2, 2, true), null)
        assertTrue(AudioRuntimeState.snapshot.pureAudioModeRequested)
        assertEquals(2, AudioRuntimeState.snapshot.applicationOutput!!.encoding)
        assertNull(AudioRuntimeState.snapshot.route)
    }

    @Test fun newProcessedServiceClearsPreviousPurePcmRequest() {
        AudioRuntimeState.serviceStarted(false, true)
        AudioRuntimeState.serviceStopped()
        AudioRuntimeState.serviceStarted(false)
        assertFalse(AudioRuntimeState.snapshot.pureAudioModeRequested)
        assertNull(AudioRuntimeState.snapshot.dspBypassReason)
    }

    @Test fun applicationFormatWithoutActualRouteRemainsUnknownRoute() {
        AudioRuntimeState.reportOutput(ApplicationAudioFormat(192_000, 2, 4, true), null)
        assertNull(AudioRuntimeState.snapshot.route)
        assertEquals(192_000, AudioRuntimeState.snapshot.applicationOutput!!.sampleRate)
    }
}
