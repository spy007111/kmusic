package com.kmusic.apkhd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.kmusic.apkhd.model.PlaylistBrief
import com.kmusic.apkhd.model.UserPlaylist
import com.kmusic.apkhd.ui.components.CoverArt
import com.kmusic.apkhd.ui.components.MoyinIcon
import com.kmusic.apkhd.ui.icons.IconBack
import com.kmusic.apkhd.ui.icons.IconChevronRight
import com.kmusic.apkhd.ui.icons.IconHeart
import com.kmusic.apkhd.ui.icons.IconPlus
import com.kmusic.apkhd.ui.theme.moyinColors
import com.kmusic.apkhd.ui.theme.window
import com.kmusic.apkhd.ui.theme.wdp
import com.kmusic.apkhd.ui.theme.wsp
import com.kmusic.apkhd.ui.theme.wspSp
import com.kmusic.apkhd.ui.tv.tvFocus
import com.kmusic.apkhd.ui.tv.requestFocusSafely
import com.kmusic.apkhd.ui.tv.LocalIsTv
import com.kmusic.apkhd.ui.tv.rememberTvPanelFocus
import com.kmusic.apkhd.ui.tv.tvRowFocus
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties

/**
 * 「我的歌单」页 —— 媒体库里唯一装歌单的入口。
 *
 * 为什么把「收藏的」和「创建的」放一页而不是两个入口：
 * 用户脑子里的模型是「我的歌单」，不是「我收藏的歌单 + 我建的歌单」两个抽屉。
 * 分两个入口就会有人问「我建的那个歌单在哪」，还得在两处各找一遍。
 * 分段只影响「看到什么」，不影响「在哪找」。
 *
 * 两段的排序都是**最近的在最前**：
 * - 收藏的：刚收藏完回来就能看见，不用翻到底
 * - 创建的：新建的默认带 `createdAt` 倒序，和收藏行为一致
 */
