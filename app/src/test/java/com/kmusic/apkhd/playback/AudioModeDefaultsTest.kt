package com.kmusic.apkhd.playback

import org.junit.Assert.*
import org.junit.Test

class AudioModeDefaultsTest {
    @Test fun firstInstallDefaultsToDailyWithEffectsOffAndConservativeTechnicalValues() {
        val defaults = AudioModeDefaults.resolve()
        assertEquals("processed", defaults.mode)
        assertEquals(AudioPipelineMode.PROCESSED_PCM, defaults.pipeline)
        assertFalse(defaults.usbHighFidelity)
        assertFalse(defaults.pureAudioMode)
        assertFalse(defaults.audioOffload)
        assertFalse(defaults.eqEnabled)
        assertEquals(0, defaults.eqPreset)
        assertEquals(0, defaults.fxBass)
        assertEquals(0, defaults.fxLoudness)
        assertTrue(defaults.callAutoResume)
        assertFalse(defaults.bluetoothAutoResume)
        assertEquals(44100, defaults.dsdOutputRate)
        assertTrue(defaults.dsdBypassEq)
        assertFalse(defaults.truePeakEnabled)
        assertEquals(-1f, defaults.truePeakCeilingDb, 0f)
    }

    @Test fun absentPreferencesAndAbsentFieldsHaveIdenticalDefaults() {
        assertEquals(AudioModeDefaults.resolve(), AudioModeDefaults.resolve(existing = AudioModePreferences()))
    }

    @Test fun falseNeverMeansMissingForOptOutAndLegacyFlags() {
        val saved = AudioModePreferences(usbHighFidelity = false, pureAudioMode = false, audioOffload = false,
            eqEnabled = false, callAutoResume = false, bluetoothAutoResume = false, dsdBypassEq = false,
            truePeakEnabled = false)
        val resolved = AudioModeDefaults.resolve(existing = saved)
        assertEquals("processed", resolved.mode)
        assertFalse(resolved.callAutoResume)
        assertFalse(resolved.dsdBypassEq)
        assertFalse(resolved.eqEnabled)
        assertFalse(resolved.truePeakEnabled)
    }

    @Test fun trueAndNonzeroHiddenPreferencesArePreserved() {
        val resolved = AudioModeDefaults.resolve(existing = AudioModePreferences(eqEnabled = true, eqPreset = 7,
            fxBass = 800, fxLoudness = 1000, callAutoResume = true, bluetoothAutoResume = true,
            dsdOutputRate = 88200, dsdBypassEq = false, truePeakEnabled = true, truePeakCeilingDb = -2.5f))
        assertTrue(resolved.eqEnabled)
        assertEquals(7, resolved.eqPreset)
        assertEquals(800, resolved.fxBass)
        assertEquals(1000, resolved.fxLoudness)
        assertTrue(resolved.callAutoResume)
        assertTrue(resolved.bluetoothAutoResume)
        assertEquals(88200, resolved.dsdOutputRate)
        assertFalse(resolved.dsdBypassEq)
        assertTrue(resolved.truePeakEnabled)
        assertEquals(-2.5f, resolved.truePeakCeilingDb, 0f)
    }

    @Test fun eachLegacyFlagMigratesWithoutOtherFlagsPresent() {
        assertEquals("usb", AudioModeDefaults.resolve(existing = AudioModePreferences(usbHighFidelity = true)).mode)
        assertEquals("pure", AudioModeDefaults.resolve(existing = AudioModePreferences(pureAudioMode = true)).mode)
        assertEquals("offload", AudioModeDefaults.resolve(existing = AudioModePreferences(audioOffload = true)).mode)
    }

