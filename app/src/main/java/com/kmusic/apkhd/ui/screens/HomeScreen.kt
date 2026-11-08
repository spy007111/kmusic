package com.kmusic.apkhd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.kmusic.apkhd.ui.tv.requestFocusSafely
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kmusic.apkhd.model.QuickEntry
import com.kmusic.apkhd.model.ChartSection
import com.kmusic.apkhd.model.PlaylistBrief
import com.kmusic.apkhd.model.ArtistBrief
import com.kmusic.apkhd.model.Track
import com.kmusic.apkhd.ui.components.CoverArt
import androidx.compose.foundation.border
import com.kmusic.apkhd.ui.components.MoyinTitle
import com.kmusic.apkhd.ui.components.MoyinNum
import com.kmusic.apkhd.ui.components.MoyinDivider
import com.kmusic.apkhd.ui.components.MoyinIcon
import com.kmusic.apkhd.ui.components.SectionHeader
import androidx.compose.foundation.combinedClickable
import com.kmusic.apkhd.ui.icons.IconHeart
import com.kmusic.apkhd.ui.icons.IconPlay
import com.kmusic.apkhd.ui.icons.IconSearch
import com.kmusic.apkhd.ui.theme.ambientAlpha
import com.kmusic.apkhd.ui.theme.ambientGlow
import com.kmusic.apkhd.ui.theme.cardGradient
import com.kmusic.apkhd.ui.theme.cardHighlightGradient
import com.kmusic.apkhd.ui.theme.coverRadiusOf
import com.kmusic.apkhd.ui.theme.moyinColors
import com.kmusic.apkhd.ui.theme.window
import com.kmusic.apkhd.ui.theme.wdp
import com.kmusic.apkhd.ui.theme.wsp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Color as UiColor
import com.kmusic.apkhd.ui.icons.IconDownload
import com.kmusic.apkhd.ui.icons.IconRefresh
import com.kmusic.apkhd.ui.tv.tvFocus
import com.kmusic.apkhd.ui.tv.tvFocusArea
import com.kmusic.apkhd.ui.tv.tvRowFocus
import com.kmusic.apkhd.ui.tv.tvRowItemEdge
import com.kmusic.apkhd.ui.tv.LocalIsTv

/**
 * 首页 —— 与设计稿 01 一致：
 * 问候语 + 搜索框 + 四个入口 + 横滑推荐卡 + 迷你播放条
 */
