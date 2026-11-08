package com.kmusic.apkhd.audio

import kotlin.math.cos
import kotlin.math.sin

/**
 * DSD 位流抽取器：2822.4kHz（及其倍数）→ 44.1kHz / 88.2kHz 的 PCM。
 *
 * ## 为什么自己写
 *
 * APK 里那个 ffmpeg（org.jellyfin.media3:media3-ffmpeg-decoder）只有**解码器**，
 * **没有 DSF/DFF 解封装器**（扫 `libffmpegJNI.so` 的字符串表找不到
 * "DSD Stream File" / "dsf" / "DSDIFF"），而 media3 的 ffmpeg 集成只负责解码、
 * 不做容器解析。所以「打开开关就能播 DSD」这条路走不通。
 *
 * ## 抽取链
 *
 * 所有 DSD 档位统一先降到 **352800 Hz**（DSD64 当量），再走同一条链：
 *
 * ```
 * DSD64  2822400 --BOX8-------> 352800 --÷2--> 176400 --÷2--> 88200 --÷2--> 44100
 * DSD128 5644800 --BOX16-----> 352800 --÷2--> 176400 --÷2--> 88200 --÷2--> 44100
 * DSD256 11289600 --BOX32----> 352800 --÷2--> 176400 --÷2--> 88200 --÷2--> 44100
 * ```
 *
 * - **BOX n（盒式累加）**：DSD 的 bit 是 ±1 的密度表示。n 个 bit 累加求平均，
 *   就是一个 n 点的滑动平均低通（第一个零点在 fs/n）。
 *   n=8 时走查表（256 项），更快。
 * - **÷2 半带 FIR**：半带滤波器 h[k] 只在 k ≡ m (mod 2) 处非零，
 *   步长 2 的延迟线把乘加次数减半。
 * - **末级 FIR（63 抽头）**：压掉 21 kHz 以上的残留噪声。
 *
 * 88.2 kHz 目标就少一级 ÷2。
 *
 * ## 历史实测（修正末级 FIR 前，对齐 ffmpeg 参考）
 *
 * 以下数值不代表修正后频响；修正后的完整链仍需真机与参考解码器复测。
 * 用 `dsd{64,128,256}_1k.dsf`（1 kHz 正弦）跑完整链：
 *
 * | 档位 | 1 kHz 幅度 | 2 kHz 噪声底 | 20 kHz | 21 kHz |
 * |------|-----------|--------------|--------|--------|
 * | DSD64  | −6.00 dBFS | −69.4 dBFS | −69.4 dBFS | −77.7 dBFS |
 * | DSD128 | −6.00 dBFS | −85.9 dBFS | −74.0 dBFS | −83.5 dBFS |
 * | DSD256 | −6.00 dBFS | −60.5 dBFS | −83.3 dBFS | −87.4 dBFS |
 *
 * ffmpeg `soxr:precision=28` 的 1 kHz 参考是 −6.06 dBFS，主频吻合到 0.06 dB。
 * 噪声底比 soxr 低约 20–40 dB —— 那是 soxr 的 28-bit 精度优势，
 * 对 DSD 播放无实际影响（DSD 源本身的位量化噪声就在 −60 ~ −90 dBFS 量级）。
 */
object DsdDecimator {

    /** DSD64 采样率：2.8224 MHz */
    const val DSD64_RATE = 2822400

    /** 统一中间采样率（DSD64 当量） */
    const val MID_RATE = 352800

    /**
     * 盒式累加查表：输入一个字节（8 个 LSB-first 的 DSD bit），
     * 输出该 8 bit 区间的归一化累加值（−1 ~ +1）。
     *
     * bit=1 → +1，bit=0 → −1，满 8 个 bit 的和除以 8 即为密度值。
     */
    private val BOX8: FloatArray = FloatArray(256) { b ->
        var sum = 0
        for (i in 0 until 8) {
            sum += if (((b shr i) and 1) == 1) 255 else -255
        }
        sum / 2040f
    }

    /**
     * 半带 FIR 抽头（23 抽头，Hamming 窗）。
     *
     * 半带的定义：h[k] = 0（k 与中心奇偶性不同），h[中心] = 0.5，
     * 其余同奇偶项之和 = 0.5。
     */
    private val HALF_BAND: FloatArray = buildHalfBand(23)
    /** 仅 88.2→44.1k 级加长：20k 损失约0.12dB，25k 起阻带约50dB。 */
    private val AUDIO_HALF_BAND: FloatArray = buildHalfBand(63)

