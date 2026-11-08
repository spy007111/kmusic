package com.kmusic.apkhd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kmusic.apkhd.ui.components.MoyinIcon
import com.kmusic.apkhd.ui.components.MoyinTitle
import com.kmusic.apkhd.ui.components.SectionHeader
import com.kmusic.apkhd.ui.icons.IconChevronRight
import com.kmusic.apkhd.ui.icons.IconDisk
import com.kmusic.apkhd.ui.icons.IconDownload
import com.kmusic.apkhd.ui.icons.IconHeart
import com.kmusic.apkhd.ui.icons.IconMusicNote
import com.kmusic.apkhd.ui.icons.IconPlaylist
import com.kmusic.apkhd.ui.icons.IconRecent
import com.kmusic.apkhd.ui.icons.IconServer
import com.kmusic.apkhd.ui.theme.ambientGlow
import com.kmusic.apkhd.ui.theme.moyinColors
import com.kmusic.apkhd.ui.theme.wdp
import com.kmusic.apkhd.ui.theme.wsp
import com.kmusic.apkhd.ui.tv.tvFocus

/**
 * 媒体库页 —— 本地音乐 + 下载管理。
 */
@Composable
fun LibraryScreen(
    localCount: Int,
    onLocalClick: () -> Unit,
    recentCount: Int = 0,
    onRecentClick: () -> Unit = {},
    favoriteCount: Int = 0,
    onFavoritesClick: () -> Unit = {},
    /** 「我的歌单」入口副标题用：收藏 + 创建 各多少个 */
    favoritePlaylistCount: Int = 0,
    userPlaylistCount: Int = 0,
    onMyPlaylistsClick: () -> Unit = {},
    davConfigured: Boolean = false,
    davPath: String = "",
    onWebDavClick: () -> Unit = {},
    /** Navidrome 曲库：未配置 / 已连接（附曲目数）/ 连接失败 */
    ndConfigured: Boolean = false,
    ndConnected: Boolean = false,
    ndTrackCount: Int = 0,
    ndError: String? = null,
    onNavidromeClick: () -> Unit = {},
    onCategoryClick: (String) -> Unit,
    downloadCount: Int = 0,
    modifier: Modifier = Modifier
) {
    val c = moyinColors()
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(c.bg)
            // 顶部氛围光：画在背景之上、列表内容之下，滚动时不跟着走 —— 像一层固定光。
            // 实现见 AmbientGlow.kt：椭圆纵向半轴压在光带之内，底边不会留横向接缝。
            .ambientGlow(
                tint = c.accent,
                bg = c.bg,
                alpha = if (c.isDark) 0.15f else 0f,
                baseHeight = 340.wdp
            )
            .padding(horizontal = 16.wdp),
        // 底部要留出「迷你播放条 + Tab 栏」的高度：它们是 overlay 在内容之上的，
        // 不留就会把最后几项压在栏下面，既看不见也点不到
        contentPadding = PaddingValues(top = 10.wdp, bottom = 142.wdp)
    ) {
        item {
            MoyinTitle(text = "媒体库", size = 28)
            Spacer(Modifier.height(10.wdp))
        }

        // 顺序按「每天先看什么」排：最近 → 喜欢 → 本地 → 网络来源 → 下载
        item {
            LibraryEntry(
                icon = IconRecent,
                title = "最近播放",
                subtitle = if (recentCount > 0) "$recentCount 首" else "还没有听过",
                onClick = onRecentClick
            )
        }
        item {
            LibraryEntry(
                icon = IconHeart,
                title = "我喜欢的音乐",
                subtitle = if (favoriteCount > 0) "$favoriteCount 首" else "还没有收藏",
                onClick = onFavoritesClick
            )
        }
        // 「我的歌单」= 收藏的 + 创建的。放一行而不是两行：
        // 用户心里只有一个「歌单」抽屉，拆成两个入口就得两处各找一遍。
        // 副标题把两个数都写出来，一眼知道里面有什么。
        item {
            LibraryEntry(
                icon = IconPlaylist,
                title = "我的歌单",
                subtitle = when {
                    favoritePlaylistCount == 0 && userPlaylistCount == 0 -> "还没有歌单"
                    userPlaylistCount == 0 -> "收藏 $favoritePlaylistCount 个"
                    favoritePlaylistCount == 0 -> "创建 $userPlaylistCount 个"
                    else -> "收藏 $favoritePlaylistCount 个 · 创建 $userPlaylistCount 个"
                },
                onClick = onMyPlaylistsClick
            )
        }
        item {
            LibraryEntry(
                icon = IconDisk,
                title = "本地音乐",
                subtitle = "$localCount 首 · 已扫描",
                onClick = onLocalClick
            )
        }
        item {
            LibraryEntry(
                icon = IconServer,
                title = "WebDAV",
                subtitle = when {
                    !davConfigured -> "未配置，点此填写"
                    davPath.isBlank() -> "已连接"
                    else -> "已连接 · $davPath"
                },
                onClick = onWebDavClick
            )
        }
        item {
            LibraryEntry(
                icon = IconServer,
                title = "Navidrome",
                subtitle = when {
                    !ndConfigured -> "未配置，点此填写服务器"
                    ndConnected -> "已连接 · $ndTrackCount 首"
                    !ndError.isNullOrBlank() -> "连接失败，点此查看"
                    else -> "已配置，未连接"
                },
                onClick = onNavidromeClick
            )
        }
        item {
            LibraryEntry(
                icon = IconDownload,
                title = "下载管理",
                subtitle = if (downloadCount > 0) "$downloadCount 个任务 · 存在 Music/KMusic" else "母带 / 无损 / 高品质",
                onClick = { onCategoryClick("下载管理") }
            )
        }
    }
}

@Composable
private fun LibraryEntry(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = moyinColors()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .tvFocus(fullWidth = true)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
            .padding(vertical = 10.wdp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(44.wdp)
                .clip(RoundedCornerShape(12.wdp))
                .background(c.s2),
            contentAlignment = Alignment.Center
        ) {
            MoyinIcon(icon = icon, tint = c.t2, size = 18.wdp)
        }
        Spacer(Modifier.width(12.wdp))
        Column(Modifier.weight(1f)) {
            Text(title, color = c.t1, fontSize = 14.wsp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.wdp))
            Text(subtitle, color = c.t3, fontSize = 11.wsp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        MoyinIcon(icon = IconChevronRight, tint = c.t3, size = 18.wdp)
    }
}
