package com.kmusic.apkhd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kmusic.apkhd.model.LocalFolder
import com.kmusic.apkhd.ui.components.MoyinIcon
import com.kmusic.apkhd.ui.icons.IconBack
import com.kmusic.apkhd.ui.icons.IconCheck
import com.kmusic.apkhd.ui.icons.IconClose
import com.kmusic.apkhd.ui.icons.IconFolder
import com.kmusic.apkhd.ui.icons.IconPlus
import com.kmusic.apkhd.ui.theme.moyinColors
import com.kmusic.apkhd.ui.theme.wdp
import com.kmusic.apkhd.ui.theme.wsp
import com.kmusic.apkhd.ui.theme.wspSp
import com.kmusic.apkhd.ui.tv.tvFocus

/**
 * 本地音乐「扫描设置」。
 *
 * 存在的理由有两个，都不是可选的：
 * 1. 让用户**选**扫哪些目录 —— 手机里歌很多时不该全塞进曲库
 * 2. 安卓 11+ 起别的 App 的私有目录不进系统媒体库，用户「明明有歌却扫不到」，
 *    这种情况只能靠「添加文件夹」直接指过去。没有这个入口，本地音乐对他就等于假功能。
 */
@Composable
fun LocalFoldersScreen(
    folders: List<LocalFolder>,
    enabled: Set<String>,
    scanning: Boolean,
    localTrackCount: Int,
    onToggle: (String, Boolean) -> Unit,
    onAddFolder: () -> Unit,
    onRemoveFolder: (String) -> Unit,
    onRescan: () -> Unit,
    onBack: () -> Unit
) {
    val c = moyinColors()
    // 「不勾选 = 不限制」：没有任何勾选时全部视为启用
    val unlimited = enabled.isEmpty()
    val mediaFolders = folders.filter { !it.fromSaf }
    val safFolders = folders.filter { it.fromSaf }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        // ---------------- 顶栏 ----------------
        Row(
            Modifier.fillMaxWidth().padding(start = 12.wdp, end = 16.wdp, top = 14.wdp, bottom = 10.wdp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(40.wdp).clip(RoundedCornerShape(20.wdp))
                    .tvFocus()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onBack() },
                contentAlignment = Alignment.Center
            ) { MoyinIcon(IconBack, size = 20.wdp, tint = c.t1) }

            Column(Modifier.weight(1f).padding(start = 6.wdp)) {
                Text(
                    "扫描设置",
                    color = c.t1,
                    fontSize = 18.wsp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    if (scanning) "扫描中…" else "当前曲库 $localTrackCount 首",
                    color = if (scanning) c.accent else c.t3,
                    fontSize = 11.wsp
                )
            }

            Text(
                "重新扫描",
                color = c.accent,
                fontSize = 13.wsp,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.wdp))
                    .tvFocus(shape = RoundedCornerShape(10.wdp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onRescan() }
                    .padding(horizontal = 10.wdp, vertical = 6.wdp)
            )
        }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.wdp)) {
            // ---------------- 说明 ----------------
            item {
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 16.wdp, vertical = 4.wdp)
                        .clip(RoundedCornerShape(14.wdp)).background(c.s2)
                        .padding(12.wdp)
                ) {
                    Text(
                        "不勾选 = 扫描全部目录。\n" +
                            "安卓 11 起，其它 App 私有目录里的歌不会进系统媒体库 —— " +
                            "如果这里找不到你的歌，用下面的「添加文件夹」直接指到存放目录即可。",
                        color = c.t2,
                        fontSize = 11.5.wspSp,
                        lineHeight = 17.wsp
                    )
                }
            }

            // ---------------- MediaStore 目录 ----------------
            if (mediaFolders.isNotEmpty()) {
                item { FoldGroupHeader("系统媒体库发现的目录", c) }
                items(mediaFolders, key = { it.path }) { f ->
                    FolderRow(
                        name = f.name,
                        subtitle = if (f.trackCount > 0) "${f.trackCount} 首" else "",
                        checked = unlimited || f.path in enabled,
                        onToggle = { onToggle(f.path, it) },
                        onRemove = null
                    )
                }
            } else {
                item { FoldGroupHeader("系统媒体库发现的目录", c) }
                item {
                    Text(
                        if (scanning) "正在读取…" else "系统媒体库里没有搜到音频（可能是权限没给，或者歌都在别的 App 私有目录里）",
                        color = c.t3,
                        fontSize = 11.5.wspSp,
                        modifier = Modifier.padding(horizontal = 20.wdp, vertical = 8.wdp)
                    )
                }
            }

            // ---------------- 手动添加的目录 ----------------
            //
            // ⚠️ 这些行**故意没有勾选框**：勾选框的语义是「MediaStore 里扫到这些目录，
            // 要不要纳入曲库」，而手动加的目录只有一种状态 —— 加了就扫、删了就不扫。
            // 给它一个永远勾上的框，用户会以为取消勾选能「临时排除」，实际等于删掉配置，
            // 反而更容易误解。所以这里用文件夹图标 + 删除按钮表达「已生效、可移除」。
            // （用户反馈「扫描设置没有勾选框」时先看这段：他是没有 MediaStore 目录，
            //   不是勾选框坏了。）
            if (safFolders.isNotEmpty()) {
                item { FoldGroupHeader("手动添加的目录 · 已全部纳入曲库", c) }
                items(safFolders, key = { it.path }) { f ->
                    FolderRow(
                        name = f.name,
                        subtitle = if (f.trackCount > 0) "${f.trackCount} 首 · 手动添加" else "手动添加",
                        checked = true,     // 手动加的默认就扫，取消它等于删掉
                        onToggle = null,
                        onRemove = { onRemoveFolder(f.path) }
                    )
                }
            }

            // ---------------- 添加按钮 ----------------
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.wdp, vertical = 10.wdp)
                        .clip(RoundedCornerShape(14.wdp)).background(c.s2)
                        .tvFocus(shape = RoundedCornerShape(14.wdp)).background(c.s2)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onAddFolder() }
                        .padding(horizontal = 14.wdp, vertical = 14.wdp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MoyinIcon(IconPlus, size = 18.wdp, tint = c.accent)
                    Text(
                        "添加文件夹",
                        color = c.accent,
                        fontSize = 14.wsp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 10.wdp)
                    )
                }
            }
        }
    }
}

