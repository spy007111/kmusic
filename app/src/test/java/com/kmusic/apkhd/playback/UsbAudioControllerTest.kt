package com.kmusic.apkhd.playback

import org.junit.Assert.*
import org.junit.Test

class UsbAudioControllerTest {
    private val route = OutputRoute(7, 11, "USB音频")
    private val other = OutputRoute(8, 22, "USB音频")
    private val source = UsbSourceFormat(UsbPcmFormat(44100, 2, 2), precisionKnown = true)
    private val output = ApplicationAudioFormat(44100, 2, 2, true)
    private val strict = UsbStrictConstraints(true, true, true, 1f, 1f, 1f, true)
    private val mixer = UsbMixerOption(UsbPcmFormat(44100, 2, 2, 12), true)

    private inner class FakePlatform(override val apiLevel: Int = 34) : UsbMixerPlatform {
        val connected = mutableSetOf(route, other)
        var options = listOf(mixer)
        var accepted = true
        var reportPreferred = true
        var clearsSucceed = true
        var throwSupported = false
        var throwRequest = false
        var throwObserve = false
        var closed = false
        var requests = 0
        var queries = 0
        val cleared = mutableListOf<OutputRoute>()
        val preferences = mutableMapOf<OutputRoute, UsbMixerOption>()
        var changed: ((OutputRoute, UsbMixerOption?) -> Unit)? = null
        var topology: (() -> Unit)? = null
        override fun isConnected(route: OutputRoute) = route in connected
        override fun supported(route: OutputRoute): List<UsbMixerOption> {
            queries++
            check(!throwSupported)
            return options
        }
        override fun request(route: OutputRoute, mixer: UsbMixerOption): Boolean {
            requests++
            preferences[route] = mixer
            check(!throwRequest)
            return accepted
        }
        override fun preferred(route: OutputRoute): UsbMixerOption? = if (reportPreferred) preferences[route] else null
        override fun clear(route: OutputRoute): Boolean {
            cleared.add(route)
            if (clearsSucceed) preferences.remove(route)
            return clearsSucceed
        }
        override fun observe(onPreferred: (OutputRoute, UsbMixerOption?) -> Unit, onTopology: () -> Unit) {
            check(!throwObserve)
            changed = onPreferred
            topology = onTopology
        }
        override fun close() { closed = true }
    }

    private fun active(platform: FakePlatform): UsbAudioController = UsbAudioController(platform).apply {
        configure(true, source, strict)
        onFocus(true)
        onOutput(output, route)
    }

    @Test fun defaultDisabledNeverQueriesOrRequestsMixer() {
        val platform = FakePlatform()
        val controller = UsbAudioController(platform)
        controller.onOutput(output, route)
        controller.onFocus(true)
        assertEquals(0, platform.requests)
        assertEquals(0, platform.queries)
        assertEquals(UsbOutputStatus.DISABLED, controller.snapshot.status)
    }

    @Test fun api33NeverInvokesPublicMixerQueries() {
        val platform = FakePlatform(33)
        val controller = active(platform)
        assertEquals(UsbOutputReason.API_TOO_OLD, controller.snapshot.reason)
        assertEquals(0, platform.requests)
        assertEquals(0, platform.queries)
        assertNull(platform.changed)
    }

    @Test fun connectedUsbWithoutAudioTrackRouteIsNotCurrentUsb() {
        val platform = FakePlatform()
        val controller = UsbAudioController(platform)
        controller.configure(true, source, strict)
        controller.onFocus(true)
        assertEquals(UsbOutputStatus.NO_USB, controller.snapshot.status)
        assertEquals(0, platform.requests)
    }

    @Test fun nonUsbCurrentRouteDoesNotUseConnectedUsb() {
        val platform = FakePlatform()
        val controller = active(platform)
        controller.onOutput(output, OutputRoute(1, 2, "扬声器"))
        assertEquals(listOf(route), platform.cleared)
        assertEquals(UsbOutputStatus.NO_USB, controller.snapshot.status)
    }

    @Test fun acceptanceAloneDoesNotVerifyPreference() {
        val platform = FakePlatform().apply { reportPreferred = false }
        val controller = active(platform)
        assertEquals(UsbOutputStatus.REQUESTED, controller.snapshot.status)
        assertFalse(controller.snapshot.preferredMixerConfirmed)
        assertTrue(controller.snapshot.applicationFormatMatches)
        assertEquals(1, platform.requests)
    }

