package com.kmusic.apkhd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kmusic.apkhd.model.CollectionItem
import com.kmusic.apkhd.ui.components.CoverArt
import com.kmusic.apkhd.ui.components.MoyinIcon
import com.kmusic.apkhd.ui.icons.IconBack
import com.kmusic.apkhd.ui.theme.moyinColors
import com.kmusic.apkhd.ui.theme.wdp
import com.kmusic.apkhd.ui.theme.wsp
import com.kmusic.apkhd.ui.tv.tvFocus

/**
 * 曲库列表页 —— 专辑 / 歌手 / 歌单共用。
 *
 * 歌手用圆形封面、专辑和歌单用圆角方形，和主流音乐 App 的习惯一致。
 */
@Composable
fun CollectionListScreen(
    title: String,
    items: List<CollectionItem>,
    emptyText: String,
    loading: Boolean = false,
    onItemClick: (CollectionItem) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = moyinColors()
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(c.bg)
    ) {
        // 顶部返回栏（与 TrackListScreen 保持一致的尺寸和字阶）
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.wdp, vertical = 8.wdp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(44.wdp)
                    .tvFocus()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { onBack() },
                contentAlignment = Alignment.Center
            ) {
                MoyinIcon(icon = IconBack, tint = c.t1, size = 22.wdp)
            }
            Spacer(Modifier.width(4.wdp))
            Text(
                text = title,
                color = c.t1,
                fontSize = 16.wsp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            if (items.isNotEmpty()) {
                Text(
                    text = "${items.size} 项",
                    color = c.t3,
                    fontSize = 11.wsp
                )
                Spacer(Modifier.width(8.wdp))
            }
        }

        when {
            loading && items.isEmpty() -> CenterHint("加载中…")
            items.isEmpty() -> CenterHint(emptyText)
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(items, key = { it.id }) { it ->
                    CollectionRow(item = it) { onItemClick(it) }
                }
                item { Spacer(Modifier.height(24.wdp)) }
            }
        }
    }
}

@Composable
private fun CenterHint(text: String) {
    val c = moyinColors()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = c.t3, fontSize = 13.wsp)
    }
}

@Composable
private fun CollectionRow(item: CollectionItem, onClick: () -> Unit) {
    val c = moyinColors()
    Row(
        Modifier
            .fillMaxWidth()
            .tvFocus(fullWidth = true)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
            .padding(horizontal = 16.wdp, vertical = 6.wdp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(48.wdp)
                .then(
                    if (item.round) Modifier.clip(CircleShape)
                    else Modifier.clip(RoundedCornerShape(8.wdp))
                )
                .background(androidx.compose.ui.graphics.Brush.linearGradient(c.coverGrad))
        ) {
            if (!item.coverUrl.isNullOrEmpty()) {
                CoverArt(
                    coverUrl = item.coverUrl,
                    modifier = Modifier.fillMaxSize(),
                    cornerRadius = if (item.round) 99.wdp else 8.wdp
                )
            }
        }
        Spacer(Modifier.width(12.wdp))
        Column(Modifier.weight(1f)) {
            Text(
                text = item.title,
                color = c.t1,
                fontSize = 14.wsp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (item.subtitle.isNotEmpty()) {
                Spacer(Modifier.height(2.wdp))
                Text(
                    text = item.subtitle,
                    color = c.t3,
                    fontSize = 11.wsp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
