package com.kmusic.apkhd.playback

import org.junit.Assert.*
import org.junit.Test

class CallResumePolicyTest {
    private val a2dp = OutputRoute(12, 8, "A2DP", isBluetooth = true)
    private val sco = OutputRoute(13, 7, "SCO", isBluetooth = true)
    private val speaker = OutputRoute(2, 2, "speaker")
    private fun playing(epoch: Long = 10) = CallPlaybackSnapshot("song", 12_345, epoch, true, a2dp)
    private fun paused(epoch: Long = 11, route: OutputRoute? = a2dp) = playing(epoch).copy(desiredPlaying = false, route = route)
    private fun end(policy: CallResumePolicy, snapshot: CallPlaybackSnapshot = paused(), at: Long = 2_000) {
        assertTrue(policy.stateChanged(CellularCallState.IDLE, snapshot, at, true))
        assertNull(policy.ready(snapshot, 0, at + 600, true))
    }

    @Test fun callThenPermanentFocusThenIdleCanResumeOriginalMediaPosition() {
        val policy = CallResumePolicy()
        policy.stateChanged(CellularCallState.RINGING, playing(), 1_000, true)
        policy.adoptCallPauseEpoch(11)
        policy.focusLost(paused(), 12, 1_100)
        end(policy, paused(12))
        val candidate = policy.ready(paused(12), 0, 3_200, true)!!
        assertEquals("song", candidate.before.mediaId)
        assertEquals(12_345L, candidate.before.positionMs)
        assertEquals(12L, candidate.expectedEpoch)
    }

    @Test fun permanentFocusThenCallThenIdleCorrelatesPriorPlayingIntent() {
        val policy = CallResumePolicy()
        policy.focusLost(playing(), 11, 1_000)
        policy.stateChanged(CellularCallState.RINGING, paused(), 1_500, true)
        assertTrue(policy.pending)
        end(policy)
        assertNotNull(policy.ready(paused(), 0, 3_200, true))
    }

    @Test fun nonCallFocusThenIdleNeverCreatesCandidate() {
        val policy = CallResumePolicy()
        policy.focusLost(playing(), 11, 1_000)
        assertFalse(policy.stateChanged(CellularCallState.IDLE, paused(), 2_000, true))
        assertNull(policy.ready(paused(), 0, 3_000, true))
    }

    @Test fun lateCallBeyondCorrelationWindowDoesNotResumeOldFocusIntent() {
        val policy = CallResumePolicy()
        policy.focusLost(playing(), 11, 1_000)
        policy.stateChanged(CellularCallState.RINGING, paused(), 4_001, true)
        assertFalse(policy.pending)
    }

    @Test fun explicitPauseAfterFocusBeforeRingingRejectsPriorIntent() {
        val policy = CallResumePolicy()
        policy.focusLost(playing(), 11, 1_000)
        policy.stateChanged(CellularCallState.RINGING, paused(12), 1_500, true)
        assertFalse(policy.pending)
    }

    @Test fun alreadyPausedBeforeCallNeverResumes() {
        val policy = CallResumePolicy()
        policy.stateChanged(CellularCallState.RINGING, paused(), 1_000, true)
        assertFalse(policy.pending)
    }

    @Test fun repeatedExplicitPauseDuringCallCannotBeReauthorizedByLaterFocusLoss() {
        val policy = CallResumePolicy()
        policy.stateChanged(CellularCallState.RINGING, playing(), 1_000, true)
        policy.adoptCallPauseEpoch(11)
        policy.focusLost(paused(12), 13, 1_500)
        assertFalse(policy.pending)
    }

    @Test fun ringingToOffhookKeepsTheOriginalCandidate() {
        val policy = CallResumePolicy()
        policy.stateChanged(CellularCallState.RINGING, playing(), 1_000, true)
        policy.adoptCallPauseEpoch(11)
        policy.stateChanged(CellularCallState.OFFHOOK, paused(), 1_500, true)
        assertEquals(12_345L, policy.candidate!!.before.positionMs)
        assertEquals(11L, policy.candidate!!.expectedEpoch)
    }

    @Test fun initialIdleHasNoCandidateAndRepeatedIdleDoesNotScheduleTwice() {
        val policy = CallResumePolicy()
        assertFalse(policy.stateChanged(CellularCallState.IDLE, playing(), 0, true))
        policy.stateChanged(CellularCallState.RINGING, playing(), 1_000, true)
        policy.adoptCallPauseEpoch(11)
        end(policy)
        assertFalse(policy.stateChanged(CellularCallState.IDLE, paused(), 2_100, true))
    }