@Composable
fun HomeScreen(
    entries: List<QuickEntry>,
    recentTracks: List<Track>,
    charts: List<ChartSection> = emptyList(),
    playlists: List<PlaylistBrief> = emptyList(),
    artists: List<ArtistBrief> = emptyList(),
    onSearchClick: () -> Unit,
    onEntryClick: (QuickEntry) -> Unit,
    onTrackClick: (Track, List<Track>) -> Unit,
    onRecentClick: (Track, List<Track>) -> Unit,
    /** 打开完整「最近播放」列表（媒体库） */
    onOpenRecentList: () -> Unit = {},
    onDownload: ((Track) -> Unit)? = null,
    downloadedIds: Set<String> = emptySet(),
    onChartClick: (ChartSection) -> Unit = {},
    onPlaylistClick: (PlaylistBrief) -> Unit = {},
    /** 该歌单是否已收藏（首页卡片角标） */
    isPlaylistFavorite: (PlaylistBrief) -> Boolean = { false },
    /** 长按卡片收藏 / 取消收藏 */
    onTogglePlaylistFavorite: (PlaylistBrief) -> Unit = {},
    onArtistClick: (ArtistBrief) -> Unit = {},
    /** 换一批（推荐歌单 + 音乐人） */
    onRefreshPlaylists: () -> Unit = {},
    onRefreshArtists: () -> Unit = {},
    playlistsRefreshing: Boolean = false,
    artistsRefreshing: Boolean = false,
    /** v2.4.5：横向滚到末尾自动加载下一批 */
    onLoadMorePlaylists: () -> Unit = {},
    onLoadMoreArtists: () -> Unit = {},
    playlistsLoadingMore: Boolean = false,
    artistsLoadingMore: Boolean = false,
    playlistsExhausted: Boolean = false,
    artistsExhausted: Boolean = false,
    onMiniPlayerClick: () -> Unit,
    onTogglePlay: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = moyinColors()
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(c.bg)
            // 顶部氛围光：从屏幕顶端往下洇开一层强调色。
            // 实现见 AmbientGlow.kt —— 椭圆的纵向半轴压在光带高度之内，
            // 底边是「背景色 → 背景色」，宽屏也不会被裁出一条横向接缝。
            .ambientGlow(
                tint = c.accent,
                bg = c.bg,
                alpha = c.ambientAlpha,
                baseHeight = 340.wdp
            )
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
        // v2.4.2：搜索框 ↓ 的落点。
        //
        // v2.4.1 这里靠几何搜索能走到下面的卡片，v2.4.2 去掉 `graphicsLayer` 缩放后
        // 节点边界变了，几何搜索从搜索框（满宽 44dp 条）往下找不到落点，
        // 遥控在搜索框上按 ↓/→ 全部无响应（实测 v2.4.1 正常、v2.4.2 卡死）。
        // 显式指定第一张卡 —— 比赌几何搜索可靠。
        val firstEntryFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        // v2.4.5：首页外层纵向滚动状态。不只为滚，还要给「某区块的换一批是否还在
        // 视口内」的判断提供 scrollY —— 跨区 ↓ 指向已滚出屏幕的 requester 时
        // Compose 不报错、按键被静默吞掉（v2.4.4 的同一类坑）。
        val homeScroll = rememberScrollState()
        // v2.4.3：「换一批」与各区块首卡的焦点 requester。
        // 必须声明在 LazyColumn **外面** —— `run {}` / `items()` 的 lambda 是
        // `LazyListScope` 作用域，不是 @Composable，里面不能调 remember。
        val refreshPlFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        val plFirstFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        val refreshArFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        val arFirstFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        // 排行榜首卡：继续播放卡 ↓ 直接落到这里（中间隔着区块标题，几何搜索会跳过整行）
        val chartFirstFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        // v2.4.4：外层从 LazyColumn 改成「一次性组合 + 滚动」的 Column。
        // 根因：LazyColumn 会把滚出屏幕的区块整个 dispose，而 `focusProperties { up/down = fr }`
        // 指向一个**已随区块被销毁**的 requester 时不抛异常、按键直接没反应 ——
        // 实测从推荐歌单卡片按 ↑（目标「换一批」已滚出屏幕）焦点原地不动，
        // 从排行榜卡片按 ↓ 则被系统几何搜索扔进左侧导航栏。
        // 首页一共只有 8 个区块，全量组合的代价可以忽略，换来跨区块连线恒定有效。
        Column(
            modifier = Modifier
                .weight(1f)
                // v2.4.5：滚动位置要能被「音乐人换一批是否可见」的判断读取，
                // 否则跨区 ↓ 只能指向一个已滚出屏幕的节点（按键被吞）。
                .verticalScroll(homeScroll)
                .padding(start = 16.wdp, end = 16.wdp, top = 8.wdp, bottom = 142.wdp)
        ) {
            run {
                Text(
                    text = greeting(),
                    color = c.t2,
                    fontSize = 14.wsp
                )
                Spacer(Modifier.height(12.wdp))
                MoyinTitle(text = "听点什么", size = 30)
                Spacer(Modifier.height(24.wdp))

                // 搜索入口（点击跳到搜索页）
                Column {
                    // 两套主题都必须有形状和边界。曾经浅色只画一条下划线，
                    // 结果是「深色有胶囊、浅色什么都没有」—— 看起来像圆角没做。
                    // 浅色改成同半径的实底 + 细线：纸感靠线，但形状必须和深色一致。
                    val searchShape = RoundedCornerShape(c.radiusCard)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.wdp)
                            .clip(searchShape)
                            .background(if (c.isDark) c.glass else c.s2)
                            .border(
                                width = 1.wdp,
                                color = if (c.isDark) c.glassBorder else c.lineStrong.copy(alpha = 0.22f),
                                shape = searchShape
                            )
                            // ↓ 显式交给下面第一张入口卡（几何搜索在满宽条上会跳空）
                            // ↑ 必须钉死：不写的话几何搜索会往上找到左侧导航栏，
                            // 从首页首行按一次 ↑ 焦点就飞进 rail（实测）。
                            .focusProperties {
                                up = androidx.compose.ui.focus.FocusRequester.Cancel
                                left = androidx.compose.ui.focus.FocusRequester.Cancel
                                right = firstEntryFocus
                                down = firstEntryFocus
                            }
                            .tvFocus()
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { onSearchClick() }
                            .padding(horizontal = 14.wdp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MoyinIcon(icon = IconSearch, tint = c.t3, size = 18.wdp)
                        Spacer(Modifier.width(10.wdp))
                        Text(
                            text = "搜索歌曲、歌手、专辑",
                            color = c.t3,
                            fontSize = 14.wsp,
                            letterSpacing = if (c.titleSerif) 0.4.sp else 0.sp
                        )
                    }
                }
                Spacer(Modifier.height(24.wdp))
            }

            // 四个入口（宽屏 4 列一行排开，窄屏 2×2）
            run {
                Column {
                    val cols = if (window.isWide) 4 else 2
                    // ⚠️ v2.4.3 修：`chunked()` 每次调用都返回**新的 List**，
                    // 原来写 `row === entries.chunked(cols).first()` 是在拿两个
                    // 不同对象比引用 —— 永远 false，`focusRequester(firstEntryFocus)`
                    // 一个卡片都没挂上。于是搜索框的 `down = firstEntryFocus`
                    // 指向一个「从未挂到焦点树」的 requester，按 ↓ 就是死键。
                    // 实测：搜索框上连按 ↓×14，焦点 bbox 一次没动。
                    // 现在先把分组算好，用下标判断首行。
                    val entryRows = entries.chunked(cols)
                    entryRows.forEachIndexed { rowIdx, row ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.wdp),
                            horizontalArrangement = Arrangement.spacedBy(8.wdp)
                        ) {
                            row.forEachIndexed { colIdx, entry ->
                                QuickEntryCard(
                                    entry = entry,
                                    modifier = Modifier
                                        .weight(1f)
                                        .then(
                                            if (rowIdx == 0 && colIdx == 0)
                                                Modifier.focusRequester(firstEntryFocus)
                                            else Modifier
                                        ),
                                    onClick = { onEntryClick(entry) }
                                )
                            }
                            // 最后一行不足时补占位，避免卡片被拉宽
                            repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                Spacer(Modifier.height(20.wdp))
            }

            // 继续播放：把最近一首提到顶部，一键续播（比让用户去列表里找少一步）
            recentTracks.firstOrNull()?.let { last ->
                run {
                    ContinueCard(
                        track = last,
                        recentCount = recentTracks.size,
                        onClick = { onRecentClick(last, recentTracks) },
                        onOpenList = onOpenRecentList,
                        modifier = Modifier
                            // ↓ 直接落到排行榜首卡：中间隔着区块标题，几何搜索会跳过整行
                            .focusProperties {
                                down = when {
                                    charts.isNotEmpty() -> chartFirstFocus
                                    playlists.isNotEmpty() -> refreshPlFocus
                                    else -> androidx.compose.ui.focus.FocusRequester.Cancel
                                }
                            }
                            .padding(top = 14.wdp)
                    )
                }
            }

            // ---- 排行榜（酷我原生，点进去必定能播） ----
            if (charts.isNotEmpty()) {
                run {
                    Spacer(Modifier.height(22.wdp))
                    SectionHeader(text = "排行榜", hint = "可播放")
                    Spacer(Modifier.height(12.wdp))
                }
                run {
                    // TV：LazyRow 自身要成为一个焦点组，否则按右键会把焦点甩到别处
                    // （侧栏 / 下一行），而不是在本行里横向移动。
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.wdp),
                        modifier = Modifier.tvRowFocus()
                    ) {
                        itemsIndexed(charts) { i, sec ->
                            ChartCard(
                                section = sec,
                                onClick = { onChartClick(sec) },
                                modifier = Modifier.tvRowItemEdge(i, charts.size)
                                    .then(if (LocalIsTv.current && i == 0) Modifier.focusRequester(chartFirstFocus) else Modifier)
                                    .then(
                                        // 榜单行的下一张不是普通几何目标：显式落到推荐歌单的
                                        // 「换一批」，从任意榜单卡按 ↓ 都能到达。
                                        if (LocalIsTv.current && playlists.isNotEmpty())
                                            Modifier.focusProperties { down = refreshPlFocus }
                                        else Modifier
                                    )
                            )
                        }
                    }
                }
            }

            // ---- 推荐歌单（网易云推荐位） ----
            if (playlists.isNotEmpty()) {
                // v2.4.3：「换一批」是个 60×24dp 的小胶囊，几何搜索从上面卡片按 ↓
                // 永远输给下方整行卡片（面积大、水平重叠多），从卡片按 ↑ 又回到上一行卡片
                // —— 上下都进不去。改成显式连线：换一批 ↓ = 本行首卡，首卡 ↑ = 换一批。
                val plListState = rememberLazyListState()
                // v2.4.5：点「换一批」→ 列表被整体替换，持焦节点消失 → Compose 把焦点
                // 扔回左侧导航栏（实测 bounds 落到 rail 上）。刷新**结束的那一刻**
                // 把焦点请回按钮本身，用户按 ↓ 可以接着往卡片走。
                // ⚠️ 不能 key 在 `playlists` 上：滚到底追加时列表也会变，
                // 那样每次加载下一批焦点都会被抢回标题栏。
                val isTvHome = LocalIsTv.current
                var plWasRefreshing by remember { mutableStateOf(false) }
                LaunchedEffect(playlistsRefreshing) {
                    if (plWasRefreshing && !playlistsRefreshing && isTvHome) {
                        refreshPlFocus.requestFocusSafely()
                    }
                    plWasRefreshing = playlistsRefreshing
                }
                // v2.4.5：滚到末尾自动追加下一批。
                // 判据用「最后一个可见项下标 ≥ size-3」，不是 firstVisibleItemIndex ——
                // 后者说的是「滚到哪了」，一屏能放 5 张时它早就等于 4 了，会误触发。
                // 只在 TV/触摸都能滚的 LazyRow 里生效，两条路径共用同一份逻辑。
                LaunchedEffect(plListState, playlists.size, playlistsExhausted) {
                    snapshotFlow {
                        val info = plListState.layoutInfo
                        (info.visibleItemsInfo.lastOrNull()?.index ?: -1) to playlists.size
                    }.collect { (lastVisible, total) ->
                        if (total > 0 && lastVisible >= total - 3 && !playlistsExhausted) {
                            onLoadMorePlaylists()
                        }
                    }
                }
                run {
                    Spacer(Modifier.height(22.wdp))
                    SectionHeader(
                        text = "推荐歌单",
                        trailing = {
                            RefreshButton(
                                onClick = onRefreshPlaylists,
                                busy = playlistsRefreshing,
                                modifier = Modifier
                                    .focusRequester(refreshPlFocus)
                                    .focusProperties {
                                        down = plFirstFocus
                                        if (artists.isNotEmpty()) right = refreshArFocus
                                    }
                            )
                        }
                    )
                    Spacer(Modifier.height(12.wdp))
                }
                run {
                    LazyRow(
                        state = plListState,
                        horizontalArrangement = Arrangement.spacedBy(12.wdp),
                        modifier = Modifier.tvRowFocus()
                    ) {
                        itemsIndexed(playlists) { i, pl ->
                            PlaylistCard(
                                playlist = pl,
                                onClick = { onPlaylistClick(pl) },
                                favorited = isPlaylistFavorite(pl),
                                onLongPress = { onTogglePlaylistFavorite(pl) },
                                modifier = Modifier
                                    .tvRowItemEdge(i, playlists.size)
                                    .then(
                                        if (LocalIsTv.current) Modifier
                                            .then(if (i == 0) Modifier.focusRequester(plFirstFocus) else Modifier)
                                            .focusProperties {
                                                up = refreshPlFocus
                                                // v2.4.5：音乐人「换一批」在小胶囊形态下几何面积太小，
                                                // 从歌单行按 ↓ 时若指向它，Compose 有时会判定「没有可聚焦
                                                // 目标」→ 按键被系统几何搜索接管，焦点飞进左侧导航栏。
                                                // 直落音乐人首卡：位置确定、面积大、用户预期就是「下一行」。
                                                if (artists.isNotEmpty()) down = arFirstFocus
                                            }
                                        else Modifier
                                    )
                            )
                        }
                    }
                }
            }

            // ---- 音乐人 ----
            if (artists.isNotEmpty()) {
                val arListState = rememberLazyListState()
                // v2.4.5：同推荐歌单，滚到末尾自动追加下一批（offset 翻页，稳定）
                LaunchedEffect(arListState, artists.size, artistsExhausted) {
                    snapshotFlow {
                        val info = arListState.layoutInfo
                        (info.visibleItemsInfo.lastOrNull()?.index ?: -1) to artists.size
                    }.collect { (lastVisible, total) ->
                        if (total > 0 && lastVisible >= total - 3 && !artistsExhausted) {
                            onLoadMoreArtists()
                        }
                    }
                }
                run {
                    Spacer(Modifier.height(22.wdp))
                    SectionHeader(
                        text = "音乐人",
                        trailing = {
                            RefreshButton(
                                onClick = onRefreshArtists,
                                busy = artistsRefreshing,
                                modifier = Modifier
                                    .focusRequester(refreshArFocus)
                                    .focusProperties {
                                        down = arFirstFocus
                                        up = refreshPlFocus
                                    }
                            )
                        }
                    )
                    Spacer(Modifier.height(12.wdp))
                }
                run {
                    LazyRow(
                        state = arListState,
                        horizontalArrangement = Arrangement.spacedBy(16.wdp),
                        modifier = Modifier.tvRowFocus()
                    ) {
                        itemsIndexed(artists) { i, a ->
                            ArtistCircle(
                                artist = a,
                                onClick = { onArtistClick(a) },
                                modifier = Modifier
                                    .tvRowItemEdge(i, artists.size)
                                    .then(
                                        if (LocalIsTv.current) Modifier
                                            .then(if (i == 0) Modifier.focusRequester(arFirstFocus) else Modifier)
                                            .focusProperties { up = refreshArFocus }
                                        else Modifier
                                    )
                            )
                        }
                    }
                }
            }
        }

        }
    }
}

