package com.kmusic.apkhd.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.kmusic.apkhd.data.DiagLog
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.sign

/**
 * 十段均衡器 + 低音 + 响度（v2.3.5 起取代 `DynamicsProcessing`）。
 *
 * ## 为什么不用系统音效了
 *
 * 旧实现把所有东西塞进一个 [android.media.audiofx.DynamicsProcessing]：
 *  - 它要占**系统音效池位**。真机日志里池位只剩 1 个，于是「开均衡器 → 低音/响度饿死」，
 *    还得在暂停 15 秒后把池位还回去、起播再抢回来 —— 一整套生命周期都是为池位服务的。
 *  - 它的 preEq 是 **FFT 分箱**（AOSP `DPFrequency.cpp`：`binStop = freq × blockSize / sampleRate`），
 *    默认 10ms 帧 @48kHz 只有 93.75 Hz/bin —— 31Hz 落 bin 0、62Hz 与 125Hz 撞在同一个 bin。
 *    在这种分辨率上画十段，低频三段调哪个都一样，UI 是骗人的。
 *
 * 现在跑在 media3 的 [AudioProcessor] 链上：每段一个 RBJ peaking biquad，
 * 中心频率精确到任意值，**不占任何系统音效池位**，也不依赖 ROM 实现了什么。
 *
 * ## 音质取舍
 *
 * Q = √2 ≈ 1.414 对应 1 个倍频程带宽；十段中心频率正好每段一个倍频程，
 * 于是相邻段首尾相接 —— 既不会在段间留出深谷，也不会叠出尖峰。
 * 转置直接 II 型，每段每声道 2 个状态变量，系数只在参数变化时重算。
 *
 * ## 关掉时真的不花钱
 *
 * 三项全关 → [isActive] 仍返回 true（否则要重配整条管线，会变成「改设置要等切歌才生效」），
 * 但 [queueInput] 只做一次 `ByteBuffer` 拷贝就返回。
 */
@UnstableApi
class EqProcessor : BaseAudioProcessor() {

    companion object {
        /**
         * 全进程唯一实例。
         *
         * 不跟 audio session 走：处理器活在 PCM 管线上，没有「音效槽」这个概念，
         * 因此不存在池位、也不需要切歌重建。播放器建 sink 时挂上它，
         * 界面改参数直接写这个对象 —— 立即生效，不用通知播放器。
         */
        val instance: EqProcessor by lazy { EqProcessor() }

        /** 每段带宽 = 1 个倍频程时 Q = √2 */
        private const val Q = 1.4142135f

        /** 低音搁架的拐点：62~125 Hz 是鼓/贝斯的基频区 */
        const val BASS_SHELF_HZ = 110f

        /** 低音满档的搁架增益（dB） */
        const val BASS_MAX_DB = 9f

        /**
         * 软限幅的起始电平（线性）。
         *
         * 取 0.90 而不是更低的 0.72：软拐点以下必须**完全线性**，否则正常的
         * 大音量素材（成品母带峰值普遍 0.9~0.99）会有一大片样本被无谓地压扁。
         * 实测 0.72 时「EQ 低音预设 + 低音极强 + 响度 +9」有 25~56% 的样本进压缩区，
         * 听感是闷且有毛刺 —— 那不是失真该有的位置，是拐点定得太低。
         */
        private const val SOFT_KNEE = 0.90f

        /** 增益上下限（mB），与旧设备均衡器一致 */
        const val LEVEL_MIN = -1_500
        const val LEVEL_MAX = 1_500
    }

    @Volatile
    private var params = EqParameters.create()

    private var lastClipWarnMs: Long = 0

    @Volatile
    private var sampleRate: Int = 0
    @Volatile
    private var channelCount: Int = 0
    private var encoding: Int = C.ENCODING_INVALID
    private var dsp: EqDspTransition? = null
    private var limiter: TruePeakLimiter? = null
    private var frameScratch = FloatArray(0)
    private var limitedScratch = FloatArray(0)
    @Volatile private var limiterRunning = false
    val currentTruePeakActive: Boolean get() = limiterRunning && params.truePeakEnabled && !params.bypassed
    val currentTruePeakLatencyFrames: Int get() = if (limiterRunning) maxOf((sampleRate * 0.004).toInt(), 24) else 0

