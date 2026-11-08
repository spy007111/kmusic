package com.kmusic.apkhd.ui.tv

/**
 * TV 遥控 seek 的纯计算部分。
 *
 * 抽成 object 而不是直接写在 `onPreviewKeyEvent` 里，是为了能在单测里覆盖
 * 「时长未知」「越界」「正好到头」这些真机上很难逐个按键复现的分支。
 *
 * ## 为什么需要 [SeekStep.NeedsPlay]
 *
 * 冷启动恢复队列时 `autoPlay=false`：播放位置有（上次断点 2:42），但
 * `durationMs` 还是 0。这时按右键如果按常规算 `pos ± 5000` 再除以 0，
 * 得到的是 `Infinity`/`NaN`，`onSeek` 拿到一个非法值 —— 表现就是
 * 「遥控器 seek 完全没反应」，日志里也看不出异常。
 *
 * 所以时长未知时明确返回 [SeekStep.NeedsPlay]，让 UI 层改成「先起播」。
 */
object TvSeek {

    /** 一次 seek 的决策结果 */
    sealed interface SeekStep {
        /** 时长未知：先让播放器起播，不要尝试跳转 */
        data object NeedsPlay : SeekStep

        /**
         * 可以跳转。
         * @param progress 目标进度（0f~1f），直接喂给 `onSeek`
         * @param targetMs 目标毫秒数，只用于提示文案
         */
        data class Jump(val progress: Float, val targetMs: Long) : SeekStep
    }

    /**
     * 算出按一次左右键应该跳到哪。
     *
     * @param positionMs 当前位置
     * @param durationMs 总时长（0 或负数代表「还不知道」）
     * @param deltaMs 负数是后退，正数是前进
     */
    fun step(positionMs: Long, durationMs: Long, deltaMs: Long): SeekStep {
        if (durationMs <= 0L) return SeekStep.NeedsPlay
        // 位置也要防脏数据：恢复快照可能比新时长还大（换了同名更短的版本）
        val from = positionMs.coerceIn(0L, durationMs)
        val target = (from + deltaMs).coerceIn(0L, durationMs)
        return SeekStep.Jump(
            progress = (target.toDouble() / durationMs.toDouble()).toFloat().coerceIn(0f, 1f),
            targetMs = target
        )
    }
}