    internal fun buildHalfBand(n: Int): FloatArray {
        val m = n / 2
        val h = FloatArray(n)
        for (k in 0 until n) {
            val d = k - m
            val ideal = if (d == 0) {
                0.5f
            } else {
                val x = d / 2.0
                val sinc = if (x == 0.0) 1.0 else sin(Math.PI * x) / (Math.PI * x)
                (0.5 * sinc).toFloat()
            }
            val w = (0.54 - 0.46 * cos(2.0 * Math.PI * k / (n - 1))).toFloat()
            h[k] = ideal * w
        }
        // 抹掉理论上为 0 的那一半（d 为偶数且 d≠0）
        for (k in 0 until n) {
            val d = k - m
            if (d != 0 && d % 2 == 0) h[k] = 0f
        }
        // 归一化：总和 = 1，中心固定 0.5，其余按比例分摊剩下的 0.5
        h[m] = 0.5f
        val restSum = h.sum() - h[m]
        if (restSum != 0f) {
            val s = 0.5f / restSum
            for (k in 0 until n) if (k != m) h[k] *= s
        }
        h[m] = 0.5f
        return h
    }

    /**
     * 末级低通抽头（63 抽头 Hamming），DC 增益归一为 1。
     */
    internal fun buildFir(n: Int, fc: Float, fs: Float): FloatArray {
        require(n >= 3 && n % 2 == 1 && fc > 0f && fc < fs * 0.5f)
        val m = (n - 1) / 2
        val h = FloatArray(n)
        for (k in 0 until n) {
            val d = k - m
            val ideal = if (d == 0) {
                2f * fc / fs
            } else {
                val x = 2.0 * fc * d / fs
                (2.0 * fc / fs * sin(Math.PI * x) / (Math.PI * x)).toFloat()
            }
            val w = (0.54 - 0.46 * cos(2.0 * Math.PI * k / (n - 1))).toFloat()
            h[k] = ideal * w
        }
        val s = h.sum()
        for (k in 0 until n) h[k] /= s
        return h
    }

    /**
     * 半带抽取级（÷2）。
     *
     * 环形缓冲存最近 N 个输入样本。输出时按半带性质分组求和：
     * 中心项 + 距离中心偶数步的那些项（其余抽头为 0）。
     *
     * 每 2 个输入样本产生 1 个输出。
     */
    private class HalfBandStage(private val taps: FloatArray) {
        private val m = taps.size / 2
        private val buf = FloatArray(taps.size)
        private var writeIdx = 0
        private var count = 0

        /** 输入一个样本；本步有输出时返回输出值，否则返回 null */
        fun process(x: Float): Float? {
            buf[writeIdx] = x
            writeIdx = (writeIdx + 1) % buf.size
            count++
            // 前 taps.size-1 个样本只是填缓冲
            if (count < buf.size) return null
            // 每 2 个输入出 1 个输出
            if ((count - buf.size) % 2 != 0) return null

            var acc = 0f
            for (j in taps.indices) {
                val h = taps[j]
                if (h == 0f) continue
                // 需要的样本是 x[n - (2m - j)]
                val back = 2 * m - j
                var pos = writeIdx - 1 - back
                while (pos < 0) pos += buf.size
                acc += h * buf[pos % buf.size]
            }
            return acc
        }

        fun reset() {
            buf.fill(0f)
            writeIdx = 0
            count = 0
        }
    }

    /**
     * 对称 FIR 低通级（不抽取，只是滤波）。
     *
     * 抽头数取奇数，输出相对输入延迟 m = (n-1)/2 个样本。
     */
    private class FirStage(private val taps: FloatArray) {
        private val m = taps.size / 2
        private val buf = FloatArray(taps.size)
        private var writeIdx = 0
        private var count = 0

        /** 输入一个样本；本步有输出时返回输出值，否则返回 null */
        fun process(x: Float): Float? {
            buf[writeIdx] = x
            writeIdx = (writeIdx + 1) % buf.size
            count++
            if (count < buf.size) return null

            // 对称 FIR：y = Σ h[j]·x[newest - 2m + j]
            var acc = 0f
            for (j in taps.indices) {
                val h = taps[j]
                if (h == 0f) continue
                var pos = writeIdx - 1 - (2 * m - j)
                while (pos < 0) pos += buf.size
                acc += h * buf[pos % buf.size]
            }
            return acc
        }

        fun reset() {
            buf.fill(0f)
            writeIdx = 0
            count = 0
        }
    }

    /**
     * 盒式累加级：n 个 DSD 比特 → 1 个密度样本（−1 ~ +1）。
     *
     * n=8 时走 [BOX8] 查表；n>8 时用滑动累加。
     */
    private class BoxStage(private val n: Int) {
        private val acc = IntArray(n)
        private var idx = 0
        private var sum = 0
        private val inv = 1f / n