@Composable
fun MyPlaylistsScreen(
    favoritePlaylists: List<PlaylistBrief>,
    userPlaylists: List<UserPlaylist>,
    onFavoriteClick: (PlaylistBrief) -> Unit,
    onFavoriteRemove: (PlaylistBrief) -> Unit,
    onUserPlaylistClick: (UserPlaylist) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    // ---- AI 建歌单（从「我的」页搬过来；Key 配置仍在「我的 → AI 导赏」） ----
    aiAvailable: Boolean = false,
    aiLoading: Boolean = false,
    aiError: String? = null,
    onAiGenerate: (String) -> Unit = {},
    /** 刚建好的歌单 id（非空时自动切到「创建的」并高亮那一条） */
    aiCreatedId: String? = null,
    onAiCreatedShown: () -> Unit = {},
) {
    val c = moyinColors()
    // 默认停在「收藏的」：用户从媒体库点进来，最想确认的是「我收藏的那些还在不在」
    var tab by remember { mutableStateOf(0) }
    var showNew by remember { mutableStateOf(false) }
    var showAi by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val listState = rememberLazyListState()
    // 两段各自一个：共用会在切分段时把上一段的滚动位置带过来
    val favoriteListState = rememberLazyListState()

    // 「创建的」排序必须和这里的下标一致，否则滚动到的是别人
    val sortedCreated = remember(userPlaylists) { userPlaylists.sortedByDescending { it.createdAt } }

    // v2.4.2：进页面**不抢焦点**，只保证「进内容区就能看见选中」。
    //
    // 试过 90/250/450/800/1300ms 五次 requestFocus 硬抢，每次都返回 true 但下一帧
    // 焦点又被还回左侧导航栏 —— 页面切换时 Compose 会把焦点交给上一个持焦的页面，
    // 抢不过，再重试只是和用户抢方向盘。
    //
    // 侧栏先持焦是**全 App 一致**的行为（首页冷启动也落在侧栏），用户按 → 进内容区。
    // 这里真正要修的是「进了内容区看不见选中」，已由 PlaylistRow 的
    // tvFocus(fullWidth = true) 解决。
    val firstRowFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val chipFocus = remember { androidx.compose.ui.focus.FocusRequester() }

    // AI 生成完：切到「创建的」+ 滚到新歌单 + 说一句建好了。
    // 以前只在设置页留一行字，用户点完只能自己去列表里猜哪条是新的。
    LaunchedEffect(aiCreatedId) {
        val id = aiCreatedId ?: return@LaunchedEffect
        tab = 1
        val idx = sortedCreated.indexOfFirst { it.id == id }
        if (idx >= 0) {
            // +1 是因为 AI 卡片占了列表上面的固定高度（不参与 LazyColumn，不需要偏移）
            listState.animateScrollToItem(idx)
            val name = sortedCreated[idx].name
            android.widget.Toast.makeText(
                ctx, "已创建「$name」· ${sortedCreated[idx].tracks.size} 首",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
        onAiCreatedShown()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(c.bg)
    ) {
        // ── 顶栏 ──
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.wdp, vertical = 8.wdp)
                // v2.4.2：顶栏成组，列表按 ↑ 能稳定落到「返回 / 新建」
                .tvRowFocus(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(44.wdp).clip(RoundedCornerShape(22.wdp))
                    .tvFocus()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { onBack() },
                contentAlignment = Alignment.Center
            ) { MoyinIcon(IconBack, size = 20.wdp, tint = c.t1) }

            Column(Modifier.weight(1f)) {
                Text("我的歌单", color = c.t1, fontSize = 18.wsp, fontWeight = FontWeight.SemiBold)
                Text(
                    "${favoritePlaylists.size} 个收藏 · ${userPlaylists.size} 个创建",
                    color = c.t3,
                    fontSize = 11.wsp
                )
            }

            // 「新建」只对「创建的」段有意义；收藏段显示会让人以为能收藏歌单到本地
            if (tab == 1) {
                Row(
                    Modifier.clip(RoundedCornerShape(10.wdp))
                        .tvFocus()
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { showNew = true }
                        .padding(horizontal = 10.wdp, vertical = 6.wdp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MoyinIcon(IconPlus, size = 15.wdp, tint = c.accent)
                    Text(
                        "新建",
                        color = c.accent,
                        fontSize = 13.wsp,
                        modifier = Modifier.padding(start = 4.wdp)
                    )
                }
            }
        }

        // ── 分段 ──
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.wdp, vertical = 4.wdp),
            horizontalArrangement = Arrangement.spacedBy(8.wdp)
        ) {
            SegmentChip(
                "收藏的 ${favoritePlaylists.size}", tab == 0, c,
                focus = if (tab == 0) chipFocus else null
            ) { tab = 0 }
            SegmentChip(
                "创建的 ${userPlaylists.size}", tab == 1, c,
                focus = if (tab == 1) chipFocus else null
            ) { tab = 1 }
        }

        // 当前 tab 的列表是不是空的 —— AI 卡的 ↓ 要据此决定落点
        val curListIsEmpty =
            if (tab == 0) favoritePlaylists.isEmpty() else sortedCreated.isEmpty()

        // ── AI 建歌单 ──
        // 放在分段**下面**而不是塞进某一列：两个分段都可能想用（"再来一批类似的"），
        // 塞进「创建的」列表里会让「收藏的」段看不到这个能力。
        AiPlaylistCard(
            available = aiAvailable,
            loading = aiLoading,
            error = aiError,
            // 空列表时的焦点落点：分段按钮下面是这张 AI 卡片，
            // 它自己也是满宽可聚焦行 —— 不定焦的话 Compose 的默认搜索会先落上去，
            // 我们的 requestFocus 就被它抢了（实测三次重试全失败）。
            focus = if (favoritePlaylists.isEmpty() && userPlaylists.isEmpty()) firstRowFocus else null,
            down = if (curListIsEmpty) chipFocus else firstRowFocus,
            // 有歌单时让位：焦点直接落到第一行，不被这张通栏卡拦截
            focusable = curListIsEmpty,
            onStart = { showAi = true }
        )

        if (tab == 0) {
            if (favoritePlaylists.isEmpty()) {
                EmptyHint(
                    "还没有收藏的歌单",
                    "在歌单页右上角 ⋯ 里点「收藏歌单」，或在首页长按歌单封面"
                )
            } else {
                PlaylistList(
                    count = favoritePlaylists.size,
                    keyOf = { i -> "${favoritePlaylists[i].source}:${favoritePlaylists[i].id}" },
                    state = favoriteListState,
                    rowOf = { i ->
                        val p = favoritePlaylists[i]
                        PlaylistRow(
                            focus = if (i == 0) firstRowFocus else null,
                            name = p.name,
                            subtitle = listOfNotNull(
                                p.creator.takeIf { it.isNotBlank() },
                                if (p.trackCount > 0) "${p.trackCount} 首" else null,
                                sourceLabel(p.source)
                            ).joinToString(" · "),
                            coverUrl = p.coverUrl,
                            onClick = { onFavoriteClick(p) },
                            // 长按移除：收藏列表里最常用的操作就是「不要了」，
                            // 点进歌单再取消要三步，长按一步
                            onLongClick = { onFavoriteRemove(p) }
                        )
                    }
                )
            }
        } else {
            if (userPlaylists.isEmpty()) {
                EmptyHint("还没有创建歌单", "点右上角「新建」，把喜欢的歌攒成一个列表")
            } else {
                // 新建的排在最前（createdAt 倒序），和「收藏的」保持同一心智
                PlaylistList(
                    count = sortedCreated.size,
                    keyOf = { i -> sortedCreated[i].id },
                    state = listState,
                    rowOf = { i ->
                        val p = sortedCreated[i]
                        PlaylistRow(
                            focus = if (i == 0) firstRowFocus else null,
                            name = p.name,
                            subtitle = if (p.tracks.isEmpty()) "空歌单 · 点进去加歌"
                                else "${p.tracks.size} 首 · 本机保存",
                            coverUrl = p.tracks.firstOrNull { !it.coverUrl.isNullOrEmpty() }?.coverUrl,
                            onClick = { onUserPlaylistClick(p) }
                        )
                    }
                )
            }
        }
    }

    if (showNew) {
        NewPlaylistDialog(
            onDismiss = { showNew = false },
            onConfirm = { n -> onCreatePlaylist(n); showNew = false }
        )
    }

    if (showAi) {
        AiInputDialog(
            title = "AI 建歌单",
            initial = "",
            hint = "例如：适合开车听的粤语老歌（可写「来20首」）",
            mask = false,
            onDismiss = { showAi = false },
            onConfirm = { d ->
                showAi = false
                // 生成是在 VM 里跑的，页面退出不打断；回来后看卡片状态就行
                tab = 1
                onAiGenerate(d)
            }
        )
    }
}

