package com.kmusic.apkhd.audio

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin

class DsdFirResponseTest {
    private fun db(taps: FloatArray, freq: Double, fs: Int): Double {
        var re = 0.0
        var im = 0.0
        for (k in taps.indices) {
            val w = 2 * PI * freq * k / fs
            re += taps[k] * cos(w)
            im -= taps[k] * sin(w)
        }
        return 20 * log10(hypot(re, im).coerceAtLeast(1e-15))
    }

    @Test fun finalFirHasDcUnitySymmetryAndFlatPassband() {
        for (fs in listOf(44100, 88200)) {
            val taps = DsdDecimator.buildFir(63, minOf(fs * 0.475f, 21000f), fs.toFloat())
            assertEquals(1f, taps.sum(), 0.00001f)
            for (k in taps.indices) assertEquals(taps[k], taps[taps.lastIndex - k], 0.00001f)
            for (f in listOf(1000.0, 10000.0, 18000.0)) assertTrue(abs(db(taps, f, fs)) < 0.05)
        }
    }

    @Test fun eightyEightKFinalFirRejectsUltrasonicBand() {
        val taps = DsdDecimator.buildFir(63, 21000f, 88200f)
        assertTrue(db(taps, 25000.0, 88200) < -60)
        assertTrue(db(taps, 30000.0, 88200) < -70)
        assertTrue(db(taps, 40000.0, 88200) < -80)
    }

    @Test fun fortyFourKFinalFirHasDefinedNyquistRejection() {
        val taps = DsdDecimator.buildFir(63, 44100 * 0.475f, 44100f)
        assertTrue(db(taps, 22050.0, 44100) < -35)
        assertTrue(db(taps, 20000.0, 44100) in -0.3..0.05)
    }

    @Test fun finalDecimatingHalfBandMeetsTwentyKPassbandAndTwentyFiveKStopband() {
        val taps = DsdDecimator.buildHalfBand(63)
        assertEquals(1f, taps.sum(), 0.00001f)
        assertTrue(db(taps, 20000.0, 88200) in -0.2..0.05)
        for (frequency in 25000..44100 step 10) {
            assertTrue("$frequency Hz", db(taps, frequency.toDouble(), 88200) < -50.0)
        }
    }

    @Test fun dsd64To256SyntheticChainsPreservePassbandAndRejectUltrasonicInputs() {
        for (rate in listOf(2822400, 5644800, 11289600)) {
            for (out in listOf(44100, 88200)) {
                for (freq in listOf(1000.0, 10000.0, 18000.0, 20000.0)) {
                    val pass = chainAmplitude(rate, out, freq)
                    // 一阶合成调制器在 DSD64 的20k频率格点会额外约0.3~0.4dB幅度误差。
                    val floor = if (freq == 20000.0) { if (out == 88200) -2.1 else -0.8 } else -0.5
                    assertTrue("$rate/$out/$freq pass=$pass", 20 * log10(pass / 0.2) in floor..0.5)
                }
                val stopFrequencies = if (out == 88200) listOf(25000.0, 30000.0) else listOf(25000.0, 30000.0, 35280.0)
                for (freq in stopFrequencies) {
                    val stop = chainAmplitude(rate, out, freq)
                    val limit = if (out == 88200) -55.0 else -49.0
                    assertTrue("$rate/$out/$freq stop=$stop", 20 * log10(stop / 0.2) < limit)
                }
            }
        }
    }

    private fun chainAmplitude(rate: Int, out: Int, frequency: Double): Double {
        val dec = DsdDecimator.Decimator(rate, out)
        val samples = ArrayList<Float>()
        var integrator = 0.0
        val byteCount = rate / 8 / 12
        for (bi in 0 until byteCount) {
            var byte = 0
            for (bit in 0 until 8) {
                val input = 0.2 * sin(2 * PI * frequency * (bi * 8 + bit) / rate)
                integrator += input
                val output = if (integrator >= 0) 1.0 else -1.0
                integrator -= output
                if (output > 0) byte = byte or (1 shl bit)
            }
            dec.feedByte(byte)
            if (bi % 1024 == 1023) samples.addAll(dec.read().toList())
        }
        samples.addAll(dec.read().toList())
        val folded = abs((frequency + out / 2.0) % out - out / 2.0)
        val n = out / 20
        assertTrue(samples.size >= n)
        var re = 0.0
        var im = 0.0
        for (i in 0 until n) {
            val sample = samples[samples.size - n + i]
            re += sample * cos(2 * PI * folded * i / out)
            im -= sample * sin(2 * PI * folded * i / out)
        }
        return 2 * hypot(re, im) / n
    }
}