@Composable
private fun FoldGroupHeader(text: String, c: com.kmusic.apkhd.ui.theme.MoyinColors) {
    Text(
        text,
        color = c.t3,
        fontSize = 11.wsp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 20.wdp, top = 14.wdp, bottom = 6.wdp)
    )
}

@Composable
private fun FolderRow(
    name: String,
    subtitle: String,
    checked: Boolean,
    onToggle: ((Boolean) -> Unit)?,
    onRemove: (() -> Unit)?
) {
    val c = moyinColors()
    Row(
        Modifier.fillMaxWidth()
            .tvFocus(fullWidth = true)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                if (onToggle != null) onToggle(!checked)
            }
            .padding(horizontal = 20.wdp, vertical = 12.wdp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onToggle != null) {
            // 自绘勾选框：勾上=强调色实心+对勾，未勾=描边空框
            Box(
                Modifier.size(20.wdp).clip(RoundedCornerShape(6.wdp))
                    .then(
                        if (checked) Modifier.background(c.accentFill)
                        else Modifier.background(c.s3).border(1.dp, c.line, RoundedCornerShape(6.wdp))
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (checked) MoyinIcon(IconCheck, size = 13.wdp, tint = c.onAccent)
            }
            Spacer(Modifier.width(12.wdp))
        } else {
            MoyinIcon(IconFolder, size = 17.wdp, tint = c.t3)
            Spacer(Modifier.width(12.wdp))
        }

        Column(Modifier.weight(1f)) {
            Text(name, color = c.t1, fontSize = 14.wsp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, color = c.t3, fontSize = 10.5.wspSp, modifier = Modifier.padding(top = 2.wdp))
            }
        }

        if (onRemove != null) {
            Box(
                Modifier.size(30.wdp).clip(RoundedCornerShape(15.wdp))
                    .tvFocus()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onRemove() },
                contentAlignment = Alignment.Center
            ) { MoyinIcon(IconClose, size = 15.wdp, tint = c.t3) }
        }
    }
}
