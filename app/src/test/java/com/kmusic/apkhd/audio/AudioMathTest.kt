package com.kmusic.apkhd.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * 纯算法自检：不依赖设备、不依赖解码器。
 *
 * 这两块（响度、频谱）出错的后果很隐蔽 —— 增益算错几 dB 用户只觉得「有点怪」，
 * 频谱判错则会把好文件标成升频。所以用合成信号把数学钉死。
 */
class AudioMathTest {

    private fun sine(freq: Double, seconds: Double, sampleRate: Int = 44100, amp: Double = 0.5): FloatArray {
        val n = (seconds * sampleRate).toInt()
        return FloatArray(n) { (amp * sin(2.0 * PI * freq * it / sampleRate)).toFloat() }
    }

    // ---------------- R128 ----------------

    @Test
    fun r128_matchesReferenceForKnownTone() {
        // 1kHz 正弦，幅度 0.5（-6dBFS 峰值）。
        // EBU 3341 参考：-6dBFS 的 1kHz 正弦 ≈ -9.0 LUFS（K 加权在 1kHz 处增益约 -3dB…）
        // 这里不追求对齐到 0.1，只要能落在合理区间 —— 目的在抓「系数写反」这类错误。
        val rate = 48000
        val pcm = sine(1000.0, 10.0, rate, 0.5)
        val r = R128(rate, 1)
        r.process(pcm, pcm.size)
        val lufs = r.integratedLufs()
        assertNotNull(lufs)
        assertTrue("1kHz/-6dBFS 应落在 -12~-6 LUFS，实际 $lufs", lufs!! in -12.0..-6.0)
    }

    @Test
    fun r128_halfAmplitudeIsSixLuQuieter() {
        val rate = 48000
        val a = R128(rate, 1).also { r -> val p = sine(1000.0, 6.0, rate, 0.5); r.process(p, p.size) }
        val b = R128(rate, 1).also { r -> val p = sine(1000.0, 6.0, rate, 0.25); r.process(p, p.size) }
        val la = a.integratedLufs()!!
        val lb = b.integratedLufs()!!
        // 幅度减半 = -6.02dB
        assertEquals(-6.02, lb - la, 0.15)
    }

    @Test
    fun r128_reportsPeak() {
        val rate = 48000
        val pcm = sine(1000.0, 2.0, rate, 0.8)
        val r = R128(rate, 2)
        // 双声道交错
        val stereo = FloatArray(pcm.size * 2)
        for (i in pcm.indices) { stereo[i * 2] = pcm[i]; stereo[i * 2 + 1] = pcm[i] }
        r.process(stereo, stereo.size)
        assertEquals(0.8, r.peak.toDouble(), 0.02)
    }

    @Test
    fun r128_shortInputReturnsNull() {
        val r = R128(48000, 1)
        val pcm = sine(1000.0, 0.1, 48000, 0.5)
        r.process(pcm, pcm.size)
        // 不足 400ms，不能冒充一个整首响度
        assertEquals(null, r.integratedLufs())
    }

    // ---------------- 频谱 ----------------

    @Test
    fun spectrum_findsSineFrequency() {
        val rate = 44100
        val pcm = sine(5000.0, 0.5, rate, 0.5)
        val r = SpectrumProbe.analyzePcm(pcm, rate)
        assertNotNull(r)
        // 单音的能量只落在 5kHz，截止应就在 5kHz 附近
        assertTrue("截止应 ≈5000Hz，实际 ${r!!.cutoffHz}", r.cutoffHz in 4500..5600)
    }

    @Test
    fun spectrum_bandLimitedSignalIsJudgedLow() {
        // 模拟 128k 转码：能量只存在于 16kHz 以下（真·砖墙，不是一阶低通的慢滚降）
        val rate = 44100
        val n = 4096
        val rnd = java.util.Random(42)
        val freqs = DoubleArray(120) { 200.0 + rnd.nextDouble() * (16_000.0 - 200.0) }
        val phases = DoubleArray(120) { rnd.nextDouble() * 2 * PI }
        val pcm = FloatArray(n) { i ->
            var v = 0.0
            for (k in freqs.indices) v += sin(2.0 * PI * freqs[k] * i / rate + phases[k])
            (v / freqs.size * 4).toFloat()
        }
        val r = SpectrumProbe.analyzePcm(pcm, rate)
        assertNotNull(r)
        assertTrue("截止应 ≤17kHz（模拟 128k），实际 ${r!!.cutoffHz}Hz / ${r.verdict}",
            r.cutoffHz <= 17_000)
        assertTrue("应判为 LOW，实际 ${r.verdict}", r.verdict == SpectrumProbe.Verdict.LOW)
    }

    @Test
    fun spectrum_silenceIsUnknown() {
        val r = SpectrumProbe.analyzePcm(FloatArray(4096), 44100)
        // 全零：没有能量，返回 null 或 UNKNOWN 都算合理，只要不误报「无损」
        assertTrue(r == null || r.verdict == SpectrumProbe.Verdict.UNKNOWN)
    }

    // ---------------- 增益夹取 ----------------

    @Test
    fun clamp_onlyAttenuatesWhenPeakUnknown() {
        // 不知道峰值时，任何正增益都要被压回 0
        assertEquals(0f, LoudnessNormalizer.clampGain(6f, null), 0.001f)
        assertEquals(-8f, LoudnessNormalizer.clampGain(-8f, null), 0.001f)
    }

    @Test
    fun clamp_respectsPeakHeadroom() {
        // 即使峰值留有余量，Media3 volume 归一也不允许正增益
        val g = LoudnessNormalizer.clampGain(6f, 0.9f)
        assertEquals(0f, g, 0.001f)
    }

    @Test
    fun clamp_neverBoostsEvenWithHeadroom() {
        // 峰值很低也不能声称 Media3.volume 可施加正增益
        assertEquals(0f, LoudnessNormalizer.clampGain(20f, 0.1f), 0.01f)
    }

    @Test
    fun clamp_limitsMaxCut() {
        assertEquals(-24f, LoudnessNormalizer.clampGain(-60f, null), 0.001f)
    }

    @Test
    fun linearConversion() {
        assertEquals(1f, LoudnessNormalizer.toLinear(0f), 0.001f)
        assertEquals(0.5f, LoudnessNormalizer.toLinear(-6.02f), 0.002f)
        assertEquals(1f, LoudnessNormalizer.toLinear(6.02f), 0.01f)
    }
}
