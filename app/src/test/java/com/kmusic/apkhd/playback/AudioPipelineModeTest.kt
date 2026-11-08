package com.kmusic.apkhd.playback

import org.junit.Assert.*
import org.junit.Test

class AudioPipelineModeTest {
    @Test fun defaultPipelineKeepsSoftwareDspAndIntegerOutput() {
        val mode = AudioPipelineMode.resolve(false, false)
        assertEquals(AudioPipelineMode.PROCESSED_PCM, mode)
        assertTrue(mode.softwareDsp)
        assertFalse(mode.floatOutput)
        assertFalse(mode.offload)
    }

    @Test fun offloadPreferenceDoesNotAttachSoftwareDsp() {
        val mode = AudioPipelineMode.resolve(true, false)
        assertEquals(AudioPipelineMode.OFFLOAD_PREFERRED, mode)
        assertFalse(mode.softwareDsp)
        assertFalse(mode.floatOutput)
        assertTrue(mode.offload)
    }

    @Test fun purePcmRequestsFloatWithoutDspOrOffload() {
        val mode = AudioPipelineMode.resolve(false, true)
        assertEquals(AudioPipelineMode.PURE_PCM, mode)
        assertTrue(mode.floatOutput)
        assertFalse(mode.softwareDsp)
        assertFalse(mode.offload)
    }

    @Test fun conflictingStoredPreferencesPreferPurePcm() {
        assertEquals(AudioPipelineMode.PURE_PCM, AudioPipelineMode.resolve(true, true))
    }

    @Test fun noModeCanAccidentallyCombineFloatWithSoftwareDspOrOffload() {
        AudioPipelineMode.entries.forEach { mode ->
            assertFalse(mode.floatOutput && mode.softwareDsp)
            assertFalse(mode.floatOutput && mode.offload)
        }
    }
}
