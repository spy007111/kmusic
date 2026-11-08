package com.kmusic.apkhd.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import kotlin.OptIn
import com.kmusic.apkhd.model.ChartSection
import com.kmusic.apkhd.model.PlaylistBrief
import com.kmusic.apkhd.model.ArtistBrief
import com.kmusic.apkhd.model.QuickEntry
import com.kmusic.apkhd.ui.components.MiniPlayerBar
import androidx.compose.ui.focus.focusProperties
import com.kmusic.apkhd.ui.components.MoyinSideRail
import com.kmusic.apkhd.ui.components.MoyinTabBar
import com.kmusic.apkhd.ui.components.TabItem
import com.kmusic.apkhd.ui.icons.IconHeart
import com.kmusic.apkhd.ui.icons.IconHome
import com.kmusic.apkhd.ui.icons.IconLibrary
import com.kmusic.apkhd.ui.icons.IconProfile
import com.kmusic.apkhd.ui.icons.IconSearch
import com.kmusic.apkhd.ui.screens.CollectionListScreen
import com.kmusic.apkhd.ui.screens.DownloadScreen
import com.kmusic.apkhd.ui.screens.HomeScreen
import com.kmusic.apkhd.ui.screens.WebDavScreen
import com.kmusic.apkhd.ui.screens.LibraryScreen
import com.kmusic.apkhd.ui.screens.PlayerScreen
import com.kmusic.apkhd.ui.screens.ProfileScreen
import com.kmusic.apkhd.ui.screens.SearchScreen
import com.kmusic.apkhd.ui.screens.LocalFoldersScreen
import com.kmusic.apkhd.ui.screens.MyPlaylistsScreen
import com.kmusic.apkhd.ui.screens.NavidromeScreen
import com.kmusic.apkhd.ui.screens.TrackListScreen
import com.kmusic.apkhd.ui.screens.WrongLyricSheet
import com.kmusic.apkhd.ui.theme.LocalMoyinColors
import com.kmusic.apkhd.ui.theme.AccentPreset
import com.kmusic.apkhd.ui.theme.FrostLevel
import com.kmusic.apkhd.ui.theme.LocalFrostLevel
import com.kmusic.apkhd.ui.theme.LocalSafeBottom
import com.kmusic.apkhd.ui.theme.ProvideMoyinColors
import com.kmusic.apkhd.ui.theme.SyncSystemBars
import com.kmusic.apkhd.ui.theme.resolveDark
import com.kmusic.apkhd.ui.theme.effectiveSafeBottom
import com.kmusic.apkhd.ui.theme.safeBottomPadding
import com.kmusic.apkhd.ui.theme.ProvideWindowInfo
import com.kmusic.apkhd.ui.theme.SideRailWidth
import com.kmusic.apkhd.ui.theme.ThemeMode
import com.kmusic.apkhd.ui.theme.window
import com.kmusic.apkhd.ui.theme.wdp
import com.kmusic.apkhd.ui.icons.IconDownload
import com.kmusic.apkhd.ui.icons.IconFolder
import com.kmusic.apkhd.ui.icons.IconPlay
import com.kmusic.apkhd.ui.icons.IconPlayShuffle
import com.kmusic.apkhd.ui.icons.IconPlayOrder
import com.kmusic.apkhd.ui.screens.ListAction
import com.kmusic.apkhd.model.AlbumBrief
import com.kmusic.apkhd.model.Track
import com.kmusic.apkhd.ui.icons.IconClose
import com.kmusic.apkhd.ui.tv.LocalIsTv

@OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {
    var permissionExistingUser: Boolean = false
        private set
    private val exitHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var activityVisible = false
    private var externalUiAtMs: Long? = null
    private var userLeaving = false
    private val tvBackgroundCheck = Runnable { stopTvIfBackground() }

    fun beginExternalUi() { externalUiAtMs = android.os.SystemClock.elapsedRealtime() }
    fun finishExternalUi() { externalUiAtMs = null; exitHandler.removeCallbacks(tvBackgroundCheck); stopTvIfBackground() }
    override fun onStart() {
        super.onStart()
        activityVisible = true
        userLeaving = false
        exitHandler.removeCallbacks(tvBackgroundCheck)
        if (com.kmusic.apkhd.ui.tv.TvEnv.isTv(this)) com.kmusic.apkhd.playback.PlaybackSessionExit.openSession()
    }
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (externalUiAtMs == null) userLeaving = true
    }
    override fun onStop() {
        activityVisible = false
        super.onStop()
        stopTvIfBackground()
    }
    private fun stopTvIfBackground() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (com.kmusic.apkhd.playback.PlaybackBackgroundPolicy.resolve(
                com.kmusic.apkhd.ui.tv.TvEnv.isTv(this), activityVisible, isChangingConfigurations, isFinishing,
                externalUiAtMs != null, externalUiAtMs, now, userLeaving)) {
            com.kmusic.apkhd.playback.PlaybackSessionExit.requestStop(this)
        } else if (!activityVisible && !isChangingConfigurations) {
            val delay = com.kmusic.apkhd.playback.PlaybackBackgroundPolicy.permissionRecheckDelayMs(externalUiAtMs != null, externalUiAtMs, now)
            if (delay > 0) { exitHandler.removeCallbacks(tvBackgroundCheck); exitHandler.postDelayed(tvBackgroundCheck, delay) }
        }
    }
    override fun onDestroy() { exitHandler.removeCallbacksAndMessages(null); super.onDestroy() }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Capture before the ViewModel can write normal settings on a fresh installation.
        permissionExistingUser = savedInstanceState?.getBoolean("permission_existing_user") ?: listOf(
            "datastore/moyin_settings.preferences_pb", "datastore/playback_queue.preferences_pb", "datastore/playback_position.preferences_pb"
        ).any { java.io.File(filesDir, it).let { file -> file.exists() && file.length() > 0 } }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MoyinRoot() }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("permission_existing_user", permissionExistingUser)
        super.onSaveInstanceState(outState)
    }
    override fun onResume() {
        super.onResume()
        externalUiAtMs = null
        if (com.kmusic.apkhd.ui.tv.TvEnv.isTv(this)) androidx.lifecycle.ViewModelProvider(this)[MoyinViewModel::class.java].onUiForeground()
        com.kmusic.apkhd.playback.CallStateMonitor.requestRefresh()
    }
}

/** 运行时需要的本地音频读取权限 */
private fun audioPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
}

/** 子页面路由（在 Tab 之上打开） */
private sealed interface Route {
    data object None : Route
    data object LocalTracks : Route
    data object LocalFolders : Route
    data object DailyRecommend : Route
    /** WebDAV 目录浏览 */
    data object WebDav : Route
    data object RecentTracks : Route
    data object Favorites : Route
    /** 我的歌单（收藏的 + 创建的） */
    data object MyPlaylists : Route
    /** 曲库详情：专辑/歌单 → 歌曲；歌手 → 专辑 */
    data class NdAlbum(val id: String, val name: String) : Route
    data class NdArtist(val id: String, val name: String) : Route
    /** 酷我专辑详情（按专辑名搜歌 + 严格比对 ALBUM 字段） */
    data class KwAlbum(val brief: AlbumBrief) : Route
    /** 自建歌单详情 */
    data class UserPlaylistRoute(val id: String) : Route
    data class NdPlaylist(val id: String, val name: String) : Route
    data object Navidrome : Route
    data object NavidromeAlbums : Route
    data object NavidromeArtists : Route
    data object NavidromeSongs : Route
    data object Downloads : Route
    data class Chart(val section: ChartSection) : Route
    data class Playlist(val brief: PlaylistBrief) : Route
    data class Artist(val brief: ArtistBrief) : Route
}