    @Test fun matchingPreferenceAndActualTrackIsConfigurationEvidenceOnly() {
        val controller = active(FakePlatform())
        assertEquals(UsbOutputStatus.CONFIGURATION_MATCHED, controller.snapshot.status)
        assertTrue(controller.snapshot.preferredMixerConfirmed)
        assertTrue(controller.snapshot.applicationFormatMatches)
        assertTrue(controller.snapshot.statusText.contains("非端到端"))
        assertEquals(controller.snapshot, UsbAudioRuntime.state.value)
    }

    @Test fun lateMixerEventNeedsFreshPreferredReadAndActualOutput() {
        val platform = FakePlatform().apply { reportPreferred = false }
        val controller = active(platform)
        platform.changed?.invoke(route, mixer)
        assertEquals(UsbOutputStatus.REQUESTED, controller.snapshot.status)
        platform.reportPreferred = true
        platform.changed?.invoke(route, mixer)
        assertEquals(UsbOutputStatus.CONFIGURATION_MATCHED, controller.snapshot.status)
    }

    @Test fun mixerEventForDifferentDeviceCannotConfirmRequest() {
        val platform = FakePlatform().apply { reportPreferred = false }
        val controller = active(platform)
        platform.reportPreferred = true
        platform.changed?.invoke(other, mixer)
        assertEquals(UsbOutputStatus.REQUESTED, controller.snapshot.status)
    }

    @Test fun outputMismatchClearsAndDoesNotClaimConversionIsExact() {
        val platform = FakePlatform()
        val controller = active(platform)
        controller.onOutput(output.copy(encoding = 4), route)
        assertEquals(UsbOutputReason.OUTPUT_FORMAT_MISMATCH, controller.snapshot.reason)
        assertEquals(UsbOutputStatus.UNSATISFIED, controller.snapshot.status)
        assertNull(controller.snapshot.requestedMixer)
        assertEquals(listOf(route), platform.cleared)
    }

    @Test fun rejectedOrThrowingPreferenceIsClearedAndFallsBack() {
        listOf(false, true).forEach { throws ->
            val platform = FakePlatform().apply { accepted = false; throwRequest = throws }
            val controller = active(platform)
            assertEquals(UsbOutputReason.PREFERENCE_REJECTED, controller.snapshot.reason)
            assertEquals(listOf(route), platform.cleared)
            assertEquals(1, platform.requests)
            platform.topology?.invoke()
            assertEquals(1, platform.requests)
        }
    }

    @Test fun unsupportedQueryErrorDoesNotCrashOrRequest() {
        val platform = FakePlatform().apply { throwSupported = true }
        val controller = active(platform)
        assertEquals(UsbOutputReason.PLATFORM_ERROR, controller.snapshot.reason)
        assertEquals(0, platform.requests)
    }

    @Test fun unsupportedDefaultOnlyMixerDoesNotRequest() {
        val platform = FakePlatform().apply { options = listOf(mixer.copy(bitPerfect = false)) }
        val controller = active(platform)
        assertEquals(UsbOutputStatus.UNSUPPORTED, controller.snapshot.status)
        assertEquals(0, platform.requests)
    }

    @Test fun focusLossClearsAndGainReappliesButRequiresFreshOutput() {
        val platform = FakePlatform()
        val controller = active(platform)
        controller.onFocus(false)
        assertEquals(listOf(route), platform.cleared)
        assertEquals(UsbOutputReason.FOCUS_SUSPENDED, controller.snapshot.reason)
        platform.changed?.invoke(route, mixer)
        assertEquals(UsbOutputReason.FOCUS_SUSPENDED, controller.snapshot.reason)
        controller.onFocus(true)
        assertEquals(2, platform.requests)
        assertEquals(UsbOutputReason.OUTPUT_UNKNOWN, controller.snapshot.reason)
        controller.onOutput(output, route)
        assertEquals(UsbOutputStatus.CONFIGURATION_MATCHED, controller.snapshot.status)
    }

    @Test fun unplugClearsAndReplugDoesNotRestoreUnconfirmedOldRoute() {
        val platform = FakePlatform()
        val controller = active(platform)
        platform.connected.remove(route)
        platform.topology?.invoke()
        assertEquals(listOf(route), platform.cleared)
        assertEquals(UsbOutputStatus.NO_USB, controller.snapshot.status)
        platform.connected.add(route)
        platform.topology?.invoke()
        assertEquals(1, platform.requests)
        controller.onOutput(output, route)
        assertEquals(2, platform.requests)
    }

