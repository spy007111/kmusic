package com.kmusic.apkhd.ui.screens

import androidx.compose.foundation.background
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
import com.kmusic.apkhd.model.DownloadTask
import com.kmusic.apkhd.ui.components.MoyinIcon
import com.kmusic.apkhd.ui.icons.IconBack
import com.kmusic.apkhd.ui.icons.IconDownload
import com.kmusic.apkhd.ui.theme.moyinColors
import com.kmusic.apkhd.ui.theme.wdp
import com.kmusic.apkhd.ui.theme.wsp
import com.kmusic.apkhd.ui.tv.tvFocus

/** 下载管理：列出任务与进度，可移除记录 */
@Composable
fun DownloadScreen(
    downloads: List<DownloadTask>,
    onRemove: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = moyinColors()
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(c.bg)
            .padding(horizontal = 16.wdp)
    ) {
        Spacer(Modifier.height(14.wdp))
        Row(verticalAlignment = Alignment.CenterVertically) {
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
            Spacer(Modifier.width(10.wdp))
            Text("下载管理", color = c.t1, fontSize = 16.wsp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(14.wdp))
        if (downloads.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(56.wdp)
                            .clip(RoundedCornerShape(99.dp))
                            .background(c.s2),
                        contentAlignment = Alignment.Center
                    ) {
                        MoyinIcon(icon = IconDownload, tint = c.t3, size = 24.wdp)
                    }
                    Spacer(Modifier.height(14.wdp))
                    Text("还没有下载任务", color = c.t3, fontSize = 13.wsp)
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(downloads) { d ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.wdp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    d.name,
                                    color = c.t1,
                                    fontSize = 14.wsp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(2.wdp))
                                Text(
                                    listOf(
                                        d.artist,
                                        d.qualityLabel,
                                        d.status
                                    ).filter { it.isNotEmpty() }.joinToString(" · "),
                                    color = if (d.status.startsWith("失败")) Color(0xFFE5484D) else c.t3,
                                    fontSize = 11.wsp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                d.path?.let {
                                    Spacer(Modifier.height(2.wdp))
                                    Text(
                                        it,
                                        color = c.t3,
                                        fontSize = 10.wsp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Box(
                                Modifier
                                    .size(36.wdp)
                                    .clip(RoundedCornerShape(99.dp))
                                    .tvFocus(shape = RoundedCornerShape(99.dp))
                                    .clickable(
                                        indication = null,
                                        interactionSource = remember { MutableInteractionSource() }
                                    ) { onRemove(d.id) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("×", color = c.t3, fontSize = 16.wsp)
                            }
                        }
                        if (d.status == "下载中…") {
                            Spacer(Modifier.height(6.wdp))
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(3.wdp)
                                    .clip(RoundedCornerShape(12.wdp))
                                    .background(if (c.isDark) Color(0x1AFFFFFF) else Color(0x17000000))
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxWidth(d.progress.coerceIn(0f, 1f))
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(12.wdp))
                                        .background(c.accentFill)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