/**
 * 「换一批」按钮 —— 推荐歌单 / 音乐人区块标题右侧。
 *
 * 做成**文字 + 图标**而不是图标按钮：区块标题行高只有 12.wsp 左右，
 * 纯图标没有说明性，用户不知道点了会发生什么。
 * busy 态下禁用点击并显示「换一批中」，避免连点造成并发请求。
 */
@Composable
private fun RefreshButton(
    onClick: () -> Unit,
    busy: Boolean,
    modifier: Modifier = Modifier
) {
    val c = moyinColors()
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(99.wdp))
            .background(if (busy) c.s2 else c.accentDim)
            .tvFocus(shape = RoundedCornerShape(99.wdp))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = !busy,
                onClick = onClick
            )
            .padding(horizontal = 10.wdp, vertical = 4.wdp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MoyinIcon(
            icon = IconRefresh,
            tint = if (busy) c.t3 else c.accent,
            size = 13.wdp
        )
        Spacer(Modifier.width(4.wdp))
        Text(
            text = if (busy) "换一批中" else "换一批",
            color = if (busy) c.t3 else c.accent,
            fontSize = 11.wsp
        )
    }
}

@Composable
private fun QuickEntryCard(
    entry: QuickEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = moyinColors()
    // 渐变由主题 token 生成（浅色也有）：高亮卡用强调色淡染，普通卡 s1→s2 / s3→s2
    val bg = Brush.linearGradient(
        if (entry.highlighted) c.cardHighlightGradient else c.cardGradient
    )
    val titleColor = if (entry.highlighted) c.accent else c.t1
    val subColor = if (entry.highlighted) c.t2 else c.t3

    Column(
        modifier = modifier
            .height(72.wdp)
            .clip(RoundedCornerShape(c.radiusCard))
            .background(bg)
            .tvFocus(shape = RoundedCornerShape(c.radiusCard))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
            .padding(horizontal = 12.wdp, vertical = 10.wdp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = entry.title, color = titleColor, fontSize = 13.wsp, maxLines = 1)
        Spacer(Modifier.height(12.wdp))
        Text(text = entry.subtitle, color = subColor, fontSize = 10.wsp, maxLines = 1)
    }
}

