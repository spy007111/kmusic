package com.kmusic.apkhd.playback

import android.media.AudioAttributes
import org.junit.Assert.*
import org.junit.Test

class ExternalPlaybackCategoryPolicyTest {
    private val route = OutputRoute(12, 8, "A2DP", isBluetooth = true)
    private val before = CallPlaybackSnapshot("song", 42_000, 10, true, route)
    private val current = before.copy(playbackEpoch = 11, desiredPlaying = false)
    private val ui = ExternalPlaybackCategories(uiSonification = 1)
    private fun policy() = AudioInterruptionPolicy().also {
        it.focusLost(before, 11, 0, true)
        it.observe(ExternalPlaybackEvidence(1, true, categories = ExternalPlaybackCategories(media = 1)), false, 800)
    }

    @Test fun explicitUiSonificationCanRemainAfterConfirmedMediaStops() {
        val policy = policy()
        val evidence = ExternalPlaybackEvidence(1, false, categories = ui)
        assertEquals(0, evidence.blockingCount)
        assertTrue(evidence.allInactive)
        policy.observe(evidence, false, 2_000)
        assertFalse(policy.ready(current, 0, false, 2_000, true))
        assertTrue(policy.ready(current, 0, false, 2_800, true))
    }

    @Test fun mediaGameVoiceNavigationAndUnknownTracksStillBlockWhenMusicFlagIsFalse() {
        listOf(ExternalPlaybackCategories(media = 1), ExternalPlaybackCategories(game = 1),
            ExternalPlaybackCategories(voice = 1), ExternalPlaybackCategories(navigation = 1),
            ExternalPlaybackCategories(otherOrUnknown = 1)).forEach { categories ->
            val policy = policy()
            val evidence = ExternalPlaybackEvidence(1, false, categories = categories)
            assertFalse(evidence.allInactive)
            policy.observe(evidence, false, 2_000)
            assertFalse(policy.ready(current, 0, false, 3_000, true))
        }
    }

    @Test fun unclassifiedOldEvidenceAndInconsistentCountsRemainBlocking() {
        assertEquals(1, ExternalPlaybackEvidence(1, false).blockingCount)
        assertFalse(ExternalPlaybackEvidence(1, false).allInactive)
        assertEquals(2, ExternalPlaybackEvidence(2, false, categories = ui).blockingCount)
    }

    @Test fun uiSonificationDoesNotOverrideMusicActiveVeto() {
        val policy = policy()
        val evidence = ExternalPlaybackEvidence(1, true, categories = ui)
        assertFalse(evidence.allInactive)
        policy.observe(evidence, false, 2_000)
        assertFalse(policy.ready(current, 0, false, 3_000, true))
        assertTrue(policy.requiresSettleCheck(3_000))
    }

    @Test fun uiOnlyActivityIsNotProofThatAnExternalMediaInterruptionEnded() {
        val policy = AudioInterruptionPolicy()
        policy.focusLost(before, 11, 0, true)
        policy.observe(ExternalPlaybackEvidence(1, false, categories = ui), false, 800)
        assertFalse(policy.ready(current, 0, false, 2_000, true))
        assertFalse(policy.awaitingQuietCheck)
    }

    @Test fun publicUsageAndContentMetadataAreSummarizedWithoutIdentity() {
        val counts = ExternalPlaybackCategoryPolicy.summarize(listOf(
            AudioAttributes.USAGE_MEDIA to AudioAttributes.CONTENT_TYPE_MOVIE,
            AudioAttributes.USAGE_GAME to AudioAttributes.CONTENT_TYPE_MUSIC,
            AudioAttributes.USAGE_VOICE_COMMUNICATION to AudioAttributes.CONTENT_TYPE_SPEECH,
            AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE to AudioAttributes.CONTENT_TYPE_SPEECH,
            AudioAttributes.USAGE_ASSISTANCE_SONIFICATION to AudioAttributes.CONTENT_TYPE_SONIFICATION,
            AudioAttributes.USAGE_UNKNOWN to AudioAttributes.CONTENT_TYPE_UNKNOWN))
        assertEquals(ExternalPlaybackCategories(1, 1, 1, 1, 1, 1), counts)
        assertEquals(5, counts.blocking)
    }

    @Test fun alarmNotificationAssistantAccessibilityAndAmbiguousUiContentRemainUnknownVetoes() {
        val attributes = listOf(AudioAttributes.USAGE_ALARM, AudioAttributes.USAGE_NOTIFICATION,
            AudioAttributes.USAGE_NOTIFICATION_RINGTONE, AudioAttributes.USAGE_ASSISTANT,
            AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).map { it to AudioAttributes.CONTENT_TYPE_SONIFICATION } +
            (AudioAttributes.USAGE_ASSISTANCE_SONIFICATION to AudioAttributes.CONTENT_TYPE_MUSIC)
        val counts = ExternalPlaybackCategoryPolicy.summarize(attributes)
        assertEquals(0, counts.uiSonification)
        assertEquals(6, counts.otherOrUnknown)
    }
}
