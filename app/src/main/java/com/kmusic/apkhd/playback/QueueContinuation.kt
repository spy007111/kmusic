package com.kmusic.apkhd.playback

/** 与 UI 无关的队列决策：便于覆盖删除和连续播放边界，避免只测「代码请求过了」。 */
object QueueContinuation {
    /**
     * 删除**前**队列里，删除后应当接管的曲目 id。
     *
     * 注意不要在删除前用 [focusAfterRemoval] 的下标去查旧队列 —— 那是「删除后」的下标，
     * 在旧队列里正好指向被删的那一首（节点即将消失，requestFocus 永远失败，
     * 表现就是删完失焦）。这里直接按旧队列取：原位置下一首，末项则上一首。
     */
    fun focusTargetIdAfterRemoval(queueIds: List<String>, removedIndex: Int): String? {
        if (removedIndex !in queueIds.indices || queueIds.size <= 1) return null
        return queueIds.getOrNull(removedIndex + 1) ?: queueIds.getOrNull(removedIndex - 1)
    }

    /** 删除后的焦点：原位置下一首；删除末项则上一首；删空返回 null。 */
    fun focusAfterRemoval(removedIndex: Int, remainingCount: Int): Int? =
        if (remainingCount <= 0) null else removedIndex.coerceIn(0, remainingCount - 1)

    /**
     * 顺序队列里「当前曲之后第一个还没铺进 timeline 的」下标；已到队尾或非法位置返回 null。
     *
     * ⚠️ 必须**向后扫描**，不能只看 `current+1`。
     *
     * v2.4.6 及以前只返回 `current+1`，那一首一旦已经在 timeline 里就直接返回 null ——
     * 于是「预取已经把下一首铺进去了」反而被当成「没有下一首可铺」，
     * 播到当前曲末尾就没人接管，播放器停在 STATE_ENDED：进度条走满、按钮变回播放三角、
     * 没有任何报错。这正是 v2.4.7 用户报的「听了几首歌又停住了」。
     *
     * 顺序模式仍然不绕回队首（播完即停），绕回只由 LOOP/SHUFFLE 的 repeatMode 负责。
     */
    fun nextOutsideWindow(queueSize: Int, currentQueueIndex: Int, timelineIds: List<String>, queueIds: List<String>): Int? {
        if (queueSize <= 0 || currentQueueIndex !in 0 until queueSize || queueIds.size != queueSize) return null
        val laid = timelineIds.toSet()
        for (i in currentQueueIndex + 1 until queueSize) {
            if (queueIds[i] !in laid) return i
        }
        return null
    }

    /**
     * 播放器停在末尾（STATE_ENDED）时，队列里应当接着播的下标。
     *
     * 顺序模式走到队尾返回 null（由调用方提示「队列已播完」）；
     * 不在这里做循环回绕 —— 列表循环/随机本来就由 ExoPlayer 的 repeatMode 负责，
     * 轮不到这层兜底。
     */
    fun nextToPlayAfterEnd(currentQueueIndex: Int, queueSize: Int): Int? {
        if (queueSize <= 0) return null
        val next = currentQueueIndex + 1
        return next.takeIf { it in 0 until queueSize }
    }
}