@Composable
private fun ContinueCard(
    track: Track,
    recentCount: Int,
    onClick: () -> Unit,
    onOpenList: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = moyinColors()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(c.radiusCard))
            .background(Brush.linearGradient(c.cardHighlightGradient))
            .tvFocus(shape = RoundedCornerShape(c.radiusCard))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
            .padding(horizontal = 12.wdp, vertical = 11.wdp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverArt(
            coverUrl = track.coverUrl,
            modifier = Modifier.size(54.wdp),
            cornerRadius = coverRadiusOf(c)
        )
        Spacer(Modifier.width(12.wdp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "继续播放",
                color = c.accent,
                fontSize = 10.wsp,
                letterSpacing = 1.2.sp
            )
            Spacer(Modifier.height(3.wdp))
            Text(
                text = track.name,
                color = c.t1,
                fontSize = 15.wsp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.wdp))
            Text(
                text = track.artist,
                color = c.t3,
                fontSize = 11.wsp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(10.wdp))
        Column(horizontalAlignment = Alignment.End) {
            MoyinIcon(icon = IconPlay, tint = c.accent, size = 22.wdp)
            Spacer(Modifier.height(4.wdp))
            // 完整列表在媒体库；这里只留一个轻入口，避免首页再长出一份列表
            Text(
                text = "最近 $recentCount 首 ›",
                color = c.t3,
                fontSize = 10.wsp,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .tvFocus(shape = RoundedCornerShape(6.dp))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { onOpenList() }
                    .padding(horizontal = 2.wdp, vertical = 2.wdp)
            )
        }
    }
}

