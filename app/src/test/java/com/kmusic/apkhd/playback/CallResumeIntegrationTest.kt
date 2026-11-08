package com.kmusic.apkhd.playback

import android.app.Application
import android.media.AudioDeviceInfo
import android.os.Handler
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.kmusic.apkhd.MoyinApp
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class CallResumeIntegrationTest {
    private lateinit var service: MoyinPlaybackService
    private lateinit var player: ExoPlayer
    private lateinit var policy: CallResumePolicy
    private val mediaRoute = OutputRoute(12, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "A2DP", isBluetooth = true)
    private val scoRoute = OutputRoute(13, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "SCO", isBluetooth = true)

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).declareComponentUnbindable(
            android.content.ComponentName(app, MoyinPlaybackService::class.java)
        )
        AudioRuntimeState.serviceStopped()
        AudioRuntimeState.serviceStarted(false)
        // Skip onCreate: no settings collectors, phone permission access, DSP or service binding.
        service = Robolectric.buildService(MoyinPlaybackService::class.java).get()
        player = ExoPlayer.Builder(app).build()
        player.setMediaItems(listOf(MediaItem.Builder()
            .setUri("file:///call-resume-test.flac").setMediaId("call-test").build()), 0, 42_000L)
        // Never prepare a source. IDLE transport still exposes play intent and position synchronously.
        player.play()
        AudioRuntimeState.recordPlayIntent()
        AudioRuntimeState.reportOutput(null, mediaRoute)
        setField("player", player)
        setField("lastConfirmedRoute", mediaRoute)
        setField("callAutoResume", true)
        policy = field("callPolicy") as CallResumePolicy
        callState(CellularCallState.IDLE)
    }

    @After
    fun tearDown() {
        if (::service.isInitialized) {
            (field("routeHandler") as Handler).removeCallbacksAndMessages(null)
            (field("serviceScope") as CoroutineScope).cancel()
        }
        if (::player.isInitialized) player.release()
        AudioRuntimeState.serviceStopped()
    }

    @Test
    fun phoneFirst_thenFocusLossResumesOnlyAfterCallIdleAndRouteSettles() {
        callState(CellularCallState.RINGING)
        assertFalse(player.playWhenReady)
        assertEquals(42_000L, policy.candidate!!.before.positionMs)
        focusLoss()
        assertEquals(AudioRuntimeState.snapshot.playbackEpoch, policy.candidate!!.expectedEpoch)
        callState(CellularCallState.OFFHOOK)
        evaluateAfter(1_000)
        assertFalse(player.playWhenReady)

        callState(CellularCallState.IDLE)
        call("evaluateCallResume")
        evaluateAfter(599)
        assertFalse(player.playWhenReady)
        evaluateAfter(1)

        assertTrue(player.playWhenReady)
        assertTrue(AudioRuntimeState.snapshot.desiredPlaying)
        assertEquals("call-test", player.currentMediaItem?.mediaId)
        assertEquals(42_000L, player.currentPosition)
        assertFalse(policy.pending)
    }

    @Test
    fun focusFirst_thenPhoneEvidenceCorrelatesOriginalPlayingSnapshot() {
        val original = snapshot()
        focusLoss()
        assertFalse(policy.pending)
        advance(100)
        callState(CellularCallState.RINGING)
        assertEquals(original, policy.candidate!!.before)
        assertEquals(AudioRuntimeState.snapshot.playbackEpoch, policy.candidate!!.expectedEpoch)
        finishAndSettle()

        assertTrue(player.playWhenReady)
        assertEquals(original.positionMs, player.currentPosition)
        assertFalse(policy.pending)
    }

    @Test
    fun nonCallFocusLossAndReturnNeverAuthorizeAutomaticPlay() {
        focusLoss()
        callState(CellularCallState.IDLE)
        evaluateAfter(1_000)
        evaluateAfter(1_000)

        assertFalse(player.playWhenReady)
        assertFalse(AudioRuntimeState.snapshot.desiredPlaying)
        assertFalse(policy.pending)
    }

    @Test
    fun repeatedExplicitPauseWhileCallPausedCancelsCandidateImmediately() {
        callState(CellularCallState.RINGING)
        assertTrue(policy.pending)
        val callEpoch = AudioRuntimeState.snapshot.playbackEpoch
        externalPause()
        val firstPauseEpoch = AudioRuntimeState.snapshot.playbackEpoch
        assertTrue(firstPauseEpoch > callEpoch)
        assertFalse(policy.pending)
        externalPause()
        assertEquals(firstPauseEpoch + 1, AudioRuntimeState.snapshot.playbackEpoch)
        focusLoss()
        finishAndSettle()

        assertFalse(player.playWhenReady)
        assertFalse(AudioRuntimeState.snapshot.desiredPlaying)
    }

    @Test
    fun directVmPauseBeforeLaterCallFocusLossCannotReadoptCandidateEpoch() {
        callState(CellularCallState.RINGING)
        AudioRuntimeState.recordPauseIntent()
        focusLoss()
        assertFalse(policy.pending)
        finishAndSettle()

        assertFalse(player.playWhenReady)
        assertFalse(AudioRuntimeState.snapshot.desiredPlaying)
    }

    @Test
    fun focusFirst_thenExplicitPausePreventsLatePhoneCorrelation() {
        focusLoss()
        externalPause()
        callState(CellularCallState.RINGING)
        assertFalse(policy.pending)
        finishAndSettle()

        assertFalse(player.playWhenReady)
        assertFalse(AudioRuntimeState.snapshot.desiredPlaying)
    }

    @Test
    fun scoDuringCallDoesNotInvalidateCandidateButCannotAuthorizeResume() {
        callState(CellularCallState.RINGING)
        val epoch = AudioRuntimeState.snapshot.playbackEpoch
        AudioRuntimeState.reportOutput(null, scoRoute)
        call("onRouteConfirmed", scoRoute)
        assertEquals(epoch, AudioRuntimeState.snapshot.playbackEpoch)
        assertEquals(mediaRoute, field("lastConfirmedRoute"))
        assertTrue(policy.pending)
        callState(CellularCallState.IDLE)
        evaluateAfter(1_000)
        assertFalse(player.playWhenReady)

        AudioRuntimeState.reportOutput(null, mediaRoute)
        call("onRouteConfirmed", mediaRoute)
        call("evaluateCallResume")
        evaluateAfter(600)

        assertTrue(player.playWhenReady)
        assertFalse(policy.pending)
    }

    @Test
    fun rememberedRouteWithoutActualOutputCannotAuthorizeResume() {
        callState(CellularCallState.RINGING)
        AudioRuntimeState.reportOutput(null, null)
        finishAndSettle()
        assertFalse(player.playWhenReady)
        assertTrue(policy.pending)

        AudioRuntimeState.reportOutput(null, mediaRoute)
        call("evaluateCallResume")
        evaluateAfter(600)
        assertTrue(player.playWhenReady)
    }

    @Test
    fun anotherA2dpDeviceCannotReplaceOriginalCallResumeRoute() {
        callState(CellularCallState.RINGING)
        val replacement = mediaRoute.copy(deviceId = 99)
        AudioRuntimeState.reportOutput(null, replacement)
        call("onRouteConfirmed", replacement)
        finishAndSettle()

        assertFalse(player.playWhenReady)
        assertTrue(policy.pending)
        evaluateAfter(8_001)
        assertFalse(policy.pending)
        assertFalse(player.playWhenReady)
    }

    @Test
    fun callEndingWhileFocusStillSuppressedCannotConsumeCandidate() {
        callState(CellularCallState.RINGING)
        callState(CellularCallState.IDLE)
        val current = snapshot()
        assertNull(policy.ready(current, 1, SystemClock.elapsedRealtime(), true))
        advance(600)
        assertNull(policy.ready(snapshot(), 1, SystemClock.elapsedRealtime(), true))
        assertTrue(policy.pending)
        assertFalse(player.playWhenReady)
        assertNotNull(policy.ready(snapshot(), 0, SystemClock.elapsedRealtime(), true))

        call("evaluateCallResume")
        assertTrue(player.playWhenReady)
        assertFalse(policy.pending)
    }

    private fun focusLoss() {
        // Feed the exact service-listener order without requesting real platform audio focus.
        val before = snapshot()
        val epoch = AudioRuntimeState.recordPauseIntent(if (policy.active) PauseCause.CALL else PauseCause.FOCUS)
        policy.focusLost(before, epoch, SystemClock.elapsedRealtime())
        player.pause()
        call("publishCallState")
    }

    private fun externalPause() {
        val pause: () -> Unit = { player.pause() }
        call("externalCommand", false, pause)
    }

    private fun snapshot() = call("callPlaybackSnapshot") as CallPlaybackSnapshot

    private fun callState(state: CellularCallState) = call("handleCallState", state)

    private fun finishAndSettle() {
        callState(CellularCallState.IDLE)
        call("evaluateCallResume")
        evaluateAfter(600)
    }

    private fun evaluateAfter(ms: Long) {
        advance(ms)
        call("evaluateCallResume")
    }

    private fun advance(ms: Long) {
        (field("routeHandler") as Handler).removeCallbacks(field("callResumeTick") as Runnable)
        ShadowSystemClock.advanceBy(ms, TimeUnit.MILLISECONDS)
    }

    private fun field(name: String): Any? = MoyinPlaybackService::class.java.getDeclaredField(name).let {
        it.isAccessible = true
        it.get(service)
    }

    private fun setField(name: String, value: Any?) {
        MoyinPlaybackService::class.java.getDeclaredField(name).let {
            it.isAccessible = true
            it.set(service, value)
        }
    }

    private fun call(name: String, vararg args: Any?): Any? {
        val method = MoyinPlaybackService::class.java.declaredMethods.single {
            it.name == name && it.parameterCount == args.size
        }
        method.isAccessible = true
        return try {
            method.invoke(service, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }
}
