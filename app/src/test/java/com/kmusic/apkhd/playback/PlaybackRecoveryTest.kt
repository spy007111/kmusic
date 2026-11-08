package com.kmusic.apkhd.playback

import org.junit.Assert.*
import org.junit.Test

class PlaybackRecoveryTest {
    private val token = PlaybackRecoveryToken(4, 7, "a")
    private fun commit(play: Long = 4, queue: Long = 7, id: String = "a",
                       desired: Boolean = true, suppressed: Boolean = false) =
        PlaybackRecovery.canCommit(token, play, queue, id, desired, suppressed)

    @Test fun currentRequestMayResume() { assertTrue(commit()) }
    @Test fun pauseDuringResolutionInvalidatesResult() { assertFalse(commit(play = 5, desired = false)) }
    @Test fun repeatedPauseStillBlocksResult() { assertFalse(commit(desired = false)) }
    @Test fun sameIndexInDifferentQueueIsNotSameRequest() { assertFalse(commit(queue = 8)) }
    @Test fun changedTrackMustNotFallBackToOldTrack() { assertFalse(commit(id = "b")) }
    @Test fun focusSuppressionMustNotBeRepaired() { assertFalse(commit(suppressed = true)) }
    @Test fun readyWithoutAudioIsNotSufficientEvidenceOfFailure() {
        assertFalse(PlaybackRecovery.needsRepair(3, 3, 1, true, false, false, false))
    }
    @Test fun suppressedIdleMustNotBeRestarted() {
        assertFalse(PlaybackRecovery.needsRepair(1, 3, 1, true, false, true, true))
    }
    @Test fun idleErrorWithPlaybackIntentMayBeRepaired() {
        assertTrue(PlaybackRecovery.needsRepair(1, 3, 1, true, false, false, true))
    }
}
