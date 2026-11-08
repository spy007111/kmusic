package com.kmusic.apkhd.audio

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class R128CorrectnessTest {
    private fun tone(channels: Int, silentRight: Boolean = false): Double {
        val rate = 48000
        val pcm = FloatArray(rate * 2 * channels) { i ->
            if (silentRight && i % channels != 0) 0f else (0.1 * sin(2 * PI * 1000 * (i / channels) / rate)).toFloat()
        }
        val r = R128(rate, channels)
        r.process(pcm, pcm.size)
        return r.integratedLufs()!!
    }

    @Test fun duplicatedStereoAddsThreeLu() {
        assertEquals(3.01029995664, tone(2) - tone(1), 0.0001)
    }

    @Test fun silentSecondChannelDoesNotReduceLoudness() {
        assertEquals(tone(1), tone(2, true), 0.0001)
    }

    @Test fun oneKhzMonoMatchesBs1770Reference() {
        assertEquals(-23.0, tone(1), 0.1)
    }

    @Test fun silenceAndShortInputHaveNoIntegratedResult() {
        val r = R128(48000, 2)
        r.process(FloatArray(48000), 48000)
        assertNull(r.integratedLufs())
        val short = R128(48000, 1)
        short.process(FloatArray(100) { 0.1f }, 100)
        assertNull(short.integratedLufs())
    }

    @Test fun multichannelWithoutLayoutIsRejected() {
        assertFalse(R128.supportsChannels(6))
        try {
            R128(48000, 6)
            fail("Unknown layout must not be presented as BS.1770")
        } catch (_: IllegalArgumentException) { }
    }
}
