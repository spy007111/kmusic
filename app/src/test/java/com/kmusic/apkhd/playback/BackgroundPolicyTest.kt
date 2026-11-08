package com.kmusic.apkhd.playback

import org.junit.Assert.*
import org.junit.Test

class BackgroundPolicyTest {
    @Test fun mobileCarAndTabletRetainBackgroundPlaybackForEveryLifecycleCombination() {
        listOf(false, true).forEach { visible ->
            listOf(false, true).forEach { finishing ->
                assertFalse(PlaybackBackgroundPolicy.resolve(false, visible, false, finishing, userLeaving = true))
            }
        }
    }

    @Test fun visibleTvPausedForDialogDoesNotStop() {
        assertFalse(PlaybackBackgroundPolicy.resolve(true, true, false, false))
    }

    @Test fun genuinelyBackgroundTvStopsAndConfigurationRecreationDoesNot() {
        assertTrue(PlaybackBackgroundPolicy.resolve(true, false, false, false))
        assertFalse(PlaybackBackgroundPolicy.resolve(true, false, true, false))
    }

    @Test fun explicitFinishAndHomeOverridePermissionGrace() {
        assertTrue(PlaybackBackgroundPolicy.resolve(true, true, false, true, true, 100, 101))
        assertTrue(PlaybackBackgroundPolicy.resolve(true, false, false, false, true, 100, 101, userLeaving = true))
    }

    @Test fun shortPermissionWindowDefersButExpiryMustStopStillInvisibleTv() {
        assertFalse(PlaybackBackgroundPolicy.resolve(true, false, false, false, true, 100, 101))
        assertFalse(PlaybackBackgroundPolicy.resolve(true, false, false, false, true, 100, 5099))
        assertTrue(PlaybackBackgroundPolicy.resolve(true, false, false, false, true, 100, 5100))
        assertEquals(4999L, PlaybackBackgroundPolicy.permissionRecheckDelayMs(true, 100, 101))
        assertEquals(1L, PlaybackBackgroundPolicy.permissionRecheckDelayMs(true, 100, 5099))
        assertEquals(0L, PlaybackBackgroundPolicy.permissionRecheckDelayMs(true, 100, 5100))
    }

    @Test fun permissionFlagAloneCannotCreatePermanentBackgroundException() {
        assertTrue(PlaybackBackgroundPolicy.resolve(true, false, false, false, returningPermission = true))
        assertTrue(PlaybackBackgroundPolicy.resolve(true, false, false, false, false, 100, 101))
        assertTrue(PlaybackBackgroundPolicy.resolve(true, false, false, false, true, -1, 0))
        assertTrue(PlaybackBackgroundPolicy.resolve(true, false, false, false, true, 100, 99))
    }

    @Test fun permissionCallbackCanEndGraceBeforeDeadlineAndResumeKeepsPlayback() {
        assertTrue(PlaybackBackgroundPolicy.resolve(true, false, false, false, false, 100, 200))
        assertFalse(PlaybackBackgroundPolicy.resolve(true, true, false, false, false, 100, 200))
    }

    @Test fun firstTvHomeBackAtZeroUptimeHintsInsteadOfExiting() {
        val policy = TvExitConfirmation()
        assertEquals(TvExitConfirmation.Action.SHOW_HINT, policy.back(true, false, true, 0))
        assertEquals(TvExitConfirmation.Action.EXIT, policy.back(true, false, true, 1))
    }

    @Test fun secondHomeBackMustBeInsideStrictTwoSecondWindowAndExitResetsArm() {
        val policy = TvExitConfirmation()
        assertEquals(TvExitConfirmation.Action.SHOW_HINT, policy.back(true, false, true, 100))
        assertEquals(TvExitConfirmation.Action.SHOW_HINT, policy.back(true, false, true, 2100))
        assertEquals(TvExitConfirmation.Action.EXIT, policy.back(true, false, true, 4099))
        assertEquals(TvExitConfirmation.Action.SHOW_HINT, policy.back(true, false, true, 4100))
    }

    @Test fun internalLayerAndNonHomeConsumeBackAndResetPreviousRootHint() {
        val policy = TvExitConfirmation()
        policy.back(true, false, true, 100)
        assertEquals(TvExitConfirmation.Action.CLOSE_LAYER, policy.back(true, true, true, 101))
        assertEquals(TvExitConfirmation.Action.SHOW_HINT, policy.back(true, false, true, 102))
        assertEquals(TvExitConfirmation.Action.GO_HOME, policy.back(true, false, false, 103))
        assertEquals(TvExitConfirmation.Action.SHOW_HINT, policy.back(true, false, true, 104))
    }

    @Test fun backgroundResetAndClockRegressionCannotReuseOldExitConfirmation() {
        val policy = TvExitConfirmation()
        policy.back(true, false, true, 100)
        policy.reset()
        assertEquals(TvExitConfirmation.Action.SHOW_HINT, policy.back(true, false, true, 101))
        assertEquals(TvExitConfirmation.Action.SHOW_HINT, policy.back(true, false, true, 99))
    }

    @Test fun mobileRootDelegatesWithoutIntroducingTvExitBehavior() {
        assertEquals(TvExitConfirmation.Action.DELEGATE, TvExitConfirmation().back(false, false, true, 100))
    }
}