    // ── 对外只读状态（界面用它解释「为什么没变化」） ──

    /** 实际输出采样率（Hz），0 = 还没接进管线 */
    val currentSampleRate: Int get() = sampleRate

    /**
     * 接进管线 / 断开时回调（参数是采样率，0 = 已断开）。
     *
     * 处理器跑在音频线程，界面不知道它什么时候真正上链 —— 没有这个回调，
     * 用户起播后看到的状态会一直停在「起播后立刻生效」，明明已经生效了。
     * 回调里只允许写 State（[FxChain] 会切到主线程），不要做重活。
     */
    @Volatile
    var onFormatChanged: ((Int) -> Unit)? = null

    /** DSD / 省电播放时整条处理器透明通过 */
    var isBypassed: Boolean
        get() = params.bypassed
        @Synchronized set(on) { publish(params.updated(bypassed = on)) }

    val working: Boolean get() = params.working || (params.truePeakEnabled && !params.bypassed)

    /** 与 FxChain 相同的解析曲线余量；读取单个完整配置。 */
    fun headroomDb(): Float {
        val p = params
        if (!p.working) return 0f
        val fs = sampleRate.toFloat().takeIf { it > 0f } ?: 48_000f
        val gains = if (p.enabled) p.gains() else IntArray(EqBands.COUNT)
        return Biquad.peakBoostDb(gains, p.bassStrength, fs, BASS_SHELF_HZ, BASS_MAX_DB, Q)
    }

    @Synchronized
    fun setEnabled(on: Boolean) { publish(params.updated(enabled = on)) }

    @Synchronized
    fun setGains(milliBel: IntArray) { publish(params.updated(gainsMb = milliBel)) }

    @Synchronized
    fun setBass(strength: Int) { publish(params.updated(bassStrength = strength)) }

    @Synchronized
    fun setPreGainDb(db: Float) { publish(params.updated(preGainDb = db)) }

    /** 配置与旁路一起原子发布；音频线程每个块只读取一次。 */
    @Synchronized
    fun applyParams(
        enabled: Boolean,
        gainsMb: IntArray,
        bassStrength: Int,
        preGainDb: Float,
        bypassed: Boolean = params.bypassed,
        truePeakEnabled: Boolean = params.truePeakEnabled,
        truePeakCeilingDb: Float = params.truePeakCeilingDb
    ) {
        publish(EqParameters.create(enabled, gainsMb, bassStrength, preGainDb, bypassed, truePeakEnabled, truePeakCeilingDb))
    }

    private fun publish(next: EqParameters) {
        if (!params.sameValues(next)) params = next
    }