    @Test fun legacyConflictsUseUsbThenPureThenOffloadAndMakeFlagsExclusive() {
        listOf(null, false, true).forEach { usb ->
            listOf(null, false, true).forEach { pure ->
                listOf(null, false, true).forEach { offload ->
                    val resolved = AudioModeDefaults.resolve(existing = AudioModePreferences(usb, pure, offload))
                    val expected = when {
                        usb == true -> "usb"
                        pure == true -> "pure"
                        offload == true -> "offload"
                        else -> "processed"
                    }
                    assertEquals(expected, resolved.mode)
                    assertEquals(expected == "usb", resolved.usbHighFidelity)
                    assertEquals(expected == "pure", resolved.pureAudioMode)
                    assertEquals(expected == "offload", resolved.audioOffload)
                }
            }
        }
    }

    @Test fun explicitModeWinsLegacyFlagsWithoutResettingHiddenEffects() {
        val existing = AudioModePreferences(true, true, true, eqEnabled = true, fxBass = 700,
            dsdOutputRate = 88200, truePeakEnabled = true, truePeakCeilingDb = -3f)
        listOf("processed", "offload", "pure", "usb").forEach { mode ->
            val resolved = AudioModeDefaults.resolve(mode, existing)
            assertEquals(mode, resolved.mode)
            assertEquals(mode == "usb", resolved.usbHighFidelity)
            assertEquals(mode == "pure", resolved.pureAudioMode)
            assertEquals(mode == "offload", resolved.audioOffload)
            assertTrue(resolved.eqEnabled)
            assertEquals(700, resolved.fxBass)
            assertEquals(88200, resolved.dsdOutputRate)
            assertTrue(resolved.truePeakEnabled)
            assertEquals(-3f, resolved.truePeakCeilingDb, 0f)
        }
    }

    @Test fun onlyAbsentFieldsAreFilledWhenOthersAreExplicit() {
        val resolved = AudioModeDefaults.resolve(existing = AudioModePreferences(callAutoResume = false,
            bluetoothAutoResume = true, dsdOutputRate = 0, truePeakCeilingDb = 0f))
        assertFalse(resolved.callAutoResume)
        assertTrue(resolved.bluetoothAutoResume)
        assertEquals(0, resolved.dsdOutputRate)
        assertEquals(0f, resolved.truePeakCeilingDb, 0f)
        assertFalse(resolved.eqEnabled)
        assertTrue(resolved.dsdBypassEq)
    }

    @Test fun unknownRequestedModeDoesNotDiscardLegacySelection() {
        assertEquals("pure", AudioModeDefaults.resolve("future-mode",
            AudioModePreferences(pureAudioMode = true)).mode)
        assertEquals("processed", AudioModeDefaults.resolve("future-mode").mode)
    }

    @Test fun usbChoiceIsConditionalButExistingUsbSelectionAlwaysVisible() {
        assertEquals(listOf("processed", "offload", "pure"), AudioModeDefaults.visibleModes(false, "processed"))
        assertEquals(listOf("processed", "offload", "pure", "usb"), AudioModeDefaults.visibleModes(true, "processed"))
        assertEquals(listOf("processed", "offload", "pure", "usb"), AudioModeDefaults.visibleModes(false, "usb"))
    }

    @Test fun alreadyResolvedPreferencesRemainStableAcrossStartupMigration() {
        val original = AudioModeDefaults.resolve("usb", AudioModePreferences(eqEnabled = true, eqPreset = 4,
            fxBass = 0, fxLoudness = 200, callAutoResume = false, bluetoothAutoResume = true,
            dsdOutputRate = 88200, dsdBypassEq = false, truePeakEnabled = false, truePeakCeilingDb = 0f))
        val stored = AudioModePreferences(original.usbHighFidelity, original.pureAudioMode, original.audioOffload,
            original.eqEnabled, original.eqPreset, original.fxBass, original.fxLoudness, original.callAutoResume,
            original.bluetoothAutoResume, original.dsdOutputRate, original.dsdBypassEq,
            original.truePeakEnabled, original.truePeakCeilingDb)
        assertEquals(original, AudioModeDefaults.resolve(existing = stored))
    }
}
