package com.kmusic.apkhd.playback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.kmusic.apkhd.data.DiagLog

/**
 * 音效链（v2.3.5 起）。
 *
 * ## 这一版到底改了什么
 *
 * 旧实现（`AudioEffectHolder`，1,073 行）是在跟**系统音效池**搏斗：
 * 用 `DynamicsProcessing` 把三项塞进 1 个池位、暂停 15 秒归还、起播再抢回来、
 * 抢不到就退避重试、ROM 只给 1 个槽时前一项把后一项饿死……
 * 那一整套复杂度（池位、重试风暴、变体探测、回退链）全是为了绕开一个
 * 「处理器必须住在 AudioFlinger 里」的前提。
 *
 * v2.3.5 把这个前提扔了：音效改跑 media3 的 `AudioProcessor` 链，
 * 就在本进程解码后的 PCM 上算（见 [EqProcessor]）。于是：
 *  - **没有池位**：不再有「开均衡器把低音饿死」，也不需要在暂停时交还什么；
 *  - **没有重试**：建不出来这件事不存在了，`-3` / `variant 0` 这类 ROM 差异一起消失；
 *  - **没有生命周期**：切歌、暂停、前后台都不用重建，参数一写立刻生效。
 *
 * 这个类只做三件事：**存**用户配置、**算**曲线/增益、**推**给处理器。
 */
object FxChain {
    /**
     * 前级增益范围（dB）。
     *
     * 下限 −24：单段最高 ±15 dB，十段叠起来峰值能到 20 dB 以上；卡在旧的 −12
     * 会让超出的部分直接怼进限幅器（实测 23.7 dB 余量只让出 12 dB，剩下靠削波，
     * 听感是低频发糊）。上限 +12 是纯响度增益的合理天花板。
     *
     * 界面和处理器共用这一对常量 —— 两处各写一遍必然漂移。
     */
    const val INPUT_GAIN_MIN_DB = -24f
    const val INPUT_GAIN_MAX_DB = 12f

    var truePeakEnabled: Boolean = false
        private set
    var truePeakCeilingDb: Float = -1f
        private set
    var devicePreGainDb: Float = 0f
        private set

    fun configureTruePeak(enabled: Boolean, ceilingDb: Float = -1f) {
        val ceiling = TruePeakLimiter.sanitizeCeiling(ceilingDb)
        if (truePeakEnabled == enabled && truePeakCeilingDb == ceiling) return
        truePeakEnabled = enabled
        truePeakCeilingDb = ceiling
        push()
    }

    fun configureDevicePreGain(db: Float) {
        val next = if (db.isFinite()) db.coerceIn(-24f, 0f) else 0f
        if (devicePreGainDb == next) return
        devicePreGainDb = next
        push()
    }

    // ── 用户配置（单一真相） ──
    /** 均衡器开关 */
    var enabled: Boolean = false
        private set

    /** 当前预设下标（[EqPresets] 的下标，全局唯一） */
    var presetIndex: Int = 0
        private set

    /** 用户是否拖过曲线（true 时曲线来自 [manualLevels] 而不是预设表） */
    var customBands: Boolean = false
        private set

    /** 手动改过的段 → 增益（mB）。只记改过的段，没改的仍跟随预设 */
    private var manualLevels: Map<Int, Int> = emptyMap()

    /** 低音增强 0..1000 */
    var bassStrength: Int = 0
        private set

    /** 响度增强 0..1500（mB，即 0..15 dB） */
    var loudnessGain: Int = 0
        private set

    /** DSD 播放时是否已旁路（由播放服务写） */
    @Volatile
    private var bypassedByDsd: Boolean = false

    /** 省电播放（offload）—— 直通 DSP，处理器收不到 PCM */
    @Volatile
    var offloadActive: Boolean = false
        private set

    /** 纯净 PCM 不挂 EQ；这是独立偏好，不代表硬件 offload 生效。 */
    @Volatile
    var pureAudioModeActive: Boolean = false
        private set

