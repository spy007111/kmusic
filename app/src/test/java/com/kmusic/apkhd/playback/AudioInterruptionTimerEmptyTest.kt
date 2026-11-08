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
import org.junit.Assert.*
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
class AudioInterruptionTimerEmptyTest {
    @Test fun frequentQuietCallbacksCannotPostponeTheExistingRecoveryDeadline() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).declareComponentUnbindable(android.content.ComponentName(app, MoyinPlaybackService::class.java))
        PlaybackSessionExit.openSession()
        AudioRuntimeState.serviceStopped()
        AudioRuntimeState.serviceStarted(false)
        val service = Robolectric.buildService(MoyinPlaybackService::class.java).get()
        val player = ExoPlayer.Builder(app).build()
        fun field(name: String) = MoyinPlaybackService::class.java.getDeclaredField(name).also { it.isAccessible = true }
        fun invoke(name: String, vararg args: Any?) {
            MoyinPlaybackService::class.java.declaredMethods.single { it.name == name && it.parameterCount == args.size }
                .also { it.isAccessible = true }.invoke(service, *args)
        }
        val route = OutputRoute(12, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "A2DP", isBluetooth = true)
        val active = ExternalPlaybackEvidence(1, true)
        val quiet = ExternalPlaybackEvidence(0, false)
        val platform = object : ExternalPlaybackPlatform {
            var evidence = active
            override fun observe(changed: () -> Unit) = Unit
            override fun snapshot() = evidence
            override fun close() = Unit
        }
        val monitor = ExternalPlaybackMonitor(platform) { invoke("handleExternalPlayback", it) }
        try {
            player.setMediaItems(listOf(MediaItem.Builder().setUri("file:///timer-test.flac").setMediaId("timer").build()), 0, 42_000)
            player.play()
            AudioRuntimeState.recordPlayIntent()
            AudioRuntimeState.reportOutput(null, route)
            field("player").set(service, player)
            field("lastConfirmedRoute").set(service, route)
            field("externalMonitor").set(service, monitor)
            val before = CallPlaybackSnapshot("timer", 42_000, AudioRuntimeState.snapshot.playbackEpoch, true, route)
            player.pause()
            val epoch = AudioRuntimeState.recordPauseIntent(PauseCause.FOCUS)
            val policy = field("interruptionPolicy").get(service) as AudioInterruptionPolicy
            policy.focusLost(before, epoch, SystemClock.elapsedRealtime(), true)
            monitor.start()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
            monitor.refresh()
            platform.evidence = quiet
            monitor.refresh()
            assertTrue(field("interruptionCheckScheduled").getBoolean(service))
            repeat(15) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
                monitor.refresh()
                assertFalse(player.playWhenReady)
            }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            assertTrue(player.playWhenReady)
            assertFalse(policy.pending)
            assertFalse(field("interruptionCheckScheduled").getBoolean(service))
        } finally {
            monitor.close()
            (field("routeHandler").get(service) as Handler).removeCallbacksAndMessages(null)
            (field("serviceScope").get(service) as CoroutineScope).cancel()
            player.release()
            AudioRuntimeState.serviceStopped()
            PlaybackSessionExit.openSession()
        }
    }
}
