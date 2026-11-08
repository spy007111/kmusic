package com.kmusic.apkhd.playback

import android.app.Application
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaItem
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
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock

/** Public-attribute injection does not prove that a particular vendor application's track is silent. */
@RunWith(RobolectricTestRunner::class)
@Config(application = MoyinApp::class, sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
@UnstableApi
class PddResidualPlaybackTest {
    private lateinit var service: MoyinPlaybackService
    private lateinit var player: ExoPlayer
    private lateinit var policy: AudioInterruptionPolicy
    private val route = OutputRoute(12, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "A2DP", isBluetooth = true)
    private val media = AudioAttributes.USAGE_MEDIA to AudioAttributes.CONTENT_TYPE_MUSIC
    private val ui = AudioAttributes.USAGE_ASSISTANCE_SONIFICATION to AudioAttributes.CONTENT_TYPE_SONIFICATION

    @Before fun setup() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).declareComponentUnbindable(android.content.ComponentName(app, MoyinPlaybackService::class.java))
        PlaybackSessionExit.openSession()
        AudioRuntimeState.serviceStopped()
        AudioRuntimeState.serviceStarted(false)
        service = Robolectric.buildService(MoyinPlaybackService::class.java).get()
        player = ExoPlayer.Builder(app).build()
        player.setMediaItems(listOf(MediaItem.Builder().setUri("file:///residual-test.flac").setMediaId("current").build()), 0, 42_000L)
        // No prepare or service onCreate; source I/O, settings and platform identity are absent.
        player.play()
        AudioRuntimeState.recordPlayIntent()
        AudioRuntimeState.reportOutput(null, route)
        setField("player", player)
        setField("lastConfirmedRoute", route)
        setField("audioInterruptionResume", true)
        policy = field("interruptionPolicy") as AudioInterruptionPolicy
    }

    @After fun cleanup() {
        if (::service.isInitialized) {
            (field("routeHandler") as Handler).removeCallbacksAndMessages(null)
            (field("serviceScope") as CoroutineScope).cancel()
            (field("externalMonitor") as? ExternalPlaybackMonitor)?.close()
        }
        if (::player.isInitialized) player.release()
        PlaybackSessionExit.openSession()
        AudioRuntimeState.serviceStopped()
    }

    @Test fun mediaThenUiResidualSequenceResumesOnlyAfterMusicFalseAndRouteSettles() {
        interrupt()
        val platform = FakePlatform(evidence(listOf(media, ui), true))
        val monitor = ExternalPlaybackMonitor(platform) { feed(it) }
        setField("externalMonitor", monitor)
        monitor.start()
        advance(800)
        monitor.refresh()
        platform.evidence = evidence(listOf(ui), true)
        monitor.refresh()
        evaluate()
        advance(800)
        evaluate()
        assertFalse(player.playWhenReady)

        platform.evidence = evidence(listOf(ui), false)
        monitor.refresh()
        evaluate()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(799))
        assertFalse(player.playWhenReady)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))

        assertTrue(player.playWhenReady)
        assertFalse(policy.pending)
        assertEquals("current", player.currentMediaItem?.mediaId)
        assertEquals(42_000L, player.currentPosition)
        assertTrue(platform.reads >= 4)
    }

    @Test fun residualUiCannotOverrideMediaNavigationVoiceGameOrUnknownTracks() {
        interrupt()
        advance(800)
        feed(evidence(listOf(media), true))
        val blockers = listOf(
            media,
            AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE to AudioAttributes.CONTENT_TYPE_SPEECH,
            AudioAttributes.USAGE_VOICE_COMMUNICATION to AudioAttributes.CONTENT_TYPE_SPEECH,
            AudioAttributes.USAGE_GAME to AudioAttributes.CONTENT_TYPE_MUSIC,
            AudioAttributes.USAGE_UNKNOWN to AudioAttributes.CONTENT_TYPE_UNKNOWN,
            AudioAttributes.USAGE_ASSISTANCE_SONIFICATION to AudioAttributes.CONTENT_TYPE_UNKNOWN
        )
        blockers.forEach { blocking ->
            feed(evidence(listOf(ui, blocking), false))
            advance(1_000)
            evaluate()
            assertFalse("public usage ${blocking.first} must veto resume", player.playWhenReady)
            assertTrue(policy.pending)
        }
    }

    @Test fun originalRawActiveOneWithoutClassificationAndUiOnlyWithoutPriorMediaStayPaused() {
        interrupt()
        advance(799)
        feed(evidence(listOf(media), true))
        advance(1)
        feed(evidence(listOf(ui), false))
        evaluate()
        advance(1_600)
        evaluate()
        assertFalse("pre-settle activity must not be attributed to others", player.playWhenReady)
        assertFalse(policy.awaitingQuietCheck)

        feed(evidence(listOf(media), true))
        // The supplied raw log has no usage/content fields: active=1, music=false is not silence proof.
        feed(ExternalPlaybackEvidence(1, false))
        evaluate()
        advance(1_000)
        evaluate()
        assertFalse(player.playWhenReady)
        assertTrue(policy.pending)
        feed(ExternalPlaybackEvidence(2, false, categories = ExternalPlaybackCategoryPolicy.summarize(listOf(ui))))
        advance(1_000)
        evaluate()
        assertFalse("inconsistent metadata must not ignore an unclassified track", player.playWhenReady)
    }

    private fun evidence(attributes: List<Pair<Int, Int>>, music: Boolean) = ExternalPlaybackEvidence(
        attributes.size, music, categories = ExternalPlaybackCategoryPolicy.summarize(attributes)
    )
    private fun interrupt() {
        val before = call("callPlaybackSnapshot") as CallPlaybackSnapshot
        player.pause()
        val epoch = AudioRuntimeState.recordPauseIntent(PauseCause.FOCUS)
        policy.focusLost(before, epoch, SystemClock.elapsedRealtime(), true)
        call("publishInterruptionState")
    }
    private fun feed(evidence: ExternalPlaybackEvidence) = call("handleExternalPlayback", evidence)
    private fun evaluate() = call("evaluateInterruptionResume")
    private fun advance(ms: Long) {
        (field("routeHandler") as Handler).removeCallbacks(field("interruptionCheck") as Runnable)
        setField("interruptionCheckScheduled", false)
        ShadowSystemClock.advanceBy(ms, TimeUnit.MILLISECONDS)
    }
    private fun field(name: String): Any? = MoyinPlaybackService::class.java.getDeclaredField(name).let { it.isAccessible = true; it.get(service) }
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
        override fun observe(changed: () -> Unit) = Unit
        override fun snapshot(): ExternalPlaybackEvidence { reads++; return evidence }
        override fun close() = Unit
    }
}