    /**
     * 每次参数真正变化就 +1。
     *
     * 界面拿它当「刷新」信号：音效状态不是 Compose State（它在音频线程被读），
     * 靠这个计数器触发重组，避免把整个处理器做成 snapshot 对象。
     */
    var version by mutableIntStateOf(0)
        private set

    /** 上次推给处理器的采样率（Hz），0 = 还没接进管线 */
    val activeSampleRate: Int get() = EqProcessor.instance.currentSampleRate

    init {
        // 处理器上链 / 下链时把采样率同步给界面。
        // 它跑在音频线程，而 version 是 Compose State —— 必须切回主线程写，
        // 否则会在音频线程上触发一次全局快照，白白卡住几毫秒的音频。
        EqProcessor.instance.onFormatChanged = {
            android.os.Handler(android.os.Looper.getMainLooper()).post { version++ }
        }
    }

    // ── 对外查询 ──

    /** 预设名（内置 12 套） */
    fun presetNames(): List<String> = EqPresets.names()

    /** 十段中心频率（Hz） */
    fun bandFreqs(): IntArray = EqBands.FREQS.copyOf()

    /** 可调范围，[最小 mB, 最大 mB] */
    fun bandRange(): IntArray = intArrayOf(EqProcessor.LEVEL_MIN, EqProcessor.LEVEL_MAX)

    /**
     * 当前十段增益（mB）—— **只看均衡器曲线**，不含低音。
     *
     * 低音是独立的低架滤波器，不叠进曲线：叠进去会让界面显示一根骗人的
     * 「60Hz +9dB」，而用户其实没动过那个圆点。
     */
    fun bandLevels(): IntArray {
        val table = EqPresets.gainsOf(presetIndex)
        return if (customBands) {
            // 第一次从预设切到自定义时，manualLevels 只有刚拖的那一段。
            // 直接补 0 会把其余九段打成 0 dB —— 用户只想加一点 62Hz，
            // 结果整条曲线塌成一条斜线（旧版实测过）。缺的段回落到当前预设值。
            IntArray(EqBands.COUNT) { b -> manualLevels[b] ?: table.getOrElse(b) { 0 } }
        } else {
            IntArray(EqBands.COUNT) { b -> table.getOrElse(b) { 0 } }
        }
    }

    /** 任一音效项开着 */
    fun fxWanted(): Boolean = enabled || bassStrength > 0 || loudnessGain > 0

    /** 用户开着几项（0..3）—— 界面用它判断「全关」 */
    val wantedCount: Int
        get() = (if (enabled) 1 else 0) + (if (bassStrength > 0) 1 else 0) +
            (if (loudnessGain > 0) 1 else 0)

    /**
     * 上一次推参数时算出的前级余量（dB）。
     *
     * ⚠️ 不能读 [EqProcessor.headroomDb]：那个读的是处理器**已经生效**的曲线，
     * 而这里要算的是「马上要生效的那条」。首轮 push 时处理器还是全零曲线，
     * 从它读余量会得到 0 —— 于是 +12dB 低频配上不减的前级，一起撞进限幅器。
     * 界面也用同一个值，保证「显示的余量」和「实际扣的余量」是同一个数。
     */
    @Volatile
    var lastHeadroomDb: Float = 0f
        private set

    /** 让给 EQ 的前级余量（dB）—— 最近一次推参数时的值 */
    fun headroomDb(): Float = lastHeadroomDb

    /**
     * 实际生效的前级增益（dB）= 响度 − 曲线正增益峰值。
     *
     * 只扣正增益、负增益不补（补了等于偷着加响度，用户看到的 dB 就不作数）。
     * **响度关着也照扣**（v2.3.4 定下的规则）：只开 EQ 时把 31Hz 拉满 +12dB
     * 一样会顶到限幅器，不扣就变成低频发糊、整体发闷 —— 用户会以为
     * 「低音增强就是这个声音」。
     */
    fun inputGainDb(): Float {
        val loud = loudnessGain / 100f
        val peak = lastHeadroomDb
        return (loud - peak + devicePreGainDb).coerceIn(INPUT_GAIN_MIN_DB, INPUT_GAIN_MAX_DB)
    }

