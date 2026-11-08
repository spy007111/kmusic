package com.moyin.player.net

import com.kmusic.apkhd.net.KuwoClient
import com.kmusic.apkhd.net.ResolvedSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 酷我「请求档位 ≠ 实际音质」的分级判定（v2.4.10）。
 *
 * 这组测试钉的是**线上真实发生过的骗局**：请求 `20900kmflac`（臻品母带），
 * 酷我回 `{"format":"mp3","bitrate":128,"duration":211}` —— 完整时长、可用直链、
 * 不报错。旧代码只看「非 null」，于是界面挂「臻品母带」、耳朵听 128k mp3。
 *
 * 所以判据必须是**响应里的 format + bitrate**，不是请求参数。
 */
class KuwoQualityRankTest {

    @Test
    fun `母带格式判母带`() {
        assertEquals(KuwoClient.RANK_MASTER, KuwoClient.rankOf("mflac", 20900))
        assertEquals(KuwoClient.RANK_MASTER, KuwoClient.rankOf("MFLAC", 20900))
        // mgg：另一套母带封装（部分曲目走这个），同样算母带
        assertEquals(KuwoClient.RANK_MASTER, KuwoClient.rankOf("mgg", 24000))
    }

    @Test
    fun `flac 判无损 ape 也算`() {
        assertEquals(KuwoClient.RANK_FLAC, KuwoClient.rankOf("flac", 2000))
        assertEquals(KuwoClient.RANK_FLAC, KuwoClient.rankOf("ape", 1000))
        // 无损格式不看到码率，只要格式对就算（800kbps 的 flac 仍是真无损）
        assertEquals(KuwoClient.RANK_FLAC, KuwoClient.rankOf("flac", 800))
    }

    @Test
    fun `有损按码率分两档`() {
        assertEquals(KuwoClient.RANK_HIGH, KuwoClient.rankOf("mp3", 320))
        assertEquals(KuwoClient.RANK_HIGH, KuwoClient.rankOf("mp3", 256))
        assertEquals(KuwoClient.RANK_LOSSY, KuwoClient.rankOf("mp3", 192))
        assertEquals(KuwoClient.RANK_LOSSY, KuwoClient.rankOf("mp3", 128))
        assertEquals(KuwoClient.RANK_LOSSY, KuwoClient.rankOf("aac", 48))
        // ogg 300kbps 落在 HIGH（≥256），比 320k mp3 差一点点，
        // 但四档分级只用来判「够不够无损」，不影响只升不降的正确性
        assertEquals(KuwoClient.RANK_HIGH, KuwoClient.rankOf("ogg", 300))
    }

    @Test
    fun `未知格式一律当没拿到`() {
        assertEquals(KuwoClient.RANK_NONE, KuwoClient.rankOf(null, 320))
        assertEquals(KuwoClient.RANK_NONE, KuwoClient.rankOf("", 320))
        assertEquals(KuwoClient.RANK_NONE, KuwoClient.rankOf("xyz", 999))
        assertEquals(KuwoClient.RANK_NONE, KuwoClient.rankOf("mp4", 128))
    }

    @Test
    fun `请求母带却回 128k mp3 必须判降档`() {
        val r = ResolvedSong(
            rid = "611237221",
            url = "http://kw-er.kuwo.cn/xxx",
            br = "20900kmflac",   // 请求的档位：母带
            durationMs = 211_000,
            ekey = null,
            format = "mp3",       // 实际给的：128k mp3
            kbps = 128
        )
        assertEquals(KuwoClient.RANK_LOSSY, r.rank)
        assertFalse("拿到的不是母带", r.rank >= KuwoClient.RANK_MASTER)
    }

    @Test
    fun `真母带响应判母带`() {
        val r = ResolvedSong(
            rid = "95769",
            url = "http://kw-lv.kuwo.cn/xxx",
            br = "20900kmflac",
            durationMs = 320_000,
            ekey = "AAAA",
            format = "mflac",
            kbps = 20900
        )
        assertEquals(KuwoClient.RANK_MASTER, r.rank)
        assertTrue(r.rank >= KuwoClient.RANK_MASTER)
    }

    @Test
    fun `标签必须跟着实际格式走`() {
        assertEquals("臻品母带", KuwoClient.labelOf("mflac", 20900))
        assertEquals("无损 FLAC", KuwoClient.labelOf("flac", 2000))
        assertEquals("320k", KuwoClient.labelOf("mp3", 320))
        assertEquals("128kbps", KuwoClient.labelOf("mp3", 128))
        assertEquals("48kbps", KuwoClient.labelOf("aac", 48))
        // 没格式就不给标签 —— 宁可界面上空着，也不编一个
        assertEquals(null, KuwoClient.labelOf(null, 320))
    }

    @Test
    fun `只升不降的判据是四级分档`() {
        // 母带 > 无损 > 320k > 128k，严格单调
        val ranks = listOf(
            KuwoClient.rankOf("mflac", 20900),
            KuwoClient.rankOf("flac", 2000),
            KuwoClient.rankOf("mp3", 320),
            KuwoClient.rankOf("mp3", 128)
        )
        assertEquals(ranks, ranks.sortedDescending())
        assertEquals(ranks.distinct(), ranks)
    }
}
