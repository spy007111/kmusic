package com.kmusic.apkhd.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AudioModeSettingsTest {
    private lateinit var settings: SettingsStore

    @Before fun reset() = runBlocking {
        settings = SettingsStore(ApplicationProvider.getApplicationContext())
        settings.setAudioOffload(false)
        settings.setPureAudioMode(false)
    }

    @After fun cleanup() = runBlocking {
        settings.setAudioOffload(false)
        settings.setPureAudioMode(false)
    }

    @Test fun defaultModesAreDisabled() = runBlocking {
        assertFalse(settings.audioOffload.first())
        assertFalse(settings.pureAudioMode.first())
    }

    @Test fun enablingPurePcmClearsOffloadInTheSameEdit() = runBlocking {
        settings.setAudioOffload(true)
        settings.setPureAudioMode(true)
        assertTrue(settings.pureAudioMode.first())
        assertFalse(settings.audioOffload.first())
    }

    @Test fun enablingOffloadClearsPurePcmInTheSameEdit() = runBlocking {
        settings.setPureAudioMode(true)
        settings.setAudioOffload(true)
        assertTrue(settings.audioOffload.first())
        assertFalse(settings.pureAudioMode.first())
    }

    @Test fun disablingPurePcmDoesNotEnableOffload() = runBlocking {
        settings.setPureAudioMode(true)
        settings.setPureAudioMode(false)
        assertFalse(settings.pureAudioMode.first())
        assertFalse(settings.audioOffload.first())
    }

    @Test fun disablingOffloadDoesNotCancelActivePurePcm() = runBlocking {
        settings.setPureAudioMode(true)
        settings.setAudioOffload(false)
        assertTrue(settings.pureAudioMode.first())
        assertFalse(settings.audioOffload.first())
    }

    @Test fun concurrentEnableRequestsStillPersistOnlyOneMode() = runBlocking {
        listOf(async { settings.setPureAudioMode(true) }, async { settings.setAudioOffload(true) }).awaitAll()
        val pure = settings.pureAudioMode.first()
        val offload = settings.audioOffload.first()
        assertTrue(pure || offload)
        assertFalse(pure && offload)
    }
}
