package com.kmusic.apkhd.playback

/**
 * 音效链状态判定。
 *
 * 界面上只写一行字，回答的必须是用户真正关心的问题：**「我听到的效果生效了吗？
 * 没生效是为什么？」** —— 而不是内部概念（池位、session、variant）。
 *
 * v2.3.5 换掉系统音效之后，状态空间塌缩了：不再有「池位被占」「暂停已归还」
 * 这两种病态（处理器就在 PCM 管线上，既不排队也不交还）。剩下的只有四种：
 * 真在改声音、被主动旁路（省电 / DSD）、还没起播、什么都没开。
 *
 * 判定抽成纯函数，分支能被单测钉死，界面只负责翻译成文案。
 */
sealed interface FxStatus {
    /**
     * 音效正在处理。
     *
     * @param sampleRateHz 输出采样率（未知为 null）
     * @param headroomDb 让给 EQ 正增益峰值的前级余量（0 = 没让）
     * @param loudnessAppliedDb 实际生效的前级增益（设定值可能被余量抵消）
     * @param loudnessRequestedDb 用户设定的响度
     */
    data class Active(
        val sampleRateHz: Int?,
        val headroomDb: Float,
        val loudnessAppliedDb: Float,
        val loudnessRequestedDb: Float
    ) : FxStatus

    /** 有配置但被主动旁路 */
    data class Bypassed(val reason: Reason) : FxStatus {
        enum class Reason {
            /** 省电播放：PCM 直通 DSP */
            OFFLOAD,
            /** 纯净 PCM 没有挂 EQ，不伪装成硬件省电。 */
            PURE_PCM,
            /** DSD 解码占满 CPU，自动旁路 */
            DSD,
            /** 三项全关（不该走到这里，兜底用） */
            ALL_OFF
        }
    }

    /**
     * 用户开着音效，但处理器还没接进 PCM 管线（还没起播）。
     *
     * 这不是「没生效」，只是没有音频流过 —— 一按播放立刻生效。
     * 旧版本这里报的是「池位为 0」或「暂停已归还」，可界面上的开关明明是「开」，
     * 文案和开关自相矛盾。
     */
    data object Waiting : FxStatus

    /** 用户什么都没开 */
    data object Idle : FxStatus

    companion object {
        /**
         * @param offload 省电播放是否开启
         * @param dsdPlaying 正在播 DSD
         * @param dsdBypassEq 用户允许 DSD 时旁路
         * @param wanted 用户配置里有几项开着（0..3）
         * @param attached 处理器是否已接进管线（[EqProcessor.currentSampleRate] > 0）
         */
        fun resolve(
            offload: Boolean,
            dsdPlaying: Boolean,
            dsdBypassEq: Boolean,
            wanted: Int,
            attached: Boolean,
            sampleRateHz: Int? = null,
            headroomDb: Float = 0f,
            loudnessAppliedDb: Float = 0f,
            loudnessRequestedDb: Float = 0f,
            pureAudioMode: Boolean = false
        ): FxStatus = when {
            pureAudioMode -> Bypassed(Bypassed.Reason.PURE_PCM)
            offload -> Bypassed(Bypassed.Reason.OFFLOAD)
            dsdPlaying && dsdBypassEq -> Bypassed(Bypassed.Reason.DSD)
            wanted == 0 -> Idle
            !attached -> Waiting
            else -> Active(
                sampleRateHz = sampleRateHz,
                headroomDb = headroomDb,
                loudnessAppliedDb = loudnessAppliedDb,
                loudnessRequestedDb = loudnessRequestedDb
            )
        }
    }
}