@Composable
private fun TrackRow(
    track: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onDownload: ((Track) -> Unit)? = null,
    downloaded: Boolean = false,
    /** 列表序号。浅色（B）会渲染成砖红色的 01/02/03 */
    index: Int? = null
) {
    val c = moyinColors()
    // 浅色走「纸感编号列表」：编号 + 方形封面 + 底部实线；
    // 深色走「卡片流」：无编号、圆角封面、靠间距分组
    val paper = c.titleSerif
    Column(modifier.fillMaxWidth()) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .tvFocus(fullWidth = true)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
            .padding(vertical = if (paper) 11.wdp else 8.wdp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (paper && index != null) {
            MoyinNum(index, Modifier.width(24.wdp))
        }
        CoverArt(
            coverUrl = track.coverUrl,
            modifier = Modifier.size(if (paper) 52.wdp else 48.wdp),
            cornerRadius = if (paper) 2.wdp else 7.wdp
        )
        Spacer(Modifier.width(12.wdp))
        Column(Modifier.weight(1f)) {
            Text(
                text = track.name,
                color = c.t1,
                fontSize = 14.wsp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = track.artist,
                color = c.t3,
                fontSize = 11.wsp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (track.durationMs > 0) {
            Text(
                text = formatDuration(track.durationMs),
                color = c.t3,
                fontSize = 10.wsp
            )
        }
        if (onDownload != null && track.source != "local") {
            Spacer(Modifier.width(4.wdp))
            Box(
                Modifier
                    .size(40.wdp)
                    .clip(RoundedCornerShape(12.wdp))
                    .tvFocus(shape = RoundedCornerShape(12.wdp))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { if (!downloaded) onDownload(track) },
                contentAlignment = Alignment.Center
            ) {
                MoyinIcon(
                    icon = IconDownload,
                    tint = if (downloaded) c.accent else c.t2,
                    size = 19.wdp
                )
            }
        }
    }
        if (paper) MoyinDivider()
    }
}

@Composable
private fun RecommendedCard(
    track: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = moyinColors()
    Column(
        modifier = modifier
            .width(140.wdp)
            .tvFocus()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
    ) {
        CoverArt(
            coverUrl = track.coverUrl,
            modifier = Modifier.size(140.wdp),
            cornerRadius = 12.wdp,
            qualityTag = if (track.qualityLabel.isNotEmpty()) track.qualityLabel else null
        )
        Spacer(Modifier.height(8.wdp))
        Text(
            text = track.name,
            color = c.t1,
            fontSize = 13.wsp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = track.artist,
            color = c.t3,
            fontSize = 11.wsp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

internal fun greeting(): String {
    val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return when (h) {
        in 5..11 -> "早上好"
        in 12..13 -> "中午好"
        in 14..17 -> "下午好"
        in 18..22 -> "晚上好"
        else -> "夜深了"
    }
}

internal fun formatDuration(ms: Long): String {
    val total = (ms / 1000).toInt()
    return "%d:%02d".format(total / 60, total % 60)
}

internal val Color0xFF262A4E = androidx.compose.ui.graphics.Color(0xFF262A4E)
internal val Color0xFF1A1D33 = androidx.compose.ui.graphics.Color(0xFF1A1D33)
internal val Color0xFFE4E3FC = androidx.compose.ui.graphics.Color(0xFFE4E3FC)
internal val Color0xFFDCDBF8 = androidx.compose.ui.graphics.Color(0xFFDCDBF8)
internal val Color0xFFC9C8FF = androidx.compose.ui.graphics.Color(0xFFC9C8FF)
internal val Color0xFF8F8ED6 = androidx.compose.ui.graphics.Color(0xFF8F8ED6)
internal val Color0xFF3F3CB8 = androidx.compose.ui.graphics.Color(0xFF3F3CB8)
internal val Color0xFF6F6CC9 = androidx.compose.ui.graphics.Color(0xFF6F6CC9)

// 设计稿固定色（不随主题变化的部分）
internal val MoyColor0DFFFFFF = UiColor(0x0DFFFFFF)
internal val MoyColor09000000 = UiColor(0x09000000)
internal val MoyColor262A4E = UiColor(0xFF262A4E)
internal val MoyColor1A1D33 = UiColor(0xFF1A1D33)
internal val MoyColorE4E3FC = UiColor(0xFFE4E3FC)
internal val MoyColorDCDBF8 = UiColor(0xFFDCDBF8)
internal val MoyColorC9C8FF = UiColor(0xFFC9C8FF)
internal val MoyColor8F8ED6 = UiColor(0xFF8F8ED6)
internal val MoyColor3F3CB8 = UiColor(0xFF3F3CB8)
internal val MoyColor6F6CC9 = UiColor(0xFF6F6CC9)

// ---------------- 发现页卡片 ----------------

/** 排行榜卡片（酷我原生，点进去必定能播） */
@Composable
private fun ChartCard(
    section: ChartSection,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = moyinColors()
    Column(
        modifier = modifier
            .width(150.wdp)
            // v2.4.5：高亮只裁到封面（理由见 PlaylistCard）
            .tvFocusArea(height = 150.wdp, cornerRadius = coverRadiusOf(c))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
    ) {
        Box(
            Modifier
                .size(150.wdp)
                .clip(RoundedCornerShape(c.radiusCard))
                .then(
                    if (!section.chart.coverUrl.isNullOrEmpty()) Modifier
                    else Modifier.background(androidx.compose.ui.graphics.Brush.linearGradient(c.coverGrad))
                )
        ) {
            if (!section.chart.coverUrl.isNullOrEmpty()) {
                CoverArt(
                    coverUrl = section.chart.coverUrl,
                    modifier = Modifier.fillMaxSize(),
                    cornerRadius = coverRadiusOf(c)
                )
            }
        }
        Spacer(Modifier.height(8.wdp))
        Text(
            section.chart.name,
            color = c.t1,
            fontSize = 13.wsp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.wdp))
        Text(
            "${section.tracks.size} 首",
            color = c.t3,
            fontSize = 11.wsp
        )
    }
}

/** 推荐歌单卡片（网易云推荐位，播放时严格校验后回退酷我） */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PlaylistCard(
    playlist: PlaylistBrief,
    onClick: () -> Unit,
    /** 已收藏时右上角显示心形角标。null = 不显示收藏状态（老调用点兼容） */
    favorited: Boolean? = null,
    /** 长按收藏 / 取消收藏。null = 不支持长按 */
    onLongPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val c = moyinColors()
    Column(
        modifier = modifier
            .width(132.wdp)
            // v2.4.5：高亮**只裁到封面这一块**，圆角与封面完全一致。
            // 焦点节点仍然是整列（点击/长按/连线全不动），只是绘制范围收窄 ——
            // 旧版按整列外接矩形上色，封面四角露底色、下方标题区被染色。
            .tvFocusArea(height = 132.wdp, cornerRadius = coverRadiusOf(c))
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onClick() },
                onLongClick = onLongPress
            )
    ) {
        Box(
            Modifier
                .size(132.wdp)
                .clip(RoundedCornerShape(c.radiusCard))
                .background(androidx.compose.ui.graphics.Brush.linearGradient(c.coverGrad))
        ) {
            if (!playlist.coverUrl.isNullOrEmpty()) {
                CoverArt(
                    coverUrl = playlist.coverUrl,
                    modifier = Modifier.fillMaxSize(),
                    cornerRadius = coverRadiusOf(c)
                )
            }
            // 收藏角标：压在封面右下角，半透明黑底保证在任何封面图上都看得清
            if (favorited == true) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.wdp)
                        .size(22.wdp)
                        .clip(RoundedCornerShape(11.wdp))
                        .background(Color.Black.copy(alpha = 0.42f)),
                    contentAlignment = Alignment.Center
                ) {
                    MoyinIcon(icon = IconHeart, tint = Color.White, size = 12.wdp)
                }
            }
        }
        Spacer(Modifier.height(8.wdp))
        Text(
            playlist.name,
            color = c.t1,
            fontSize = 13.wsp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (playlist.trackCount > 0) {
            Spacer(Modifier.height(2.wdp))
            Text("${playlist.trackCount} 首", color = c.t3, fontSize = 11.wsp)
        }
    }
}

/** 音乐人（圆形头像） */
@Composable
private fun ArtistCircle(
    artist: ArtistBrief,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = moyinColors()
    Column(
        modifier = modifier
            .width(76.wdp)
            // v2.4.5：高亮只裁到头像（正圆）。整列只有 76×68+文字，
            // 裁到 68dp 高度后高亮就是一个与头像完全重合的圆。
            .tvFocusArea(height = 68.wdp, cornerRadius = 99.wdp)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(68.wdp)
                .clip(RoundedCornerShape(99.dp))
                .background(c.s2)
        ) {
            if (!artist.picUrl.isNullOrEmpty()) {
                CoverArt(
                    coverUrl = artist.picUrl,
                    modifier = Modifier.fillMaxSize(),
                    cornerRadius = 99.wdp
                )
            }
        }
        Spacer(Modifier.height(8.wdp))
        Text(
            artist.name,
            color = c.t2,
            fontSize = 12.wsp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
