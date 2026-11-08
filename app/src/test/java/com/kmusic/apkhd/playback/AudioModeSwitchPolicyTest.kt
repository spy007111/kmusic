package com.kmusic.apkhd.playback

import org.junit.Assert.*
import org.junit.Test

class AudioModeSwitchPolicyTest {
    private val snapshot = AudioModeSwitchSnapshot(listOf("a", "b"), 1, 42_000, "b", 10,
        true, true, 0, 2, true, 1f, 1f, 0.7f, true)
    private fun resume(value: AudioModeSwitchSnapshot<String> = snapshot, epoch: Long = 11,
        id: String? = "b", desired: Boolean = true, exit: Boolean = false) =
        AudioModeSwitchPolicy.shouldResume(value, epoch, 11, id, desired, exit)

    @Test fun supportedModeNamesArePreserved() {
        listOf("processed", "offload", "pure", "usb").forEach { assertEquals(it, AudioModeSwitchPolicy.normalize(it)) }
    }
    @Test fun unknownModeFallsBackToProcessed() { assertEquals("processed", AudioModeSwitchPolicy.normalize("legacy")) }
    @Test fun uninterruptedUserPlayingCanResumeWithMatchingTransaction() { assertTrue(resume()) }
    @Test fun pausedTransportNeverStartsBecauseOfModeChange() { assertFalse(resume(snapshot.copy(playWhenReady = false))) }
    @Test fun explicitPauseIntentBlocksEvenOldTransportStillTrue() { assertFalse(resume(snapshot.copy(desiredPlaying = false))) }
    @Test fun temporaryFocusSuppressionDoesNotRequestFreshCompetingFocus() { assertFalse(resume(snapshot.copy(suppressionReason = 1))) }
    @Test fun emptyTimelineNeverStarts() { assertFalse(resume(snapshot.copy(items = emptyList()))) }
    @Test fun userPauseOrQueueEpochChangeInvalidatesLateCommit() { assertFalse(resume(epoch = 12)); assertFalse(resume(desired = false)) }
    @Test fun newMediaAndTvExitInvalidateLateCommit() { assertFalse(resume(id = "c")); assertFalse(resume(exit = true)) }
    @Test fun snapshotRetainsTransportAttributesWithoutChangingPausedState() {
        val paused = snapshot.copy(playWhenReady = false, desiredPlaying = false)
        assertEquals(listOf("a", "b"), paused.items)
        assertEquals(1, paused.index)
        assertEquals(42_000L, paused.positionMs)
        assertEquals(2, paused.repeatMode)
        assertTrue(paused.shuffleEnabled)
        assertEquals(0.7f, paused.volume, 0f)
        assertFalse(resume(paused))
    }
}
