package com.kmusic.apkhd.playback

import android.app.Application
import android.media.AudioDeviceInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.kmusic.apkhd.MoyinApp
import java.time.Duration
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

@RunWith(RobolectricTestRunner::class)
@Config(application = MoyinApp::class, sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
@UnstableApi
class AudioInterruptionSettlingTest {
    private lateinit var service: MoyinPlaybackService
    private lateinit var player: ExoPlayer
    private lateinit var monitor: ExternalPlaybackMonitor
    private lateinit var policy: AudioInterruptionPolicy
    private val platform = object : ExternalPlaybackPlatform {
        var evidence = ExternalPlaybackEvidence(1, true)
        var reads = 0
        override fun observe(changed: () -> Unit) = Unit
        override fun snapshot(): ExternalPlaybackEvidence { reads++; return evidence }
        override fun close() = Unit
    }

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).declareComponentUnbindable(android.content.ComponentName(app, MoyinPlaybackService::class.java))
        PlaybackSessionExit.openSession()
        AudioRuntimeState.serviceStopped()
        AudioRuntimeState.serviceStarted(false)
        service = Robolectric.buildService(MoyinPlaybackService::class.java).get()
        player = ExoPlayer.Builder(app).build()
        player.setMediaItems(listOf(MediaItem.Builder().setUri("file:///settle-test.flac").setMediaId("settle").build()), 0, 42_000)
        player.play()
        AudioRuntimeState.recordPlayIntent()
        val route = OutputRoute(12, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "A2DP", isBluetooth = true)
        AudioRuntimeState.reportOutput(null, route)
        setField("player", player)
        setField("lastConfirmedRoute", route)
        monitor = ExternalPlaybackMonitor(platform) { invoke("handleExternalPlayback", it) }
        setField("externalMonitor", monitor)
        val before = CallPlaybackSnapshot("settle", 42_000, AudioRuntimeState.snapshot.playbackEpoch, true, route)
        player.pause()
        val epoch = AudioRuntimeState.recordPauseIntent(PauseCause.FOCUS)
        policy = field("interruptionPolicy") as AudioInterruptionPolicy
        policy.focusLost(before, epoch, SystemClock.elapsedRealtime(), true)
        invoke("scheduleInterruptionCheck")
        monitor.start()
    }

    @After fun cleanup() {
        monitor.close()
        (field("routeHandler") as Handler).removeCallbacksAndMessages(null)
        (field("serviceScope") as CoroutineScope).cancel()
        player.release()
        AudioRuntimeState.serviceStopped()
        PlaybackSessionExit.openSession()
    }

    @Test fun earlyActiveCallbackPreservesTheOnlyOwnStopSettlingCheck() {
        idle(100)
        monitor.refresh()
        assertTrue(field("interruptionCheckScheduled") as Boolean)
        assertFalse(player.playWhenReady)
        idle(700)
        assertTrue(policy.status.contains("仍活动"))
        assertFalse(field("interruptionCheckScheduled") as Boolean)
        platform.evidence = ExternalPlaybackEvidence(0, false)
        monitor.refresh()
        idle(1_600)
        assertTrue(player.playWhenReady)
        assertFalse(policy.pending)
    }

    @Test fun emptyActiveListWithStaleMusicFlagIsRecheckedWithoutAnotherPlatformCallback() {
        idle(800)
        assertTrue(policy.status.contains("仍活动"))
        platform.evidence = ExternalPlaybackEvidence(0, true)
        monitor.refresh()
        assertTrue(field("interruptionCheckScheduled") as Boolean)
        idle(800)
        assertFalse(player.playWhenReady)
        assertTrue(policy.status.contains("尚未清静"))
        // The mixer flag changes without a playback-configuration callback.
        platform.evidence = ExternalPlaybackEvidence(0, false)
        idle(2_400)
        assertTrue(player.playWhenReady)
        assertFalse(policy.pending)
        assertFalse(field("interruptionCheckScheduled") as Boolean)

        val bounded = AudioInterruptionPolicy()
        bounded.focusLost(CallPlaybackSnapshot("other", 0, 1, true, null), 2, 0, true)
        bounded.observe(ExternalPlaybackEvidence(1, true), false, 800)
        bounded.observe(ExternalPlaybackEvidence(0, true), false, 1_000)
        assertTrue(bounded.requiresSettleCheck(5_999))
        assertFalse(bounded.requiresSettleCheck(6_000))
        bounded.observe(ExternalPlaybackEvidence(0, true), false, 6_100)
        assertFalse(bounded.requiresSettleCheck(6_100))
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun field(name: String): Any? = MoyinPlaybackService::class.java.getDeclaredField(name).let {
        it.isAccessible = true; it.get(service)
    }
    private fun setField(name: String, value: Any?) = MoyinPlaybackService::class.java.getDeclaredField(name).let {
        it.isAccessible = true; it.set(service, value)
    }
    private fun invoke(name: String, vararg args: Any?) = MoyinPlaybackService::class.java.declaredMethods.single {
        it.name == name && it.parameterCount == args.size
    }.let { it.isAccessible = true; it.invoke(service, *args) }
}
