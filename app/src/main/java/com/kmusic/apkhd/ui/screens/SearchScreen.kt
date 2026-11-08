package com.kmusic.apkhd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kmusic.apkhd.model.Track
import com.kmusic.apkhd.ui.components.CoverArt
import com.kmusic.apkhd.ui.components.MoyinIcon
import com.kmusic.apkhd.ui.components.pressScale
import com.kmusic.apkhd.ui.icons.IconBack
import com.kmusic.apkhd.ui.icons.IconMore
import com.kmusic.apkhd.ui.icons.IconQueue
import com.kmusic.apkhd.ui.icons.IconNext
import com.kmusic.apkhd.ui.icons.IconDownload
import com.kmusic.apkhd.ui.icons.IconSearch
import com.kmusic.apkhd.ui.theme.ambientGlow
import com.kmusic.apkhd.ui.theme.moyinColors
import com.kmusic.apkhd.ui.theme.sheetBottomPadding
import com.kmusic.apkhd.ui.theme.wdp
import com.kmusic.apkhd.ui.theme.frosted
import com.kmusic.apkhd.ui.theme.wsp
import com.kmusic.apkhd.model.AlbumBrief
import com.kmusic.apkhd.model.ArtistBrief
import com.kmusic.apkhd.model.PlaylistBrief
import com.kmusic.apkhd.model.SearchTab
import com.kmusic.apkhd.ui.icons.IconClose
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import com.kmusic.apkhd.ui.tv.tvFocus

/**
 * 搜索页 —— 与设计稿一致：输入框带光标、类型 chip、结果行右侧母带标签。
 */
