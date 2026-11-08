package com.kmusic.apkhd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kmusic.apkhd.ui.components.MoyinIcon
import com.kmusic.apkhd.ui.icons.IconArtist
import com.kmusic.apkhd.ui.icons.IconBack
import com.kmusic.apkhd.ui.icons.IconChevronRight
import com.kmusic.apkhd.ui.icons.IconMusicNote
import com.kmusic.apkhd.ui.icons.IconPlaylist
import com.kmusic.apkhd.ui.icons.IconServer
import com.kmusic.apkhd.ui.theme.moyinColors
import com.kmusic.apkhd.ui.theme.wdp
import com.kmusic.apkhd.ui.theme.wsp
import com.kmusic.apkhd.ui.tv.tvFocus

/**
 * Navidrome（Subsonic 协议）曲库入口页。
 *
 * 曲库的连接状态、错误原因、三个分类的数量都摆在同一屏：
 * 以前连接失败只有 toast，用户回到这一页看到的是空列表，不知道是地址错、账号错还是网络不通。
 */
@Composable
fun NavidromeScreen(
    url: String,
    connected: Boolean,
    checking: Boolean,
    error: String?,
    trackCount: Int,
    albumCount: Int,
    artistCount: Int,
    playlistCount: Int,
    onOpenAlbums: () -> Unit,
    onOpenArtists: () -> Unit,
    onOpenPlaylists: () -> Unit,
    onReconnect: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
    /** 底部有没有迷你播放条：有的话列表要多留一条的高度 */
    bottomInsetDp: Int = 0,
    modifier: Modifier = Modifier
) {
    val c = moyinColors()
    val configured = url.isNotBlank()

    Column(
        modifier
            .fillMaxSize()
            .background(c.bg)
    ) {
        // 顶栏
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.wdp, vertical = 10.wdp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MoyinIcon(
                icon = IconBack,
                tint = c.t2,
                size = 40.wdp,
                modifier = Modifier
                    .clip(RoundedCornerShape(99.dp))
                    .tvFocus(shape = RoundedCornerShape(99.dp))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { onBack() }
                    .padding(10.wdp)
            )
            Spacer(Modifier.width(6.wdp))
            Column(Modifier.weight(1f)) {
                Text("Navidrome", color = c.t1, fontSize = 15.wsp)
                Text(
                    when {
                        !configured -> "未配置"
                        error != null -> "连接失败"
                        checking -> "正在连接…"
                        connected -> url
                        else -> url
                    },
                    color = c.t3,
                    fontSize = 11.wsp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (configured) {
                Text(
                    "刷新",
                    color = c.accent,
                    fontSize = 12.wsp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(99.dp))
                        .tvFocus(shape = RoundedCornerShape(99.dp))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { onRefresh() }
                        .padding(horizontal = 10.wdp, vertical = 6.wdp)
                )
            }
        }

        if (!configured) {
            // 没配置：给明确入口，别让用户对着空列表猜
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("还没有连接曲库", color = c.t1, fontSize = 15.wsp)
                Spacer(Modifier.height(6.wdp))
                Text("填上 Navidrome 地址和账号，就能浏览并播放自己的曲库", color = c.t3, fontSize = 12.wsp)
                Spacer(Modifier.height(16.wdp))
                PillButton("去设置", filled = true) { onOpenSettings() }
            }
            return@Column
        }

        // 状态卡：已连接显示曲目数；出错就把原因原样摆出来（HTTP 码 / 账号错 / 网络）
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.wdp)
                .clip(RoundedCornerShape(14.wdp))
                .background(c.s2)
                .padding(horizontal = 14.wdp, vertical = 12.wdp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (checking) {
                CircularProgressIndicator(color = c.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.wdp))
            } else {
                MoyinIcon(
                    icon = IconServer,
                    tint = if (connected) c.accent else c.t3,
                    size = 18.wdp
                )
            }
            Spacer(Modifier.width(10.wdp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        checking -> "正在连接…"
                        connected -> "已连接 · $trackCount 首"
                        else -> "连接失败"
                    },
                    color = c.t1,
                    fontSize = 13.wsp
                )
                if (error != null) {
                    Spacer(Modifier.height(3.wdp))
                    Text(error, color = c.t3, fontSize = 11.wsp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(10.wdp))
            PillButton(if (connected) "重连" else "重试", filled = !connected) { onReconnect() }
        }

        Spacer(Modifier.height(14.wdp))

        LazyColumn(contentPadding = PaddingValues(bottom = (24 + bottomInsetDp).wdp)) {
            item {
                NavEntry(IconMusicNote, "专辑", "$albumCount 张", connected, onOpenAlbums)
            }
            item {
                NavEntry(IconArtist, "歌手", "$artistCount 位", connected, onOpenArtists)
            }
            item {
                NavEntry(IconPlaylist, "播放列表", "$playlistCount 个", connected, onOpenPlaylists)
            }
            item {
                Column(Modifier.padding(horizontal = 16.wdp, vertical = 8.wdp)) {
                    Text(
                        "服务器设置（地址 / 账号 / 密码）在「我的 → Navidrome 曲库」里修改。",
                        color = c.t3,
                        fontSize = 11.wsp
                    )
                    Spacer(Modifier.height(10.wdp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.wdp)) {
                        PillButton("服务器设置") { onOpenSettings() }
                    }
                }
            }
        }
    }
}

@Composable
private fun NavEntry(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val c = moyinColors()
    Row(
        Modifier
            .fillMaxWidth()
            .tvFocus(fullWidth = true)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { if (enabled) onClick() }
            .padding(horizontal = 16.wdp, vertical = 11.wdp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(44.wdp)
                .clip(RoundedCornerShape(12.wdp))
                .background(c.s2),
            contentAlignment = Alignment.Center
        ) {
            MoyinIcon(icon = icon, tint = if (enabled) c.t2 else c.t3, size = 18.wdp)
        }
        Spacer(Modifier.width(12.wdp))
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) c.t1 else c.t3, fontSize = 14.wsp, maxLines = 1)
            Spacer(Modifier.height(2.wdp))
            Text(
                if (enabled) subtitle else "连接后可浏览",
                color = c.t3,
                fontSize = 11.wsp,
                maxLines = 1
            )
        }
        MoyinIcon(icon = IconChevronRight, tint = c.t3, size = 18.wdp)
    }
}

@Composable
private fun PillButton(text: String, filled: Boolean = false, onClick: () -> Unit) {
    val c = moyinColors()
    Text(
        text,
        color = if (filled) c.onAccent else c.t1,
        fontSize = 12.wsp,
        modifier = Modifier
            .clip(RoundedCornerShape(99.wdp))
            .background(if (filled) c.accentFill else c.s2)
            .tvFocus(shape = RoundedCornerShape(99.wdp))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
            .padding(horizontal = 14.wdp, vertical = 8.wdp)
    )
}