    @Test fun unplugDuringFocusLossCannotReapplyAfterUnconfirmedReplug() {
        val platform = FakePlatform()
        val controller = active(platform)
        controller.onFocus(false)
        platform.connected.remove(route)
        platform.topology?.invoke()
        platform.connected.add(route)
        platform.topology?.invoke()
        controller.onFocus(true)
        assertEquals(1, platform.requests)
        assertEquals(UsbOutputStatus.NO_USB, controller.snapshot.status)
    }

    @Test fun switchingUsbClearsOldPreferenceBeforeRequestingNewDevice() {
        val platform = FakePlatform()
        val controller = active(platform)
        controller.onOutput(output, other)
        assertEquals(listOf(route), platform.cleared)
        assertFalse(platform.preferences.containsKey(route))
        assertEquals(mixer, platform.preferences[other])
    }

    @Test fun clearFailurePreventsApplyingNewDevicePreferenceUntilRetrySucceeds() {
        val platform = FakePlatform()
        val controller = active(platform)
        platform.clearsSucceed = false
        controller.onOutput(output, other)
        assertEquals(UsbOutputReason.CLEAR_FAILED, controller.snapshot.reason)
        assertEquals(1, platform.requests)
        platform.clearsSucceed = true
        platform.topology?.invoke()
        assertEquals(2, platform.requests)
        assertFalse(platform.preferences.containsKey(route))
    }

    @Test fun disablingOrStrictConstraintViolationClearsOwnedPreference() {
        val platform = FakePlatform()
        val controller = active(platform)
        controller.configure(true, source, strict.copy(volume = 0.5f))
        assertEquals(UsbOutputReason.STRICT_CONSTRAINTS, controller.snapshot.reason)
        assertEquals(listOf(route), platform.cleared)
        controller.configure(false, source, strict)
        assertEquals(UsbOutputStatus.DISABLED, controller.snapshot.status)
    }

    @Test fun sourceChangeDoesNotReUseOldPrecisionEvidence() {
        val platform = FakePlatform()
        val controller = active(platform)
        controller.configure(true, source.copy(precisionKnown = false), strict)
        assertEquals(UsbOutputReason.SOURCE_PRECISION_UNKNOWN, controller.snapshot.reason)
        assertEquals(listOf(route), platform.cleared)
    }

    @Test fun trackReplacementDropsOldOutputProofBeforeCreation() {
        val platform = FakePlatform()
        val controller = active(platform)
        controller.beforeOutputCreated(output)
        assertEquals(UsbOutputReason.OUTPUT_UNKNOWN, controller.snapshot.reason)
        assertFalse(controller.snapshot.applicationFormatMatches)
        controller.onOutput(output, route)
        assertEquals(UsbOutputStatus.CONFIGURATION_MATCHED, controller.snapshot.status)
        assertEquals(1, platform.requests)
    }

    @Test fun incompatibleReplacementIsClearedBeforeTrackCreation() {
        val platform = FakePlatform()
        val controller = active(platform)
        controller.beforeOutputCreated(output.copy(sampleRate = 48000))
        assertEquals(UsbOutputReason.OUTPUT_FORMAT_MISMATCH, controller.snapshot.reason)
        assertEquals(listOf(route), platform.cleared)
        assertEquals(1, platform.requests)
    }

    @Test fun observerRegistrationFailureNeverClaimsConfiguredSuccess() {
        val controller = active(FakePlatform().apply { throwObserve = true })
        assertEquals(UsbOutputReason.PLATFORM_ERROR, controller.snapshot.reason)
        assertNotEquals(UsbOutputStatus.CONFIGURATION_MATCHED, controller.snapshot.status)
    }

    @Test fun closeClearsUnregistersAndIgnoresAllLateCallbacks() {
        val platform = FakePlatform()
        val controller = active(platform)
        controller.close()
        controller.close()
        controller.onFocus(true)
        controller.onOutput(output, route)
        controller.configure(true, source, strict)
        platform.changed?.invoke(route, mixer)
        platform.topology?.invoke()
        assertEquals(listOf(route), platform.cleared)
        assertEquals(UsbOutputReason.CLOSED, controller.snapshot.reason)
        assertTrue(platform.closed)
        assertEquals(1, platform.requests)
    }

    @Test fun closeClearFailureIsNotReportedAsCleanSuccess() {
        val platform = FakePlatform()
        val controller = active(platform)
        platform.clearsSucceed = false
        controller.close()
        assertEquals(UsbOutputReason.CLEAR_FAILED, controller.snapshot.reason)
        assertEquals(UsbOutputStatus.UNSATISFIED, controller.snapshot.status)
        assertTrue(platform.closed)
    }
}
