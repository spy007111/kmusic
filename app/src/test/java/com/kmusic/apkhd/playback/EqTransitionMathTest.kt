package com.kmusic.apkhd.playback

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class EqTransitionMathTest {
    @Test fun snapshotsDefensivelyCopyAndUpdateAtomically() {
        val input = IntArray(EqBands.COUNT) { 100 }
        val p = EqParameters.create(true, input, 700, -8f, true)
        input[0] = 1500
        val exposed = p.gains()
        exposed[1] = -1500
        assertEquals(100, p.gains()[0])
        assertEquals(100, p.gains()[1])
        val next = p.updated(bypassed = false, preGainDb = -9f)
        assertTrue(p.bypassed)
        assertEquals(-8f, p.preGainDb, 0f)
        assertFalse(next.bypassed)
        assertEquals(700, next.bassStrength)
        assertEquals(-9f, next.preGainDb, 0f)
    }

    @Test fun gainRampsExactlyTenMillisecondsAndStaysChannelLinked() {
        val dsp = EqDspTransition(48000, 2)
        dsp.prepare(EqParameters.create(preGainDb = -6f))
        var previous = 0.5f
        for (f in 0 until 480) {
            dsp.beginFrame()
            val left = dsp.process(0, 0.5f)
            val right = dsp.process(1, 0.5f)
            assertEquals(left, right, 0f)
            assertTrue(left <= previous)
            assertTrue(abs(previous - left) < 0.001f)
            previous = left
            dsp.endFrame()
        }
        assertEquals(0.2505936f, previous, 0.00001f)
        assertFalse(dsp.transitioning)
    }

    @Test fun retargetedRampStartsAtCurrentGain() {
        val ramp = GainRamp()
        ramp.setTarget(0.5f, 480)
        repeat(100) { ramp.next() }
        val old = ramp.value
        ramp.setTarget(0.8f, 480)
        assertTrue(abs(ramp.next() - old) < 0.001f)
        repeat(479) { ramp.next() }
        assertEquals(0.8f, ramp.value, 0f)
        assertFalse(ramp.moving)
    }

    @Test fun dualChainChangesKeepFixedStableFiltersAndReachNewEndpoint() {
        val dsp = EqDspTransition(48000, 2)
        val params = EqParameters.create(true, IntArray(EqBands.COUNT) { 1500 }, 1000, -24f)
        dsp.prepare(params)
        assertTrue(dsp.transitioning)
        var previous = 0f
        for (f in 0 until 4800) {
            val input = (0.01 * sin(2 * PI * 1000 * f / 48000)).toFloat()
            dsp.beginFrame()
            val y = dsp.process(0, input)
            assertEquals(y, dsp.process(1, input), 0f)
            assertTrue(y.isFinite())
            assertTrue(abs(y) < 0.2f)
            assertTrue(abs(y - previous) < 0.03f)
            previous = y
            dsp.endFrame()
            if (f == 479) assertFalse(dsp.transitioning)
        }
        dsp.prepare(EqParameters.create())
        repeat(480) {
            dsp.beginFrame()
            dsp.process(0, 0f)
            dsp.process(1, 0f)
            dsp.endFrame()
        }
        assertFalse(dsp.processing)
        dsp.beginFrame()
        assertEquals(0.123f, dsp.process(0, 0.123f), 0f)
    }

    @Test fun rapidEditsWaitForCurrentFadeThenAcceptLatestSnapshot() {
        val dsp = EqDspTransition(48000, 1)
        dsp.prepare(EqParameters.create(true, IntArray(EqBands.COUNT) { 600 }, preGainDb = -12f))
        val latest = EqParameters.create(true, IntArray(EqBands.COUNT) { -600 }, preGainDb = -3f)
        dsp.prepare(latest)
        repeat(480) {
            dsp.beginFrame(); dsp.process(0, 0.01f); dsp.endFrame()
        }
        assertFalse(dsp.transitioning)
        dsp.prepare(latest)
        assertTrue(dsp.transitioning)
    }

    @Test fun supportedRbJEndpointsHavePolesInsideUnitCircle() {
        for (fs in listOf(44100f, 48000f, 96000f, 192000f)) {
            for (freq in EqBands.FREQS) for (gain in listOf(-15f, 15f)) {
                stable(Biquad.peaking(freq.toFloat(), gain, 1.4142135f, fs))
            }
            stable(Biquad.lowShelf(110f, 9f, 0.8f, fs))
        }
    }

    private fun stable(c: FloatArray) {
        assertTrue(c.all { it.isFinite() })
        assertTrue(1.0 + c[3] + c[4] > 0.0)
        assertTrue(1.0 - c[3] + c[4] > 0.0)
        assertTrue(1.0 - c[4] > 0.0)
    }
}
