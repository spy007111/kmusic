package com.kmusic.apkhd.data

/**
 * 音源目录：id / 显示名 / 设置页说明 / 默认开关。
 *
 * [ALL] 的顺序 = 搜索结果的拼接优先级，也是同曲去重时「保留谁」的依据。
 * [ONLINE] 是在线源取链的尝试顺序：酷我 → 网易云 → 咪咕。
 *
 * 不再有「音源开关」——所有源始终可用，按优先级自动取链/回退。
 */
data class SourceDef(
    val id: String,
    val label: String,
    val hint: String
)

object SourceCatalog {

    const val LOCAL = "local"
    const val NAVIDROME = "navidrome"
    const val KUWO = "kuwo"
    const val NETEASE = "netease"
    const val MIGU = "migu"

    /** 全量音源（顺序即优先级） */
    val ALL: List<SourceDef> = listOf(
        SourceDef(LOCAL, "本地音乐", "手机内的音频文件"),
        SourceDef(NAVIDROME, "Navidrome", "自建曲库 · Subsonic 协议"),
        SourceDef(KUWO, "酷我", "母带 / 无损 / 320k"),
        SourceDef(NETEASE, "网易云", "GD 无损通道 · 失败回退酷我"),
        SourceDef(MIGU, "咪咕", "官方接口 · 免登录 128k")
    )

    fun label(id: String): String = ALL.firstOrNull { it.id == id }?.label ?: id

    /** 在线源取链优先级：酷我（音质最好）→ 网易云 → 咪咕（保底能出声） */
    val ONLINE: List<String> = listOf(KUWO, NETEASE, MIGU)
}