    @Test fun scoDuringCallIsNotAResumeRouteOrProfileDisconnect() {
        val policy = CallResumePolicy()
        policy.stateChanged(CellularCallState.RINGING, playing(), 1_000, true)
        policy.adoptCallPauseEpoch(11)
        assertTrue(policy.callTransportRoute(sco, 1_100))
        end(policy, paused(route = sco))
        assertNull(policy.ready(paused(route = sco), 0, 3_200, true))
        assertNull(policy.ready(paused(), 0, 3_500, true))
        assertNotNull(policy.ready(paused(), 0, 4_100, true))
    }

    @Test fun scoOutsideCallCorrelationIsNotTreatedAsCallEvidence() {
        val policy = CallResumePolicy()
        assertFalse(policy.callTransportRoute(sco, 1_000))
        policy.focusLost(playing(), 11, 1_000)
        assertTrue(policy.callTransportRoute(sco, 1_100))
        assertFalse(policy.callTransportRoute(sco, 4_001))
    }

    @Test fun disconnectedOriginalDeviceMustNotResumeThroughSpeaker() {
        val policy = CallResumePolicy()
        policy.stateChanged(CellularCallState.RINGING, playing(), 1_000, true)
        policy.adoptCallPauseEpoch(11)
        end(policy, paused(route = speaker))
        assertNull(policy.ready(paused(route = speaker), 0, 3_200, true))
    }

    @Test fun unknownRouteCannotProveSafeMediaReturn() {
        val policy = CallResumePolicy()
        policy.stateChanged(CellularCallState.RINGING, playing().copy(route = null), 1_000, true)
        policy.adoptCallPauseEpoch(11)
        end(policy)
        assertNull(policy.ready(paused(), 0, 3_200, true))
    }

    @Test fun manualCancelRemovesBothCandidateAndFocusCorrelation() {
        val policy = CallResumePolicy()
        policy.focusLost(playing(), 11, 1_000)
        policy.cancel()
        policy.stateChanged(CellularCallState.RINGING, paused(), 1_100, true)
        assertFalse(policy.pending)
    }

    @Test fun changedMediaCancelsPendingRecovery() {
        val policy = CallResumePolicy()
        policy.stateChanged(CellularCallState.RINGING, playing(), 1_000, true)
        policy.adoptCallPauseEpoch(11)
        end(policy)
        assertNull(policy.ready(paused().copy(mediaId = "new-song"), 0, 3_200, true))
        assertFalse(policy.pending)
    }

    @Test fun userEpochChangeCancelsPendingRecovery() {
        val policy = CallResumePolicy()
        policy.stateChanged(CellularCallState.RINGING, playing(), 1_000, true)
        policy.adoptCallPauseEpoch(11)
        end(policy)
        assertNull(policy.ready(paused(12), 0, 3_200, true))
        assertFalse(policy.pending)
    }

    @Test fun disabledFeatureNeverCreatesOrMaintainsCandidate() {
        val policy = CallResumePolicy()
        policy.stateChanged(CellularCallState.RINGING, playing(), 1_000, false)
        assertFalse(policy.pending)
    }

    @Test fun remainingTransientFocusBlocksResumeWithoutInfiniteWindow() {
        val policy = CallResumePolicy()
        policy.stateChanged(CellularCallState.RINGING, playing(), 1_000, true)
        policy.adoptCallPauseEpoch(11)
        end(policy)
        assertNull(policy.ready(paused(), 1, 3_200, true))
        assertNull(policy.ready(paused(), 1, 10_001, true))
        assertFalse(policy.pending)
    }

    @Test fun recoveryAttemptsAreBoundedAndSpaced() {
        val policy = CallResumePolicy()
        policy.stateChanged(CellularCallState.RINGING, playing(), 1_000, true)
        policy.adoptCallPauseEpoch(11)
        end(policy)
        assertNotNull(policy.ready(paused(), 0, 3_200, true)); policy.attempted(3_200)
        assertNull(policy.ready(paused(), 0, 3_300, true))
        assertNotNull(policy.ready(paused(), 0, 4_200, true)); policy.attempted(4_200)
        assertNotNull(policy.ready(paused(), 0, 5_200, true)); policy.attempted(5_200)
        assertNull(policy.ready(paused(), 0, 6_200, true))
        assertFalse(policy.pending)
    }

    @Test fun newNonCallPermanentFocusAfterIdleCancelsPendingCandidate() {
        val policy = CallResumePolicy()
        policy.stateChanged(CellularCallState.RINGING, playing(), 1_000, true)
        policy.adoptCallPauseEpoch(11)
        end(policy)
        policy.focusLost(paused(), 12, 2_300)
        assertFalse(policy.pending)
    }

    @Test fun completedCandidateCannotResumeOnRepeatedIdle() {
        val policy = CallResumePolicy()
        policy.stateChanged(CellularCallState.RINGING, playing(), 1_000, true)
        policy.adoptCallPauseEpoch(11)
        end(policy)
        policy.completed()
        assertFalse(policy.stateChanged(CellularCallState.IDLE, paused(), 3_000, true))
        assertNull(policy.ready(paused(), 0, 4_000, true))
    }
}
