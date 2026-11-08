package com.kmusic.apkhd.ui.screens

import com.kmusic.apkhd.playback.AudioModeDefaults
import org.junit.Assert.*
import org.junit.Test

class AudioSettingsPresentationTest {
    @Test fun ordinaryAdvancedSettingsContainOnlyPlaybackMode() {
        assertEquals(listOf("playback-mode"), AudioSettingsPresentation.advancedItems())
        listOf("call", "bluetooth", "dsd", "true-peak", "gain", "profile", "permission", "diagnostics").forEach {
            assertFalse(AudioSettingsPresentation.advancedItems().any { item -> item.contains(it) })
        }
    }

    @Test fun normalModeIsRecommendedAndUnknownStoredValueDoesNotInventMode() {
        assertEquals("日常播放（推荐）", AudioSettingsPresentation.currentMode("processed").label)
        assertEquals(AudioSettingsPresentation.currentMode("processed"), AudioSettingsPresentation.currentMode("unknown"))
    }

    @Test fun originalModeUsesPlainLanguageInsteadOfTechnicalTerms() {
        assertEquals("原声播放", AudioSettingsPresentation.currentMode("pure").label)
        listOf("processed", "offload", "pure", "usb").forEach { key ->
            val mode = AudioSettingsPresentation.currentMode(key)
            val text = mode.label + mode.description
            listOf("PCM", "bit-perfect", "dBTP", "重启", "192k", "float").forEach { technical -> assertFalse(text.contains(technical)) }
            assertTrue(mode.description.length <= 32)
        }
    }

    @Test fun unavailableUsbIsHiddenButExistingUsbChoiceRemainsVisibleWithLabel() {
        val ordinary = AudioModeDefaults.visibleModes(false, "processed").map(AudioSettingsPresentation::currentMode)
        assertEquals(listOf("processed", "offload", "pure"), ordinary.map { it.key })
        val selectedUsb = AudioModeDefaults.visibleModes(false, "usb").map(AudioSettingsPresentation::currentMode)
        assertEquals("USB高保真", selectedUsb.single { it.key == "usb" }.label)
    }

    @Test fun availableUsbUsesSameModeLabelsWithoutDuplicatingCapabilityRules() {
        val modes = AudioModeDefaults.visibleModes(true, "processed").map(AudioSettingsPresentation::currentMode)
        assertEquals(listOf("processed", "offload", "pure", "usb"), modes.map { it.key })
        assertTrue(modes.all { it.label.isNotBlank() && it.description.isNotBlank() })
    }
}