/**
 * AI 建歌单入口卡片。
 *
 * 四种状态必须都看得懂（以前塞在设置页只有一行副标题，生成中/失败全靠猜）：
 * - 没 Key：显示「先去填 Key」，点了给一句提示，**不跳页**（跳走会丢掉当前浏览位置）
 * - 生成中：转圈 + 文案，期间点击无效
 * - 失败：红色错误文案，点一下重新弹输入框
 * - 正常：一句话说明它干什么
 */
@Composable
private fun AiPlaylistCard(
    available: Boolean,
    loading: Boolean,
    error: String?,
    /** v2.4.2：空列表时的焦点落点 */
    focus: androidx.compose.ui.focus.FocusRequester? = null,
    /** v2.4.2：按 ↓ 去哪。几何搜索从这张通栏卡往下会跳空，必须显式指定。 */
    down: androidx.compose.ui.focus.FocusRequester? = null,
    /**
     * v2.4.2：这张卡要不要参与遥控焦点。
     *
     * 当前 tab 有歌单时**必须**关掉：它是通栏大卡，几何上压在列表上方，
     * 遥控从内容区往下走会先撞上它，把下面的歌单行全挡住 ——
     * 用户报的「进我的歌单选不到歌单」一半是这个原因。
     */
    focusable: Boolean = true,
    onStart: () -> Unit
) {
    val c = moyinColors()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.wdp, vertical = 6.wdp)
            .clip(RoundedCornerShape(14.wdp))
            .background(c.s1)
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .then(
                // ⚠️ 有歌单时**整条 clickable 都不能挂**。
                //
                // 试过 `.focusProperties { canFocus = false }` —— 不管用：
                // `clickable` 内部自带焦点节点（Modifier.clickable 的 semantics +
                // focusable），外层 focusProperties 拦不住它，遥控照样落在卡上，
                // 把下面真正的歌单行全挡住。只能从根上不挂 clickable。
                //
                // 卡片在「有歌单」时本来也只是引导入口（点了要么提示填 Key、
                // 要么弹 AI 输入框），让位给歌单访问优先级更高 —— 这两个 tab
                // 各自的第一行才是用户要按的东西。
                if (focusable) Modifier
                    .focusProperties { if (down != null) this.down = down }
                    .tvFocus(shape = RoundedCornerShape(14.wdp))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) {
                        when {
                            loading -> Unit
                            !available -> android.widget.Toast.makeText(
                                ctx,
                                "先在「我的 → AI 导赏」填 API Key",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            else -> onStart()
                        }
                    }
                else Modifier
            )
            .padding(horizontal = 14.wdp, vertical = 12.wdp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(34.wdp).clip(RoundedCornerShape(11.wdp))
                .background(c.accent.copy(alpha = if (available) 0.16f else 0.07f)),
            contentAlignment = Alignment.Center
        ) {
            if (loading) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(16.wdp),
                    strokeWidth = 2.wdp,
                    color = c.accent
                )
            } else {
                Text("✨", fontSize = 15.wsp)
            }
        }
        Spacer(Modifier.width(12.wdp))
        Column(Modifier.weight(1f)) {
            Text(
                "AI 建歌单",
                color = if (available) c.t1 else c.t2,
                fontSize = 14.wsp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(2.wdp))
            Text(
                when {
                    loading -> "正在搜索并逐首验证…（可退出本页，回来再看）"
                    !available -> "先在「我的 → AI 导赏」填 API Key"
                    error != null -> error
                    else -> "描述想要的歌单，AI 挑真能播的歌"
                },
                color = if (error != null && !loading) c.accent else c.t3,
                fontSize = 11.wsp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        // 宽屏下通栏卡片右侧会空一大片，加个箭头说明「这里能点」；
        // 窄屏本来就窄，箭头反而挤，所以只在宽屏显示
        if (window.isWide && window.widthDp > window.heightDp) {
            MoyinIcon(icon = IconChevronRight, tint = c.t3, size = 16.wdp)
        }
    }
}