    /** 实际旁路原因由运行态报告；三种条件仅合成透明通过。 */
    internal fun shouldBypass(dsd: Boolean, offload: Boolean, pure: Boolean): Boolean = dsd || offload || pure

    // ── 用户动作 ──

    /** 开关均衡器（保留预设/曲线，不动低音与响度） */
    fun configure(on: Boolean, presetIndex: Int) {
        this.enabled = on
        val idx = presetIndex.coerceIn(0, EqPresets.builtInCount - 1)
        // ⚠️ 只有真的换了预设才丢掉自定义曲线。
        // 旧实现先赋值再比较，条件恒为 true —— 设置流每次重发都会清空用户拖好的曲线。
        if (idx != this.presetIndex) {
            this.presetIndex = idx
            customBands = false
            manualLevels = emptyMap()
        }
        push()
    }

    /** 一次设置两个增强参数。0 = 关闭该项 */
    fun configureEnhanced(bass: Int, loudness: Int) {
        bassStrength = bass.coerceIn(0, 1_000)
        loudnessGain = loudness.coerceIn(0, 1_500)
        push()
    }

    /**
     * 手动设置某一段（mB）。
     *
     * 会顺手打开均衡器并切到自定义 —— 用户拖了却听不到变化是最容易被当成「坏了」的情况。
     */
    fun setBandLevel(band: Int, levelMiliBel: Int) {
        if (band !in 0 until EqBands.COUNT) return
        val range = bandRange()
        val v = levelMiliBel.coerceIn(range[0], range[1])
        customBands = true
        enabled = true
        manualLevels = manualLevels + (band to v)
        push()
        DiagLog.log("EQ", "band[$band]=${EqBands.FREQS[band]}Hz → ${v / 100f} dB")
    }

    /** 回到预设模式，丢掉已自定义的段 */
    fun usePresetMode(index: Int) {
        presetIndex = index.coerceIn(0, EqPresets.builtInCount - 1)
        customBands = false
        manualLevels = emptyMap()
        enabled = true
        push()
    }

    /**
     * 直接套一条曲线（用户预设 / 从别处恢复）。
     *
     * 和 [usePresetMode] 的区别：那条走 `presetIndex`，这条把十段全写进 `manualLevels`
     * 并标记 custom —— 曲线是「用户自己的」，不该再被任何内置预设覆盖。
     */
    fun applyCurve(gains: IntArray, presetIndexHint: Int = -1) {
        val full = EqBands.normalize(gains)
        val range = bandRange()
        manualLevels = (0 until EqBands.COUNT).associateWith { b ->
            full[b].coerceIn(range[0], range[1])
        }
        customBands = true
        // 命中内置曲线就把下标对回去，界面顶部才会显示预设名而不是「自定义」
        val hit = EqPresets.builtIn().indexOfFirst { def -> def.gains.contentEquals(full) }
        presetIndex = when {
            presetIndexHint in 0 until EqPresets.builtInCount -> presetIndexHint
            hit >= 0 -> hit
            else -> presetIndex.coerceIn(0, EqPresets.builtInCount - 1)
        }
        enabled = true
        push()
        DiagLog.log("EQ", "套用曲线 ${full.joinToString(",")}（预设 #$presetIndex）")
    }

    /** 用户动了音效 —— 只有一个意思：立刻把参数推给处理器 */
    fun onUserEdited() {
        push()
    }

    /**
     * DSD 旁路。
     *
     * DSD 解码链本来就吃满 CPU，再叠十个 biquad 会挤掉音频线程 ——
     * 现在只是把处理器切成透传（一次拷贝，无浮点运算），不拆管线、不重建。
     */
    fun setDsdBypass(bypass: Boolean) {
        if (bypassedByDsd == bypass) return
        bypassedByDsd = bypass
        push()
    }

    /** 省电播放状态（服务启动时按设置写一次） */
    fun setOffload(on: Boolean) {
        if (offloadActive == on) return
        offloadActive = on
        push()
    }

    fun setPureAudioMode(on: Boolean) {
        if (pureAudioModeActive == on) return
        pureAudioModeActive = on
        push()
    }

