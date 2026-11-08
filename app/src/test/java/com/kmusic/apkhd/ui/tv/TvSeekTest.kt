package com.kmusic.apkhd.ui.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TV 遥控 seek 的决策逻辑。
 *
 * 这些分支真机很难逐个按键复现 —— 尤其是「时长未知」那条：它只有在
 * 「冷启动恢复队列、autoPlay=false」这个很窄的时序窗口里出现，
 * 而且表现得和「按键没被处理」一模一样（都是屏幕没反应）。
 * 所以钉在纯函数上，而不是靠上机戳。
 */
class TvSeekTest {

    private val dur = 242_000L   // 4:02

    @Test
    fun `进度按 5 秒前进`() {
        val r = TvSeek.step(positionMs = 60_000L, durationMs = dur, deltaMs = 5_000L)
        assertTrue(r is TvSeek.SeekStep.Jump)
        r as TvSeek.SeekStep.Jump
        assertEquals(65_000L, r.targetMs)
    }

    @Test
    fun `进度按 5 秒后退`() {
        val r = TvSeek.step(positionMs = 60_000L, durationMs = dur, deltaMs = -5_000L) as TvSeek.SeekStep.Jump
        assertEquals(55_000L, r.targetMs)
    }

    @Test
    fun `后退不会小于 0`() {
        // 位置在 2 秒，按一次左键（-5 秒）→ 停在 0，不能变成负数
        val r = TvSeek.step(positionMs = 2_000L, durationMs = dur, deltaMs = -5_000L) as TvSeek.SeekStep.Jump
        assertEquals(0L, r.targetMs)
        assertEquals(0f, r.progress, 1e-6f)
    }

    @Test
    fun `前进不会超过总时长`() {
        val r = TvSeek.step(positionMs = dur - 1_000L, durationMs = dur, deltaMs = 5_000L) as TvSeek.SeekStep.Jump
        assertEquals(dur, r.targetMs)
        assertEquals(1f, r.progress, 1e-6f)
    }

    @Test
    fun `时长未知时要求先起播`() {
        // ⚠️ 这是 v2.4.0 实测踩到的坑：冷启动恢复队列时 durationMs=0，
        // 旧写法直接 (pos+delta)/0 → Infinity → onSeek 收到非法值 → 屏幕无反应
        assertEquals(
            TvSeek.SeekStep.NeedsPlay,
            TvSeek.step(positionMs = 162_666L, durationMs = 0L, deltaMs = 5_000L)
        )
    }

    @Test
    fun `时长是负数也视为未知`() {
        // media3 在某些错误态下会回 -1
        assertEquals(
            TvSeek.SeekStep.NeedsPlay,
            TvSeek.step(positionMs = 0L, durationMs = -1L, deltaMs = 5_000L)
        )
    }

    @Test
    fun `位置比时长还大时先夹回时长`() {
        // 恢复快照存的是 4:10，但当前这首只有 4:02（同名不同版本）
        val r = TvSeek.step(positionMs = 250_000L, durationMs = dur, deltaMs = 5_000L) as TvSeek.SeekStep.Jump
        assertEquals(dur, r.targetMs)
    }

    @Test
    fun `进度永远是 0 到 1`() {
        for (pos in listOf(-10_000L, 0L, dur / 2, dur, dur + 10_000L)) {
            for (d in listOf(-99_000L, -5_000L, 0L, 5_000L, 99_000L)) {
                val r = TvSeek.step(pos, dur, d)
                if (r is TvSeek.SeekStep.Jump) {
                    assertTrue("progress=${r.progress} pos=$pos d=$d", r.progress in 0f..1f)
                }
            }
        }
    }
}
