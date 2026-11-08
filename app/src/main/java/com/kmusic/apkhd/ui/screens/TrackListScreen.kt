package com.kmusic.apkhd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.kmusic.apkhd.model.Track
import com.kmusic.apkhd.ui.components.CoverArt
import com.kmusic.apkhd.ui.components.MoyinIcon
import com.kmusic.apkhd.ui.icons.IconBack
import com.kmusic.apkhd.ui.icons.IconHeart
import com.kmusic.apkhd.ui.theme.moyinColors
import com.kmusic.apkhd.ui.theme.sheetBottomPadding
import com.kmusic.apkhd.ui.theme.window
import com.kmusic.apkhd.ui.theme.wdp
import com.kmusic.apkhd.ui.theme.wsp
import com.kmusic.apkhd.ui.icons.IconMore
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import com.kmusic.apkhd.ui.icons.IconDownload
import com.kmusic.apkhd.ui.icons.IconClose
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import com.kmusic.apkhd.ui.tv.rememberTvPanelFocus
import com.kmusic.apkhd.ui.tv.tvFocus

/**
 * 曲目列表页 —— 本地音乐 / 最近播放 / Navidrome 分类共用。
 * 顶部：返回 + 标题 + 数量。
 */
@Composable
fun TrackListScreen(
    title: String,
    tracks: List<Track>,
    emptyText: String,
    loading: Boolean = false,
    /** 顶栏「⋯」里的操作（播放全部 / 下载全部…）。为空则不显示按钮 */
    actions: List<ListAction> = emptyList(),
    /** 每行右侧的下载按钮。为空则不显示 */
    onDownload: ((Track) -> Unit)? = null,
    /** 已在下载队列/已完成的曲目 id，用于把按钮点亮 */
    downloadedIds: Set<String> = emptySet(),
    /** 已收藏的曲目 id（用于点亮心形） */
    favoriteIds: Set<String> = emptySet(),
    /** 不为空则每行显示收藏按钮 —— 目前只有「我喜欢的音乐」列表需要 */
    onToggleFavorite: ((Track) -> Unit)? = null,
    /** 不为空则每行显示「移出」按钮 —— 自建歌单详情用 */
    onRemoveTrack: ((Track) -> Unit)? = null,
    onTrackClick: (Track, List<Track>) -> Unit,
    onBack: () -> Unit,
    /**
     * 源给的曲目数少于歌单声明数（例如声明 200 首只给 10 首）时填说明文案。
     *
     * 这个参数存在的唯一理由：以前这种情况完全静默 —— 列表页写「200 首」，
     * 点进去 10 首，用户只会觉得 app 坏了。现在在列表底部说明原因。
     */
    footerNote: String? = null,
    modifier: Modifier = Modifier
) {
    val c = moyinColors()
    var showActions by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(c.bg)
    ) {
        // 顶部返回栏
        // TV：整条顶栏作为一个焦点组，列表往上走时能稳定落到「返回 / ⋯」这两个按钮上，
        // 否则几何搜索可能直接跳到屏幕外的其它可聚焦元素
        Row(
            Modifier
                .fillMaxWidth()
                .focusGroup()
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
            androidx.compose.material3.Text(
                text = title,
                color = c.t1,
                fontSize = 16.wsp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            if (tracks.isNotEmpty()) {
                androidx.compose.material3.Text(
                    text = "${tracks.size} 首",
                    color = c.t3,
                    fontSize = 11.wsp
                )
                Spacer(Modifier.width(4.wdp))
            }
            if (actions.isNotEmpty()) {
                Box(
                    Modifier
                        .size(44.wdp)
                        .clip(RoundedCornerShape(12.wdp))
                        .tvFocus(shape = RoundedCornerShape(12.wdp))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { showActions = true },
                    contentAlignment = Alignment.Center
                ) {
                    MoyinIcon(icon = IconMore, tint = c.t1, size = 20.wdp)
                }
            }
        }

        if (tracks.isEmpty()) {
            // 加载中 / 空态
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                androidx.compose.material3.Text(
                    text = emptyText,
                    color = c.t3,
                    fontSize = 13.wsp
                )
            }
        } else {
            // contentPadding 不在 Composable 作用域，改用 item 内 padding 实现同样效果
            // 宽屏（平板 / 折叠屏展开）：两列网格 —— 单列列表在 1000dp+ 上会拉得很空
            val wide = window.isWide
            val rowFor: @Composable (Track) -> Unit = { t ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.wdp)
                            .tvFocus()
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { onTrackClick(t, tracks) }
                            .padding(vertical = 8.wdp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CoverArt(
                            coverUrl = t.coverUrl,
                            modifier = Modifier.size(48.wdp),
                            cornerRadius = 8.wdp
                        )
                        Spacer(Modifier.width(12.wdp))
                        Column(Modifier.weight(1f)) {
                            androidx.compose.material3.Text(
                                text = t.name,
                                color = c.t1,
                                fontSize = 14.wsp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(2.wdp))
                            androidx.compose.material3.Text(
                                text = listOf(t.artist, t.album).filter { it.isNotEmpty() }
                                    .joinToString(" · ").ifEmpty { "未知" },
                                color = c.t3,
                                fontSize = 11.wsp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (t.qualityLabel.isNotEmpty()) {
                            androidx.compose.material3.Text(
                                text = t.qualityLabel,
                                color = c.t3,
                                fontSize = 9.wsp
                            )
                            Spacer(Modifier.width(8.wdp))
                        }
                        // 移出歌单（只在自建歌单详情里出现）
                        if (onRemoveTrack != null) {
                            Box(
                                Modifier
                                    .size(40.wdp)
                                    .clip(RoundedCornerShape(12.wdp))
                                    .tvFocus(shape = RoundedCornerShape(12.wdp))
                                    .clickable(
                                        indication = null,
                                        interactionSource = remember { MutableInteractionSource() }
                                    ) { onRemoveTrack(t) },
                                contentAlignment = Alignment.Center
                            ) {
                                MoyinIcon(icon = IconClose, tint = c.t3, size = 15.wdp)
                            }
                        }
                        // 收藏按钮（只在需要时出现，比如「我喜欢的音乐」列表）
                        if (onToggleFavorite != null) {
                            val fav = t.id in favoriteIds
                            Box(
                                Modifier
                                    .size(40.wdp)
                                    .clip(RoundedCornerShape(12.wdp))
                                    .tvFocus(shape = RoundedCornerShape(12.wdp))
                                    .clickable(
                                        indication = null,
                                        interactionSource = remember { MutableInteractionSource() }
                                    ) { onToggleFavorite(t) },
                                contentAlignment = Alignment.Center
                            ) {
                                MoyinIcon(
                                    icon = IconHeart,
                                    tint = if (fav) c.accent else c.t3,
                                    size = 17.wdp
                                )
                            }
                        }
                        // 每行最右的下载按钮（本地曲目不用下，本来就在本机）
                        if (onDownload != null && t.source != "local") {
                            val done = t.id in downloadedIds
                            Box(
                                Modifier
                                    .size(40.wdp)
                                    .clip(RoundedCornerShape(12.wdp))
                                    .tvFocus(shape = RoundedCornerShape(12.wdp))
                                    .clickable(
                                        indication = null,
                                        interactionSource = remember { MutableInteractionSource() }
                                    ) { if (!done) onDownload(t) },
                                contentAlignment = Alignment.Center
                            ) {
                                MoyinIcon(
                                    icon = IconDownload,
                                    tint = if (done) c.accent else c.t2,
                                    size = 19.wdp
                                )
                            }
                        }
                    }
            }
            if (wide) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize()
                ) {
                    gridItems(tracks, key = { it.id }) { t -> rowFor(t) }
                }
            } else {
                LazyColumn {
                    items(tracks, key = { it.id }) { t -> rowFor(t) }
                    footerNote?.let { note ->
                        item(key = "__footer_note") {
                            Text(
                                text = note,
                                color = c.t3,
                                fontSize = 11.wsp,
                                lineHeight = 16.wsp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.wdp, vertical = 14.wdp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showActions) {
        // BACK 关闭面板（TV 遥控器上返回键是最顺手的退出方式）
        androidx.activity.compose.BackHandler(enabled = true) { showActions = false }
        ActionSheet(actions = actions, onDismiss = { showActions = false })
    }
    }
}

/** 列表页顶栏「⋯」里的一项操作 */
data class ListAction(
    val label: String,
    val icon: ImageVector? = null,
    val onClick: () -> Unit
)

/**
 * 底部升起的操作面板。
 * 用自绘 overlay 而不是 ModalBottomSheet —— 现网多个 Material3 版本的
 * ModalBottomSheet 在这个 Compose 版本上行为不一致，自绘最稳。
 *
 * ⚠️ v2.4.1：面板打开时必须**把焦点抢进来**（[rememberTvPanelFocus]）。
 * 以前打开后焦点还留在顶栏那个「⋯」上，遥控器上下动的是背后那张列表，
 * 所以「播放全部 / 下载全部 / 收藏歌单」看得见、选不中。
 *
 * 同时遮罩和面板容器都 `canFocus = false`：它们是两个满屏的 `clickable` 空壳，
 * 留着会跟真实菜单项抢焦点（按一次方向键可能落在空壳上，看起来就是"卡住不动"）。
 */
@Composable
private fun ActionSheet(actions: List<ListAction>, onDismiss: () -> Unit) {
    val c = moyinColors()
    val firstFocus = rememberTvPanelFocus()
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            // 遮罩只负责点击关闭，不参与焦点
            .focusProperties { canFocus = false }
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onDismiss() },
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 18.wdp, topEnd = 18.wdp))
                .background(c.s1)
                // 容器只负责吃掉点击（不要穿透到遮罩上导致直接关掉），不参与焦点
                .focusProperties { canFocus = false }
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { }
                .sheetBottomPadding()
                .padding(vertical = 10.wdp, horizontal = 8.wdp)
        ) {
            actions.forEachIndexed { i, a ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.wdp))
                        .then(if (i == 0) Modifier.focusRequester(firstFocus) else Modifier)
                        .tvFocus(shape = RoundedCornerShape(12.wdp))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { onDismiss(); a.onClick() }
                        .padding(horizontal = 14.wdp, vertical = 14.wdp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    a.icon?.let {
                        MoyinIcon(icon = it, tint = c.t2, size = 19.wdp)
                        Spacer(Modifier.width(14.wdp))
                    }
                    androidx.compose.material3.Text(
                        text = a.label,
                        color = c.t1,
                        fontSize = 14.wsp
                    )
                }
            }
            Spacer(Modifier.height(6.wdp))
        }
    }
}