    /**
     * 老版本存的 `eq_preset` 是**设备预设下标**（0..9），现在换成了内置表。
     *
     * 只迁移一次：拿 fx_history 里缓存的旧预设名做名字匹配（摇滚 → 摇滚），
     * 认不出来就落到「原声」。迁移完写标记，之后不再动用户的选择。
     */
    fun migratePresetIndex(oldIndex: Int): Int {
        val prefs = DiagLog.appContextOrNull()
            ?.getSharedPreferences("fx_history", 0) ?: return oldIndex.coerceIn(0, EqPresets.builtInCount - 1)
        if (prefs.getBoolean("preset_migrated_232", false)) {
            return oldIndex.coerceIn(0, EqPresets.builtInCount - 1)
        }
        val oldNames = prefs.getString("eq_presets", null)
            ?.split('\u0001')
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        val rawName = oldNames.getOrNull(oldIndex)?.trim().orEmpty()
        val mapped = presetNames().indexOfFirst { it == rawName }
            .takeIf { it >= 0 }
            ?: when {
                rawName.contains("流行") || rawName.contains("pop", true) -> 1
                rawName.contains("摇滚") || rawName.contains("rock", true) -> 2
                rawName.contains("古典") || rawName.contains("classical", true) -> 3
                rawName.contains("人声") || rawName.contains("vocal", true) -> 4
                rawName.contains("低音") || rawName.contains("bass", true) -> 5
                rawName.contains("高音") || rawName.contains("treble", true) -> 6
                else -> 0
            }
        prefs.edit().putBoolean("preset_migrated_232", true).apply()
        DiagLog.log("EQ", "预设迁移：设备预设 #$oldIndex（${rawName.ifBlank { "未知" }}）→ 内置 #$mapped")
        return mapped
    }

    /** 释放：进程退出时清空处理器参数（不影响别的 App，也不占任何系统资源） */
    fun release() {
        bypassedByDsd = false
        offloadActive = false
        pureAudioModeActive = false
        lastHeadroomDb = 0f
        truePeakEnabled = false
        truePeakCeilingDb = -1f
        devicePreGainDb = 0f
        EqProcessor.instance.applyParams(false, IntArray(EqBands.COUNT), 0, 0f, bypassed = false, truePeakEnabled = false)
        version++
    }

    // ── 推给处理器 ──

    /**
     * 把当前配置算成「处理器要的四个数」并一次性写进去。
     *
     * 顺序很重要：先算 headroom（要用曲线），再算前级增益（要用 headroom），
     * 最后一起写 —— 分三次写会让音频线程重算三遍系数，还可能读到中间态。
     */
    private fun push() {
        val gains = if (enabled) bandLevels() else IntArray(EqBands.COUNT)
        val proc = EqProcessor.instance
        // 余量必须按**这条**曲线算（含低音搁架），不能用处理器里那条旧的 —— 见 [lastHeadroomDb]。
        // 采样率还没接进管线时按 48k 估：31Hz 这类低频段的峰值对采样率不敏感，
        // 差几百 Hz 也只在小数点后动。
        val fs = proc.currentSampleRate.toFloat().takeIf { it > 0f } ?: 48_000f
        lastHeadroomDb = Biquad.peakBoostDb(gains, bassStrength, fs, EqProcessor.BASS_SHELF_HZ, EqProcessor.BASS_MAX_DB)
        val preGain = inputGainDb()
        proc.applyParams(
            enabled = enabled,
            gainsMb = gains,
            bassStrength = bassStrength,
            preGainDb = preGain,
            bypassed = shouldBypass(bypassedByDsd, offloadActive, pureAudioModeActive),
            truePeakEnabled = truePeakEnabled,
            truePeakCeilingDb = truePeakCeilingDb
        )
        version++
        DiagLog.log(
            "EQ",
            "推参数：eq=$enabled 曲线=${gains.joinToString(",")} " +
                "低音=$bassStrength 前级=${"%.1f".format(preGain)}dB " +
                "余量=${"%.1f".format(headroomDb())}dB " +
                "旁路=${proc.isBypassed}"
        )
    }
}
