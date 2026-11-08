package com.kmusic.apkhd.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper

/** No package, UID, session ID or hidden API is inspected. Ordinary clients receive active anonymous tracks. */
interface ExternalPlaybackPlatform : AutoCloseable {
    fun observe(changed: () -> Unit)
    fun snapshot(): ExternalPlaybackEvidence
}

class ExternalPlaybackMonitor(
    private val platform: ExternalPlaybackPlatform,
    private val onEvidence: (ExternalPlaybackEvidence) -> Unit
) : AutoCloseable {
    constructor(context: Context, onEvidence: (ExternalPlaybackEvidence) -> Unit) :
        this(AndroidExternalPlaybackPlatform(context), onEvidence)
    private var closed = false
    private var started = false

    fun start() {
        if (closed || started) return
        started = true
        runCatching { platform.observe { refresh() } }.onFailure {
            onEvidence(ExternalPlaybackEvidence(0, false, false))
        }
        refresh()
    }
    fun refresh() {
        if (closed) return
        onEvidence(runCatching { platform.snapshot() }.getOrElse { ExternalPlaybackEvidence(0, false, false) })
    }
    override fun close() {
        if (closed) return
        closed = true
        runCatching { platform.close() }
    }
}

/** Only the explicit UI-sonification usage/content pair is exempt; all unknown metadata vetoes play. */
object ExternalPlaybackCategoryPolicy {
    fun summarize(attributes: List<Pair<Int, Int>>): ExternalPlaybackCategories {
        var media = 0; var game = 0; var voice = 0; var navigation = 0; var ui = 0; var other = 0
        attributes.forEach { (usage, content) ->
            when (usage) {
                AudioAttributes.USAGE_MEDIA -> media++
                AudioAttributes.USAGE_GAME -> game++
                AudioAttributes.USAGE_VOICE_COMMUNICATION, AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING -> voice++
                AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE -> navigation++
                AudioAttributes.USAGE_ASSISTANCE_SONIFICATION -> if (content == AudioAttributes.CONTENT_TYPE_SONIFICATION) ui++ else other++
                else -> other++
            }
        }
        return ExternalPlaybackCategories(media, game, voice, navigation, ui, other)
    }
}

private class AndroidExternalPlaybackPlatform(context: Context) : ExternalPlaybackPlatform {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var callback: AudioManager.AudioPlaybackCallback? = null
    private var closed = false
    override fun observe(changed: () -> Unit) {
        val audio = manager ?: error("AudioManager unavailable")
        val listener = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                if (!closed) changed()
            }
        }
        callback = listener
        audio.registerAudioPlaybackCallback(listener, handler)
    }
    override fun snapshot(): ExternalPlaybackEvidence {
        val audio = manager ?: return ExternalPlaybackEvidence(0, false, false)
        // AOSP sanitizes non-privileged lists to active tracks only. Never call hidden isActive/identity getters.
        val active = audio.activePlaybackConfigurations
        val categories = ExternalPlaybackCategoryPolicy.summarize(active.map {
            val attributes = it.audioAttributes
            attributes.usage to attributes.contentType
        })
        return ExternalPlaybackEvidence(active.size, audio.isMusicActive, categories = categories)
    }
    override fun close() {
        closed = true
        callback?.let { manager?.unregisterAudioPlaybackCallback(it) }
        callback = null
        handler.removeCallbacksAndMessages(null)
    }
}