/** 顶层：主题提供 + 页面切换 */
@OptIn(UnstableApi::class)
@Composable
fun MoyinRoot() {
    val vm: MoyinViewModel = viewModel()
    val themeFlow = vm.settings.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
    val themeMode = themeFlow.value
    // 马卡龙主题色：枚举名存 DataStore，未知值回默认
    val accentName by vm.settings.accentPreset.collectAsState(initial = "DEFAULT")
    val accentPreset = AccentPreset.of(accentName)
    val accentAlpha by vm.settings.accentAlpha.collectAsState(initial = 0.8f)
    // 底部安全区：-1 自动（车机 56dp），>=0 手动
    val safeBottomSetting by vm.settings.safeBottomDp.collectAsState(initial = -1)
    val safeBottom = effectiveSafeBottom(safeBottomSetting).dp
    val frostName by vm.settings.frostLevel.collectAsState(initial = "LIGHT")
    val frostLevel = FrostLevel.of(frostName)
    val darkStyleName by vm.settings.darkStyle.collectAsState(initial = "NAVY")

    ProvideWindowInfo {
        ProvideMoyinColors(
            themeMode = themeMode,
            accentPreset = accentPreset,
            accentAlpha = accentAlpha,
            darkStyle = darkStyleName
        ) {
          // TV 判定放在最外层：手机 / 车机 false，下面所有 tvFocus / tvClickable
          // 都退化成改造前的行为，渲染路径不变
          val tvMode = com.kmusic.apkhd.ui.tv.TvEnv.isTv(androidx.compose.ui.platform.LocalContext.current)
          CompositionLocalProvider(
              LocalIsTv provides tvMode,
              LocalSafeBottom provides safeBottom,
              LocalFrostLevel provides frostLevel
          ) {
            val c = LocalMoyinColors.current
            // 系统栏跟随 App 主题（而不是系统夜间模式）
            SyncSystemBars(
                dark = themeMode.resolveDark(androidx.compose.foundation.isSystemInDarkTheme()),
                background = c.bg
            )
            var tab by remember { mutableStateOf(0) }
            var showPlayer by remember { mutableStateOf(false) }
            var route by remember { mutableStateOf<Route>(Route.None) }
            var showWrongLyric by remember { mutableStateOf(false) }
            // 首页双击返回退出：第一次只提示，不 finish。防止误触回桌面
            var backHintAt by remember { mutableStateOf(0L) }
            val tvExitConfirmation = remember { com.kmusic.apkhd.playback.TvExitConfirmation() }
            LaunchedEffect(tab, route, showPlayer, showWrongLyric) { tvExitConfirmation.reset() }

            // BackHandler 的排查 Toast 要用，必须声明在 BackHandler 之前
            val ctx = androidx.compose.ui.platform.LocalContext.current
            // 首页双击退出需要 finish；ContextWrapper 链上逐层解到 Activity
            val activity = remember(ctx) {
                var inner: android.content.Context? = ctx
                while (inner is android.content.ContextWrapper) {
                    if (inner is android.app.Activity) break
                    inner = inner.baseContext
                }
                inner as? android.app.Activity
            }

            // 系统返回。层级从外到内：
            //   1. 「歌词不对」面板
            //   2. 播放页
            //   3. 子页面（route 非 None）→ 退到 Route.None
            //   4. 非首页 Tab → 退到首页（tab 0）
            //   5. 首页 → 双击退出（2s 内再按一次才交还系统）
            // ⚠️ 第 4 层以前漏了：tab != 0 时 route/showPlayer 全是 false，
            // enabled 整个为 false → handler 不注册 → 系统直接 finish Activity。
            // 现象就是「搜索/媒体库/我的按返回直接回桌面」，而播放页看着正常。
            androidx.activity.compose.BackHandler(enabled = true) {
                com.kmusic.apkhd.data.DiagLog.log(
                    "BACK",
                    "handler fired: tab=$tab showPlayer=$showPlayer " +
                        "route=${route::class.simpleName} showWrongLyric=$showWrongLyric"
                )
                when {
                    showWrongLyric -> showWrongLyric = false
                    showPlayer -> showPlayer = false
                    route != Route.None -> route = Route.None
                    // 首页：2s 内第二次按才放行；第一次弹提示
                    tab == 0 -> {
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (tvMode) {
                            if (tvExitConfirmation.back(true, false, true, now) == com.kmusic.apkhd.playback.TvExitConfirmation.Action.EXIT) {
                                vm.exitTvPlayback()
                                activity?.finishAndRemoveTask()
                            } else android.widget.Toast.makeText(ctx, "再按一次返回退出", android.widget.Toast.LENGTH_SHORT).show()
                        } else if (now - backHintAt < 2000L) {
                            backHintAt = 0L
                            activity?.finish()
                        } else {
                            backHintAt = now
                            android.widget.Toast.makeText(
                                ctx, "再按一次返回退出", android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    tab != 0 -> tab = 0
                }
            }

            // 主题变更写回存储
            LaunchedEffect(themeMode) { }

            var pendingDeviceBinding by remember { mutableStateOf<String?>(null) }
            var pendingDeviceSave by remember { mutableStateOf<String?>(null) }
            val devicePermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                (activity as? MainActivity)?.finishExternalUi()
                vm.refreshDeviceIdentity()
                pendingDeviceBinding?.let(vm::bindDeviceProfile)
                pendingDeviceSave?.let { name -> vm.saveDeviceProfile(name, true) }
                pendingDeviceBinding = null
                pendingDeviceSave = null
            }
            val needsDevicePermission = {
                vm.audioRuntime.route?.isBluetooth == true && Build.VERSION.SDK_INT >= 31 &&
                    androidx.core.content.ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            val bindDeviceProfile: (String) -> Unit = { id ->
                if (needsDevicePermission()) {
                    pendingDeviceBinding = id
                    (activity as? MainActivity)?.beginExternalUi()
                    devicePermLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                } else vm.bindDeviceProfile(id)
            }
            val saveSoundScheme: (String, Boolean) -> Unit = { name, bind ->
                if (bind && needsDevicePermission()) {
                    pendingDeviceSave = name
                    (activity as? MainActivity)?.beginExternalUi()
                    devicePermLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                } else vm.saveDeviceProfile(name, bind)
            }
            PermissionOnboarding(context = ctx, existingUser = (activity as? MainActivity)?.permissionExistingUser ?: true,
                isTv = tvMode, beforeRequest = { (activity as? MainActivity)?.beginExternalUi() },
                afterRequest = { (activity as? MainActivity)?.finishExternalUi() }, onAudioResult = vm::scanLocal)
            LaunchedEffect(Unit) { vm.scanLocal() }

            // 系统目录选择器：用户手动指定要扫描的目录（MediaStore 看不见的也能扫）
            val safTreeLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
            ) { uri -> (activity as? MainActivity)?.finishExternalUi(); if (uri != null) vm.addSafFolder(uri) }

            // 一次性提示（下载加入、连接失败…）—— VM 发，这里弹一次就消费掉
            // ctx 已在上面声明（BackHandler 的排查 Toast 共用）
            val userMsg = vm.userMessage
            LaunchedEffect(userMsg) {
                if (userMsg != null) {
                    android.widget.Toast.makeText(ctx, userMsg.text, if (userMsg.long) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT).show()
                    vm.consumeUserMessage()
                }
            }

            // 首页推荐位（排行榜/推荐歌单/音乐人）
            LaunchedEffect(Unit) { vm.loadDiscovery() }
            // 进「我的」时刷新缓存占用
            LaunchedEffect(tab) { if (tab == 3) vm.refreshCacheSize() }

            // 列表行右侧下载按钮要点亮时用
            val downloadedIds = vm.downloads.filter { it.status != "失败" }.map { it.id }.toSet()

            val tabs = listOf(
                TabItem("首页", IconHome),
                TabItem("搜索", IconSearch),
                TabItem("媒体库", IconLibrary),
                TabItem("我的", IconProfile)
            )

            // 宽屏（平板 / 折叠屏展开）：左侧竖向导航，内容区右移让位
            val w = window

            Box(
                Modifier
                    .fillMaxSize()
                    .background(c.bg)
            ) {
                // 宽屏：左侧竖向导航栏（替代底部 Tab）
                if (w.isWide) {
                    MoyinSideRail(
                        items = tabs,
                        current = tab,
                        onSelect = { t ->
                            // 重新进入搜索页要回到空白+历史，不能留着上次的结果
                            if (t == 1) vm.resetSearch()
                            tab = t
                            // 子页面盖在主区上面，光切 tab 不会退出来 —— 用户看到的就是
                            // 「点了首页却没反应」。切 Tab 一律回到该 Tab 的根页面。
                            route = Route.None
                        },
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .fillMaxHeight()
                            .statusBarsPadding()
                            .navigationBarsPadding()
                            // 播放页是覆盖层，但侧栏仍在同一棵焦点树里，焦点搜索会
                            // 穿到遮罩背后拿到它 —— 用户看到的就是「进了播放页，
                            // 方向键动的却是看不见的侧栏」。播放页打开时让出焦点。
                            .focusProperties { if (showPlayer) canFocus = false }
                    )
                }

                // 主界面（首页/搜索/媒体库）在这个容器里，避开状态栏和导航栏
                Box(
                    Modifier
                        .fillMaxSize()
                        .then(
                            if (w.isWide) Modifier.padding(start = SideRailWidth) else Modifier
                        )
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        // 同上：播放页打开时整块主内容退出焦点树，
                        // 否则焦点会从播放页跑到背后看不见的列表/卡片上。
                        .focusProperties { if (showPlayer) canFocus = false }
                ) {
                    // 子页面优先于 Tab
                    // 页面切换淡入淡出 —— 硬切太生硬。Route 全是 data object/class，
                    // 结构相等性稳定，不会因为重组误触发。
                    // TV：底部迷你播放条是**悬浮覆盖**在内容之上的。
                    // Compose 的 bringIntoView 只认「滚动容器视口」，不知道上面还压了一条，
                    // 于是列表最后一行拿到焦点时刚好滑到视口底边＝被播放条盖住
                    // （遥控没法像手指那样再往上推一把，就是看不见自己选中了什么）。
                    // 解法：TV 下把页面容器底边抬到播放条之上，让视口本身变矮。
                    // 手机/车机 tvMode=false → 0dp，布局一个像素都不动。
                    val tvBottomReserve = if (tvMode) window.miniPlayerHeight + 12.dp else 0.dp
                    Crossfade(
                        targetState = route,
                        modifier = Modifier.padding(bottom = tvBottomReserve),
                        // ⚠️ v2.4.2：TV 上动画时长归零。
                        // Crossfade 期间新旧两页同时挂树，旧页在 220ms 里被销毁时
                        // 会把焦点抢回它自己 —— 新页面里刚 requestFocus 成功的行
                        // 一帧后就被抢走，用户看到的就是「进页面焦点丢了」。
                        // 遥控器用户也不在意这 220ms 的淡入，直接切更利落。
                        animationSpec = tween(if (tvMode) 0 else 220),
                        label = "route"
                    ) { r ->
                    when (r) {
                        is Route.LocalTracks -> TrackListScreen(
                            title = "本地音乐",
                            tracks = vm.localTracks,
                            emptyText = "没有扫描到本地音频\n点右上角「⋯」→ 扫描设置，可以勾选目录或手动添加",
                            actions = listOf(
                                ListAction("播放全部", IconPlay) { vm.playAll(vm.localTracks); showPlayer = true },
                                ListAction("扫描设置", IconFolder) {
                                    vm.loadLocalFolders()
                                    route = Route.LocalFolders
                                }
                            ),
                            onTrackClick = { t, list ->
                                vm.play(t, list)
                                showPlayer = true
                            },
                            onBack = { route = Route.None }
                        )
                        is Route.LocalFolders -> {
                            LaunchedEffect(Unit) { vm.loadLocalFolders() }
                            LocalFoldersScreen(
                                folders = vm.localFolders,
                                enabled = vm.enabledLocalFolders,
                                scanning = vm.localLoading,
                                localTrackCount = vm.localTracks.size,
                                onToggle = { path, on ->
                                    // 「不限制」（空集合）下取消某个目录时，要先把当前
                                    // 全部目录落成具体集合，否则会理解成「只剩这一个」
                                    val base = if (vm.enabledLocalFolders.isEmpty()) {
                                        vm.localFolders.filter { !it.fromSaf }.map { it.path }.toSet()
                                    } else {
                                        vm.enabledLocalFolders
                                    }
                                    vm.applyLocalFolderSelection(if (on) base + path else base - path)
                                },
                                onAddFolder = { run { (activity as? MainActivity)?.beginExternalUi(); safTreeLauncher.launch(null) } },
                                onRemoveFolder = vm::removeSafFolder,
                                onRescan = { vm.rescanLocal(); vm.loadLocalFolders() },
                                onBack = { route = Route.LocalTracks }
                            )
                        }
                        is Route.DailyRecommend -> {
                            LaunchedEffect(Unit) { vm.loadDailyMix() }
                            TrackListScreen(
                                title = "今日推荐 · ${vm.dailyMixDateLabel()}",
                                tracks = vm.dailyMix,
                                emptyText = if (vm.dailyLoading) "正在挑歌…" else "没挑出歌来，稍后再试",
                                loading = vm.dailyLoading,
                                actions = listOf(
                                    ListAction("播放全部", IconPlay) {
                                        vm.playAll(vm.dailyMix); showPlayer = true
                                    },
                                    ListAction("下载全部", IconDownload) { vm.downloadAll(vm.dailyMix) }
                                ),
                                onTrackClick = { t, list -> vm.play(t, list); showPlayer = true },
                                onBack = { route = Route.None }
                            )
                        }
                        is Route.RecentTracks -> TrackListScreen(
                            title = "最近播放",
                            tracks = vm.recentTracks,
                            emptyText = "还没有播放记录",
                            actions = listOf(
                                ListAction("播放全部", IconPlay) { vm.playAll(vm.recentTracks); showPlayer = true },
                                ListAction("下载全部", IconDownload) { vm.downloadAll(vm.recentTracks) }
                            ),
                            onTrackClick = { t, list ->
                                vm.play(t, list)
                                showPlayer = true
                            },
                            onBack = { route = Route.None }
                        )
                        is Route.NdAlbum -> TrackListScreen(
                            title = vm.collectionTitle.ifEmpty { "专辑" },
                            tracks = vm.collectionTracks,
                            emptyText = if (vm.collectionLoading) "加载中…" else "这张专辑没有歌曲",
                            loading = vm.collectionLoading,
                            actions = listOf(
                                ListAction("播放全部", IconPlay) {
                                    vm.playAll(vm.collectionTracks); showPlayer = true
                                },
                                ListAction("下载全部", IconDownload) { vm.downloadAll(vm.collectionTracks) }
                            ),
                            onTrackClick = { t, list -> vm.play(t, list); showPlayer = true },
                            onBack = { vm.closeCollection(); route = Route.NavidromeAlbums }
                        )
                        is Route.NdArtist -> CollectionListScreen(
                            title = vm.collectionTitle.ifEmpty { "歌手" },
                            items = vm.collectionAlbums,
                            emptyText = if (vm.collectionLoading) "加载中…" else "这位歌手没有专辑",
                            loading = vm.collectionLoading,
                            onItemClick = { item ->
                                vm.openAlbum(item.id, item.title)
                                route = Route.NdAlbum(item.id, item.title)
                            },
                            onBack = { vm.closeCollection(); route = Route.NavidromeArtists }
                        )
                        is Route.NdPlaylist -> TrackListScreen(
                            title = vm.collectionTitle.ifEmpty { "播放列表" },
                            tracks = vm.collectionTracks,
                            emptyText = if (vm.collectionLoading) "加载中…" else "这个歌单没有歌曲",
                            loading = vm.collectionLoading,
                            actions = listOf(
                                ListAction("播放全部", IconPlay) {
                                    vm.playAll(vm.collectionTracks); showPlayer = true
                                },
                                ListAction("下载全部", IconDownload) { vm.downloadAll(vm.collectionTracks) }
                            ),
                            onTrackClick = { t, list -> vm.play(t, list); showPlayer = true },
                            onBack = { vm.closeCollection(); route = Route.None }
                        )
                        is Route.KwAlbum -> TrackListScreen(
                            title = vm.albumTitle.ifEmpty { "专辑" },
                            tracks = vm.albumTracks,
                            emptyText = if (vm.albumLoading) "加载中…" else "没有找到这张专辑的歌曲",
                            loading = vm.albumLoading,
                            actions = listOf(
                                ListAction("播放全部", IconPlay) {
                                    vm.playAll(vm.albumTracks); showPlayer = true
                                },
                                ListAction("下载全部", IconDownload) { vm.downloadAll(vm.albumTracks) }
                            ),
                            onTrackClick = { t, list -> vm.play(t, list); showPlayer = true },
                            onBack = { vm.closeAlbum(); route = Route.None }
                        )
                        is Route.UserPlaylistRoute -> {
                            // ⚠️ 必须用 Crossfade 传进来的 r，不能强转外层的 route。
                            // 返回/删除歌单时 route 已经变成 None，而这边（r 还是旧路由）
                            // 的动画还在跑，此时强转就是 ClassCastException → 整个 App 崩掉。
                            // 「一按返回就退回桌面」「一删歌单就崩」都是这一行。
                            val rid = r.id
                            val pl = vm.userPlaylists.firstOrNull { it.id == rid }
                            TrackListScreen(
                                title = pl?.name ?: "歌单",
                                tracks = pl?.tracks.orEmpty(),
                                emptyText = "这个歌单还是空的 —— 在歌曲的「⋯ → 加入歌单」里加进来",
                                actions = listOf(
                                    ListAction("播放全部", IconPlay) {
                                        val ts = pl?.tracks.orEmpty()
                                        if (ts.isNotEmpty()) { vm.playAll(ts); showPlayer = true }
                                    },
                                    ListAction("删除歌单", IconClose) {
                                        pl?.let { vm.deletePlaylist(it.id) }
                                        route = Route.None
                                    }
                                ),
                                onRemoveTrack = { t -> vm.removeFromPlaylist(rid, t.id) },
                                onTrackClick = { t, list -> vm.play(t, list); showPlayer = true },
                                onBack = { route = Route.None }
                            )
                        }
                        is Route.MyPlaylists -> MyPlaylistsScreen(
                            favoritePlaylists = vm.favoritePlaylists,
                            userPlaylists = vm.userPlaylists,
                            onFavoriteClick = { p ->
                                vm.openPlaylist(p)
                                route = Route.Playlist(p)
                            },
                            onFavoriteRemove = { vm.removeFavoritePlaylist(it) },
                            onUserPlaylistClick = { route = Route.UserPlaylistRoute(it.id) },
                            onCreatePlaylist = { n -> vm.createPlaylist(n) },
                            onBack = { route = Route.None },
                            // AI 建歌单：能力搬到这里，Key 仍在「我的 → AI 导赏」配
                            aiAvailable = vm.aiConfigured,
                            aiLoading = vm.aiGenLoading,
                            aiError = vm.aiGenError,
                            onAiGenerate = { d -> vm.aiGeneratePlaylist(d) },
                            aiCreatedId = vm.aiCreatedId,
                            onAiCreatedShown = { vm.consumeAiCreated() }
                        )
                        is Route.Favorites -> TrackListScreen(
                            title = "我喜欢的音乐",
                            tracks = vm.favorites,
                            emptyText = "还没有收藏的歌曲 —— 播放页点心形即可收藏",
                            favoriteIds = vm.favorites.map { it.id }.toSet(),
                            onToggleFavorite = { vm.toggleFavorite(it) },
                            actions = listOf(
                                ListAction("播放全部", IconPlay) { vm.playAll(vm.favorites); showPlayer = true },
                                ListAction("下载全部", IconDownload) { vm.downloadAll(vm.favorites) }
                            ),
                            onTrackClick = { t, list ->
                                vm.play(t, list)
                                showPlayer = true
                            },
                            onBack = { route = Route.None }
                        )
                        is Route.Navidrome -> NavidromeScreen(
                            url = vm.navidromeAddress,
                            connected = vm.navidromeConnected,
                            checking = vm.navidromeChecking,
                            error = vm.navidromeError,
                            trackCount = vm.navidromeTrackCount,
                            albumCount = vm.navidromeAlbums.size,
                            artistCount = vm.navidromeArtists.size,
                            onOpenAlbums = { vm.refreshNavidrome(); route = Route.NavidromeAlbums },
                            onOpenArtists = { vm.refreshNavidrome(); route = Route.NavidromeArtists },
                            onOpenSongs = { vm.loadNavidromeAllSongs(); route = Route.NavidromeSongs },
                            onReconnect = { vm.retryNavidrome() },
                            onRefresh = { vm.refreshNavidrome() },
                            onOpenSettings = { tab = 3; route = Route.None },
                            onBack = { route = Route.None },
                            bottomInsetDp = if (vm.playbackState.track != null) 72 else 0
                        )
                        is Route.NavidromeAlbums -> CollectionListScreen(
                            title = "专辑",
                            items = vm.albumItems,
                            emptyText = if (vm.navidromeConnected) "曲库里没有专辑" else "还没连接曲库",
                            onItemClick = { item ->
                                vm.openAlbum(item.id, item.title)
                                route = Route.NdAlbum(item.id, item.title)
                            },
                            onBack = { route = Route.None }
                        )
                        is Route.NavidromeArtists -> CollectionListScreen(
                            title = "歌手",
                            items = vm.artistItems,
                            emptyText = if (vm.navidromeConnected) "曲库里没有歌手" else "还没连接曲库",
                            onItemClick = { item ->
                                vm.openArtist(item.id, item.title)
                                route = Route.NdArtist(item.id, item.title)
                            },
                            onBack = { route = Route.None }
                        )
                        is Route.NavidromeSongs -> TrackListScreen(
                            title = "所有歌曲",
                            tracks = vm.navidromeAllTracks,
                            emptyText = if (vm.navidromeAllLoading) "加载中…" else "曲库里没有歌曲",
                            loading = vm.navidromeAllLoading,
                            actions = listOf(
                                ListAction("顺序播放", IconPlayOrder) {
                                    vm.changePlayMode(com.kmusic.apkhd.model.PlayMode.ORDER)
                                    vm.playAll(vm.navidromeAllTracks); showPlayer = true
                                },
                                ListAction("随机播放", IconPlayShuffle) {
                                    vm.changePlayMode(com.kmusic.apkhd.model.PlayMode.SHUFFLE)
                                    vm.playAll(vm.navidromeAllTracks); showPlayer = true
                                },
                                ListAction("下载全部", IconDownload) { vm.downloadAll(vm.navidromeAllTracks) }
                            ),
                            onTrackClick = { t, list -> vm.play(t, list); showPlayer = true },
                            onBack = { route = Route.None }
                        )
                        is Route.WebDav -> WebDavScreen(
                            base = vm.davUrl,
                            path = vm.davPath,
                            entries = vm.davEntries,
                            loading = vm.davLoading,
                            error = vm.davError,
                            onOpenDir = { vm.davOpen(it) },
                            onUp = { vm.davUp() },
                            onPlayFile = { vm.davPlay(it) },
                            onPlayAll = { vm.davPlayAll() },
                            onDownload = { vm.davDownload(it) },
                            onDownloadAll = { vm.davDownloadAll() },
                            onRefresh = { vm.davReload() },
                            onBack = { route = Route.None },
                            onOpenSettings = { tab = 3; route = Route.None },
                            bottomInsetDp = if (vm.playbackState.track != null) 72 else 0
                        )
                        is Route.Downloads -> DownloadScreen(
                            downloads = vm.downloads,
                            onRemove = vm::removeDownload,
                            onBack = { route = Route.None }
                        )
                        is Route.Chart -> TrackListScreen(
                            title = r.section.chart.name,
                            tracks = vm.chartDetailTracks.ifEmpty { r.section.tracks },
                            emptyText = "榜单暂无歌曲",
                            actions = listOf(
                                ListAction("播放全部", IconPlay) { vm.playAll(vm.chartDetailTracks.ifEmpty { r.section.tracks }); showPlayer = true },
                                ListAction("下载全部", IconDownload) { vm.downloadAll(vm.chartDetailTracks.ifEmpty { r.section.tracks }) }
                            ),

                            onTrackClick = { t, list -> vm.play(t, list); showPlayer = true },
                            onBack = { vm.closeChart(); route = Route.None },
                            onDownload = vm::download,
                            downloadedIds = downloadedIds,
                        )
                        is Route.Playlist -> TrackListScreen(
                            title = r.brief.name,
                            tracks = vm.playlistTracks,
                            emptyText = if (vm.playlistLoading) "正在加载…"
                                else "歌单暂无歌曲（或源不可用）",
                            loading = vm.playlistLoading,
                            actions = listOfNotNull(
                                ListAction("播放全部", IconPlay) { vm.playAll(vm.playlistTracks); showPlayer = true },
                                ListAction("下载全部", IconDownload) { vm.downloadAll(vm.playlistTracks) },
                                // 收藏 / 取消收藏：判重要用 brief 而不是 r.brief ——
                                // r.brief 是进页面那一刻的快照，收藏状态可能已经变了。
                                ListAction(
                                    if (vm.isPlaylistFavorite(vm.playlistDetail ?: r.brief)) "取消收藏歌单" else "收藏歌单",
                                    IconHeart
                                ) {
                                    vm.togglePlaylistFavorite(vm.playlistDetail ?: r.brief)
                                }
                            ),
                            onTrackClick = { t, list -> vm.play(t, list); showPlayer = true },
                            onBack = { vm.closePlaylist(); route = Route.None },
                            onDownload = vm::download,
                            downloadedIds = downloadedIds,
                            footerNote = if (vm.playlistTruncated) {
                                "这个歌单声明 ${(vm.playlistDetail ?: r.brief).trackCount} 首，" +
                                    "但源只返回了 ${vm.playlistTracks.size} 首。\n" +
                                    "换源重试或稍后再试；能播的仍会按顺序全部播放。"
                            } else null,
                        )
                        is Route.Artist -> TrackListScreen(
                            title = r.brief.name,
                            tracks = vm.artistTracks,
                            emptyText = if (vm.artistLoading) "正在加载…" else "暂无该歌手的歌曲",
                            loading = vm.artistLoading,
                            actions = listOf(
                                ListAction("播放全部", IconPlay) { vm.playAll(vm.artistTracks); showPlayer = true },
                                ListAction("下载全部", IconDownload) { vm.downloadAll(vm.artistTracks) }
                            ),
                            onTrackClick = { t, list -> vm.play(t, list); showPlayer = true },
                            onBack = { vm.closeArtist(); route = Route.None },
                            onDownload = vm::download,
                            downloadedIds = downloadedIds,
                        )
                        is Route.None -> when (tab) {
                            0 -> HomeContent(
                                vm = vm,
                                onSearchClick = { vm.resetSearch(); tab = 1 },
                                onFavoritesClick = { route = Route.Favorites },
                                onChartClick = { vm.openChart(it); route = Route.Chart(it) },
                                onPlaylistClick = { vm.openPlaylist(it); route = Route.Playlist(it) },
                                isPlaylistFavorite = { vm.isPlaylistFavorite(it) },
                                onTogglePlaylistFavorite = { vm.togglePlaylistFavorite(it) },
                                onArtistClick = { vm.openArtist(it); route = Route.Artist(it) },
                                onMiniPlayerClick = { showPlayer = true },
                                onDownload = vm::download,
                                downloadedIds = downloadedIds,
                                onLocalClick = { route = Route.LocalTracks },
                                onRecentClick = { route = Route.RecentTracks },
                                onOpenRecentList = { route = Route.RecentTracks },
                                onDailyClick = {
                                    vm.loadDailyMix()
                                    route = Route.DailyRecommend
                                },
                                onMyPlaylistsClick = { route = Route.MyPlaylists },
                                onNavidromeClick = { route = Route.Navidrome },
                                onNdAlbumClick = { a ->
                                    vm.openAlbum(a.id, a.name)
                                    route = Route.NdAlbum(a.id, a.name)
                                },
                                onNdSeeAll = { route = Route.NavidromeAlbums }
                            )
                            1 -> {
                                vm.loadHotKeywords()
                                SearchScreen(
                                keyword = vm.searchKeyword,
                    aiAvailable = vm.aiConfigured,
                    aiRewriting = vm.aiRewriting,
                    aiHint = vm.aiRewriteHint,
                    onAiSearch = vm::aiRewriteAndSearch,
                                onKeywordChange = vm::onKeywordChange,
                                onSubmit = vm::search,
                                results = vm.searchResults,
                                searching = vm.searching,
                                error = vm.searchError,
                                onResultClick = { t, list ->
                                    vm.play(t, list)
                                    showPlayer = true
                                },
                                onBack = { tab = 0 },
                                history = vm.searchHistory,
                                onHistoryClick = { k -> vm.search(k) },
                                tab = vm.searchTab,
                                onTabChange = vm::changeSearchTab,
                                artistResults = vm.artistResults,
                                albumResults = vm.albumResults,
                                playlistResults = vm.playlistResults,
                                onArtistClick = { a -> vm.openArtist(a); route = Route.Artist(a) },
                                onAlbumClick = { a -> vm.openAlbum(a); route = Route.KwAlbum(a) },
                                onPlaylistClick = { p -> vm.openPlaylist(p); route = Route.Playlist(p) },
                                suggestions = vm.suggestions,
                                onSuggestionClick = { k -> vm.search(k) },
                                hotKeywords = vm.hotKeywords,
                                onHotClick = { k -> vm.search(k) },
                                onHistoryRemove = vm::removeSearchHistory,
                                onHistoryClear = vm::clearSearchHistory,
                                onAddToQueue = vm::addToQueue,
                                onPlayNext = vm::playNext,
                                onDownload = vm::download,
                                downloadedIds = downloadedIds
                                )
                            }
                            2 -> LibraryScreen(
                                localCount = vm.localTracks.size,
                                onLocalClick = { route = Route.LocalTracks },
                                recentCount = vm.recentTracks.size,
                                onRecentClick = { route = Route.RecentTracks },
                                favoriteCount = vm.favorites.size,
                                onFavoritesClick = { route = Route.Favorites },
                                favoritePlaylistCount = vm.favoritePlaylists.size,
                                userPlaylistCount = vm.userPlaylists.size,
                                onMyPlaylistsClick = { route = Route.MyPlaylists },
                                davConfigured = vm.davConfigured,
                                davPath = vm.davPath.removePrefix(vm.davUrl).trim('/'),
                                onWebDavClick = { route = Route.WebDav },
                                ndConfigured = vm.navidromeAddress.isNotBlank(),
                                ndConnected = vm.navidromeConnected,
                                ndTrackCount = vm.navidromeTrackCount,
                                ndError = vm.navidromeError,
                                onNavidromeClick = { route = Route.Navidrome },
                                downloadCount = vm.downloads.size,
                                onCategoryClick = { cat ->
                                    if (cat == "下载管理") route = Route.Downloads
                                }
                            )
                            3 -> ProfileScreen(
                                themeMode = themeMode,
                                onThemeModeChange = { vm.setThemeMode(it) },
                                accentPreset = accentPreset,
                                onAccentPresetChange = { vm.setAccentPreset(it?.name ?: "DEFAULT") },
                                accentAlpha = accentAlpha,
                                onAccentAlphaChange = { vm.setAccentAlpha(it) },
                                safeBottomSetting = safeBottomSetting,
                                onSafeBottomChange = { vm.setSafeBottomDp(it) },
                                frostLevel = frostLevel,
                                onFrostLevelChange = { vm.setFrostLevel(it.name) },
                                darkStyle = darkStyleName,
                                onDarkStyleChange = { vm.updateDarkStyle(it) },
                                davUrl = vm.davUrl,
                                davUser = vm.davUser,
                                davPass = vm.davPass,
                                davTestResult = vm.davTestResult,
                                davChecking = vm.davChecking,
                                onSaveDav = vm::saveDavConfig,
                                onTestDav = vm::testDav,
                                davUa = vm.davUa,
                                onSaveDavUa = vm::updateDavUa,
                                davDiagText = vm.davDiagText,
                                davDiagRunning = vm.davDiagRunning,
                                onDiagnoseDav = vm::davDiagnose,
                                ndUrl = vm.navidromeAddress,
                                ndUser = vm.navidromeUser,
                                ndPass = vm.navidromePass,
                                ndConnected = vm.navidromeConnected,
                                ndChecking = vm.navidromeChecking,
                                ndError = vm.navidromeError,
                                ndTrackCount = vm.navidromeTrackCount,
                                onSaveNavidrome = vm::saveNavidrome,
                                onTestNavidrome = { vm.recheckNavidrome() },
                                onClearNavidrome = { vm.clearNavidrome() },
                                lastPlayError = vm.lastPlayError,
                                defaultQuality = vm.defaultQuality,
                                onQualityChange = vm::changeQuality,
                                losslessFallback = vm.losslessFallback,
                                onLosslessFallbackChange = vm::changeLosslessFallback,
                                cacheSizeGb = vm.cacheSizeGb,
                                cacheBytes = vm.cacheBytes,
                                onCacheSizeChange = vm::changeCacheSize,
                                onClearCache = vm::clearMediaCache,
                                loudnessMode = vm.loudnessMode,
                                onLoudnessModeChange = vm::changeLoudnessMode,
                                audioOffload = vm.audioOffload,
                                onAudioOffloadChange = vm::changeAudioOffload,
                                audioOffloadStatus = vm.audioRuntime.offloadStatus,
                                audioOutputMode = vm.audioOutputMode,
                                onAudioOutputModeChange = vm::changeAudioOutputMode,
                                usbModeAvailable = vm.usbModeAvailable,
                                deviceProfileActiveId = vm.deviceProfileRuntime.active?.id,
                                hasConfirmedAudioRoute = vm.audioRuntime.route != null,
                                onBindDeviceProfile = bindDeviceProfile,
                                deviceProfiles = vm.deviceProfiles,
                                deviceProfileAuto = vm.deviceProfileAuto,
                                deviceProfileStatus = vm.deviceProfileRuntime.active?.name ?: "全局音效 · ${vm.deviceProfileRuntime.routeLabel}",
                                devicePreGainDb = com.kmusic.apkhd.playback.FxChain.devicePreGainDb,
                                onDevicePreGainChange = vm::changeDevicePreGain,
                                onDeviceProfileAutoChange = vm::changeDeviceProfileAuto,
                                onSaveDeviceProfile = saveSoundScheme,
                                onApplyDeviceProfile = vm::applyDeviceProfile,
                                onDeleteDeviceProfile = vm::deleteDeviceProfile,
                                pureAudioMode = vm.pureAudioMode,
                                onPureAudioModeChange = vm::changePureAudioMode,
                                bluetoothAutoResume = vm.bluetoothAutoResume,
                                onBluetoothAutoResumeChange = vm::changeBluetoothAutoResume,
                                dsdOutRate = vm.dsdOutRate,
                                onDsdOutRateChange = vm::changeDsdOutRate,
                                dsdBypassEq = vm.dsdBypassEq,
                                onDsdBypassEqChange = vm::changeDsdBypassEq,
                                onExportDiagLog = { shareDiagLog(ctx, vm) },
                                onClearDiagLog = {
                                    vm.clearDiagLog()
                                    android.widget.Toast.makeText(
                                        ctx, "日志已清空", android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                },
                                wifiOnlyDownload = vm.wifiOnlyDownload,
                                onWifiOnlyDownloadChange = vm::changeWifiOnlyDownload,
                                masterWifiOnly = vm.masterWifiOnly,
                                onMasterWifiOnlyChange = vm::changeMasterWifiOnly,
                                autoResumePlayback = vm.autoResumePlayback,
                                onAutoResumePlaybackChange = vm::changeAutoResumePlayback,
                                aiEndpoint = vm.aiEndpoint,
                    aiApiKey = vm.aiApiKey,
                    aiModel = vm.aiModel,
                    onAiEndpointChange = vm::changeAiEndpoint,
                    onAiApiKeyChange = vm::changeAiApiKey,
                    onAiModelChange = vm::changeAiModel,
                    eqEnabled = vm.eqEnabled,
                                eqAvailable = vm.eqAvailable,
                                onEqEnabledChange = vm::changeEqEnabled,
                                onRefreshEq = vm::refreshEqBands,
                                eqPresets = vm.eqPresets,
                                eqPreset = vm.eqPreset,
                                onEqPresetChange = vm::changeEqPreset,
                                eqBands = vm.eqBands,
                                eqFreqs = vm.eqFreqs,
                                eqRange = vm.eqRange,
                                eqCustom = vm.eqCustom,
                                onEqBandChange = vm::changeEqBand,
                                userEqPresets = vm.userEqPresets,
                                onSaveEqCurve = vm::saveCurrentEqCurve,
                                onApplyEqCurve = vm::applyEqCurve,
                                onDeleteEqCurve = vm::deleteEqCurve,
                                fxBass = vm.fxBass,
                                onFxBassChange = vm::changeFxBass,
                                fxLoudness = vm.fxLoudness,
                                onFxLoudnessChange = vm::changeFxLoudness,
                                fxWantedCount = vm.fxWantedCount,
                                fxSampleRate = vm.fxSampleRate,
                                sleepTotalMin = vm.sleepTotalMin,
                                sleepRemainingSec = vm.sleepRemainingSec,
                                onSleepChange = vm::setSleepTimer
                            )
                        }
                    }
                    }
                }

                // Tab 栏 + 迷你播放条（子页面与播放页隐藏）
                // 迷你条必须叠在 Tab 栏**上方**，否则会被 Tab 栏压住看不见
                // WebDAV 浏览页也要有迷你条：从那里点歌播放后，没有条就回不到播放页了
                val hasMiniBar = (route is Route.None || route is Route.WebDav) && !showPlayer
                if (hasMiniBar) {
                    // navigationBarsPadding 必须有：三键导航的系统按键有 48dp 高，
                    // 手势条也占 24dp，不避让就会压住图标和文字
                    // 宽屏：Tab 已移到左侧 SideRail，这里只保留迷你播放条，并避开侧栏
                    Column(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .then(
                                if (w.isWide) Modifier.padding(start = SideRailWidth) else Modifier
                            )
                            .safeBottomPadding()
                    ) {
                        if (vm.playbackState.track != null) {
                            MiniPlayerBar(
                                title = vm.playbackState.track?.name.orEmpty(),
                                artist = vm.playbackState.track?.artist.orEmpty(),
                                qualityLabel = vm.playbackState.track?.qualityLabel
                                    ?.ifEmpty { "标准" } ?: "标准",
                                coverUrl = vm.playbackState.track?.coverUrl,
                                progress = vm.playbackState.progress,
                                isPlaying = vm.playbackState.isPlaying,
                                stalled = vm.playbackState.stalled,
                                onClick = { showPlayer = true },
                                onTogglePlay = vm::togglePlay,
                                modifier = Modifier.padding(horizontal = 16.wdp)
                            )
                            Spacer(Modifier.height(4.wdp))
                        }
                        // Tab 栏只在首页/搜索/媒体库/我的这几个主页面出现
                        if (route is Route.None && !w.isWide) {
                            MoyinTabBar(
                                items = tabs,
                                current = tab,
                                onSelect = { t ->
                                    // 重新进入搜索页要回到空白+历史，不能留着上次的结果
                                    if (t == 1) vm.resetSearch()
                                    tab = t
                                    // 同侧栏：切 Tab 必须同时退出子页面
                                    route = Route.None
                                }
                            )
                        }
                    }
                }

                // 播放页（覆盖层）
                if (showPlayer) {
                    PlayerScreen(
                        track = vm.playbackState.track,
                        isPlaying = vm.playbackState.isPlaying,
                        stalled = vm.playbackState.stalled,
                        // 进度/位置都用 lambda 传：这两个值每 100ms 变一次，
                        // 直接传值会让整个播放页跟着轮询重组，传 lambda 则只在叶子读取处重组
                        progressProvider = { vm.playbackState.progress },
                        positionProvider = { vm.playbackState.positionMs },
                        durationMs = vm.playbackState.durationMs,
                        lyrics = vm.lyrics,
                        currentLyricIndex = vm.currentLyricIndex,
                        loadingLyrics = vm.loadingLyrics,
                        queue = vm.queue,
                        currentQueueIndex = vm.queueIndex,
                        onSeek = vm::seekTo,
                        onQueueSelect = vm::playAt,
                        onQueueRemove = vm::removeFromQueue,
                        onQueueMove = vm::moveInQueue,
                        onTogglePlay = vm::togglePlay,
                        onNext = vm::next,
                        onPrev = vm::prev,
                        onBack = { showPlayer = false },
                        playMode = vm.playMode,
                        isFavorite = vm.playbackState.track?.let { vm.isFavorite(it.id) } ?: false,
                        qualityTier = vm.defaultQuality,
                        audioInfo = vm.readAudioInfo(),
                        aiConfigured = vm.aiConfigured,
                        aiText = vm.aiText,
                        aiLoading = vm.aiLoading,
                        aiError = vm.aiError,
                        aiComplete = vm.aiComplete,
                        onOpenInfo = vm::loadCachedAiGuide,
                        lyricTranslation = vm.lyricTranslation,
                        translating = vm.translating,
                        hasTranslation = vm.lyricTranslation.isNotEmpty(),
                        onTranslateLyrics = vm::translateLyrics,
                        onRequestAi = vm::requestAiGuide,
                        onCancelAi = vm::cancelAiGuide,
                        onOpenAiSettings = { showPlayer = false; tab = 3 },
                        onPlayModeChange = vm::changePlayMode,
                        onQualityChange = vm::changeQuality,
                        onToggleFavorite = { vm.playbackState.track?.let { vm.toggleFavorite(it) } },
                        onDownload = { vm.playbackState.track?.let { vm.download(it) } },
                        onAddToQueue = { vm.playbackState.track?.let { vm.addToQueue(it) } },
                        lyricOffsetMs = vm.lyricOffsetMs,
                        onNudgeLyricOffset = vm::nudgeLyricOffset,
                        onResetLyricOffset = vm::resetLyricOffset,
                        lyricFontScale = vm.lyricFontScale,
                        onNudgeLyricFontScale = vm::nudgeLyricFontScale,
                        onResetLyricFontScale = vm::resetLyricFontScale,
                        onReportWrongLyric = {
                            vm.openWrongLyricPanel()
                            showWrongLyric = true
                        },
                        eqEnabled = vm.eqEnabled,
                                eqAvailable = vm.eqAvailable,
                        eqPresets = vm.eqPresets,
                        eqPreset = vm.eqPreset,
                        onEqEnabledChange = vm::changeEqEnabled,
                                onRefreshEq = vm::refreshEqBands,
                        onEqPresetChange = vm::changeEqPreset,
                                eqBands = vm.eqBands,
                                eqFreqs = vm.eqFreqs,
                                eqRange = vm.eqRange,
                                eqCustom = vm.eqCustom,
                                onEqBandChange = vm::changeEqBand,
                                deviceProfiles = vm.deviceProfiles,
                                deviceProfileActiveId = vm.deviceProfileRuntime.active?.id,
                                hasConfirmedAudioRoute = vm.audioRuntime.route != null,
                                onSaveDeviceProfile = saveSoundScheme,
                                onApplyDeviceProfile = vm::applyDeviceProfile,
                                onDeleteDeviceProfile = vm::deleteDeviceProfile,
                                onBindDeviceProfile = bindDeviceProfile,
                                deviceProfileAuto = vm.deviceProfileAuto,
                                onDeviceProfileAutoChange = vm::changeDeviceProfileAuto,
                                userEqPresets = vm.userEqPresets,
                                onSaveEqCurve = vm::saveCurrentEqCurve,
                                onApplyEqCurve = vm::applyEqCurve,
                                onDeleteEqCurve = vm::deleteEqCurve,
                                fxBass = vm.fxBass,
                        onFxBassChange = vm::changeFxBass,
                        fxLoudness = vm.fxLoudness,
                        onFxLoudnessChange = vm::changeFxLoudness,
                        fxWantedCount = vm.fxWantedCount,
                        fxSampleRate = vm.fxSampleRate,
                        fxHeadroomDb = vm.fxHeadroomDb,
                        audioOffload = vm.audioRuntime.offloadRequested,
                        pureAudioMode = vm.audioRuntime.pureAudioModeRequested || vm.audioRuntime.usbHighFidelityRequested,
                        dsdPlaying = vm.dsdPlaying,
                        dsdLabel = vm.dsdLabel,
                        dsdBypassEq = vm.dsdBypassEq,
                        loudnessGainDb = vm.loudnessGainDb,
                        probeResult = vm.probeResult,
                        probeRunning = vm.probeRunning,
                        probeError = vm.probeError,
                        onProbe = vm::probeCurrentTrack,
                        sleepTotalMin = vm.sleepTotalMin,
                        sleepRemainingSec = vm.sleepRemainingSec,
                        onSleepChange = vm::setSleepTimer,
                        playlists = vm.userPlaylists,
                        onAddToPlaylist = { pid ->
                            vm.playbackState.track?.let { vm.addToPlaylist(pid, it) }
                        },
                        onCreatePlaylistWithTrack = { name ->
                            vm.createPlaylist(name) { pid ->
                                vm.playbackState.track?.let { vm.addToPlaylist(pid, it) }
                            }
                        }
                    )
                }
            }

            // 「歌词不对」反馈面板：换源重试（歌词配错无法自动检测，只能让用户说）
            if (showWrongLyric) {
                WrongLyricSheet(
                    trackName = vm.playbackState.track?.name ?: "",
                    onDismiss = { showWrongLyric = false },
                    onSubmit = { reason ->
                        vm.reportWrongLyric(reason)
                        showWrongLyric = false
                    }
                )
            }

            // 诊断日志导出

          }
        }
    }
}

/**
 * 导出诊断日志并弹出系统分享面板（微信 / QQ / 邮件 / 网盘都行）。
 *
 * 为什么用 FileProvider：App 私有目录（cache/logs）里的文件，
 * 直接传 file:// URI 在 Android 7+ 会抛 FileUriExposedException，
 * 必须转成 content:// 并临时授权给接收方。
 */
private fun shareDiagLog(context: android.content.Context, vm: MoyinViewModel) {
    try {
        val file = vm.exportDiagLog(context)
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            putExtra(android.content.Intent.EXTRA_SUBJECT, "KMusic 诊断日志 ${file.name}")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            android.content.Intent.createChooser(intent, "分享诊断日志")
        )
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "导出失败：${e.message}", android.widget.Toast.LENGTH_LONG).show()
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun HomeContent(
    vm: MoyinViewModel,
    onSearchClick: () -> Unit,
    onFavoritesClick: () -> Unit,
    onChartClick: (ChartSection) -> Unit,
    onPlaylistClick: (PlaylistBrief) -> Unit,
    isPlaylistFavorite: (PlaylistBrief) -> Boolean,
    onTogglePlaylistFavorite: (PlaylistBrief) -> Unit,
    onArtistClick: (ArtistBrief) -> Unit,
    onMiniPlayerClick: () -> Unit,
    onDownload: ((Track) -> Unit)? = null,
    downloadedIds: Set<String> = emptySet(),
    onLocalClick: () -> Unit,
    onRecentClick: () -> Unit,
    onOpenRecentList: () -> Unit,
    onDailyClick: () -> Unit,
    onMyPlaylistsClick: () -> Unit = {},
    onNavidromeClick: () -> Unit = {},
    onNdAlbumClick: (com.kmusic.apkhd.net.AlbumInfo) -> Unit = {},
    onNdSeeAll: () -> Unit = {}
) {
    // 四个入口：今日推荐 / 我喜欢的音乐 / 本地音乐 / 收藏的歌单。
    // WebDAV 仍留在媒体库（它是「来源」，不是每天要点的东西）。
    // 本地音乐、收藏歌单是用户明确要求的：车机上这两个是最高频的，
    // 每次都要点「媒体库 → 再点一行」多两步。
    val entries = remember(
        vm.dailyMix.size, vm.favorites.size, vm.localTracks.size,
        vm.navidromeConnected, vm.navidromeTrackCount, vm.navidromeAddress
    ) {
        listOf(
            QuickEntry(
                "今日推荐",
                if (vm.dailyMix.isNotEmpty()) "${vm.dailyMix.size} 首" else "每天换一批",
                highlighted = true
            ),
            QuickEntry(
                "我喜欢的音乐",
                if (vm.favorites.isNotEmpty()) "${vm.favorites.size} 首" else "还没有收藏"
            ),
            QuickEntry(
                "本地音乐",
                if (vm.localTracks.isNotEmpty()) "${vm.localTracks.size} 首 · 已扫描" else "未扫描"
            ),
            QuickEntry(
                "Navidrome",
                if (vm.navidromeConnected) "${vm.navidromeTrackCount} 首"
                else if (vm.navidromeAddress.isBlank()) "未配置"
                else "未连接"
            )
        )
    }

    HomeScreen(
        entries = entries,
        recentTracks = vm.recentTracks,
        charts = vm.charts,
        playlists = vm.recommendedPlaylists,
        artists = vm.topArtists,
        onChartClick = onChartClick,
        onPlaylistClick = onPlaylistClick,
        isPlaylistFavorite = isPlaylistFavorite,
        onTogglePlaylistFavorite = onTogglePlaylistFavorite,
        onArtistClick = onArtistClick,
        ndAlbums = if (vm.navidromeConnected) vm.navidromeAlbums else emptyList(),
        onNdAlbumClick = onNdAlbumClick,
        onNdSeeAll = onNdSeeAll,
        onRefreshPlaylists = { vm.refreshPlaylists() },
        onRefreshArtists = { vm.refreshArtists() },
        playlistsRefreshing = vm.playlistsRefreshing,
        artistsRefreshing = vm.artistsRefreshing,
        onLoadMorePlaylists = { vm.loadMorePlaylists() },
        onLoadMoreArtists = { vm.loadMoreArtists() },
        playlistsLoadingMore = vm.playlistsLoadingMore,
        artistsLoadingMore = vm.artistsLoadingMore,
        playlistsExhausted = vm.playlistsExhausted,
        artistsExhausted = vm.artistsExhausted,
        onSearchClick = onSearchClick,
        onEntryClick = { e ->
            when (e.title) {
                "今日推荐" -> onDailyClick()
                "我喜欢的音乐" -> onFavoritesClick()
                "本地音乐" -> onLocalClick()
                "Navidrome" -> onNavidromeClick()
                else -> onSearchClick()
            }
        },
        onTrackClick = { t, list -> vm.play(t, list) },
        onRecentClick = { t, list -> vm.play(t, list) },
        onOpenRecentList = onOpenRecentList,
        onMiniPlayerClick = onMiniPlayerClick,
        onDownload = onDownload,
        downloadedIds = downloadedIds,
        onTogglePlay = vm::togglePlay,
        modifier = Modifier.fillMaxSize()
    )
}
