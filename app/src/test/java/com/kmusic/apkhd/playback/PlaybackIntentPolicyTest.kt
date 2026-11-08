package com.kmusic.apkhd.playback

import org.junit.Assert.*
import org.junit.Test

class PlaybackIntentPolicyTest {
    private val bluetooth = OutputRoute(12, 8, "蓝牙A2DP", isBluetooth = true)
    private val otherBluetooth = OutputRoute(13, 8, "蓝牙A2DP", isBluetooth = true)
    private val speaker = OutputRoute(2, 2, "扬声器")

    @Test fun repeatedPauseAlwaysInvalidatesEpoch() {
        val policy = PlaybackIntentPolicy()
        policy.play()
        val first = policy.pause(PauseCause.USER).playbackEpoch
        val second = policy.pause(PauseCause.USER)
        assertEquals(first + 1, second.playbackEpoch)
        assertFalse(second.desiredPlaying)
    }

    @Test fun errorKeepsPlayIntentAndEpochForNetworkRecovery() {
        val policy = PlaybackIntentPolicy()
        val before = policy.play()
        val after = policy.error()
        assertTrue(after.desiredPlaying)
        assertEquals(before.playbackEpoch, after.playbackEpoch)
        assertEquals(PauseCause.ERROR, after.pauseCause)
    }

    @Test fun transientFocusPreservesEpochAndPlayIntent() {
        val policy = PlaybackIntentPolicy()
        val playing = policy.play()
        val suppressed = policy.suppression(1)
        assertTrue(suppressed.desiredPlaying)
        assertEquals(playing.playbackEpoch, suppressed.playbackEpoch)
        assertEquals(PauseCause.FOCUS, suppressed.pauseCause)
        val restored = policy.suppression(0)
        assertTrue(restored.desiredPlaying)
        assertEquals(playing.playbackEpoch, restored.playbackEpoch)
        assertEquals(PauseCause.NONE, restored.pauseCause)
    }

    @Test fun permanentFocusLossCancelsIntent() {
        val policy = PlaybackIntentPolicy()
        val playing = policy.play()
        val paused = policy.pause(PauseCause.FOCUS)
        assertFalse(paused.desiredPlaying)
        assertTrue(paused.playbackEpoch > playing.playbackEpoch)
    }

    @Test fun defaultDisconnectPausesAndNeverResumes() {
        val policy = PlaybackIntentPolicy()
        policy.play()
        policy.disconnected(bluetooth, 1_000, false)
        assertFalse(policy.intent.desiredPlaying)
        assertEquals(PauseCause.ROUTE_DISCONNECT, policy.intent.pauseCause)
        assertFalse(policy.canResume(bluetooth, 2_000, false))
        assertFalse(policy.canResume(bluetooth, 2_000, true))
    }

    @Test fun sameConfirmedSessionRouteCanResumeWithinSixtySeconds() {
        val policy = PlaybackIntentPolicy()
        policy.play()
        policy.disconnected(bluetooth, 1_000, true)
        assertTrue(policy.canResume(bluetooth, 61_000, true))
        assertFalse(policy.canResume(bluetooth, 61_001, true))
        assertFalse(policy.canResume(bluetooth, 999, true))
    }

    @Test fun connectedTopologyWithoutConfirmedRouteCannotResume() {
        val policy = PlaybackIntentPolicy()
        policy.play()
        policy.disconnected(bluetooth, 1_000, true)
        assertFalse(policy.canResume(null, 2_000, true))
        assertFalse(policy.canResume(speaker, 2_000, true))
        assertFalse(policy.canResume(otherBluetooth, 2_000, true))
    }

    @Test fun equalNameDoesNotIdentifyTheSameDevice() {
        val original = bluetooth.copy(displayName = "Headphones")
        val replacement = otherBluetooth.copy(displayName = "Headphones")
        assertFalse(original.sameSessionDevice(replacement))
        assertFalse(original.sameSessionDevice(original.copy(deviceType = 26)))
    }

    @Test fun explicitPauseDuringDisconnectCancelsResumeEvenAlreadyPaused() {
        val policy = PlaybackIntentPolicy()
        policy.play()
        policy.disconnected(bluetooth, 1_000, true)
        policy.pause(PauseCause.USER)
        assertFalse(policy.canResume(bluetooth, 2_000, true))
    }

    @Test fun trackOrQueueChangeCancelsResume() {
        val policy = PlaybackIntentPolicy()
        policy.play()
        policy.disconnected(bluetooth, 1_000, true)
        policy.invalidate()
        assertFalse(policy.canResume(bluetooth, 2_000, true))
    }

    @Test fun focusInterruptionCancelsResumeEvenAfterFocusReturns() {
        val policy = PlaybackIntentPolicy()
        policy.play()
        policy.disconnected(bluetooth, 1_000, true)
        policy.suppression(1)
        policy.suppression(0)
        assertFalse(policy.canResume(bluetooth, 2_000, true))
    }

    @Test fun alreadyPausedBeforeDisconnectDoesNotBecomeResumeCandidate() {
        val policy = PlaybackIntentPolicy()
        policy.disconnected(bluetooth, 1_000, true)
        assertFalse(policy.canResume(bluetooth, 2_000, true))
    }

    @Test fun wiredDisconnectNeverCreatesBluetoothResumeCandidate() {
        val policy = PlaybackIntentPolicy()
        policy.play()
        policy.disconnected(speaker, 1_000, true)
        assertFalse(policy.canResume(speaker, 2_000, true))
    }

    @Test fun explicitPlayConsumesOldReconnectCandidate() {
        val policy = PlaybackIntentPolicy()
        policy.play()
        policy.disconnected(bluetooth, 1_000, true)
        policy.play()
        assertFalse(policy.canResume(bluetooth, 2_000, true))
        assertTrue(policy.intent.desiredPlaying)
    }

    @Test fun settingDisabledBlocksAnExistingCandidate() {
        val policy = PlaybackIntentPolicy()
        policy.play()
        policy.disconnected(bluetooth, 1_000, true)
        assertFalse(policy.canResume(bluetooth, 2_000, false))
    }
}
