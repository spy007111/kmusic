package com.kmusic.apkhd.playback

import android.app.Application
import android.os.Handler
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import com.kmusic.apkhd.MoyinApp
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
class AudioModeSwitchServiceTest {
    private lateinit var service: MoyinPlaybackService
    private lateinit var session: MediaSession
    private var opens = 0
    private val items = listOf("one", "two").map { MediaItem.Builder().setUri("file:///$it.flac").setMediaId(it).build() }
    private fun player() = field("player") as ExoPlayer

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).declareComponentUnbindable(android.content.ComponentName(app, MoyinPlaybackService::class.java))
        PlaybackSessionExit.openSession()
        AudioRuntimeState.serviceStopped()
        AudioRuntimeState.serviceStarted(false)
        service = Robolectric.buildService(MoyinPlaybackService::class.java).get()
        val p = ExoPlayer.Builder(app).build()
        p.setMediaItems(items, 1, 42_000)
        p.repeatMode = Player.REPEAT_MODE_ALL
        p.shuffleModeEnabled = true
        p.playbackParameters = PlaybackParameters(1.1f, 0.9f)
        p.volume = 0.7f
        setField("player", p)
        setField("lastMediaId", "two")
        setField("playbackDataFactory", DataSource.Factory {
            object : DataSource {
                override fun addTransferListener(transferListener: TransferListener) = Unit
                override fun open(dataSpec: DataSpec): Long { opens++; error("Unexpected source open") }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
                override fun getUri(): android.net.Uri? = null
                override fun close() = Unit
            }
        })
        invoke("attachPlayerListeners", p)
        session = MediaSession.Builder(app, invoke("wrapPlayer", p) as Player).build()
        setField("session", session)
    }

    @After fun cleanup() {
        (field("routeHandler") as Handler).removeCallbacksAndMessages(null)
        (field("serviceScope") as CoroutineScope).cancel()
        invoke("releasePlaybackResources")
        PlaybackSessionExit.openSession()
        AudioRuntimeState.serviceStopped()
    }

    @Test fun pausedSwitchPreservesQueuePositionAndSessionTokenWithoutLoading() {
        val old = player()
        val token = session.token
        service.applyOutputMode("pure")
        val replacement = player()
        assertNotSame(old, replacement)
        assertEquals(token, session.token)
        assertEquals(2, replacement.mediaItemCount)
        assertEquals("two", replacement.currentMediaItem?.mediaId)
        assertEquals(1, replacement.currentMediaItemIndex)
        assertEquals(42_000L, replacement.currentPosition)
        assertEquals(Player.REPEAT_MODE_ALL, replacement.repeatMode)
        assertTrue(replacement.shuffleModeEnabled)
        assertEquals(1.1f, replacement.playbackParameters.speed, 0f)
        assertEquals(0.9f, replacement.playbackParameters.pitch, 0f)
        assertEquals(0.7f, replacement.volume, 0f)
        assertFalse(replacement.playWhenReady)
        assertEquals(Player.STATE_IDLE, replacement.playbackState)
        assertEquals(0, opens)
        assertTrue(AudioRuntimeState.snapshot.pureAudioModeRequested)
        assertFalse(AudioRuntimeState.snapshot.modeSwitching)
    }

    @Test fun originallyPlayingUserIntentSurvivesControlledPauseOfOldPlayer() {
        AudioRuntimeState.recordPlayIntent()
        player().play()
        service.applyOutputMode("offload")
        assertTrue(player().playWhenReady)
        assertTrue(AudioRuntimeState.snapshot.desiredPlaying)
        assertTrue(AudioRuntimeState.snapshot.offloadRequested)
        assertEquals(42_000L, player().currentPosition)
        assertEquals(0, opens)
    }

    @Test fun sameModeEmissionDoesNotRebuildOrInvalidatePlaybackEpoch() {
        val old = player()
        val epoch = AudioRuntimeState.snapshot.playbackEpoch
        service.applyOutputMode("processed")
        assertSame(old, player())
        assertEquals(epoch, AudioRuntimeState.snapshot.playbackEpoch)
    }

    @Test fun repeatedPausedModeChangesDoNotAccumulatePlaybackOrChangeQueue() {
        listOf("pure", "usb", "offload", "processed").forEach { mode ->
            service.applyOutputMode(mode)
            assertFalse(player().playWhenReady)
            assertEquals("two", player().currentMediaItem?.mediaId)
            assertEquals(42_000L, player().currentPosition)
        }
        assertEquals(0, opens)
    }

    @Test fun oldControllerWrapperCannotPlayAReplacedPlayerOrChangeIntent() {
        val oldWrapper = session.player
        service.applyOutputMode("pure")
        val epoch = AudioRuntimeState.snapshot.playbackEpoch
        oldWrapper.play()
        oldWrapper.pause()
        assertFalse(player().playWhenReady)
        assertEquals(epoch, AudioRuntimeState.snapshot.playbackEpoch)
    }

    @Test fun emptyTimelineSwitchDoesNotPrepareOrStartPlayback() {
        player().clearMediaItems()
        service.applyOutputMode("pure")
        assertEquals(0, player().mediaItemCount)
        assertFalse(player().playWhenReady)
        assertEquals(Player.STATE_IDLE, player().playbackState)
        assertEquals(0, opens)
    }

    @Test fun endingSessionRejectsLateModeFlowRequests() {
        val old = player()
        setField("endingSession", true)
        service.applyOutputMode("usb")
        assertSame(old, player())
        assertFalse(AudioRuntimeState.snapshot.usbHighFidelityRequested)
    }

    @Test fun manuallyPausedCurrentMediaIsNotRevivedByModeChange() {
        AudioRuntimeState.recordPlayIntent()
        player().play()
        session.player.pause()
        service.applyOutputMode("pure")
        assertFalse(player().playWhenReady)
        assertFalse(AudioRuntimeState.snapshot.desiredPlaying)
        assertEquals(42_000L, player().currentPosition)
    }

    @Test fun unsupportedAtOrderTailStopsAndKeepsQueue() {
        player().shuffleModeEnabled = false
        player().repeatMode = Player.REPEAT_MODE_OFF
        AudioRuntimeState.recordPlayIntent()
        player().play()
        invoke("skipUnsupportedAudio", player())
        assertFalse(player().playWhenReady)
        assertFalse(AudioRuntimeState.snapshot.desiredPlaying)
        assertEquals(2, player().mediaItemCount)
        assertEquals("two", player().currentMediaItem?.mediaId)
        assertTrue(org.robolectric.shadows.ShadowToast.getTextOfLatestToast().contains("已停止"))
    }

    @Test fun unsupportedAfterManualPauseDoesNotAdvanceBackgroundPlayback() {
        player().seekTo(0, 0)
        AudioRuntimeState.recordPauseIntent()
        invoke("skipUnsupportedAudio", player())
        assertEquals("one", player().currentMediaItem?.mediaId)
        assertFalse(AudioRuntimeState.snapshot.desiredPlaying)
        assertTrue(org.robolectric.shadows.ShadowToast.getTextOfLatestToast().contains("已停止"))
    }

    private fun field(name: String): Any? = MoyinPlaybackService::class.java.getDeclaredField(name).let {
        it.isAccessible = true; it.get(service)
    }
    private fun setField(name: String, value: Any?) = MoyinPlaybackService::class.java.getDeclaredField(name).let {
        it.isAccessible = true; it.set(service, value)
    }
    private fun invoke(name: String, vararg args: Any?): Any? = MoyinPlaybackService::class.java.declaredMethods.single {
        it.name == name && it.parameterCount == args.size
    }.let { it.isAccessible = true; it.invoke(service, *args) }
}
