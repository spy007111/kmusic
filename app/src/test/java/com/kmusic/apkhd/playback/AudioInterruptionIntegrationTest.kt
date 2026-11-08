package com.kmusic.apkhd.playback

import android.app.Application
import android.media.AudioDeviceInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.kmusic.apkhd.MoyinApp
import java.lang.reflect.InvocationTargetException
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(application = MoyinApp::class, sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
@UnstableApi
class AudioInterruptionIntegrationTest {
    private lateinit var app: Application
    private lateinit var service: MoyinPlaybackService
    private lateinit var player: ExoPlayer
    private lateinit var policy: AudioInterruptionPolicy
    private val route = OutputRoute(12, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "A2DP", isBluetooth = true)
    private val active = ExternalPlaybackEvidence(1, true)
    private val quiet = ExternalPlaybackEvidence(0, false)

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).declareComponentUnbindable(android.content.ComponentName(app, MoyinPlaybackService::class.java))
        PlaybackSessionExit.openSession()
        AudioRuntimeState.serviceStopped()
        AudioRuntimeState.serviceStarted(false)
        service = Robolectric.buildService(MoyinPlaybackService::class.java).get()
        player = ExoPlayer.Builder(app).build()
        player.setMediaItems(listOf(item("current")), 0, 42_000L)
        // No prepare and no onCreate: transport is real, but source I/O and settings are absent.
        player.play()
        AudioRuntimeState.recordPlayIntent()
        AudioRuntimeState.reportOutput(null, route)
        setField("player", player)
        setField("lastConfirmedRoute", route)
        setField("audioInterruptionResume", true)
        policy = field("interruptionPolicy") as AudioInterruptionPolicy
    }

    @After
    fun tearDown() {
        if (::service.isInitialized) {
            PlaybackSessionExit.unbind(service)
            (field("routeHandler") as Handler).removeCallbacksAndMessages(null)
            (field("serviceScope") as CoroutineScope).cancel()
            (field("externalMonitor") as? ExternalPlaybackMonitor)?.close()
        }
        if (::player.isInitialized && (!::service.isInitialized || field("resourcesReleased") != true)) player.release()
        PlaybackSessionExit.openSession()
        AudioRuntimeState.serviceStopped()
    }

    @Test
    fun confirmedExternalPlaybackStops_thenStableOriginalRouteResumesCurrentPosition() {
        permanentFocusLoss()
        advance(800)
        feed(active)
        advance(100)
        feed(quiet)
        evaluate()
        advance(799)
        evaluate()
        assertFalse(player.playWhenReady)
        advance(1)
        evaluate()

        assertTrue(player.playWhenReady)
        assertTrue(AudioRuntimeState.snapshot.desiredPlaying)
        assertEquals("current", player.currentMediaItem?.mediaId)
        assertEquals(42_000L, player.currentPosition)
        assertFalse(policy.pending)
    }

    @Test
    fun externalPlaybackStillActive_neverFightsForFocus() {
        permanentFocusLoss()
        repeat(5) {
            advance(800)
            feed(active)
            evaluate()
            assertFalse(player.playWhenReady)
        }
        assertTrue(policy.pending)
        assertFalse(AudioRuntimeState.snapshot.desiredPlaying)
    }

    @Test
    fun ownPlaybackActivityCannotBeAttributedToExternalApp() {
        permanentFocusLoss()
        player.play()
        advance(800)
        feed(active)
        assertFalse(policy.pending)
        player.pause()
        feed(quiet)
        advance(1_600)
        evaluate()
        assertFalse(player.playWhenReady)
    }

    @Test
    fun explicitPauseEvenAlreadyPausedCancelsExternalRecovery() {
        permanentFocusLoss()
        advance(800)
        feed(active)
        val pause: () -> Unit = { player.pause() }
        call("externalCommand", false, pause)
        val epoch = AudioRuntimeState.snapshot.playbackEpoch
        call("externalCommand", false, pause)
        assertEquals(epoch + 1, AudioRuntimeState.snapshot.playbackEpoch)
        assertFalse(policy.pending)
        feed(quiet)
        advance(1_600)
        evaluate()
        assertFalse(player.playWhenReady)
    }

    @Test
    fun changedMediaCannotReceiveOriginalInterruptionResume() {
        prepareQuietCandidate()
        player.setMediaItems(listOf(item("replacement")), 0, 0L)
        advance(800)
        evaluate()
        assertFalse(player.playWhenReady)
        assertFalse(policy.pending)
        assertEquals("replacement", player.currentMediaItem?.mediaId)
    }

    @Test
    fun unknownActualRouteCannotUseRememberedRouteForResume() {
        prepareQuietCandidate()
        AudioRuntimeState.reportOutput(null, null)
        advance(800)
        evaluate()
        assertFalse(player.playWhenReady)
        assertTrue(policy.pending)
        AudioRuntimeState.reportOutput(null, route)
        evaluate()
        advance(800)
        evaluate()
        assertTrue(player.playWhenReady)
    }

    @Test
    fun repeatedQuietEventsAfterResumeCannotCreatePlayLoop() {
        prepareQuietCandidate()
        advance(800)
        evaluate()
        assertTrue(player.playWhenReady)
        val epoch = AudioRuntimeState.snapshot.playbackEpoch
        // A later pause is not a focus-loss candidate, even if callbacks keep arriving.
        player.pause()
        repeat(5) { feed(quiet); advance(800); evaluate() }
        assertFalse(player.playWhenReady)
        assertFalse(policy.pending)
        assertEquals(epoch, AudioRuntimeState.snapshot.playbackEpoch)
    }

    @Test
    fun duckAndTransientSuppressionUseNaturalPlayerIntentWithoutManualRestart() {
        val epoch = AudioRuntimeState.snapshot.playbackEpoch
        AudioRuntimeState.reportSuppression(Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS)
        feed(active)
        advance(800)
        feed(quiet)
        evaluate()
        assertTrue(player.playWhenReady)
        assertFalse(policy.pending)
        AudioRuntimeState.reportSuppression(Player.PLAYBACK_SUPPRESSION_REASON_NONE)
        evaluate()
        assertTrue(player.playWhenReady)
        assertEquals(epoch, AudioRuntimeState.snapshot.playbackEpoch)
    }

    @Test
    fun musicActiveAloneOrUnreadableObservationCannotAuthorizeResume() {
        permanentFocusLoss()
        advance(800)
        feed(ExternalPlaybackEvidence(0, true))
        feed(quiet)
        advance(1_600)
        evaluate()
        assertFalse(player.playWhenReady)
        feed(active)
        feed(ExternalPlaybackEvidence(0, false, false))
        advance(1_600)
        evaluate()
        assertFalse(player.playWhenReady)
    }

    @Test
    fun phoneCallTakesPriorityAndCancelsGenericCandidate() {
        permanentFocusLoss()
        advance(800)
        feed(active)
        call("handleCallState", CellularCallState.RINGING)
        assertFalse(policy.pending)
        feed(quiet)
        advance(1_600)
        evaluate()
        assertFalse(player.playWhenReady)
    }

    @Test
    fun monitorTimerRefreshesFreshEvidenceAndResumesOnlyAfterQuietSettles() {
        permanentFocusLoss()
        val platform = FakePlatform(active)
        val monitor = ExternalPlaybackMonitor(platform) { feed(it) }
        setField("externalMonitor", monitor)
        monitor.start()
        advance(800)
        monitor.refresh()
        platform.evidence = quiet
        monitor.refresh()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_600))

        assertTrue(player.playWhenReady)
        assertFalse(policy.pending)
        assertTrue(platform.reads >= 3)
    }

    @Test
    fun tvExitReleasesMonitorAndLateEvidenceOrCommandsCannotReviveSession() {
        prepareQuietCandidate()
        val platform = FakePlatform(quiet)
        val monitor = ExternalPlaybackMonitor(platform) { feed(it) }
        setField("externalMonitor", monitor)
        monitor.start()
        PlaybackSessionExit.bind(service, service::endPlaybackSession)
        PlaybackSessionExit.requestStop(app)
        val epoch = AudioRuntimeState.snapshot.playbackEpoch
        PlaybackSessionExit.requestStop(app)

        assertTrue(PlaybackSessionExit.isExitRequested)
        assertTrue(field("endingSession") as Boolean)
        assertTrue(field("resourcesReleased") as Boolean)
        assertNull(field("player"))
        assertNull(field("externalMonitor"))
        assertEquals(1, platform.closes)
        assertFalse(policy.pending)
        assertFalse(AudioRuntimeState.snapshot.serviceAlive)
        assertFalse(AudioRuntimeState.snapshot.desiredPlaying)
        assertTrue(AudioRuntimeState.snapshot.playbackEpoch >= epoch)
        var commands = 0
        val play: () -> Unit = { commands++; player.play() }
        call("externalCommand", true, play)
        feed(active)
        evaluate()
        call("handleCallState", CellularCallState.RINGING)
        assertEquals(0, commands)
        assertFalse((field("callPolicy") as CallResumePolicy).pending)
        assertFalse(AudioRuntimeState.snapshot.desiredPlaying)
    }

    private fun prepareQuietCandidate() {
        permanentFocusLoss()
        advance(800)
        feed(active)
        feed(quiet)
        evaluate()
    }

    private fun permanentFocusLoss() {
        // Exact permanent-focus listener input order; not a real vendor application interruption.
        val before = call("callPlaybackSnapshot") as CallPlaybackSnapshot
        player.pause()
        val epoch = AudioRuntimeState.recordPauseIntent(PauseCause.FOCUS)
        policy.focusLost(before, epoch, SystemClock.elapsedRealtime(), true)
        call("publishInterruptionState")
        assertTrue(policy.pending)
    }

    private fun feed(evidence: ExternalPlaybackEvidence) = call("handleExternalPlayback", evidence)
    private fun evaluate() = call("evaluateInterruptionResume")
    private fun advance(ms: Long) {
        (field("routeHandler") as Handler).removeCallbacks(field("interruptionCheck") as Runnable)
        setField("interruptionCheckScheduled", false)
        ShadowSystemClock.advanceBy(ms, TimeUnit.MILLISECONDS)
    }
    private fun item(id: String) = MediaItem.Builder().setUri("file:///audio-interruption/$id.flac").setMediaId(id).build()
    private fun field(name: String): Any? = MoyinPlaybackService::class.java.getDeclaredField(name).let {
        it.isAccessible = true
        it.get(service)
    }
    private fun setField(name: String, value: Any?) {
        MoyinPlaybackService::class.java.getDeclaredField(name).let { it.isAccessible = true; it.set(service, value) }
    }
    private fun call(name: String, vararg args: Any?): Any? {
        val method = MoyinPlaybackService::class.java.declaredMethods.single { it.name == name && it.parameterCount == args.size }
        method.isAccessible = true
        return try { method.invoke(service, *args) } catch (e: InvocationTargetException) { throw e.targetException }
    }
    private class FakePlatform(var evidence: ExternalPlaybackEvidence) : ExternalPlaybackPlatform {
        var reads = 0
        var closes = 0
        override fun observe(changed: () -> Unit) = Unit
        override fun snapshot(): ExternalPlaybackEvidence { reads++; return evidence }
        override fun close() { closes++ }
    }
}
