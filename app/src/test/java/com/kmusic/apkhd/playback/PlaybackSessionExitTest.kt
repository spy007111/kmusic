package com.kmusic.apkhd.playback

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Stop callbacks/latches are real; notification and service destruction still need runtime acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class PlaybackSessionExitTest {
    private lateinit var app: Application
    private val owner = Any()

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        PlaybackSessionExit.openSession()
        AudioRuntimeState.serviceStarted(false)
    }

    @After fun cleanup() {
        PlaybackSessionExit.unbind(owner)
        PlaybackSessionExit.openSession()
        AudioRuntimeState.serviceStopped()
        shadowOf(Looper.getMainLooper()).idle()
    }
    private val bluetooth = OutputRoute(7, 8, "Bluetooth", isBluetooth = true)

    @Test fun boundStopCallbackRunsMarksExitAndCancelsDesiredPlaying() {
        var stopped = 0
        PlaybackSessionExit.bind(owner) { stopped++ }
        AudioRuntimeState.recordPlayIntent()
        val playingEpoch = AudioRuntimeState.snapshot.playbackEpoch
        PlaybackSessionExit.requestStop(app)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, stopped)
        assertTrue(PlaybackSessionExit.isExitRequested)
        assertFalse(AudioRuntimeState.snapshot.desiredPlaying)
        assertEquals(PauseCause.SERVICE_STOPPED, AudioRuntimeState.snapshot.pauseCause)
        assertTrue(AudioRuntimeState.snapshot.playbackEpoch > playingEpoch)
        PlaybackSessionExit.openSession()
        assertFalse(PlaybackSessionExit.isExitRequested)
        assertFalse(AudioRuntimeState.snapshot.desiredPlaying)
    }

    @Test fun unboundServiceStillLatchesExitAndInvalidatesIntentWithoutCallback() {
        var stopped = 0
        PlaybackSessionExit.bind(owner) { stopped++ }
        PlaybackSessionExit.unbind(owner)
        AudioRuntimeState.recordPlayIntent()
        PlaybackSessionExit.requestStop(app)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, stopped)
        assertTrue(PlaybackSessionExit.isExitRequested)
        assertFalse(AudioRuntimeState.snapshot.desiredPlaying)
    }

    @Test fun unrelatedOwnerCannotUnbindCurrentStopCallback() {
        var stopped = 0
        PlaybackSessionExit.bind(owner) { stopped++ }
        PlaybackSessionExit.unbind(Any())
        PlaybackSessionExit.requestStop(app)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, stopped)
    }

    @Test fun mobileBackgroundDecisionDoesNotRequestStopOrSetExitLatch() {
        var stopped = 0
        PlaybackSessionExit.bind(owner) { stopped++ }
        AudioRuntimeState.recordPlayIntent()
        val epoch = AudioRuntimeState.snapshot.playbackEpoch
        if (PlaybackBackgroundPolicy.resolve(false, false, false, false, userLeaving = true))
            PlaybackSessionExit.requestStop(app)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, stopped)
        assertFalse(PlaybackSessionExit.isExitRequested)
        assertTrue(AudioRuntimeState.snapshot.desiredPlaying)
        assertEquals(epoch, AudioRuntimeState.snapshot.playbackEpoch)
    }

    @Test fun confirmedTvDoubleBackClearsPlayIntentAndInvalidatesLateWork() {
        val back = TvExitConfirmation()
        val intent = PlaybackIntentPolicy()
        intent.play()
        val capturedEpoch = intent.intent.playbackEpoch
        assertEquals(TvExitConfirmation.Action.SHOW_HINT, back.back(true, false, true, 100))
        assertTrue(intent.intent.desiredPlaying)
        assertEquals(TvExitConfirmation.Action.EXIT, back.back(true, false, true, 101))
        if (PlaybackBackgroundPolicy.resolve(true, true, false, true)) intent.pause(PauseCause.SERVICE_STOPPED)
        assertFalse(intent.intent.desiredPlaying)
        assertEquals(PauseCause.SERVICE_STOPPED, intent.intent.pauseCause)
        assertTrue(intent.intent.playbackEpoch > capturedEpoch)
    }

    @Test fun tvHomeCancelsPendingBluetoothResumeInsteadOfRestartingWhileHidden() {
        val intent = PlaybackIntentPolicy()
        intent.play()
        intent.disconnected(bluetooth, 100, true)
        assertTrue(intent.canResume(bluetooth, 101, true))
        if (PlaybackBackgroundPolicy.resolve(true, false, false, false, userLeaving = true))
            intent.pause(PauseCause.SERVICE_STOPPED)
        assertFalse(intent.canResume(bluetooth, 102, true))
        assertFalse(intent.intent.desiredPlaying)
    }

    @Test fun permissionDialogPauseAndConfigurationChangeDoNotInvalidatePlayingSession() {
        val intent = PlaybackIntentPolicy()
        intent.play()
        val epoch = intent.intent.playbackEpoch
        val decisions = listOf(
            PlaybackBackgroundPolicy.resolve(true, true, false, false),
            PlaybackBackgroundPolicy.resolve(true, false, true, false),
            PlaybackBackgroundPolicy.resolve(true, false, false, false, true, 100, 101)
        )
        decisions.forEach { stop -> if (stop) intent.pause(PauseCause.SERVICE_STOPPED) }
        assertTrue(intent.intent.desiredPlaying)
        assertEquals(epoch, intent.intent.playbackEpoch)
    }

    @Test fun permissionExceptionExpiresAndCannotKeepHiddenTvSessionAliveForever() {
        val intent = PlaybackIntentPolicy()
        intent.play()
        assertFalse(PlaybackBackgroundPolicy.resolve(true, false, false, false, true, 100, 101))
        val delay = PlaybackBackgroundPolicy.permissionRecheckDelayMs(true, 100, 101)
        if (PlaybackBackgroundPolicy.resolve(true, false, false, false, true, 100, 101 + delay))
            intent.pause(PauseCause.SERVICE_STOPPED)
        assertFalse(intent.intent.desiredPlaying)
    }

    @Test fun mobileBackgroundKeepsIntentAndDoesNotInvalidateAsyncPlaybackEpoch() {
        val intent = PlaybackIntentPolicy()
        intent.play()
        val epoch = intent.intent.playbackEpoch
        if (PlaybackBackgroundPolicy.resolve(false, false, false, false, userLeaving = true))
            intent.pause(PauseCause.SERVICE_STOPPED)
        assertTrue(intent.intent.desiredPlaying)
        assertEquals(epoch, intent.intent.playbackEpoch)
    }

    @Test fun consumedInternalBackNeverConfirmsExitOrClearsIntent() {
        val back = TvExitConfirmation()
        val intent = PlaybackIntentPolicy()
        intent.play()
        back.back(true, false, true, 100)
        assertEquals(TvExitConfirmation.Action.CLOSE_LAYER, back.back(true, true, true, 101))
        assertEquals(TvExitConfirmation.Action.SHOW_HINT, back.back(true, false, true, 102))
        assertTrue(intent.intent.desiredPlaying)
    }
}