@Composable
fun SearchScreen(
    keyword: String,
    onKeywordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    results: List<Track>,
    searching: Boolean,
    error: String?,
    onResultClick: (Track, List<Track>) -> Unit,
    onBack: () -> Unit,
    history: List<String> = emptyList(),
    onHistoryClick: (String) -> Unit = {},
    onHistoryRemove: (String) -> Unit = {},
    onHistoryClear: () -> Unit = {},
    // AI 查询改写：只在配了 key 时露出按钮，避免摆一个点了报错的入口
    aiAvailable: Boolean = false,
    aiRewriting: Boolean = false,
    aiHint: String? = null,
    onAiSearch: () -> Unit = {},
    onAddToQueue: (Track) -> Unit = {},
    onPlayNext: (Track) -> Unit = {},
    onDownload: (Track) -> Unit = {},
    downloadedIds: Set<String> = emptySet(),
    tab: SearchTab = SearchTab.SONG,
    onTabChange: (SearchTab) -> Unit = {},
    artistResults: List<ArtistBrief> = emptyList(),
    albumResults: List<AlbumBrief> = emptyList(),
    playlistResults: List<PlaylistBrief> = emptyList(),
    onArtistClick: (ArtistBrief) -> Unit = {},
    onAlbumClick: (AlbumBrief) -> Unit = {},
    onPlaylistClick: (PlaylistBrief) -> Unit = {},
    suggestions: List<String> = emptyList(),
    onSuggestionClick: (String) -> Unit = {},
    hotKeywords: List<String> = emptyList(),
    onHotClick: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // 长按/点「⋯」选中的曲目，非空时弹出操作菜单
    var menuTrack by remember { mutableStateOf<Track?>(null) }
    val c = moyinColors()
    val keyboard = LocalSoftwareKeyboardController.current
    // 是否已经提交过搜索。未提交时展示联想词，提交后展示结果 ——
    // 否则联想词会一直盖在结果上面
    var submitted by remember { mutableStateOf(false) }

    // 输入停顿 500ms 自动搜，不用按键盘上的"搜索"。
    // 首次进入不触发：从详情页带着关键词回来时不该立刻重搜一遍。
    var autoArmed by remember { mutableStateOf(false) }
    LaunchedEffect(keyword) {
        if (!autoArmed) { autoArmed = true; return@LaunchedEffect }
        if (keyword.trim().isEmpty()) return@LaunchedEffect
        kotlinx.coroutines.delay(500)
        submitted = true
        onSubmit()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(c.bg)
            // 顶部氛围光 —— 与首页同一套视觉语言（同一实现，见 AmbientGlow.kt）
            .ambientGlow(
                tint = c.accent,
                bg = c.bg,
                alpha = if (c.isDark) 0.15f else 0f,
                baseHeight = 300.wdp
            )
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.wdp)
    ) {
        Spacer(Modifier.height(14.wdp))
        // 搜索框占满整行：左侧返回键去掉了（侧栏/车机自带返回，这个箭头没意义）。
        // 面板换成毛玻璃，和底栏同一套材质。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.wdp)
                // 半径跟卡片同源（c.radiusCard）。以前深色写 14、浅色写 2，
                // 同一个控件在首页是 14、在搜索页是 2 —— 两套数字必然漂移。
                .frosted(
                    shape = RoundedCornerShape(c.radiusCard),
                    alpha = if (c.isDark) 0.88f else 0.92f
                )
                .padding(horizontal = 12.wdp),
            verticalAlignment = Alignment.CenterVertically
        ) {
                MoyinIcon(icon = IconSearch, tint = c.t3, size = 18.wdp)
                Spacer(Modifier.width(12.wdp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (keyword.isEmpty()) {
                        Text("搜索歌曲、歌手、专辑", color = c.t3, fontSize = 14.wsp)
                    }
                    BasicTextField(
                        value = keyword,
                        onValueChange = { submitted = false; onKeywordChange(it) },
                        singleLine = true,
                        textStyle = TextStyle(color = c.t1, fontSize = 14.wsp),
                        cursorBrush = SolidColor(c.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = {
                                keyboard?.hide()
                                onSubmit()
                            }
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (keyword.isNotBlank() && aiAvailable) {
                    Spacer(Modifier.width(8.wdp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape((c.radiusCard - 6.wdp).coerceAtLeast(2.wdp)))
                            .background(if (aiRewriting) c.accentDim else c.s3)
                            .tvFocus(shape = RoundedCornerShape((c.radiusCard - 6.wdp).coerceAtLeast(2.wdp)))
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { if (!aiRewriting) onAiSearch() }
                            .padding(horizontal = 9.wdp, vertical = 5.wdp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (aiRewriting) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(12.wdp),
                                strokeWidth = 1.4.dp,
                                color = c.accent
                            )
                        } else {
                            Text(
                                "AI",
                                color = c.accent,
                                fontSize = 11.wsp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

        // 改写提示（「AI 理解为：…」/ 失败原因）
        if (aiHint != null) {
            Spacer(Modifier.height(8.wdp))
            Text(
                aiHint,
                color = c.t3,
                fontSize = 11.wsp,
                modifier = Modifier.padding(horizontal = 2.wdp)
            )
        }

        Spacer(Modifier.height(18.wdp))

        // 搜索页签：每个页签走各自的接口、渲染各自的数据（不是只换高亮色）
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            SearchTab.entries.forEach { t ->
                val on = t == tab
                Text(
                    text = t.label,
                    color = if (on) {
                        if (c.isDark) androidx.compose.ui.graphics.Color(0xFFB9B8F5)
                        else androidx.compose.ui.graphics.Color(0xFF3F3CB8)
                    } else c.t2,
                    fontSize = 12.wsp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(99.dp))
                        .background(if (on) c.accentDim else c.s2)
                        .tvFocus(shape = RoundedCornerShape(99.dp))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { onTabChange(t); submitted = true }
                        .padding(horizontal = 10.wdp, vertical = 5.wdp)
                )
            }
        }

        Spacer(Modifier.height(17.wdp))

        val emptyNow = when (tab) {
            SearchTab.SONG -> results.isEmpty()
            SearchTab.ARTIST -> artistResults.isEmpty()
            SearchTab.ALBUM -> albumResults.isEmpty()
            SearchTab.PLAYLIST -> playlistResults.isEmpty()
        }

        when {
            // 首次搜索才出骨架；已有结果时继续显示旧列表，避免整页闪空
            searching && emptyNow -> SearchSkeleton()
            // 正在输入（还没提交）→ 出联想词
            keyword.isNotEmpty() && !submitted && suggestions.isNotEmpty() ->
                SuggestionList(suggestions) { onSuggestionClick(it); submitted = true }
            error != null -> EmptyState(message = error)
            keyword.isEmpty() ->
                SearchHistoryPane(
                    history = history,
                    onHistoryClick = onHistoryClick,
                    onHistoryRemove = onHistoryRemove,
                    onHistoryClear = onHistoryClear,
                    hotKeywords = hotKeywords,
                    onHotClick = { onHotClick(it); submitted = true }
                )
            emptyNow -> EmptyState(
                message = when (tab) {
                    SearchTab.SONG -> "没有找到相关歌曲"
                    SearchTab.ARTIST -> "没有找到相关歌手"
                    SearchTab.ALBUM -> "没有找到相关专辑"
                    SearchTab.PLAYLIST -> "没有找到相关歌单"
                }
            )
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                // 留出底部「迷你条 + Tab 栏」的高度，否则最后几条被压住点不到
                contentPadding = PaddingValues(bottom = 142.wdp)
            ) {
                when (tab) {
                    SearchTab.SONG -> items(results, key = { it.id }) { t ->
                        SearchResultRow(
                            track = t,
                            onClick = { onResultClick(t, results) },
                            onMore = { menuTrack = t },
                            onDownload = onDownload,
                            downloaded = t.id in downloadedIds
                        )
                    }
                    SearchTab.ARTIST -> items(artistResults, key = { it.id }) { a ->
                        BriefRow(
                            title = a.name,
                            subtitle = "${a.songCount} 首 · ${a.albumCount} 张专辑",
                            coverUrl = a.picUrl,
                            round = true
                        ) { onArtistClick(a) }
                    }
                    SearchTab.ALBUM -> items(albumResults, key = { it.id }) { a ->
                        BriefRow(
                            title = a.name,
                            subtitle = listOfNotNull(
                                a.artist.takeIf { it.isNotBlank() },
                                "${a.songCount} 首"
                            ).joinToString(" · "),
                            coverUrl = a.coverUrl
                        ) { onAlbumClick(a) }
                    }
                    SearchTab.PLAYLIST -> items(playlistResults, key = { it.id }) { p ->
                        BriefRow(
                            title = p.name,
                            subtitle = listOfNotNull(
                                p.creator.takeIf { it.isNotBlank() },
                                "${p.trackCount} 首"
                            ).joinToString(" · "),
                            coverUrl = p.coverUrl
                        ) { onPlaylistClick(p) }
                    }
                }
            }
        }
    }

        // 曲目操作菜单
        menuTrack?.let { t ->
            TrackActionSheet(
                track = t,
                onAddToQueue = { onAddToQueue(t); menuTrack = null },
                onPlayNext = { onPlayNext(t); menuTrack = null },
                onDownload = { onDownload(t); menuTrack = null },
                onDismiss = { menuTrack = null }
            )
        }
    }
}

/**
 * 关键词为空时展示：热门搜索 + 搜索历史。
 *
 * 热门搜索取的是**酷我热歌榜的真实歌名**（不是写死的词），所以它会跟着榜单变。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchHistoryPane(
    history: List<String>,
    onHistoryClick: (String) -> Unit,
    onHistoryRemove: (String) -> Unit,
    onHistoryClear: () -> Unit,
    hotKeywords: List<String> = emptyList(),
    onHotClick: (String) -> Unit = {}
) {
    val c = moyinColors()
    if (history.isEmpty() && hotKeywords.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("输入关键词开始搜索", color = c.t3, fontSize = 14.wsp)
        }
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        // 留出底部「迷你条 + Tab 栏」的高度
        contentPadding = PaddingValues(bottom = 142.wdp)
    ) {
        if (hotKeywords.isNotEmpty()) {
            item {
                Spacer(Modifier.height(4.wdp))
                Text("热门搜索", color = c.t2, fontSize = 13.wsp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(10.wdp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(7.wdp),
                    verticalArrangement = Arrangement.spacedBy(7.wdp)
                ) {
                    hotKeywords.forEach { k ->
                        Text(
                            text = k,
                            color = c.t1,
                            fontSize = 12.wsp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .clip(RoundedCornerShape(99.dp))
                                .background(c.s2)
                                .tvFocus(shape = RoundedCornerShape(99.dp))
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() }
                                ) { onHotClick(k) }
                                .padding(horizontal = 11.wdp, vertical = 6.wdp)
                        )
                    }
                }
            }
        }

        if (history.isNotEmpty()) {
            item {
                Spacer(Modifier.height(if (hotKeywords.isEmpty()) 4.wdp else 22.wdp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("搜索历史", color = c.t2, fontSize = 13.wsp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "清空",
                        color = c.t3,
                        fontSize = 12.wsp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(99.dp))
                            .tvFocus(shape = RoundedCornerShape(99.dp))
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { onHistoryClear() }
                            .padding(horizontal = 8.wdp, vertical = 4.wdp)
                    )
                }
                Spacer(Modifier.height(4.wdp))
            }
            items(history) { k ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .tvFocus(fullWidth = true)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { onHistoryClick(k) }
                        .padding(vertical = 11.wdp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MoyinIcon(icon = IconSearch, tint = c.t3, size = 15.wdp)
                    Spacer(Modifier.width(10.wdp))
                    Text(
                        text = k,
                        color = c.t1,
                        fontSize = 13.wsp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.wdp))
                    Box(
                        Modifier
                            .size(32.wdp)
                            .tvFocus()
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { onHistoryRemove(k) },
                        contentAlignment = Alignment.Center
                    ) {
                        MoyinIcon(icon = IconClose, tint = c.t3, size = 12.wdp)
                    }
                }
            }
        }
    }
}

/** 联想词：输入过程中展示，点一条即搜 */
@Composable
private fun SuggestionList(suggestions: List<String>, onClick: (String) -> Unit) {
    val c = moyinColors()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 142.wdp)
    ) {
        items(suggestions) { k ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .tvFocus(fullWidth = true)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { onClick(k) }
                    .padding(vertical = 12.wdp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MoyinIcon(icon = IconSearch, tint = c.t3, size = 15.wdp)
                Spacer(Modifier.width(10.wdp))
                Text(
                    text = k,
                    color = c.t1,
                    fontSize = 13.wsp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** 歌手 / 专辑 / 歌单 共用的一行（封面 + 标题 + 副标题） */
@Composable
private fun BriefRow(
    title: String,
    subtitle: String,
    coverUrl: String?,
    round: Boolean = false,
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
            ) { onClick() }
            .padding(vertical = 8.wdp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverArt(
            coverUrl = coverUrl,
            modifier = Modifier.size(48.wdp),
            cornerRadius = if (round) 99.wdp else 8.wdp
        )
        Spacer(Modifier.width(12.wdp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = c.t1,
                fontSize = 14.wsp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.wdp))
                Text(
                    text = subtitle,
                    color = c.t3,
                    fontSize = 11.wsp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SearchResultRow(
    track: Track,
    onClick: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    onDownload: ((Track) -> Unit)? = null,
    downloaded: Boolean = false
) {
    val c = moyinColors()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .pressScale { onClick() }
            .padding(vertical = 8.wdp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverArt(coverUrl = track.coverUrl, modifier = Modifier.size(48.wdp), cornerRadius = 8.wdp)
        Spacer(Modifier.width(12.wdp))
        Column(Modifier.weight(1f)) {
            Text(
                text = track.name,
                color = c.t1,
                fontSize = 14.wsp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 来源标记：搜索聚合了本地/Navidrome/酷我三源，不分清会让人以为都是在线歌
                val (tag, tagColor) = when (track.source) {
                    "local" -> "本地" to c.t3
                    "navidrome" -> "曲库" to c.t3
                    "netease" -> "网易云" to c.t3
                    "migu" -> "咪咕" to c.t3
                    else -> "酷我" to c.t3
                }
                Text(
                    text = tag,
                    color = tagColor,
                    fontSize = 10.wsp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(c.s2)
                        .padding(horizontal = 4.wdp, vertical = 1.wdp)
                )
                Spacer(Modifier.width(6.wdp))
                Text(
                    text = track.artist + if (track.album.isNotEmpty()) " · ${track.album}" else "",
                    color = c.t3,
                    fontSize = 11.wsp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (track.isMaster) {
            com.kmusic.apkhd.ui.components.QualityTag(text = "母带")
            Spacer(Modifier.width(4.wdp))
        }
        if (onDownload != null && track.source != "local") {
            Box(
                Modifier
                    .size(40.wdp)
                    .clip(RoundedCornerShape(99.dp))
                    .tvFocus(shape = RoundedCornerShape(99.dp))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { if (!downloaded) onDownload(track) },
                contentAlignment = Alignment.Center
            ) {
                MoyinIcon(
                    icon = IconDownload,
                    tint = if (downloaded) c.accent else c.t3,
                    size = 17.wdp
                )
            }
        }
        Box(
            Modifier
                .size(40.wdp)
                .clip(RoundedCornerShape(99.dp))
                .tvFocus(shape = RoundedCornerShape(99.dp))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onMore() },
            contentAlignment = Alignment.Center
        ) {
            MoyinIcon(icon = IconMore, tint = c.t3, size = 16.wdp)
        }
    }
}

/** 曲目操作菜单：加入队列 / 下一首播放 / 下载 */
@Composable
private fun TrackActionSheet(
    track: Track,
    onAddToQueue: () -> Unit,
    onPlayNext: () -> Unit,
    onDownload: () -> Unit,
    onDismiss: () -> Unit
) {
    val c = moyinColors()
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .tvFocus()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onDismiss() }
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 18.wdp, topEnd = 18.wdp))
                .background(if (c.isDark) c.s1 else Color(0xFFFFFFFF))
                .tvFocus(shape = RoundedCornerShape(topStart = 18.wdp, topEnd = 18.wdp))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { }
                .sheetBottomPadding()
                .padding(vertical = 14.wdp)
        ) {
            Text(
                track.name,
                color = c.t1,
                fontSize = 14.wsp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.wdp)
            )
            if (track.artist.isNotEmpty()) {
                Spacer(Modifier.height(2.wdp))
                Text(
                    track.artist,
                    color = c.t3,
                    fontSize = 11.wsp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 20.wdp)
                )
            }
            Spacer(Modifier.height(12.wdp))
            ActionRow("加入队列", IconQueue) { onAddToQueue() }
            ActionRow("下一首播放", IconNext) { onPlayNext() }
            ActionRow("下载（母带/无损）", IconDownload) { onDownload() }
        }
    }
}

@Composable
private fun ActionRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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
            ) { onClick() }
            .padding(horizontal = 20.wdp, vertical = 13.wdp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MoyinIcon(icon = icon, tint = c.t2, size = 17.wdp)
        Spacer(Modifier.width(14.wdp))
        Text(label, color = c.t1, fontSize = 14.wsp)
    }
}

