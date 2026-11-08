package com.kmusic.apkhd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kmusic.apkhd.ui.icons.IconPause
import com.kmusic.apkhd.ui.icons.IconPlay
import com.kmusic.apkhd.ui.theme.coverRadiusOf
import com.kmusic.apkhd.ui.theme.moyinColors
import com.kmusic.apkhd.ui.theme.wdp
import com.kmusic.apkhd.ui.theme.wsp
import com.kmusic.apkhd.ui.tv.tvFocus

/**
 * 迷你播放条 —— 悬浮在 Tab 栏之上，玻璃拟态。
 * 与设计稿一致：24dp 封面 + 标题/歌手 + 细进度 + 播放键。
 */
@Composable
fun MiniPlayerBar(
    title: String,
    artist: String,
    qualityLabel: String,
    coverUrl: String?,
    progress: Float,
    isPlaying: Boolean,
    /** v2.4.8：连续缓冲超过 3 秒 —— 只在按钮上叠加载圈，图标本身不动 */
    stalled: Boolean = false,
    onClick: () -> Unit,
    onTogglePlay: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = moyinColors()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(60.wdp)
            // 深色（A）：玻璃条，和底栏胶囊同一语言；浅色（B）：纸白方块 + 细线
            .clip(RoundedCornerShape(c.radiusCard))
            // 深色下原来用 7.5% 白的玻璃，背后封面一透上来文字就看不清了；
            // 改成实底 + 高光边，保住层次又不牺牲可读性
            .background(c.s1)
            .border(
                width = 1.wdp,
                color = if (c.isDark) c.glassBorder else c.lineStrong.copy(alpha = 0.20f),
                shape = RoundedCornerShape(c.radiusCard)
            )
            // shape 必须传实际圆角：tvFocus 默认 8dp，迷你条是 radiusCard（14~20dp），
            // 用默认值会让描边在内侧跑出一条「方角」，圆角处对不上
            // scale = 1f：这条是满宽条且自带 .clip(radiusCard)，放大 6% 会被外层 clip 裁掉，
            // 只剩上边一条线（实测）。满宽元素放大本来也没有视觉意义。
            .tvFocus(shape = RoundedCornerShape(c.radiusCard), scale = 1f)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
            .padding(horizontal = 10.wdp, vertical = 8.wdp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 内层永远比外层小 6dp，避免「内圆角大于外圆角」露方角
        CoverArt(coverUrl = coverUrl, modifier = Modifier.size(44.wdp), cornerRadius = coverRadiusOf(c))
        Spacer(Modifier.width(12.wdp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = c.t1,
                fontSize = 13.wsp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "$artist · $qualityLabel",
                color = c.t3,
                fontSize = 10.wsp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.wdp))
            // 细进度条
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.wdp)
                    .clip(RoundedCornerShape(2.wdp))
                    .background(if (c.isDark) Color(0x1AFFFFFF) else Color(0x17000000))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.wdp))
                        .background(c.accentFill)
                )
            }
        }
        Spacer(Modifier.width(12.wdp))
        Box(
            Modifier
                .size(40.wdp)
                .clip(RoundedCornerShape(99.dp))
                .background(if (c.isDark) Color(0x17FFFFFF) else Color(0x0F000000))
                .tvFocus(shape = RoundedCornerShape(99.dp))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onTogglePlay() },
            contentAlignment = Alignment.Center
        ) {
            MoyinIcon(
                icon = if (isPlaying) IconPause else IconPlay,
                tint = c.t1,
                size = 18.wdp
            )
            if (stalled) {
                CircularProgressIndicator(
                    modifier = Modifier.size(34.wdp),
                    color = c.accent,
                    strokeWidth = 1.5.dp
                )
            }
        }
    }
}
