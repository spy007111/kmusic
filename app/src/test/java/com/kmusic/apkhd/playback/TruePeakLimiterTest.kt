package com.kmusic.apkhd.playback

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class TruePeakLimiterTest {
    private fun run(input: Array<FloatArray>, fs: Int = 48000, chunk: Int = 127): Array<FloatArray> {
        val limiter = TruePeakLimiter(fs, input.size)
        limiter.configure(true, -1f)
        val incoming = FloatArray(input.size)
        val outgoing = FloatArray(input.size)
        val result = Array(input.size) { ArrayList<Float>() }
        var index = 0
        while (index < input[0].size) {
            val end = minOf(index + chunk, input[0].size)
            while (index < end) {
                for (ch in input.indices) incoming[ch] = input[ch][index]
                if (limiter.push(incoming, outgoing)) for (ch in result.indices) result[ch].add(outgoing[ch])
                index++
            }
        }
        while (limiter.drain(outgoing)) for (ch in result.indices) result[ch].add(outgoing[ch])
        assertEquals(0, limiter.pendingFrames)
        assertFalse(limiter.drain(outgoing))
        return Array(input.size) { result[it].toFloatArray() }
    }

    private fun reconstructedPeak(samples: FloatArray, factor: Int = 8): Double {
        var peak = samples.maxOfOrNull { abs(it.toDouble()) } ?: 0.0
        val kernels = TruePeakInterpolation.kernels(factor)
        for (first in -23 until samples.size) for (kernel in kernels) {
            var y = 0.0
            for (k in kernel.indices) y += kernel[k] * samples.getOrElse(first + k) { 0f }
            peak = maxOf(peak, abs(y))
        }
        return peak
    }

    @Test fun detectorFindsIntersamplePeakNotJustSamples() {
        val input = Array(1) { FloatArray(2400) { (0.99 * sin(2 * PI * 0.25 * it + PI / 4)).toFloat() } }
        val limiter = TruePeakLimiter(48000, 1)
        val output = FloatArray(1)
        for (x in input[0]) limiter.push(floatArrayOf(x), output)
        assertTrue(input[0].maxOf { abs(it) } < 0.71f)
        assertTrue(limiter.maximumEstimatedPeak > 0.98)
        assertTrue(reconstructedPeak(run(input)[0]) <= 0.8913)
    }

    @Test fun lowAmplitudeAndShortEosPreserveExactlyAllFrames() {
        for (n in listOf(0, 1, 7, 23, 64, 191, 192, 193)) {
            val input = Array(2) { ch -> FloatArray(n) { if (ch == 0) 0.1f else -0.05f } }
            val result = run(input)
            for (ch in input.indices) assertArrayEquals(input[ch], result[ch], 0f)
        }
    }

    @Test fun chunksDoNotChangeSamplesOrTailLength() {
        val input = Array(2) { ch -> FloatArray(2500) { (sin(2 * PI * (if (ch == 0) 1000 else 18000) * it / 48000) * 1.3).toFloat() } }
        val a = run(input, chunk = 1)
        val b = run(input, chunk = 255)
        for (ch in a.indices) assertArrayEquals(a[ch], b[ch], 0f)
        assertEquals(input[0].size, a[0].size)
    }

    @Test fun fullScaleOneKhzAttenuatesApproximatelyOneDb() {
        for (fs in listOf(44100, 48000, 96000, 192000)) {
            val input = FloatArray(fs / 10) { sin(2 * PI * 1000 * it / fs).toFloat() }
            val output = run(arrayOf(input), fs)[0]
            var dry = 0.0; var wet = 0.0
            for (i in fs / 50 until input.size) { dry += input[i].toDouble() * input[i]; wet += output[i].toDouble() * output[i] }
            val gain = 20 * log10(sqrt(wet / dry))
            assertTrue("$fs gain=$gain", gain in -1.2..-0.8)
            assertTrue("$fs output peak", reconstructedPeak(output) <= 0.8913)
        }
    }

    @Test fun channelGainIsLinkedAndDoesNotBoost() {
        val loud = FloatArray(4000) { (1.5 * sin(2 * PI * 1000 * it / 48000)).toFloat() }
        val quiet = FloatArray(loud.size) { loud[it] * 0.1f }
        val result = run(arrayOf(loud, quiet))
        for (i in loud.indices) {
            assertEquals(result[0][i] * 0.1f, result[1][i], 0.000001f)
            assertTrue(abs(result[0][i]) <= abs(loud[i]) + 0.000001f)
        }
    }

    @Test fun multitoneImpulseAndRandomVectorsRespectDenseReference() {
        val random = Random(7)
        val inputs = listOf(
            FloatArray(4000) { if (it == 190 || it == 2400) 2f else 0f },
            FloatArray(4000) { (0.7 * sin(2 * PI * 997 * it / 48000) + 0.7 * sin(2 * PI * 18000 * it / 48000)).toFloat() },
            FloatArray(4000) { random.nextFloat() * 3f - 1.5f }
        )
        for (input in inputs) {
            val output = run(arrayOf(input))[0]
            assertTrue("peak=${reconstructedPeak(output)}", reconstructedPeak(output) <= 0.8913)
        }
    }

    @Test fun resetClearsLookaheadAndDetectorHistory() {
        val limiter = TruePeakLimiter(48000, 1)
        val output = FloatArray(1)
        repeat(100) { limiter.push(floatArrayOf(3f), output) }
        limiter.reset()
        assertEquals(0, limiter.pendingFrames)
        assertEquals(0.0, limiter.maximumEstimatedPeak, 0.0)
        repeat(12) { assertFalse(limiter.push(floatArrayOf(0.2f), output)) }
        repeat(12) { assertTrue(limiter.drain(output)); assertEquals(0.2f, output[0], 0f) }
        assertFalse(limiter.drain(output))
    }

    @Test fun printOneSecondRuntimeBaselineAtCommonAndHighRates() {
        for (rate in listOf(44100, 48000, 96000, 192000)) {
            val limiter = TruePeakLimiter(rate, 2)
            val input = FloatArray(2)
            val output = FloatArray(2)
            val started = System.nanoTime()
            repeat(rate) { frame ->
                input[0] = (sin(2 * PI * 1000 * frame / rate) * 0.99).toFloat()
                input[1] = (sin(2 * PI * 10000 * frame / rate) * 0.5).toFloat()
                limiter.push(input, output)
            }
            while (limiter.drain(output)) { }
            val elapsed = (System.nanoTime() - started) / 1_000_000.0
            println("TRUE_PEAK_BENCH rate=$rate channels=2 audioSeconds=1 elapsedMs=$elapsed")
            assertEquals(0, limiter.pendingFrames)
        }
    }

    @Test fun disabledProtectionAndCeilingSanitizationAreHonest() {
        assertEquals(-0.5f, TruePeakLimiter.sanitizeCeiling(5f), 0f)
        assertEquals(-1f, TruePeakLimiter.sanitizeCeiling(Float.NaN), 0f)
        val limiter = TruePeakLimiter(48000, 1)
        limiter.configure(false, -1f)
        val output = FloatArray(1)
        repeat(20) { limiter.push(floatArrayOf(0.99f), output) }
        repeat(20) { assertTrue(limiter.drain(output)); assertEquals(0.99f, output[0], 0f) }
    }
}
