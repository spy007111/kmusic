package com.kmusic.apkhd.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tan

/**
 * EBU R128 / ITU-R BS.1770-4 积分响度计算。
 *
 * 只做「测」，不做「改」：喂进 PCM，吐出 LUFS。真正的增益在播放器音量上施加。
 *
 * 为什么不用现成库：Android 上没有 ReplayGain 扫描器，第三方库要么带 native
 * 要么依赖 ffmpeg —— 这里只需要两条 biquad + 分段门限，两百行足够。
 *
 * 系数用 libebur128 的解析式按实际采样率现算，不写死 48kHz 表 ——
 * 44.1k 的曲库占绝大多数，套 48k 系数会偏 0.1~0.3 LU，虽小但没必要。
 */
class R128(val sampleRate: Int, private val channels: Int) {

    companion object {
        const val ALGORITHM_VERSION = 2

        /** 无声道布局元数据时仅支持 mono/stereo；不猜测 surround/LFE 权重。 */
        fun supportsChannels(channels: Int): Boolean = channels in 1..2
    }

    private val channelWeights = DoubleArray(channels.also {
        require(supportsChannels(it)) { "R128 requires mono/stereo; unknown multichannel layout: $it" }
        require(sampleRate > 0) { "Invalid sample rate: $sampleRate" }
    }) { 1.0 }

    // K 加权：第一级高架（+4dB @1.7kHz），第二级高通（38Hz）
    private val sb = FloatArray(3)
    private val sa = FloatArray(2)
    private val hb = FloatArray(3)
    private val ha = FloatArray(2)

    // 每个声道两条 biquad 的状态：x1 x2 y1 y2
    private val sState = Array(channels) { FloatArray(4) }
    private val hState = Array(channels) { FloatArray(4) }

    /** 100ms 一段的能量（双声道求和后的均方），攒 4 段凑一个 400ms 块 */
    private val segLen = (sampleRate / 10).coerceAtLeast(1)
    private var segFill = 0
    private var segSum = 0.0
    private val segRing = DoubleArray(4)
    private var segIndex = 0
    private var segCount = 0
    private val blocks = ArrayList<Double>(2048)

    var peak: Float = 0f
        private set
    var frames: Long = 0
        private set

    init {
        // ---- 高架 ----
        run {
            val f0 = 1681.974450955533
            val gainDb = 3.999843853973347
            val q = 0.7071752369554196
            val k = tan(PI * f0 / sampleRate)
            val vh = 10.0.pow(gainDb / 20.0)
            val vb = vh.pow(0.4996667741545416)
            val a0 = 1.0 + k / q + k * k
            sb[0] = ((vh + vb * k / q + k * k) / a0).toFloat()
            sb[1] = (2.0 * (k * k - vh) / a0).toFloat()
            sb[2] = ((vh - vb * k / q + k * k) / a0).toFloat()
            sa[0] = (2.0 * (k * k - 1.0) / a0).toFloat()
            sa[1] = ((1.0 - k / q + k * k) / a0).toFloat()
        }
        // ---- 高通 ----
        run {
            val f0 = 38.13547087602444
            val q = 0.5003270373238773
            val k = tan(PI * f0 / sampleRate)
            val a0 = 1.0 + k / q + k * k
            hb[0] = (1.0 / a0).toFloat()
            hb[1] = (-2.0 / a0).toFloat()
            hb[2] = (1.0 / a0).toFloat()
            ha[0] = (2.0 * (k * k - 1.0) / a0).toFloat()
            ha[1] = ((1.0 - k / q + k * k) / a0).toFloat()
        }
    }

    /** 交错 PCM，float，范围 -1..1 */
    fun process(interleaved: FloatArray, count: Int) {
        var i = 0
        while (i + channels <= count) {
            var frameSq = 0.0
            for (ch in 0 until channels) {
                val raw = interleaved[i + ch]
                if (abs(raw) > peak) peak = abs(raw)
                val st = sState[ch]
                val y1 = sb[0] * raw + sb[1] * st[0] + sb[2] * st[1] - sa[0] * st[2] - sa[1] * st[3]
                st[1] = st[0]; st[0] = raw; st[3] = st[2]; st[2] = y1
                val ht = hState[ch]
                val y2 = hb[0] * y1 + hb[1] * ht[0] + hb[2] * ht[1] - ha[0] * ht[2] - ha[1] * ht[3]
                ht[1] = ht[0]; ht[0] = y1; ht[3] = ht[2]; ht[2] = y2
                frameSq += y2.toDouble() * y2 * channelWeights[ch]
            }
            segSum += frameSq
            segFill++
            frames++
            if (segFill >= segLen) {
                pushSegment(segSum / segFill)
                segSum = 0.0
                segFill = 0
            }
            i += channels
        }
    }

    private fun pushSegment(meanSq: Double) {
        segRing[segIndex] = meanSq
        segIndex = (segIndex + 1) % 4
        if (segCount < 4) segCount++
        if (segCount == 4) {
            var sum = 0.0
            for (v in segRing) sum += v
            blocks.add(sum / 4.0)
        }
    }

    /**
     * 积分响度（LUFS）。样本不足 400ms 时返回 null —— 别拿半秒的数据冒充整首。
     */
    fun integratedLufs(): Double? {
        if (blocks.isEmpty()) return null
        val absGate = 10.0.pow((-70.0 + 0.691) / 10.0)
        var absSum = 0.0
        var absCount = 0
        for (z in blocks) if (z > absGate) { absSum += z; absCount++ }
        if (absCount == 0) return null
        val absMean = absSum / absCount
        val relGate = absMean * 10.0.pow(-10.0 / 10.0)
        var sum = 0.0
        var count = 0
        for (z in blocks) if (z > absGate && z > relGate) { sum += z; count++ }
        if (count == 0) return null
        val mean = sum / count
        if (mean <= 0.0) return null
        return -0.691 + 10.0 * log10(mean)
    }
}
