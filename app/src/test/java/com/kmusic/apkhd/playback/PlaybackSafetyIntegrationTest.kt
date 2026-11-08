package com.kmusic.apkhd.playback

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import com.kmusic.apkhd.MoyinApp
import com.kmusic.apkhd.data.PlaybackSnapshotStore
import com.kmusic.apkhd.model.PlaybackSnapshot
import com.kmusic.apkhd.model.Track
import com.kmusic.apkhd.ui.MoyinViewModel
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

@RunWith(RobolectricTestRunner::class)
@Config(application = MoyinApp::class, sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
@UnstableApi
class PlaybackSafetyIntegrationTest {
    private lateinit var app: Application
    private lateinit var vm: MoyinViewModel
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private lateinit var controller: MediaController
    private lateinit var snapshots: PlaybackSnapshotStore
    private val store = ViewModelStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        AudioRuntimeState.serviceStopped()
        snapshots = PlaybackSnapshotStore(app)
        runBlocking { snapshots.clearAll() }
        shadowOf(app).declareComponentUnbindable(
            android.content.ComponentName(app, MoyinPlaybackService::class.java)
        )
        vm = MoyinViewModel(app)
        store.put("playback-safety", vm)
        (field(vm, "controllerFuture") as? java.util.concurrent.Future<*>)?.cancel(false)
        setField(vm, "controllerFuture", null)
        // A local session keeps transport real, but never opens a file or a network stream.
        player = ExoPlayer.Builder(app).build()
        session = MediaSession.Builder(app, object : ForwardingPlayer(player) {
            override fun prepare() = Unit
            override fun play() {
                if (!AudioRuntimeState.snapshot.desiredPlaying) AudioRuntimeState.recordPlayIntent()
                player.play()
            }
            override fun pause() {
                AudioRuntimeState.recordPauseIntent()
                player.pause()
            }
        }).build()
        val future = MediaController.Builder(app, session.token).buildAsync()
        pumpUntil("local MediaController connection") { future.isDone }
        controller = future.get()
        setField(vm, "controller", controller)
        pump()
    }

    @After
    fun tearDown() {
        scope.cancel()
        store.clear()
        if (::controller.isInitialized) controller.release()
        if (::session.isInitialized) session.release()
        if (::player.isInitialized) player.release()
        AudioRuntimeState.serviceStopped()
        if (::snapshots.isInitialized) runBlocking { snapshots.clearAll() }
        pump()
    }

    @Test
    fun coldRestore_latePreferencesCannotOverwriteNewUserQueue() {
        val saved = track("saved")
        val selected = track("selected")
        saveSnapshot(saved)
        val gate = holdRestorePreferences()
        val restore = scope.launch { callSuspend("restoreLastSession", controller) }
        pumpUntil("restore reached preferences suspension") { gate.entered.isCompleted }

        suppressLyrics(selected)
        vm.play(selected)
        gate.release.complete(Unit)
        pumpUntil("restore completed") { restore.isCompleted }

        assertEquals(listOf(selected.id), vm.queue.map { it.id })
        assertEquals(selected.id, vm.playbackState.track?.id)
        assertEquals(-1L, field(vm, "restoredPositionMs"))
    }

    @Test
    fun coldRestore_latePreferencesCannotCommitAfterExternalPause() {
        saveSnapshot(track("saved"))
        val gate = holdRestorePreferences()
        val restore = scope.launch { callSuspend("restoreLastSession", controller) }
        pumpUntil("restore reached preferences suspension") { gate.entered.isCompleted }

        // An already-paused transport still receives an explicit external pause intent.
        session.player.pause()
        gate.release.complete(Unit)
        pumpUntil("paused restore completed") { restore.isCompleted }

        assertTrue(vm.queue.isEmpty())
        assertEquals(0, controller.mediaItemCount)
        assertEquals(-1L, field(vm, "restoredPositionMs"))
        assertFalse(AudioRuntimeState.snapshot.desiredPlaying)
    }

    @Test
    fun restoredPlayback_externalPauseCancelsPendingResolution() {
        val t = track("restore")
        val resolution = startPendingRestore(t)
        val job = field(vm, "playJob") as Job
        assertTrue(job.isActive)

        session.player.pause()
        pumpUntil("pending play cancelled by external intent") { job.isCancelled }
        resolution.complete(t)
        pump()

        assertEquals(0, controller.mediaItemCount)
        assertFalse(vm.playbackState.isPlaying)
        assertFalse(vm.playbackState.buffering)
        assertFalse(field(vm, "awaitingNetwork") as Boolean)
    }

    @Test
    fun restoredPlayback_sameMediaIdInReplacementQueueRejectsOldResolution() {
        val t = track("restore")
        val resolution = startPendingRestore(t)
        val job = field(vm, "playJob") as Job
        val replacement = t.copy(album = "replacement queue")
        setQueue(listOf(replacement))
        resolution.complete(t)
        pumpUntil("stale restore resolution completed") { job.isCompleted }

        assertEquals(listOf(replacement), vm.queue)
        assertEquals(0, controller.mediaItemCount)
        assertEquals(0L, field(vm, "pendingSeekMs"))
        assertTrue(job.isCancelled)
        assertFalse(vm.playbackState.buffering)
    }

    @Test
    fun restoredPlayback_queueReplacementCancelsFocusWaitWithoutRuntimeChange() {
        val t = track("restore")
        val resolution = startPendingRestore(t)
        val job = field(vm, "playJob") as Job
        AudioRuntimeState.reportSuppression(Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS)
        resolution.complete(t)
        pump()
        assertTrue(job.isActive)
        val runtime = AudioRuntimeState.snapshot

        val replacement = t.copy(album = "replacement queue")
        setQueue(listOf(replacement))
        pumpUntil("queue replacement cancels suppressed restore") { job.isCancelled }

        assertEquals(runtime.playbackEpoch, AudioRuntimeState.snapshot.playbackEpoch)
        assertEquals(runtime.desiredPlaying, AudioRuntimeState.snapshot.desiredPlaying)
        assertEquals(runtime.suppressionReason, AudioRuntimeState.snapshot.suppressionReason)
        assertEquals(listOf(replacement), vm.queue)
        assertEquals(42_000L, field(vm, "restoredPositionMs"))
        assertFalse(vm.playbackState.buffering)
        assertEquals(0, controller.mediaItemCount)

        AudioRuntimeState.reportSuppression(Player.PLAYBACK_SUPPRESSION_REASON_NONE)
        pump()
        assertEquals(0, controller.mediaItemCount)
        assertFalse(vm.playbackState.buffering)
    }

    @Test
    fun restoredPlayback_focusSuppressionDefersCommitAndPreservesPosition() {
        val t = track("restore")
        val resolution = startPendingRestore(t)
        val job = field(vm, "playJob") as Job
        AudioRuntimeState.reportSuppression(Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS)
        resolution.complete(t)
        pump()
        assertTrue(job.isActive)
        assertEquals(0, controller.mediaItemCount)

        AudioRuntimeState.reportSuppression(Player.PLAYBACK_SUPPRESSION_REASON_NONE)
        pumpUntil("focus return commits restored playback") { job.isCompleted }
        pumpUntil("restored media item reached session") { session.player.mediaItemCount == 1 }

        assertEquals(t.id, controller.currentMediaItem?.mediaId)
        assertEquals(42_000L, vm.playbackState.positionMs)
        assertEquals(42_000L, field(vm, "pendingSeekMs"))
        assertTrue(vm.playbackState.isPlaying)
    }

    @Test
    fun restoredPlayback_pauseWhileSuppressedCannotResumeOnFocusReturn() {
        val t = track("restore")
        val resolution = startPendingRestore(t)
        val job = field(vm, "playJob") as Job
        AudioRuntimeState.reportSuppression(Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS)
        resolution.complete(t)
        pump()
        assertTrue(job.isActive)

        session.player.pause()
        pumpUntil("suppressed restore cancelled") { job.isCancelled }
        AudioRuntimeState.reportSuppression(Player.PLAYBACK_SUPPRESSION_REASON_NONE)
        pump()

        assertEquals(0, controller.mediaItemCount)
        assertFalse(vm.playbackState.isPlaying)
        assertFalse(AudioRuntimeState.snapshot.desiredPlaying)
    }

    @Test
    fun restoredPlayback_seekWhileResolvingMustNotRevertToCapturedPosition() {
        val t = track("restore")
        val resolution = startPendingRestore(t)
        val job = field(vm, "playJob") as Job
        vm.seekTo(0.75f)
        assertEquals(90_000L, vm.playbackState.positionMs)

        resolution.complete(t)
        pumpUntil("restore after buffering seek completed") { job.isCompleted }

        assertEquals("new seek wins over the old resume position", 90_000L, vm.playbackState.positionMs)
        assertEquals(90_000L, field(vm, "pendingSeekMs"))
    }

    @Test
    fun restoredPlayback_addToQueueRestartsPendingSelectionAndKeepsLatestSeek() {
        val t = track("restore")
        val marker = track("queued")
        suppressLyrics(marker)
        val resolution = startPendingRestore(t)
        val oldJob = field(vm, "playJob") as Job
        vm.seekTo(0.75f)
        val oldEpoch = AudioRuntimeState.snapshot.playbackEpoch

        vm.addToQueue(marker)
        val newJob = field(vm, "playJob") as Job
        assertTrue(oldJob.isCancelled)
        assertTrue(newJob !== oldJob)
        assertTrue(newJob.isActive)
        assertTrue(AudioRuntimeState.snapshot.playbackEpoch > oldEpoch)
        assertEquals(90_000L, field(vm, "restoredPositionMs"))

        resolution.complete(t)
        pumpUntil("replacement restore committed") { newJob.isCompleted }
        pumpUntil("queue marker reached local timeline") { controller.mediaItemCount == 2 }

        assertEquals(listOf(t, marker), vm.queue)
        assertEquals(0, vm.queueIndex)
        assertEquals(t.id, controller.currentMediaItem?.mediaId)
        assertEquals(listOf(t.id, marker.id), (0 until controller.mediaItemCount).map {
            controller.getMediaItemAt(it).mediaId
        })
        assertEquals(90_000L, vm.playbackState.positionMs)
        assertEquals(90_000L, field(vm, "pendingSeekMs"))
        assertTrue(vm.playbackState.isPlaying)
    }

    @Test
    fun restoredPlayback_addToQueueDuringFocusWaitRestartsWithoutPrematureCommit() {
        val t = track("restore")
        val marker = track("queued")
        suppressLyrics(marker)
        val resolution = startPendingRestore(t)
        val oldJob = field(vm, "playJob") as Job
        val suppressed = Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
        AudioRuntimeState.reportSuppression(suppressed)
        resolution.complete(t)
        pump()
        assertTrue(oldJob.isActive)

        vm.addToQueue(marker)
        val newJob = field(vm, "playJob") as Job
        pump()
        assertTrue(oldJob.isCancelled)
        assertTrue(newJob !== oldJob)
        assertTrue(newJob.isActive)
        assertEquals(suppressed, AudioRuntimeState.snapshot.suppressionReason)
        assertEquals(42_000L, field(vm, "restoredPositionMs"))
        assertEquals(0, controller.mediaItemCount)

        AudioRuntimeState.reportSuppression(Player.PLAYBACK_SUPPRESSION_REASON_NONE)
        pumpUntil("restarted restore committed after focus return") { newJob.isCompleted }
        pumpUntil("focus-return queue marker reached timeline") { controller.mediaItemCount == 2 }

        assertEquals(t.id, controller.currentMediaItem?.mediaId)
        assertEquals(listOf(t, marker), vm.queue)
        assertEquals(42_000L, vm.playbackState.positionMs)
        assertEquals(42_000L, field(vm, "pendingSeekMs"))
        assertTrue(vm.playbackState.isPlaying)
    }

    @Test fun unsupportedAudioStopsSingleItemAndDisplaysLongWarning() {
        val t = track("unsupported")
        setQueue(listOf(t)); setProperty("setQueueIndex", 0)
        player.setMediaItem(androidx.media3.common.MediaItem.Builder().setMediaId(t.id).setUri(t.playUrl).build())
        AudioRuntimeState.recordPlayIntent()
        player.play()
        pump()
        call("handlePlaybackError", androidx.media3.common.PlaybackException("unsupported", null, 4004))
        pump()
        assertFalse(player.playWhenReady)
        assertFalse(AudioRuntimeState.snapshot.desiredPlaying)
        assertFalse(vm.playbackState.isPlaying)
        assertTrue(vm.userMessage!!.long)
        assertTrue(vm.userMessage!!.text.contains("已停止"))
    }

    @Test fun unsupportedErrorDoesNotRetryOnlineSourceOrWaitForNetwork() {
        val t = track("online-unsupported").copy(source = "kuwo")
        setQueue(listOf(t)); setProperty("setQueueIndex", 0)
        player.setMediaItem(androidx.media3.common.MediaItem.Builder().setMediaId(t.id).setUri(t.playUrl).build())
        AudioRuntimeState.recordPlayIntent()
        call("handlePlaybackError", androidx.media3.common.PlaybackException("unsupported", null, 4001))
        pump()
        assertTrue((field(vm, "streamRetried") as Set<*>).isEmpty())
        assertFalse(field(vm, "awaitingNetwork") as Boolean)
        assertTrue(vm.userMessage!!.text.contains("已停止"))
    }

    @Test fun unsupportedAfterManualPauseDoesNotStartAnotherItem() {
        val one = track("paused-unsupported"); val two = track("next")
        setQueue(listOf(one, two)); setProperty("setQueueIndex", 0)
        player.setMediaItems(listOf(one, two).map { androidx.media3.common.MediaItem.Builder().setMediaId(it.id).setUri(it.playUrl).build() })
        AudioRuntimeState.recordPauseIntent()
        call("handlePlaybackError", androidx.media3.common.PlaybackException("unsupported", null, 4005))
        pump()
        assertEquals(0, vm.queueIndex)
        assertFalse(AudioRuntimeState.snapshot.desiredPlaying)
        assertFalse(player.playWhenReady)
    }

    @Test fun unsupportedSkipsToNextLogicalItemEvenBeforeTimelineWindowIsFilled() {
        val one = track("bad"); val two = track("next-good")
        suppressLyrics(one); suppressLyrics(two)
        setQueue(listOf(one, two)); setProperty("setQueueIndex", 0)
        player.setMediaItem(androidx.media3.common.MediaItem.Builder().setMediaId(one.id).setUri(one.playUrl).build())
        pump()
        val gate = CompletableDeferred<Track?>()
        @Suppress("UNCHECKED_CAST")
        val inFlight = field(vm, "inFlight") as ConcurrentHashMap<String, CompletableDeferred<Track?>>
        inFlight[two.id] = gate
        AudioRuntimeState.recordPlayIntent()
        call("handlePlaybackError", androidx.media3.common.PlaybackException("unsupported", null, 4004))
        pump()
        assertEquals(1, vm.queueIndex)
        assertTrue(vm.userMessage!!.text.contains("已跳过"))
        assertTrue(vm.userMessage!!.long)
        assertTrue(AudioRuntimeState.snapshot.desiredPlaying)
    }

    private fun startPendingRestore(t: Track): CompletableDeferred<Track?> {
        suppressLyrics(t)
        setQueue(listOf(t))
        setProperty("setQueueIndex", 0)
        setProperty("setPlaybackState", vm.playbackState.copy(
            track = t, positionMs = 42_000L, durationMs = t.durationMs
        ))
        setField(vm, "restoredPositionMs", 42_000L)
        // resolveOnce uses this same deferred when an existing request is in flight.
        val result = CompletableDeferred<Track?>()
        @Suppress("UNCHECKED_CAST")
        val inFlight = field(vm, "inFlight") as ConcurrentHashMap<String, CompletableDeferred<Track?>>
        inFlight[t.id] = result
        call("startRestoredPlayback", true)
        pump()
        assertTrue(vm.playbackState.buffering)
        return result
    }

    private data class RestoreGate(
        val entered: CompletableDeferred<Unit>,
        val release: CompletableDeferred<Unit>
    )

    private fun holdRestorePreferences(): RestoreGate {
        val gate = RestoreGate(CompletableDeferred(), CompletableDeferred())
        // Replace only this store instance's flow: no application settings keys are written.
        setField(field(vm, "settings")!!, "autoResumePlayback", flow {
            gate.entered.complete(Unit)
            gate.release.await()
            emit(false)
        })
        return gate
    }

    private fun saveSnapshot(t: Track) {
        suppressLyrics(t)
        runBlocking {
            snapshots.saveQueue(PlaybackSnapshot(tracks = listOf(t), currentId = t.id, index = 0))
            snapshots.savePosition(42_000L)
        }
    }

    private fun suppressLyrics(t: Track) {
        @Suppress("UNCHECKED_CAST")
        val cache = field(vm, "lyricCache") as MutableMap<String, Any>
        val type = MoyinViewModel::class.java.declaredClasses.single { it.simpleName == "CachedLyric" }
        val constructor = type.declaredConstructors.single { it.parameterTypes.size == 2 }
        constructor.isAccessible = true
        cache[t.id] = constructor.newInstance(emptyList<Any>(), "test")
    }

    private fun track(id: String) = Track(
        id = "nd:test-$id", name = id, artist = "test", album = "test", durationMs = 120_000L,
        source = "navidrome", sourceId = "test-$id", playUrl = "file:///playback-safety/$id.flac"
    )

    private fun setQueue(tracks: List<Track>) = setProperty("setQueue", tracks)

    private fun setProperty(name: String, value: Any) {
        val method = MoyinViewModel::class.java.declaredMethods.single { it.name == name }
        method.isAccessible = true
        method.invoke(vm, value)
    }

    private fun call(name: String, vararg args: Any?) {
        val method = MoyinViewModel::class.java.declaredMethods.single {
            it.name == name && it.parameterCount == args.size
        }
        method.isAccessible = true
        try {
            method.invoke(vm, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private suspend fun callSuspend(name: String, vararg args: Any?): Any? =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            val method = MoyinViewModel::class.java.declaredMethods.single {
                it.name == name && it.parameterCount == args.size + 1
            }
            method.isAccessible = true
            try {
                method.invoke(vm, *args, continuation)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }

    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).let {
        it.isAccessible = true
        it.get(target)
    }

    private fun setField(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).let {
            it.isAccessible = true
            it.set(target, value)
        }
    }

    private fun pump() = shadowOf(Looper.getMainLooper()).idle()

    private fun pumpUntil(label: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            pump()
            Thread.sleep(5)
        }
        pump()
        assertTrue("timed out: $label", condition())
    }
}