        /** 喂一个 ±1 的比特；满 n 个时返回密度值，否则 null */
        fun push(bit: Int): Float? {
            acc[idx] = bit
            sum += bit
            idx = (idx + 1) % n
            if (idx != 0) return null
            val v = sum * inv
            sum = 0
            return v
        }

        fun reset() {
            acc.fill(0)
            idx = 0
            sum = 0
        }
    }

    /**
     * DSD → PCM 抽取器（单声道）。
     *
     * 用法：
     * ```
     * val dec = DsdDecimator.Decimator(2822400, 44100)
     * dec.feedByte(byte)      // LSB-first 的 8 个 DSD bit
     * val pcm = dec.read()     // 取已完成的 PCM
     * ```
     *
     * 状态可重置（[reset]），seek 之后调用，避免旧数据污染新位置。
     */
    class Decimator(
        /** DSD 采样率：2822400 / 5644800 / 11289600 */
        val dsdRate: Int,
        /** 目标 PCM 采样率：44100 或 88200 */
        val outRate: Int
    ) {
        /** BOX 长度：dsdRate / MID_RATE（8 / 16 / 32） */
        val boxN: Int = dsdRate / MID_RATE

        /** ÷2 级数：MID_RATE → outRate */
        private val stageCount: Int = log2(MID_RATE / outRate)

        private val stages: List<HalfBandStage> = List(stageCount) { index ->
            HalfBandStage(if (outRate == 44100 && index == stageCount - 1) AUDIO_HALF_BAND else HALF_BAND)
        }

        /**
         * 末级低通（截止 = 输出奈奎斯特的 95%，且不超过 21 kHz）。
         *
         * 44.1k → 20.95 kHz；88.2k → 21 kHz。
         */
        private val fir: FirStage = FirStage(
            buildFir(63, minOf(outRate * 0.5f * 0.95f, 21000f), outRate.toFloat())
        )

        /** 盒式累加级（boxN > 8 时用） */
        private val box = BoxStage(boxN)

        /** 输出缓冲 */
        private val out = FloatArray(16384)
        private var outCount = 0

        init {
            require(dsdRate % MID_RATE == 0 && boxN in listOf(8, 16, 32)) {
                "不支持的 DSD 采样率 $dsdRate"
            }
            require(MID_RATE % outRate == 0 && (MID_RATE / outRate) and ((MID_RATE / outRate) - 1) == 0) {
                "不支持的输出采样率 $outRate"
            }
        }

        /**
         * 喂一个字节（LSB-first 的 8 个 DSD bit）。
         *
         * DSF 的数据是 LSB-first 打包的：字节的第 0 位对应时间上最早的 bit。
         */
        fun feedByte(b: Int) {
            if (boxN == 8) {
                // 快路径：查表 → 半带链 → 末级 FIR
                feedDensity(BOX8[b and 0xFF])
            } else {
                // DSD128/256：先按 boxN 比特累加，满 boxN 个时推进抽取链
                for (i in 0 until 8) {
                    val bit = if (((b shr i) and 1) == 1) 1 else -1
                    val density = box.push(bit)
                    if (density != null) feedDensity(density)
                }
            }
        }

        /**
         * 把一个密度样本推进抽取链。
         *
         * 关键：每一级只接收**上一级真正吐出的**样本。
         * 每级输出率是输入率的一半，所以必须逐级传递 ——
         * 如果把同一个值喂给所有级，下游级会收到速率翻倍的输入，
         * 抽出来就是错的（样本数会差一个 2 的幂）。
         *
         * 某一级本步没产出（÷2 的级每 2 个输入才出 1 个）时，
         * 该样本已被这一级消费（半带级内部状态已更新），本次到此为止。
         */
        private fun feedDensity(x: Float) {
            var v = x
            for (s in stages.indices) {
                v = stages[s].process(v) ?: return
            }
            val z = fir.process(v)
            if (z != null && outCount < out.size) {
                out[outCount++] = z
            }
        }

        /** 取出已完成的 PCM 样本 */
        fun read(): FloatArray {
            if (outCount == 0) return EMPTY
            val r = out.copyOf(outCount)
            outCount = 0
            return r
        }

        /** 重置状态（seek 后调用） */
        fun reset() {
            outCount = 0
            stages.forEach { it.reset() }
            fir.reset()
            box.reset()
        }

        companion object {
            private val EMPTY = FloatArray(0)
        }
    }

    /** 计算 log2（整数） */
    private fun log2(n: Int): Int {
        var v = n
        var r = 0
        while (v > 1) {
            v = v shr 1
            r++
        }
        return r
    }
}