    // ── AudioProcessor ──

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // 只处理线性 PCM。不认识的编码就声明「我不处理」（NOT_SET）——
        // 假装处理然后吐噪音是最坏的结果。
        if (!isSupportedPcm(inputAudioFormat.encoding) ||
            inputAudioFormat.sampleRate <= 0 ||
            inputAudioFormat.channelCount <= 0
        ) {
            DiagLog.log(
                "EQ",
                "处理器跳过：enc=${inputAudioFormat.encoding} " +
                    "sr=${inputAudioFormat.sampleRate} ch=${inputAudioFormat.channelCount}"
            )
            return AudioProcessor.AudioFormat.NOT_SET
        }
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        encoding = inputAudioFormat.encoding
        clearState()
        DiagLog.log(
            "EQ",
            "十段处理器接入：${sampleRate}Hz ${channelCount}ch ${encodingName(encoding)}"
        )
        onFormatChanged?.invoke(sampleRate)
        // 输出格式与输入一致：只改振幅，不改采样率、不改声道数
        return inputAudioFormat
    }

    override fun onFlush() {
        clearState()
    }

    override fun onReset() {
        dsp = null
        limiter = null
        limiterRunning = false
        frameScratch = FloatArray(0)
        limitedScratch = FloatArray(0)
        sampleRate = 0
        channelCount = 0
        onFormatChanged?.invoke(0)
    }

    /**
     * 永远 active。
     *
     * 若按「有没有参数」动态返回 false，media3 会在 configure/flush 时把这条处理器
     * 从管线里摘掉，于是「播放中打开均衡器」要等切歌才生效 —— 最容易被当成开关坏了。
     * 透传路径只多一次拷贝，代价可忽略。
     */
    override fun isActive(): Boolean = true

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size <= 0) return
        val bps = bytesPerSample(encoding)
        val frameSize = bps * channelCount
        val p = params
        val valid = sampleRate > 0 && channelCount > 0 && frameSize > 0
        if (!valid) {
            val out = replaceOutputBuffer(size)
            out.put(inputBuffer).flip()
            return
        }
        // Retain a started delay line until flush: toggling a protection cannot lose buffered frames.
        // When a forced bypass arrives, delayed data drains in order before new byte-exact dry input.
        if (p.bypassed) dsp = null else {
            if (dsp == null) dsp = EqDspTransition(sampleRate, channelCount)
            dsp?.prepare(p)
        }
        if (p.truePeakEnabled && !p.bypassed && limiter == null) {
            limiter = TruePeakLimiter(sampleRate, channelCount)
            limiterRunning = true
        }
        val protection = limiter
        protection?.configure(p.truePeakEnabled && !p.bypassed, p.truePeakCeilingDb)
        val core = dsp
        if (protection == null && (p.bypassed || core?.processing != true)) {
            val out = replaceOutputBuffer(size)
            out.put(inputBuffer).flip()
            return
        }
        val frames = size / frameSize
        val start = inputBuffer.position()
        val tail = size % frameSize
        val out = replaceOutputBuffer(size)
        for (f in 0 until frames) {
            core?.beginFrame()
            for (c in 0 until channelCount) {
                val raw = readSample(inputBuffer, start + f * frameSize + c * bps)
                var x = if (p.bypassed) raw else core?.process(c, raw) ?: raw
                if (protection == null || !p.truePeakEnabled || p.bypassed) {
                    if (!p.bypassed && (x > SOFT_KNEE || x < -SOFT_KNEE)) x = softClip(x)
                } else if (!x.isFinite()) {
                    x = 0f // abnormal fallback only; no normal softclip after peak protection
                }
                frameScratch[c] = x
            }
            core?.endFrame()
            if (protection == null) {
                for (c in 0 until channelCount) writeSample(out, frameScratch[c])
            } else if (protection.push(frameScratch, limitedScratch)) {
                for (c in 0 until channelCount) writeSample(out, limitedScratch[c])
            }
        }
        // Partial PCM frames are not expected by Media3. Preserve their bytes on the undelayed path.
        if (protection == null) for (i in 0 until tail) out.put(inputBuffer.get(start + frames * frameSize + i))
        out.flip()
        inputBuffer.position(start + frames * frameSize + if (protection == null) tail else 0)
    }

    override fun onQueueEndOfStream() {
        val protection = limiter ?: return
        val out = replaceOutputBuffer(protection.pendingFrames * channelCount * bytesPerSample(encoding))
        while (protection.drain(limitedScratch)) {
            for (c in 0 until channelCount) writeSample(out, limitedScratch[c])
        }
        out.flip()
    }

    // ── 内部 ──

    private fun clearState() {
        dsp = if (sampleRate > 0 && channelCount > 0) EqDspTransition(sampleRate, channelCount) else null
        limiter = null
        limiterRunning = false
        frameScratch = FloatArray(channelCount.coerceAtLeast(0))
        limitedScratch = FloatArray(channelCount.coerceAtLeast(0))
    }

    /**
     * 软限幅：|x| 超过 [SOFT_KNEE] 后按 tanh 平滑压向 ±1。
     *
     * 公式必须是 `knee + (1-knee)·tanh((|x|-knee)/(1-knee))` ——
     * 这样在拐点上**值连续且导数连续**（导数正好是 1）。曾经写成
     * `0.7·tanh(...)·(1-knee) + knee`，在拐点处导数从 1 陡降到 0.07，
     * 相当于给波形打了个折角，听感是毛刺 —— 数值上「不削顶」，实际照样失真。
     */
    private fun softClip(x: Float): Float {
        val a = abs(x)
        val over = (a - SOFT_KNEE) / (1f - SOFT_KNEE)
        // tanhApprox 在 x>3 之后会越过 1 继续增长（有理近似只在 |x|<3 内准），
        // 必须夹到 1 —— 否则「限幅器」自己变成放大器，和这次的 bug 同一个形状。
        val shaped = SOFT_KNEE + (1f - SOFT_KNEE) * tanhApprox(over).coerceIn(0f, 1f)
        val out = shaped * sign(x)
        // 极小概率出现 Inf/NaN 时退回硬限幅：绝不让一个坏样本污染整个缓冲
        return if (out.isFinite()) out.coerceIn(-1f, 1f) else sign(x)
    }

    /** tanh 的有理近似（|x|<3 时误差 <1e-3），比 Math.tanh 快一个数量级 */
    private fun tanhApprox(x: Float): Float {
        val x2 = x * x
        return x * (27f + x2) / (27f + 9f * x2)
    }

    private fun isSupportedPcm(enc: Int): Boolean =
        enc == C.ENCODING_PCM_16BIT || enc == C.ENCODING_PCM_24BIT ||
            enc == C.ENCODING_PCM_32BIT || enc == C.ENCODING_PCM_FLOAT

    private fun bytesPerSample(enc: Int): Int = when (enc) {
        C.ENCODING_PCM_16BIT -> 2
        C.ENCODING_PCM_24BIT -> 3
        C.ENCODING_PCM_32BIT -> 4
        C.ENCODING_PCM_FLOAT -> 4
        else -> 2
    }

    private fun readSample(buf: ByteBuffer, index: Int): Float = when (encoding) {
        C.ENCODING_PCM_FLOAT -> buf.getFloat(index)
        C.ENCODING_PCM_24BIT -> {
            val lo = buf.get(index).toInt() and 0xFF
            val mid = buf.get(index + 1).toInt() and 0xFF
            val hi = buf.get(index + 2).toInt() // 有符号
            ((hi shl 16) or (mid shl 8) or lo) / 8_388_608f
        }
        C.ENCODING_PCM_32BIT -> buf.getInt(index) / 2_147_483_648f
        else -> buf.getShort(index) / 32_768f
    }

    /** 相对写入（自动前进 position，配合 flip() 定界） */
    private fun writeSample(buf: ByteBuffer, v: Float) {
        val x = if (v.isFinite()) v.coerceIn(-1f, 1f) else 0f
        when (encoding) {
            C.ENCODING_PCM_FLOAT -> buf.putFloat(x)
            C.ENCODING_PCM_24BIT -> {
                val i = (x * 8_388_607f).toInt().coerceIn(-8_388_608, 8_388_607)
                buf.put((i and 0xFF).toByte())
                buf.put(((i shr 8) and 0xFF).toByte())
                buf.put(((i shr 16) and 0xFF).toByte())
            }
            C.ENCODING_PCM_32BIT -> buf.putInt((x * 2_147_483_647f).toInt())
            else -> buf.putShort((x * 32_767f).toInt().coerceIn(-32_768, 32_767).toShort())
        }
    }

    private fun encodingName(enc: Int): String = when (enc) {
        C.ENCODING_PCM_16BIT -> "16bit"
        C.ENCODING_PCM_24BIT -> "24bit"
        C.ENCODING_PCM_32BIT -> "32bit"
        C.ENCODING_PCM_FLOAT -> "float"
        else -> "enc$enc"
    }
}
