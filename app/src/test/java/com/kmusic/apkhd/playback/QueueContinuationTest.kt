package com.kmusic.apkhd.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueContinuationTest {
    @Test fun `删除中间项焦点去原来的下一首`() =
        assertEquals(2, QueueContinuation.focusAfterRemoval(2, 5))

    @Test fun `删除末项焦点去新的末项`() =
        assertEquals(4, QueueContinuation.focusAfterRemoval(5, 5))

    @Test fun `删空后没有列表焦点`() =
        assertNull(QueueContinuation.focusAfterRemoval(0, 0))

    @Test fun `播到铺窗末端时补下一首而不是重复已有项`() {
        val ids = (0 until 12).map { "id$it" }
        assertEquals(6, QueueContinuation.nextOutsideWindow(12, 5, ids.take(6), ids))
    }

    @Test fun `真实末曲不循环补队首`() {
        val ids = (0 until 3).map { "id$it" }
        assertNull(QueueContinuation.nextOutsideWindow(3, 2, ids, ids))
    }

    // ── v2.4.7：「听了几首歌定住」的根因回归 ──
    //
    // 旧实现只看 current+1，那一首已在 timeline 里就返回 null ——
    // 「预取已经铺好了下一首」反而被当成「没有下一首要铺」，播到末尾静默停住。

    @Test fun `下一首已在timeline时必须往后找而不是放弃`() {
        val ids = (0 until 8).map { "id$it" }
        // 当前播 id4；id5、id6 都已铺过，id7 还没 → 应该补 id7
        assertEquals(7, QueueContinuation.nextOutsideWindow(8, 4, ids.take(7), ids))
    }

    @Test fun `后面全铺过就返回null不绕回`() {
        val ids = (0 until 8).map { "id$it" }
        assertEquals(null, QueueContinuation.nextOutsideWindow(8, 4, ids, ids))
    }

    @Test fun `timeline有重复项也不影响向后扫描`() {
        val ids = (0 until 6).map { "id$it" }
        // timeline 里 id2 出现两次（实测 21 项 / 队列 20 首的重复追加），
        // 当前播 id3，后面 id4 已铺、id5 没铺 → 应补 id5
        val timeline = listOf("id0", "id1", "id2", "id2", "id3", "id4")
        assertEquals(5, QueueContinuation.nextOutsideWindow(6, 3, timeline, ids))
        // 用重复项把「当前+1」占住，仍要往后找到 id3
        assertEquals(3, QueueContinuation.nextOutsideWindow(6, 0, listOf("id1", "id1", "id2"), ids))
    }

    @Test fun `ENDED兜底给出队列下一首`() {
        assertEquals(4, QueueContinuation.nextToPlayAfterEnd(3, 10))
    }

    @Test fun `ENDED兜底在队尾返回null表示播完`() {
        assertNull(QueueContinuation.nextToPlayAfterEnd(9, 10))
    }

    @Test fun `ENDED兜底空队列不崩`() {
        assertNull(QueueContinuation.nextToPlayAfterEnd(0, 0))
    }

    // v2.4.4：删除前必须在**旧队列**里取接管目标。
    // 旧实现用 focusAfterRemoval 的「删除后下标」去查旧队列，删中间项时正好指向被删那首，
    // requestFocus 打在一个即将消失的节点上 → 删完必然失焦（TVQDEL 实测 wanted == removed id）。
    @Test fun `删除中间项接管旧队列里的下一首`() =
        assertEquals("id2", QueueContinuation.focusTargetIdAfterRemoval(
            listOf("id0", "id1", "id2", "id3"), 1))

    @Test fun `删除末项接管旧队列里的上一首`() =
        assertEquals("id1", QueueContinuation.focusTargetIdAfterRemoval(
            listOf("id0", "id1", "id2"), 2))

    @Test fun `只剩一首时没有可接管目标`() =
        assertNull(QueueContinuation.focusTargetIdAfterRemoval(listOf("only"), 0))

    @Test fun `越界下标不接管`() =
        assertNull(QueueContinuation.focusTargetIdAfterRemoval(emptyList(), 0))
}
