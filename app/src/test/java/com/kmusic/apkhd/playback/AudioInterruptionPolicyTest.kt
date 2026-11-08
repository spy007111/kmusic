package com.kmusic.apkhd.playback

import org.junit.Assert.*
import org.junit.Test

class AudioInterruptionPolicyTest {
    private val route = OutputRoute(12, 8, "A2DP", isBluetooth = true)
    private val before = CallPlaybackSnapshot("song", 42_000, 10, true, route)
    private val current = before.copy(playbackEpoch = 11, desiredPlaying = false)
    private val active = ExternalPlaybackEvidence(1, true)
    private val silent = ExternalPlaybackEvidence(0, false)
    private fun policy(): AudioInterruptionPolicy = AudioInterruptionPolicy().also { it.focusLost(before, 11, 0, true) }
    private fun confirmStop(p: AudioInterruptionPolicy) {
        p.observe(active, false, 800)
        p.observe(silent, false, 2_000)
        assertFalse(p.ready(current, 0, false, 2_000, true))
    }

    @Test fun repeatedQuietObservationsDoNotResetTheOriginalStableWindow() {
        val p = policy()
        confirmStop(p)
        for (time in 2_100L..2_700L step 100) {
            p.observe(silent, false, time)
            assertFalse(p.ready(current, 0, false, time, true))
        }
        p.observe(silent, false, 2_800)
        assertTrue(p.ready(current, 0, false, 2_800, true))
    }

    @Test fun stoppedExternalActivityCanResumeOnlyAfterQuietAndActualRouteSettle() {
        val p = policy()
        confirmStop(p)
        assertFalse(p.ready(current, 0, false, 2_799, true))
        assertTrue(p.ready(current, 0, false, 2_800, true))
        assertEquals(42_000L, p.candidate!!.before.positionMs)
    }

    @Test fun ongoingExternalAudioNeverAuthorizesFocusFight() {
        val p = policy()
        p.observe(active, false, 800)
        p.observe(active, false, 60_000)
        assertFalse(p.ready(current, 0, false, 60_001, true))
        assertTrue(p.status.contains("仍活动"))
    }

    @Test fun ownPlaybackBeforeStopSettleIsNotForeignEvidence() {
        val p = policy()
        p.observe(active, false, 799)
        p.observe(silent, false, 1_000)
        assertFalse(p.ready(current, 0, false, 2_000, true))
        assertFalse(p.awaitingQuietCheck)
    }

    @Test fun ownStillActiveCancelsCandidateInsteadOfCountingItAsExternal() {
        val p = policy()
        p.observe(active, true, 1_000)
        assertFalse(p.pending)
    }

    @Test fun musicActiveAloneIsVetoNotPositiveForeignEvidence() {
        val p = policy()
        p.observe(ExternalPlaybackEvidence(0, true), false, 800)
        p.observe(silent, false, 2_000)
        assertFalse(p.ready(current, 0, false, 3_000, true))
        assertFalse(p.awaitingQuietCheck)
    }

    @Test fun noExternalActiveBaselineNeverResumesOnEmptyListAlone() {
        val p = policy()
        p.observe(silent, false, 800)
        p.observe(silent, false, 2_000)
        assertFalse(p.ready(current, 0, false, 3_000, true))
        assertTrue(p.status.contains("未获得"))
    }

    @Test fun unreadableListCannotBeMisreadAsExternalStop() {
        val p = policy()
        p.observe(active, false, 800)
        p.observe(ExternalPlaybackEvidence(0, false, false), false, 2_000)
        assertFalse(p.ready(current, 0, false, 3_000, true))
        assertTrue(p.status.contains("不可用"))
    }

    @Test fun musicFlagStillActivePreventsClearEvenWithEmptyList() {
        val p = policy()
        p.observe(active, false, 800)
        p.observe(ExternalPlaybackEvidence(0, true), false, 2_000)
        assertFalse(p.ready(current, 0, false, 3_000, true))
    }

    @Test fun restartedExternalActivityResetsQuietStability() {
        val p = policy()
        confirmStop(p)
        p.observe(active, false, 2_500)
        assertFalse(p.ready(current, 0, false, 3_000, true))
        p.observe(silent, false, 3_100)
        assertFalse(p.ready(current, 0, false, 3_100, true))
        assertTrue(p.ready(current, 0, false, 3_900, true))
    }

    @Test fun userPauseEpochCancelsCandidateEvenWhenAlreadyPaused() {
        val p = policy()
        confirmStop(p)
        assertFalse(p.ready(current.copy(playbackEpoch = 12), 0, false, 2_800, true))
        assertFalse(p.pending)
    }

    @Test fun changedMediaCancelsCandidate() {
        val p = policy()
        confirmStop(p)
        assertFalse(p.ready(current.copy(mediaId = "new-song"), 0, false, 2_800, true))
        assertFalse(p.pending)
    }

    @Test fun unknownOrDifferentActualRouteCannotResumeThroughSpeaker() {
        val p = policy()
        confirmStop(p)
        assertFalse(p.ready(current.copy(route = null), 0, false, 2_800, true))
        assertFalse(p.ready(current.copy(route = OutputRoute(2, 2, "speaker")), 0, false, 3_000, true))
        assertFalse(p.ready(current.copy(route = OutputRoute(13, 7, "SCO")), 0, false, 3_100, true))
    }

    @Test fun callCandidateHasPriorityAndTransientSuppressionDoesNotRestart() {
        val p = policy()
        confirmStop(p)
        assertFalse(p.ready(current, 0, true, 2_800, true))
        assertFalse(p.ready(current, 1, false, 3_000, true))
    }

    @Test fun retriesAreBoundedAfterPositiveEndEvidence() {
        val p = policy()
        confirmStop(p)
        assertTrue(p.ready(current, 0, false, 2_800, true)); p.attempted(2_800)
        assertFalse(p.ready(current, 0, false, 3_000, true))
        assertTrue(p.ready(current, 0, false, 3_800, true)); p.attempted(3_800)
        assertTrue(p.ready(current, 0, false, 4_800, true)); p.attempted(4_800)
        assertFalse(p.ready(current, 0, false, 5_800, true))
        assertFalse(p.pending)
    }

    @Test fun quietRecoveryWindowDoesNotRemainPendingForever() {
        val p = policy()
        confirmStop(p)
        assertFalse(p.ready(current, 0, false, 10_001, true))
        assertFalse(p.pending)
    }

    @Test fun manualCancelOrCompletionCannotBeReopenedByRepeatedEmptyCallbacks() {
        val p = policy()
        confirmStop(p)
        p.completed()
        p.observe(silent, false, 3_000)
        assertFalse(p.ready(current, 0, false, 4_000, true))
        assertFalse(p.pending)
    }

    @Test fun disabledOrAlreadyPausedDoesNotCaptureCandidate() {
        val p = AudioInterruptionPolicy()
        p.focusLost(before, 11, 0, false)
        assertFalse(p.pending)
        p.focusLost(before.copy(desiredPlaying = false), 11, 0, true)
        assertFalse(p.pending)
    }
}
