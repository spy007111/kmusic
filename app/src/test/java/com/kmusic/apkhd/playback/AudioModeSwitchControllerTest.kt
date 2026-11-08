package com.kmusic.apkhd.playback

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import com.kmusic.apkhd.MoyinApp
import java.lang.reflect.InvocationTargetException
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
class AudioModeSwitchControllerTest {
    private lateinit var service: MoyinPlaybackService
    private lateinit var session: MediaSession
    private lateinit var controller: MediaController
    private var disconnected = 0
    private var opens = 0
    private val items = listOf("one", "two").map {
        MediaItem.Builder().setUri("file:///mode-controller/$it.flac").setMediaId(it).build()
    }

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).declareComponentUnbindable(android.content.ComponentName(app, MoyinPlaybackService::class.java))
        PlaybackSessionExit.openSession()
        AudioRuntimeState.serviceStopped()
        AudioRuntimeState.serviceStarted(false)
        service = Robolectric.buildService(MoyinPlaybackService::class.java).get()
        val old = ExoPlayer.Builder(app).build()
        old.setMediaItems(items, 1, 42_000L)
        old.repeatMode = Player.REPEAT_MODE_ALL
        old.shuffleModeEnabled = true
        old.playbackParameters = PlaybackParameters(1.1f, 0.9f)
        old.volume = 0.7f
        setField("player", old)
        setField("lastMediaId", "two")
        // Explicit controller play may prepare this fake source; mode changes of an IDLE paused player must not.
        setField("playbackDataFactory", DataSource.Factory {
            object : DataSource {
                override fun addTransferListener(transferListener: TransferListener) = Unit
                override fun open(dataSpec: DataSpec): Long { opens++; error("Unexpected source I/O") }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
                override fun getUri(): android.net.Uri? = null
                override fun close() = Unit
            }
        })
        call("attachPlayerListeners", old)
        session = MediaSession.Builder(app, call("wrapPlayer", old) as Player).build()
        setField("session", session)
        val future = MediaController.Builder(app, session.token).setListener(object : MediaController.Listener {
            override fun onDisconnected(controller: MediaController) { disconnected++ }
        }).buildAsync()
        pumpUntil("local controller connection") { future.isDone }
        controller = future.get()
        pump()
        assertTrue(controller.isConnected)
    }

    @After fun cleanup() {
        if (::controller.isInitialized) controller.release()
        if (::service.isInitialized) {
            (field("routeHandler") as Handler).removeCallbacksAndMessages(null)
            (field("serviceScope") as CoroutineScope).cancel()
            call("releasePlaybackResources")
        }
        PlaybackSessionExit.openSession()
        AudioRuntimeState.serviceStopped()
    }

    @Test fun pausedModeSwitchKeepsConnectedControllerTokenAndFullPlaybackState() {
        val token = session.token
        val old = player()
        val epoch = AudioRuntimeState.snapshot.playbackEpoch
        service.applyOutputMode("pure")
        pumpUntil("controller receives replacement mode state") {
            controller.currentMediaItem?.mediaId == "two" && controller.currentPosition == 42_000L
        }
        assertNotSame(old, player())
        assertEquals(token, session.token)
        assertTrue(controller.isConnected)
        assertEquals(0, disconnected)
        assertEquals(2, controller.mediaItemCount)
        assertEquals(1, controller.currentMediaItemIndex)
        assertEquals(Player.REPEAT_MODE_ALL, controller.repeatMode)
        assertTrue(controller.shuffleModeEnabled)
        assertEquals(1.1f, controller.playbackParameters.speed, 0f)
        assertEquals(0.9f, controller.playbackParameters.pitch, 0f)
        assertEquals(0.7f, controller.volume, 0f)
        assertFalse(controller.playWhenReady)
        assertFalse(AudioRuntimeState.snapshot.desiredPlaying)
        assertTrue(AudioRuntimeState.snapshot.playbackEpoch > epoch)
        assertFalse(AudioRuntimeState.snapshot.modeSwitching)
        assertEquals(Player.STATE_IDLE, player().playbackState)
        assertEquals(0, opens)
    }

    @Test fun originalControllerCommandsReachReplacementButStaleWrapperCannotChangeEpoch() {
        val stale = session.player
        service.applyOutputMode("pure")
        pump()
        val epoch = AudioRuntimeState.snapshot.playbackEpoch
        stale.play()
        stale.pause()
        assertEquals(epoch, AudioRuntimeState.snapshot.playbackEpoch)
        assertFalse(player().playWhenReady)
        assertEquals(0, opens)

        controller.volume = 0.4f
        controller.seekTo(1, 51_000L)
        pumpUntil("old connected controller commands reach new player") {
            player().volume == 0.4f && player().currentPosition == 51_000L
        }
        controller.play()
        pumpUntil("controller play reaches replacement") { player().playWhenReady }
        assertTrue(AudioRuntimeState.snapshot.desiredPlaying)
        controller.pause()
        pumpUntil("controller pause reaches replacement") { !player().playWhenReady }
        assertFalse(AudioRuntimeState.snapshot.desiredPlaying)
        assertTrue(controller.isConnected)
        assertEquals(0, disconnected)
    }

    @Test fun repeatedModesKeepSameControllerAndDoNotReviveManuallyPausedMedia() {
        controller.play()
        pumpUntil("initial controller play") { player().playWhenReady }
        controller.pause()
        pumpUntil("explicit pause recorded") { !AudioRuntimeState.snapshot.desiredPlaying }
        val token = session.token
        listOf("pure", "offload", "usb", "processed").forEach { mode ->
            service.applyOutputMode(mode)
            pump()
            assertEquals(token, session.token)
            assertTrue(controller.isConnected)
            assertFalse(controller.playWhenReady)
            assertFalse(controller.isPlaying)
            assertFalse(AudioRuntimeState.snapshot.desiredPlaying)
            assertEquals("two", controller.currentMediaItem?.mediaId)
            assertEquals(42_000L, controller.currentPosition)
            val epochAfterSwitch = AudioRuntimeState.snapshot.playbackEpoch
            pump()
            assertEquals(epochAfterSwitch, AudioRuntimeState.snapshot.playbackEpoch)
        }
        assertEquals(0, disconnected)
    }

    private fun player() = field("player") as ExoPlayer
    private fun pump() = shadowOf(Looper.getMainLooper()).idle()
    private fun pumpUntil(label: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) { pump(); Thread.sleep(5) }
        pump()
        assertTrue("timed out: $label", condition())
    }
    private fun field(name: String): Any? = MoyinPlaybackService::class.java.getDeclaredField(name).let {
        it.isAccessible = true; it.get(service)
    }
    private fun setField(name: String, value: Any?) {
        MoyinPlaybackService::class.java.getDeclaredField(name).let { it.isAccessible = true; it.set(service, value) }
    }
    private fun call(name: String, vararg args: Any?): Any? {
        val method = MoyinPlaybackService::class.java.declaredMethods.single { it.name == name && it.parameterCount == args.size }
        method.isAccessible = true
        return try { method.invoke(service, *args) } catch (e: InvocationTargetException) { throw e.targetException }
    }
}
