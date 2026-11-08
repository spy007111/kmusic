package com.kmusic.apkhd.net

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GD 取链结果的音质判定。
 *
 * 为什么值得单独钉：以前 `gdResolved()` 只判断「url 非空」就写死
 * `qualityLabel = "无损"`，而域名又指着恒 401 的 `music.gdstudio.org` ——
 * 实际播的是 320k，界面显示无损。等于全程在骗用户。
 *
 * 现在标签一律由**响应里的 `br`** 决定，所以这里的边界值必须钉死。
 */
class GdQualityLabelTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @Test
    fun `真实无损档位全部判为无损`() {
        // 实测 FLAC 的 br 分布：786 / 918 / 1013 / 1546 / 2832 …
        for (br in listOf(786, 900, 1013, 1546, 2832)) {
            assertEquals("br=$br 应判无损", "无损", GdStudioClient.labelOf(br))
            assertTrue("br=$br 应 isLossless", GdLink("u", br, 1).isLossless)
        }
    }

    @Test
    fun `有损档位不得标成无损`() {
        assertEquals("320k", GdStudioClient.labelOf(320))
        assertEquals("320k", GdStudioClient.labelOf(321))   // 服务端偶尔回 321
        assertFalse(GdLink("u", 320, 1).isLossless)

        assertEquals("128k", GdStudioClient.labelOf(128))
        assertFalse(GdLink("u", 128, 1).isLossless)
    }

    @Test
    fun `700 是分界值`() {
        assertEquals("无损", GdStudioClient.labelOf(700))
        assertEquals("320k", GdStudioClient.labelOf(699))
    }

    @Test
    fun `解析真实取链响应`() {
        val body = """{"url":"https://m801.music.126.net/x.flac","br":918,"size":48614400}"""
        val d = json.decodeFromString<GdUrl>(body)
        assertEquals(918, d.br)
        assertEquals(48614400L, d.size)
        assertTrue(GdLink(d.url!!, d.br, d.size).isLossless)
    }

    @Test
    fun `解析空响应不算命中`() {
        val d = json.decodeFromString<GdUrl>("""{"url":"","br":0,"size":0}""")
        assertTrue(d.url.isNullOrEmpty())
        assertEquals("低音质", GdStudioClient.labelOf(d.br))
    }

    @Test
    fun `搜索结果的扁平结构与歌单结构不混用`() {
        // search 返回 artist 数组 + album 字符串；playlist 返回 ar/al 对象。
        // 两者混用会静默解析出空歌名/空歌手（见 GdPlaylistParseTest 的注释）。
        val search = """[{"id":"186016","name":"晴天","artist":["周杰伦"],"album":"叶惠美"}]"""
        val list = json.decodeFromString<List<GdTrack>>(search)
        assertEquals(1, list.size)
        assertEquals("晴天", list[0].name)
        assertEquals(listOf("周杰伦"), list[0].artist)
    }
}
