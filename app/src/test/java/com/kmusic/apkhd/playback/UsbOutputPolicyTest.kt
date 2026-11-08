package com.kmusic.apkhd.playback

import org.junit.Assert.*
import org.junit.Test

class UsbOutputPolicyTest {
    private val strict = UsbStrictConstraints(true, true, true, 1f, 1f, 1f, true)
    private val pcm16 = UsbSourceFormat(UsbPcmFormat(44100, 2, 2), precisionKnown = true)
    private fun mixer(rate: Int = 44100, channels: Int = 2, encoding: Int = 2, perfect: Boolean = true) =
        UsbMixerOption(UsbPcmFormat(rate, channels, encoding, if (channels == 2) 12 else 4), perfect)

    @Test fun selectsExactBitPerfectInsteadOfDefaultOrHigherDacRate() {
        val exact = mixer()
        assertEquals(exact, UsbOutputPolicy.select(pcm16, strict,
            listOf(mixer(perfect = false), mixer(96000), exact)).option)
    }

    @Test fun floatIsEligibleOnlyWithExplicitSourceEvidence() {
        val float = UsbSourceFormat(UsbPcmFormat(96000, 2, 4), precisionKnown = true)
        assertEquals(mixer(96000, encoding = 4), UsbOutputPolicy.select(float, strict,
            listOf(mixer(96000, encoding = 4))).option)
        assertEquals(UsbOutputReason.SOURCE_PRECISION_UNKNOWN,
            UsbOutputPolicy.select(float.copy(precisionKnown = false), strict, listOf(mixer())).reason)
    }

    @Test fun integer24And32NeverBecomeExactByChoosingFloat() {
        listOf(0x20000000, 0x30000000).forEach { encoding ->
            assertEquals(UsbOutputReason.SOURCE_ENCODING_UNSUPPORTED,
                UsbOutputPolicy.select(pcm16.copy(pcm = pcm16.pcm.copy(encoding = encoding)), strict,
                    listOf(mixer(encoding = 4))).reason)
        }
    }

    @Test fun dsdConversionIsNotNativeOrDop() {
        assertEquals(UsbOutputReason.DSD_CONVERTED,
            UsbOutputPolicy.select(pcm16.copy(convertedFromDsd = true), strict, listOf(mixer())).reason)
    }

    @Test fun samplingChannelsAndEncodingMustAllMatchWithoutUpsampling() {
        listOf(mixer(48000), mixer(channels = 1), mixer(encoding = 4)).forEach {
            assertEquals(UsbOutputReason.SOURCE_FORMAT_MISMATCH,
                UsbOutputPolicy.select(pcm16, strict, listOf(it)).reason)
        }
    }

    @Test fun multichannelLayoutUnknownAndExplicitMaskMismatchAreRejected() {
        assertEquals(UsbOutputReason.CHANNEL_LAYOUT_UNKNOWN, UsbOutputPolicy.select(
            pcm16.copy(pcm = pcm16.pcm.copy(channelCount = 6)), strict, listOf(mixer(channels = 6))).reason)
        assertEquals(UsbOutputReason.SOURCE_FORMAT_MISMATCH, UsbOutputPolicy.select(
            pcm16.copy(pcm = pcm16.pcm.copy(channelMask = 4)), strict, listOf(mixer())).reason)
    }

    @Test fun everyStrictConstraintMustBeSatisfiedIncludingExactUnity() {
        val invalid = listOf(strict.copy(dspBypassed = false), strict.copy(normalizationDisabled = false),
            strict.copy(truePeakDisabled = false), strict.copy(offloadDisabled = false),
            strict.copy(speed = 1.01f), strict.copy(pitch = 0.9f), strict.copy(volume = 0.99f),
            strict.copy(volume = Float.NaN))
        invalid.forEach { assertEquals(UsbOutputReason.STRICT_CONSTRAINTS,
            UsbOutputPolicy.select(pcm16, it, listOf(mixer())).reason) }
    }

    @Test fun supportedUsbTypesAreSessionRoutesNotBluetoothOrHdmi() {
        listOf(11, 12, 22).forEach { assertTrue(UsbOutputPolicy.isUsb(it)) }
        listOf(0, 2, 8, 9, 23).forEach { assertFalse(UsbOutputPolicy.isUsb(it)) }
    }

    @Test fun noBitPerfectDescriptorAndUnknownSourceStayHonest() {
        assertEquals(UsbOutputReason.NO_BIT_PERFECT_MIXER,
            UsbOutputPolicy.select(pcm16, strict, listOf(mixer(perfect = false))).reason)
        assertEquals(UsbOutputReason.SOURCE_UNKNOWN, UsbOutputPolicy.select(null, strict, listOf(mixer())).reason)
    }

    @Test fun outputComparisonRejectsCompressedOrConvertedAndMissingEvidence() {
        val good = ApplicationAudioFormat(44100, 2, 2, true)
        assertTrue(UsbOutputPolicy.outputMatches(pcm16, mixer(), good))
        assertFalse(UsbOutputPolicy.outputMatches(pcm16, mixer(), good.copy(isPcm = false)))
        assertFalse(UsbOutputPolicy.outputMatches(pcm16, mixer(), good.copy(encoding = 4)))
        assertFalse(UsbOutputPolicy.outputMatches(pcm16, mixer(), good.copy(sampleRate = 48000)))
        assertFalse(UsbOutputPolicy.outputMatches(pcm16, mixer(), null))
    }

    @Test fun usbPipelineWinsConflictingFlagsWithoutDspOrOffload() {
        val mode = AudioPipelineMode.resolve(true, true, true)
        assertEquals(AudioPipelineMode.USB_HIGH_FIDELITY, mode)
        assertTrue(mode.floatOutput)
        assertFalse(mode.softwareDsp)
        assertFalse(mode.offload)
    }
}
