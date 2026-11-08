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
import androidx.compose.foundation.lazy.items
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
import com.kmusic.apkhd.net.DavEntry
import com.kmusic.apkhd.ui.components.MoyinIcon
import com.kmusic.apkhd.ui.icons.IconBack
import com.kmusic.apkhd.ui.icons.IconDownload
import com.kmusic.apkhd.ui.icons.IconFolder
import com.kmusic.apkhd.ui.icons.IconImage
import com.kmusic.apkhd.ui.icons.IconMusicNote
import com.kmusic.apkhd.ui.theme.moyinColors
import com.kmusic.apkhd.ui.theme.wdp
import com.kmusic.apkhd.ui.theme.wsp
import com.kmusic.apkhd.ui.tv.tvFocus

/**
 * WebDAV 浏览页。
 *
 * 车机场景：能用方向键/滚轮上下选，选中即播；不搞多级弹层。
 * 目录结构就是列表结构，最上面一条面包屑告诉你在哪，右上角「播放全部」整目录入队。
 */
@Composable
fun WebDavScreen(
    base: String,
    path: String,
    entries: List<DavEntry>,
    loading: Boolean,
    error: String?,
    onOpenDir: (String) -> Unit,
    onUp: () -> Unit,
    onPlayFile: (DavEntry) -> Unit,
    onPlayAll: () -> Unit,
    onDownload: (DavEntry) -> Unit,
    onDownloadAll: () -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    /** 底部有没有迷你播放条：有的话列表要多留一条的高度，别让最后一行被压住 */
    bottomInsetDp: Int = 0,
    modifier: Modifier = Modifier
) {
    val c = moyinColors()
    val relPath = path.removePrefix(base).trim('/')
    // 路径里是 %20 / %E6…，直接显示是一串编码；解码只用于显示，逻辑仍用 raw
    val relPathText = runCatching { java.net.URLDecoder.decode(relPath, "UTF-8") }.getOrDefault(relPath)

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
                    ) { if (relPath.isEmpty()) onBack() else onUp() }
                    .padding(10.wdp)
            )
            Spacer(Modifier.width(6.wdp))
            Column(Modifier.weight(1f)) {
                Text("WebDAV", color = c.t1, fontSize = 15.wsp)
                Text(
                    if (relPath.isEmpty()) (base.ifBlank { "未配置" }) else "/$relPathText",
                    color = c.t3,
                    fontSize = 11.wsp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
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

        if (base.isBlank()) {
            // 没配置：给一条明确的入口，别让用户对着空列表猜
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("还没有配置 WebDAV", color = c.t1, fontSize = 15.wsp)
                Spacer(Modifier.height(6.wdp))
                Text("填上服务器地址和账号，就能在线播放和下载", color = c.t3, fontSize = 12.wsp)
                Spacer(Modifier.height(16.wdp))
                PillButton("去设置", filled = true) { onOpenSettings() }
            }
            return@Column
        }

        // 动作条
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.wdp),
            horizontalArrangement = Arrangement.spacedBy(10.wdp)
        ) {
            PillButton("播放全部") { onPlayAll() }
            PillButton("下载全部") { onDownloadAll() }
            if (relPath.isNotEmpty()) {
                PillButton("上一级") { onUp() }
            }
        }

        Spacer(Modifier.height(8.wdp))

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = c.accent, strokeWidth = 2.dp, modifier = Modifier.size(22.wdp))
            }

            error != null -> Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(error, color = c.t2, fontSize = 13.wsp)
                Spacer(Modifier.height(12.wdp))
                PillButton("重试", filled = true) { onRefresh() }
            }

            entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("这个目录是空的", color = c.t3, fontSize = 13.wsp)
            }

            else -> LazyColumn(contentPadding = PaddingValues(bottom = (24 + bottomInsetDp).wdp)) {
                items(entries, key = { it.url }) { e ->
                    DavRow(
                        entry = e,
                        onClick = { if (e.isDir) onOpenDir(e.url) else onPlayFile(e) },
                        onDownload = { onDownload(e) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DavRow(entry: DavEntry, onClick: () -> Unit, onDownload: () -> Unit) {
    val c = moyinColors()
    Row(
        Modifier
            .fillMaxWidth()
            .tvFocus(fullWidth = true)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
            .padding(horizontal = 16.wdp, vertical = 11.wdp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MoyinIcon(
            icon = when {
                entry.isDir -> IconFolder
                entry.isAudio -> IconMusicNote
                else -> IconImage   // cover.jpg 这类：别再用音符，一眼能分出不是歌
            },
            tint = when {
                entry.isDir -> c.t2
                entry.isAudio -> c.accent
                else -> c.t3
            },
            size = 20.wdp
        )
        Spacer(Modifier.width(12.wdp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.name + if (entry.isDir) "/" else "",
                color = c.t1,
                fontSize = 14.wsp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!entry.isDir) {
                Text(
                    buildString {
                        if (entry.size > 0) append(formatSize(entry.size))
                        if (entry.mtime.isNotEmpty()) {
                            if (isNotEmpty()) append(" · ")
                            append(entry.mtime.take(10))
                        }
                    },
                    color = c.t3,
                    fontSize = 11.wsp
                )
            }
        }
        if (!entry.isDir) {
            MoyinIcon(
                icon = IconDownload,
                tint = c.t3,
                size = 18.wdp,
                modifier = Modifier
                    .clip(RoundedCornerShape(99.dp))
                    .tvFocus(shape = RoundedCornerShape(99.dp))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { onDownload() }
                    .padding(8.wdp)
            )
        }
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

private fun formatSize(v: Long): String = when {
    v >= 1L shl 30 -> "%.1f GB".format(v / (1L shl 30).toDouble())
    v >= 1L shl 20 -> "%.1f MB".format(v / (1L shl 20).toDouble())
    v >= 1L shl 10 -> "%.0f KB".format(v / (1L shl 10).toDouble())
    else -> "$v B"
}