/** 来源标注：让用户知道这歌单是哪个平台的，避免同名混淆 */
private fun sourceLabel(source: String): String = when (source) {
    "netease" -> "网易云"
    "kuwo" -> "酷我"
    "migu" -> "咪咕"
    else -> source.ifBlank { "未知来源" }
}

@Composable
private fun SegmentChip(
    text: String,
    selected: Boolean,
    c: com.kmusic.apkhd.ui.theme.MoyinColors,
    /** v2.4.2：空列表时作为焦点兜底落点 */
    focus: androidx.compose.ui.focus.FocusRequester? = null,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .clip(RoundedCornerShape(9.wdp))
            .background(if (selected) c.accentFill else c.s2)
            .tvFocus(shape = RoundedCornerShape(9.wdp))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
            .padding(horizontal = 12.wdp, vertical = 7.wdp)
    ) {
        Text(
            text,
            color = if (selected) c.onAccent else c.t2,
            fontSize = 12.wsp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
        )
    }
}

/**
 * 歌单列表容器。
 *
 * 窄屏单列；宽屏（车机 / 平板横屏）双列 —— 1600dp 宽只剩一列 500dp 的行，
 * 右边大片空白，看起来像是没加载完。分两列后每列约 380dp，接近手机宽度，
 * 阅读距离也够。
 *
 * 用 `count + rowOf(index)` 而不是直接传列表：收藏和自建是两种类型
 * （PlaylistBrief / UserPlaylist），传泛型列表会逼着调用方在这里做类型分支。
 */
@Composable
private fun PlaylistList(
    count: Int,
    keyOf: (Int) -> Any,
    state: LazyListState,
    rowOf: @Composable (Int) -> Unit
) {
    val cols = if (window.isWide && window.widthDp > window.heightDp) 2 else 1
    LazyColumn(
        Modifier.fillMaxSize(),
        state = state,
        contentPadding = PaddingValues(start = 16.wdp, end = 16.wdp, top = 6.wdp, bottom = 24.wdp),
        verticalArrangement = Arrangement.spacedBy(if (cols > 1) 2.wdp else 0.wdp)
    ) {
        var idx = 0
        while (idx < count) {
            val start = idx
            item(key = "row-${keyOf(start)}") {
                if (cols == 1) {
                    rowOf(start)
                } else {
                    // 同一行的两张卡各自带封面 + 两行文字，用 weight 均分；
                    // 行内不再加分隔线，避免和单列时的视觉重量不一致
                    Row(Modifier.fillMaxWidth()) {
                        Box(Modifier.weight(1f)) { rowOf(start) }
                        Spacer(Modifier.width(20.wdp))
                        if (start + 1 < count) {
                            Box(Modifier.weight(1f)) { rowOf(start + 1) }
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            idx += cols
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PlaylistRow(
    /** v2.4.2：首行定焦用；null = 不抢焦点 */
    focus: androidx.compose.ui.focus.FocusRequester? = null,
    name: String,
    subtitle: String,
    coverUrl: String?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val c = moyinColors()
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            // v2.4.2：整行补焦点视觉 —— 原来只有 combinedClickable，遥控能选中但
            // 屏幕上毫无反馈，用户报的「选不到歌单 / 焦点消失」就是这个。
            .tvFocus(shape = RoundedCornerShape(10.wdp), fullWidth = true)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onClick() },
                onLongClick = onLongClick
            )
            .padding(vertical = 8.wdp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverArt(coverUrl = coverUrl, modifier = Modifier.size(52.wdp), cornerRadius = 10.wdp)
        Spacer(Modifier.width(12.wdp))
        Column(Modifier.weight(1f)) {
            Text(name, color = c.t1, fontSize = 14.wsp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.wdp))
                Text(subtitle, color = c.t3, fontSize = 11.wsp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun EmptyHint(title: String, hint: String) {
    val c = moyinColors()
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.wdp, vertical = 60.wdp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, color = c.t2, fontSize = 14.wsp)
        Spacer(Modifier.height(6.wdp))
        Text(hint, color = c.t3, fontSize = 11.5.wspSp, lineHeight = 17.wsp)
    }
}