@Composable
fun EmptyState(
    message: String,
    modifier: Modifier = Modifier
) {
    val c = moyinColors()
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(bottom = 24.wdp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(56.wdp)
                .clip(RoundedCornerShape(99.dp))
                .background(c.s2),
            contentAlignment = Alignment.Center
        ) {
            MoyinIcon(icon = IconSearch, tint = c.t3, size = 24.wdp)
        }
        Spacer(Modifier.height(15.wdp))
        Text(message, color = c.t2, fontSize = 13.wsp)
    }
}

/**
 * 首屏骨架：搜索通常 0.5–2 秒，比一行「搜索中…」更能表达「内容马上来」。
 * 只在**还没有任何结果**时出现，已有结果的二次搜索继续显示旧列表。
 */
@Composable
private fun SearchSkeleton(rows: Int = 7) {
    val c = moyinColors()
    val blockColor = if (c.isDark) c.s3 else c.s2
    Column(
        Modifier
            .fillMaxSize()
            .padding(top = 6.wdp)
    ) {
        repeat(rows) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.wdp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(44.wdp)
                        .clip(RoundedCornerShape(8.wdp))
                        .background(blockColor)
                )
                Spacer(Modifier.width(10.wdp))
                Column(Modifier.weight(1f)) {
                    Box(
                        Modifier
                            .fillMaxWidth(0.55f)
                            .height(12.wdp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(blockColor)
                    )
                    Spacer(Modifier.height(7.dp))
                    Box(
                        Modifier
                            .fillMaxWidth(0.35f)
                            .height(10.wdp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(blockColor.copy(alpha = 0.7f))
                    )
                }
            }
        }
    }
}
