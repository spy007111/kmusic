package com.kmusic.apkhd.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrefetchWindowTest {

    @Test fun `正常队列预取下一首并预热点二首`() {
        val t = PrefetchWindow.targets(size = 20, current = 3, next = 4)
        assertEquals(4, t!!.next)
        assertEquals(5, t.following)
    }

    @Test fun `随机模式的下一首不是当前加一也能正确预热点二首`() {
        val t = PrefetchWindow.targets(size = 20, current = 3, next = 11)
        assertEquals(11, t!!.next)
        assertEquals(12, t.following)
    }

    @Test fun `队尾的下一首回到队首`() {
        val t = PrefetchWindow.targets(size = 10, current = 9, next = 0)
        assertEquals(0, t!!.next)
        assertEquals(1, t.following)
    }

    @Test fun `点二首绕回当前曲时不再预取`() {
        // size=10, current=9, next=8 → +2 = 9 就是当前曲，不能再热一次
        val t = PrefetchWindow.targets(size = 10, current = 9, next = 8)
        assertEquals(8, t!!.next)
        assertNull(t.following)
    }

    @Test fun `两首队列只预取下一首不预热点二首`() {
        val t = PrefetchWindow.targets(size = 2, current = 0, next = 1)
        assertEquals(1, t!!.next)
        assertNull(t.following)
    }

    @Test fun `单曲队列没有可预取对象`() {
        assertNull(PrefetchWindow.targets(size = 1, current = 0, next = 0))
        assertNull(PrefetchWindow.targets(size = 0, current = -1, next = -1))
    }

    @Test fun `下一首就是当前曲时不预取`() {
        assertNull(PrefetchWindow.targets(size = 5, current = 2, next = 2))
    }
    @Test fun orderModeStopsAtTail() {
        assertEquals(-1, PrefetchWindow.nextIndex(10, 9, loop = false))
        assertEquals(0, PrefetchWindow.nextIndex(10, 9, loop = true))
    }
    @Test fun orderModeDoesNotWarmQueueHeadPastTail() {
        val t = PrefetchWindow.targets(10, 8, 9, loop = false)
        assertEquals(9, t!!.next)
        assertNull(t.following)
    }
}
