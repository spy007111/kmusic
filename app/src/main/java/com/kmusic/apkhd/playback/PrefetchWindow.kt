package com.kmusic.apkhd.playback

object PrefetchWindow {
    data class Targets(val next: Int, val following: Int?)

    fun targets(size: Int, current: Int, next: Int, loop: Boolean = true): Targets? {
        if (size <= 1 || next !in 0 until size || next == current) return null
        val candidate = next + 1
        val following = if (candidate < size) candidate else if (loop) 0 else null
        return Targets(next, following?.takeIf { it != current && it != next })
    }

    fun nextIndex(size: Int, current: Int, loop: Boolean): Int {
        if (size <= 0 || current !in 0 until size) return -1
        return if (current + 1 < size) current + 1 else if (loop) 0 else -1
    }
}
