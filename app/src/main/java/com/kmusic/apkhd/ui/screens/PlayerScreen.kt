package com.kmusic.apkhd.ui.screens

import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import com.kmusic.apkhd.ui.icons.IconChevronRight
import com.kmusic.apkhd.ui.icons.IconVolume
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kmusic.apkhd.ui.theme.LocalMoyinColors
import com.kmusic.apkhd.ui.theme.LocalPlayerAccent
import com.kmusic.apkhd.ui.theme.rememberCoverTint
import com.kmusic.apkhd.ui.theme.withPlayerAccent
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kmusic.apkhd.model.LyricLine
import com.kmusic.apkhd.playback.FxChain
import com.kmusic.apkhd.model.PlayMode
import com.kmusic.apkhd.model.Track
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.kmusic.apkhd.ui.tv.LocalIsTv
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.kmusic.apkhd.net.GuideMarkup
import com.kmusic.apkhd.ui.components.CoverArt
import com.kmusic.apkhd.ui.components.VinylCover
import com.kmusic.apkhd.audio.SpectrumProbe
import com.kmusic.apkhd.ui.AudioInfo
import com.kmusic.apkhd.playback.FxStatus
import com.kmusic.apkhd.ui.theme.MoyinShape
import com.kmusic.apkhd.ui.components.EqCurve
import com.kmusic.apkhd.ui.components.EqPresetGroupedChips
import com.kmusic.apkhd.ui.components.NumberInputDialog
import com.kmusic.apkhd.ui.components.PlayerImmersiveMode
import com.kmusic.apkhd.ui.components.UserCurveList
import com.kmusic.apkhd.ui.components.playerSwipeGestures
import com.kmusic.apkhd.ui.components.restoreBarsOnTouch
import com.kmusic.apkhd.ui.components.suggestedCurveName
import com.kmusic.apkhd.ui.components.SectionHeader
import com.kmusic.apkhd.ui.components.MoyinIcon
import com.kmusic.apkhd.ui.icons.IconBack
import com.kmusic.apkhd.ui.icons.IconCheck
import com.kmusic.apkhd.ui.icons.IconDownload
import com.kmusic.apkhd.ui.icons.IconHeart
import com.kmusic.apkhd.ui.icons.IconPlayLoop
import com.kmusic.apkhd.ui.icons.IconPlayOrder
import com.kmusic.apkhd.ui.icons.IconPlayShuffle
import com.kmusic.apkhd.ui.icons.IconPlaySingle
import com.kmusic.apkhd.ui.icons.IconPlus
import com.kmusic.apkhd.ui.icons.IconInfo
import com.kmusic.apkhd.ui.icons.IconLyrics
import com.kmusic.apkhd.ui.icons.IconMore
import com.kmusic.apkhd.ui.icons.IconNext
import com.kmusic.apkhd.ui.icons.IconPause
import com.kmusic.apkhd.ui.icons.IconPlay
import com.kmusic.apkhd.ui.icons.IconPrev
import com.kmusic.apkhd.ui.icons.IconEqualizer
import com.kmusic.apkhd.ui.icons.IconQueue
import com.kmusic.apkhd.ui.icons.IconVolume
import com.kmusic.apkhd.data.LYRIC_OFFSET_DEFAULT_MS
import com.kmusic.apkhd.ui.lyricLineSp
import com.kmusic.apkhd.ui.theme.moyinColors
import com.kmusic.apkhd.ui.theme.safeBottomPadding
import com.kmusic.apkhd.ui.theme.sheetBottomPadding
import com.kmusic.apkhd.ui.theme.wdp
import com.kmusic.apkhd.ui.theme.wsp
import com.kmusic.apkhd.ui.theme.wspSp
import com.kmusic.apkhd.ui.theme.window
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.zIndex
import kotlin.math.abs
import kotlin.math.roundToInt
import com.kmusic.apkhd.ui.icons.IconClose
import com.kmusic.apkhd.model.UserPlaylist
import com.kmusic.apkhd.ui.icons.IconPlaylist
import com.kmusic.apkhd.ui.tv.tvFocus
import com.kmusic.apkhd.ui.tv.TvDimens
import com.kmusic.apkhd.ui.tv.isTvMode
import com.kmusic.apkhd.ui.tv.requestFocusSafely
import com.kmusic.apkhd.ui.tv.rememberTvPanelFocus
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.kmusic.apkhd.ui.tv.tvRootFocus
import com.kmusic.apkhd.ui.tv.tvPanelGroup
import com.kmusic.apkhd.ui.tv.tvEdgeUp
import com.kmusic.apkhd.ui.tv.tvEdgeDown

/**
 * 播放页。
 *
 * 结构：**一套布局**，中央方块区域在「封面」和「歌词」之间 Crossfade 切换。
 * 这样歌词与封面天然同位置同大小，不会出现两套布局对不齐的问题。
 * 点中央方块切到歌词，再点一次回封面。
 */
@Composable
fun PlayerScreen(
    track: Track?,
    isPlaying: Boolean,
    /** 连续缓冲超过 3 秒（PlaybackState.stalled）—— 叠加加载圈，图标仍显示「暂停」 */
    stalled: Boolean = false,
    /** 播放进度（0~1）。用 lambda 提供：进度每 100ms 变一次，只有真正读它的进度条才重组 */
    progressProvider: () -> Float,
    /** 播放位置（毫秒）。同样用 lambda：读在顶层会把封面 / 歌词 / 按钮全带着重组 */
    positionProvider: () -> Long,
    durationMs: Long,
    lyrics: List<LyricLine>?,
    currentLyricIndex: Int,
    loadingLyrics: Boolean = false,
    queue: List<Track> = emptyList(),
    currentQueueIndex: Int = -1,
    onSeek: (Float) -> Unit = {},
    onQueueSelect: (Int) -> Unit = {},
    onQueueRemove: (Int) -> Unit = {},
    onQueueMove: (Int, Int) -> Unit = { _, _ -> },
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onBack: () -> Unit,
    playMode: PlayMode = PlayMode.ORDER,
    isFavorite: Boolean = false,
    qualityTier: String = "master",
    onPlayModeChange: (PlayMode) -> Unit = {},
    onQualityChange: (String) -> Unit = {},
    onToggleFavorite: () -> Unit = {},
    onDownload: () -> Unit = {},
    onAddToQueue: () -> Unit = {},
    lyricOffsetMs: Long = 0L,
    onNudgeLyricOffset: (Long) -> Unit = {},
    onResetLyricOffset: () -> Unit = {},
    /** 歌词字号倍率（0.8~1.8）*/
    lyricFontScale: Float = 1.0f,
    onNudgeLyricFontScale: (Float) -> Unit = {},
    onResetLyricFontScale: () -> Unit = {},
    onReportWrongLyric: (() -> Unit)? = null,
    // 音效：均衡器 + 系统增强 + 睡眠定时。直接放在播放页，不藏在设置里
    eqEnabled: Boolean = false,
    eqAvailable: Boolean = false,
    eqPresets: List<String> = emptyList(),
    eqPreset: Int = 0,
    /** 各频段增益（mB），空表表示设备没给均衡器频段 */
    eqBands: List<Int> = emptyList(),
    /** 各频段中心频率（Hz） */
    eqFreqs: List<Int> = emptyList(),
    /** 增益可调范围 [min, max] mB */
    eqRange: IntArray = intArrayOf(-1500, 1500),
    /** 用户拖过滑块，曲线已不是预设（折叠行据此显示「自定义」而不是预设名） */
    eqCustom: Boolean = false,
    onEqEnabledChange: (Boolean) -> Unit = {},
    onEqPresetChange: (Int) -> Unit = {},
    onRefreshEq: () -> Unit = {},
    onEqBandChange: (Int, Int) -> Unit = { _, _ -> },
    /** 用户自建曲线（A3），上限 20 条 */
    deviceProfiles: List<com.kmusic.apkhd.data.DeviceEqProfile> = emptyList(),
    deviceProfileActiveId: String? = null,
    hasConfirmedAudioRoute: Boolean = false,
    onSaveDeviceProfile: (String, Boolean) -> Unit = { _, _ -> },
    onApplyDeviceProfile: (String?) -> Unit = {},
    onDeleteDeviceProfile: (String) -> Unit = {},
    onBindDeviceProfile: (String) -> Unit = {},
    deviceProfileAuto: Boolean = false,
    onDeviceProfileAutoChange: (Boolean) -> Unit = {},
    userEqPresets: List<com.kmusic.apkhd.playback.UserEqPresets.Preset> = emptyList(),
    onSaveEqCurve: (String) -> Unit = {},
    onApplyEqCurve: (com.kmusic.apkhd.playback.UserEqPresets.Preset) -> Unit = {},
    onDeleteEqCurve: (String) -> Unit = {},
    fxBass: Int = 0,
    onFxBassChange: (Int) -> Unit = {},
    fxLoudness: Int = 0,
    onFxLoudnessChange: (Int) -> Unit = {},
    fxBassAvailable: Boolean = false,
    fxLoudnessAvailable: Boolean = false,
    /** 正在播 DSD（音效面板据此提示「均衡器已自动旁路」） */
    dsdPlaying: Boolean = false,
    /** DSD 倍率标签 */
    dsdLabel: String? = null,
    /** 用户是否开启了 DSD 自动旁路 EQ */
    dsdBypassEq: Boolean = true,
    /** 用户配置里开着几项（0..3）—— 用来区分「全关」和「还没起播」 */
    fxWantedCount: Int = 0,
    /** 处理器接进管线后的采样率；0 = 还没起播 */
    fxSampleRate: Int = 0,
    /** 让给 EQ 正增益峰值的前级余量（dB） */
    fxHeadroomDb: Float = 0f,
    /** 省电播放（offload）已开：PCM 直通 DSP，音效不可用 */
    audioOffload: Boolean = false,
    pureAudioMode: Boolean = false,
    // 音频信息 + AI 导赏
    audioInfo: AudioInfo? = null,
    /** 响度归一当前增益（dB）；0 表示没归一 */
    loudnessGainDb: Float = 0f,
    /** 频谱检测 */
    probeResult: SpectrumProbe.Result? = null,
    probeRunning: Boolean = false,
    probeError: String? = null,
    onProbe: () -> Unit = {},
    aiConfigured: Boolean = false,
    aiText: String = "",
    aiLoading: Boolean = false,
    aiError: String? = null,
    /** 这篇导赏是否写完（false = 中途停止，只有半篇） */
    aiComplete: Boolean = false,
    /** 打开详情时调用：把已生成过的导赏读出来（只读缓存，不发起请求） */
    onOpenInfo: () -> Unit = {},
    // 歌词翻译
    lyricTranslation: Map<Int, String> = emptyMap(),
    translating: Boolean = false,
    hasTranslation: Boolean = false,
    onTranslateLyrics: () -> Unit = {},
    onRequestAi: () -> Unit = {},
    onCancelAi: () -> Unit = {},
    onOpenAiSettings: () -> Unit = {},
    sleepTotalMin: Int = 0,
    sleepRemainingSec: Int = 0,
    onSleepChange: (Int) -> Unit = {},
    playlists: List<UserPlaylist> = emptyList(),
    onAddToPlaylist: (String) -> Unit = {},
    onCreatePlaylistWithTrack: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val themeColors = moyinColors()
    // 封面解码要按目标尺寸限制，Builder 需要 Context
    val context = LocalContext.current
    // ── B1 · 封面取色 ──
    // 只在深色生效（浅色是纸感设计，一层封面色会把纸面弄脏，见 CoverTint 注释）。
    // 取不到合格颜色时返回 null，一律回退主题 accent —— 不做「凑一个色」。
    val coverTint = rememberCoverTint(track?.coverUrl)
    var showLyrics by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showMode by remember { mutableStateOf(false) }
    var showQuality by remember { mutableStateOf(false) }
    var showEffects by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    // 自建歌单：⋯ → 加入歌单 →（可选）新建
    var showPlaylistPicker by remember { mutableStateOf(false) }
    var showNewPlaylist by remember { mutableStateOf(false) }

    // 换歌时回到封面态
    LaunchedEffect(track?.id) { showLyrics = false }

    // ⚠️ 播放页是覆盖层（盖在首页/列表之上），但根布局原来只有背景色、不参与命中测试：
    // Compose 会继续往下层找可点击元素 —— 用户在黑胶与标题/控制栏之间的空白处点一下，
    // 事件就穿透到下面的歌曲列表，点到哪首就换哪首（表现像"看不见的按钮"）。
    //
    // 修法：**根 Box 自己接 clickable**（不是加一个兄弟层 —— 兄弟层在最底层会被上层的
    // Column 盖住，命中测试走不到它）。根节点自己的 pointerInput 在冒泡链的末端，
    // 子元素（黑胶/按钮/进度条）先拿到事件，没人消费的才落到这里被吞掉。
    // ── B1 · 播放页强调色 ──
    // 取到封面主色就整页换过去（进度条/主键/选中态/音效面板一起变），
    // 取不到就原样使用主题 accent。浅色主题下 coverTint 恒为 null。
    CompositionLocalProvider(
        LocalMoyinColors provides themeColors.withPlayerAccent(coverTint),
        LocalPlayerAccent provides coverTint
    ) {
    // 阴影掉外层的 c：下面几十处 c.accent / c.accentFill 必须读到替换后的调色板，
    // 否则会出现「进度条变了、按钮还是旧的」的半改状态。
    val c = moyinColors()

    // ── B3 · 沉浸模式 ──
    // 只有「播放页开着、且没有任何面板弹在上面」时才隐藏系统栏：
    // 面板里要拖动、要读数值，状态栏消失会让面板顶到刘海/摄像头下面。
    var touchTick by remember { mutableIntStateOf(0) }
    // ⚠️ showLyrics 不算「面板」：它是封面/歌词互换，不是盖在上面的浮层，
    // 算进去会导致「切到歌词就再也不进沉浸」，而那正是最需要沉浸的界面。

    // v2.4.2：顶栏可达 —— 进度条按 ↑ 落到「⋯」，再 ←/→ 在 返回 / ⋯ 之间移动。
    // v2.4.1 的路径是 进度条 ↑ → 根节点 ↑ → 上一首，顶栏根本没有入口，
    // 用户报的「选不到右上角三个点」就是这个。
    val topMoreFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val topBackFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    // 控制区里各面板按钮的 requester：关闭面板后把焦点还回去，
    // 否则面板一关、菜单项节点消失，焦点就悬空了（用户报的「焦点丢失」）。
    val queueFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val effectsFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val infoFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val focusReturn = remember { com.kmusic.apkhd.ui.tv.TvFocusReturn() }

    val anySheetOpen = showQueue || showMode || showQuality ||
        showEffects || showInfo || showMore || showPlaylistPicker || showNewPlaylist

    // ── v2.4.2 · 面板分层返回 ──
    // 以前 5 个面板一个 BackHandler 都没有，按 BACK 会一路落到 MainActivity 的
    // 总处理器，而那里第一优先级是 `showPlayer -> showPlayer = false` ——
    // 面板没关、整个播放页先被关掉，用户看到的就是「返回后焦点丢失」。
    // 这里按「最内层先关」的顺序拦下来，都没开才交还给 MainActivity。
    androidx.activity.compose.BackHandler(enabled = anySheetOpen) {
        when {
            showNewPlaylist -> { showNewPlaylist = false; showPlaylistPicker = true }
            showPlaylistPicker -> showPlaylistPicker = false
            showQueue -> showQueue = false
            showEffects -> showEffects = false
            showInfo -> { showInfo = false; onCancelAi() }
            showMore -> showMore = false
            showMode -> showMode = false
            showQuality -> showQuality = false
        }
    }
    // 面板全关之后把焦点还给「打开它的那个按钮」。
    // 否则菜单项节点已从树上消失，焦点悬空，遥控器上下左右全无反应。
    androidx.compose.runtime.LaunchedEffect(anySheetOpen) {
        if (!anySheetOpen) focusReturn.restore()
    }
    PlayerImmersiveMode(
        enabled = !anySheetOpen,
        interactionTick = touchTick
    )
    // ── TV 遥控：播放页全屏，不该有焦点框，改用按键直控 ──
    // 用 onPreviewKeyEvent 而不是 onKeyEvent：预览阶段从根往焦点项传，
    // 能在焦点系统把 ←/→ 当成「移动焦点」之前先吃掉，否则按一次左键
    // 只是把焦点从播放键挪到上一首，进度条纹丝不动。
    val tv = isTvMode()
    var tvHint by remember { mutableStateOf<String?>(null) }
    var tvHintAt by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    // 提示条 1.4s 后自动消失；用 key 变化重启这个协程
    LaunchedEffect(tvHintAt) {
        if (tvHintAt != 0L) {
            kotlinx.coroutines.delay(1400)
            if (System.currentTimeMillis() - tvHintAt >= 1400) tvHint = null
        }
    }
    fun flashHint(text: String) {
        tvHint = text
        tvHintAt = System.currentTimeMillis()
    }
    // ⚠️ Compose 的按键事件只发给「有焦点的节点」及其祖先。播放页打开时页内没有
    // 任何控件拿到焦点（全屏手势页，Tab/焦点都留在上一屏），于是 onPreviewKeyEvent
    // 一次都收不到 —— 表现就是「遥控器在播放页完全没反应」。
    // 所以必须给根节点挂 FocusRequester 并主动 requestFocus。
    val rootFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    // v2.4.1：主播放键的 requester —— 根节点按 ↓ 直接进控制区，不靠几何搜索。
    // 播放页控件在封面下方，几何搜索会先撞上不可聚焦的文字块，实测容易原地不动。
    val playFocus = remember { androidx.compose.ui.focus.FocusRequester() }

    // 根节点自己是否持焦。**必须是它持焦时才接管方向键**：
    // 否则根节点的 onPreviewKeyEvent 会先于一切吃掉 ←/→，
    // 焦点永远进不了按钮（v2.4.0 的原始缺陷）。
    var rootActive by remember { mutableStateOf(false) }

    // v2.4.3：进播放页直接落在**控制区**（主播放键）——用户原话「进入播放页直接
    // 就是控制区，不需要向下焦点才进入」。根节点只是按键接收器，让用户先站在一个
    // 看不见的焦点上再按 ↓ 才见到高亮，等于白走一步。
    //
    // ⚠️ 必须「先根节点、再接力」，不能直接 requestFocus(playFocus)：
    // `FocusRequester.requestFocus()` 只对**当前活跃焦点子树内**的节点生效。
    // 播放页是覆盖层，进页那一刻活跃子树还在上一屏（侧栏/列表），跨子树请求
    // **静默失败**——不抛异常，`requestFocusSafely()` 因此假报 true。
    // 先请求根节点（它在播放页子树里、且是本页新组合的节点）把活跃子树切过来，
    // 再接力给主播放键才有效。
    //
    // 接力不能靠返回值提前 return（同上，恒 true），固定打几轮兜住首帧布局差异。
    // 以下写法已逐一实测无效，别再重走：awaitFrame/withFrameNanos 等帧、
    // focusManager.moveFocus(Down)、view.dispatchKeyEvent、
    // rootView.dispatchKeyEvent、onFocusChanged 里 post { requestFocus }。
    LaunchedEffect(tv, track?.id) {
        if (!tv) return@LaunchedEffect
        // ① 先把焦点从上一屏（侧栏 / 列表面板）抢到播放页根节点。
        //    `requestFocus()` 只对**当前活跃焦点子树内**的节点有效，播放页是
        //    覆盖层、进页时活跃子树还在侧栏 → 直接 requestFocus(playFocus)
        //    会静默失败（既不抛异常也不生效）。所以先占根节点，把活跃子树
        //    换成播放页，再往下走。
        runCatching { rootFocus.requestFocus() }
        androidx.compose.runtime.withFrameNanos { }
        // ② 再接力到控制区。**不能靠 `requestFocusSafely()` 的返回值提前
        //    return** —— 它对「还没挂到焦点树」的 requester 也返回 true。
        //    固定打几轮，最后一轮一定在布局完成之后。
        for (delayMs in longArrayOf(40L, 80L, 180L)) {
            kotlinx.coroutines.delay(delayMs)
            runCatching { playFocus.requestFocus() }
        }
        com.kmusic.apkhd.data.DiagLog.log("TVKEY", "进播放页：已接力到控制区")
    }
    // 播放页的 ±N 秒跳转 —— 根节点（封面态）和进度条（控件态）共用一份逻辑，
    // 避免两处各写一套导致「同一个动作、两个步长」。
    // 决策逻辑抽在 TvSeek（纯函数，单测覆盖时长未知/越界/到头）。
    val tvSeekBy: (Long) -> Unit = { deltaMs ->
        when (val step = com.kmusic.apkhd.ui.tv.TvSeek.step(
            positionMs = positionProvider(),
            durationMs = durationMs,
            deltaMs = deltaMs
        )) {
            is com.kmusic.apkhd.ui.tv.TvSeek.SeekStep.NeedsPlay -> {
                // 冷启动恢复队列：位置有、时长还是 0。按右键先起播，
                // 不然算出来是 NaN、onSeek 收到非法值，看起来就是「没反应」。
                onTogglePlay()
                flashHint("▶ 开始播放（时长加载中，再按 ←/→ 可跳转）")
            }
            is com.kmusic.apkhd.ui.tv.TvSeek.SeekStep.Jump -> {
                onSeek(step.progress)
                flashHint(
                    (if (deltaMs < 0) "⏪ " else "⏩ ") +
                        "${kotlin.math.abs(deltaMs) / 1000} 秒    " +
                        formatMs(step.targetMs) + " / " + formatMs(durationMs)
                )
            }
        }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(c.bg)
            .restoreBarsOnTouch { touchTick++ }
            .onFocusChanged { rootActive = it.isFocused }
            .tvRootFocus(rootFocus, tv, down = playFocus)
            .then(
                if (!tv) Modifier else Modifier.onPreviewKeyEvent { ce: androidx.compose.ui.input.key.KeyEvent ->
                    val e = ce.nativeKeyEvent
                    if (e.action != android.view.KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                    // 有面板弹出时全部让给面板，避免上下键既切歌又挪焦点
                    if (anySheetOpen) return@onPreviewKeyEvent false
                    // v2.4.3：MENU 和 CHANNEL+/− 是「全局媒体键」，**不受焦点位置限制**。
                    // 原来它们跟方向键一起被 `if (!rootActive) return` 挡在门外 ——
                    // 焦点一进控制区（现在是进页默认状态），MENU 就废了，
                    // 用户必须先把焦点退回根节点才能开「更多」，毫无道理。
                    when (e.keyCode) {
                        android.view.KeyEvent.KEYCODE_MENU -> {
                            focusReturn.remember(if (rootActive) playFocus else topMoreFocus)
                            showMode = false
                            showMore = true
                            return@onPreviewKeyEvent true
                        }
                        android.view.KeyEvent.KEYCODE_CHANNEL_UP -> { onNext(); flashHint("⏭ 下一首"); return@onPreviewKeyEvent true }
                        android.view.KeyEvent.KEYCODE_CHANNEL_DOWN -> { onPrev(); flashHint("⏮ 上一首"); return@onPreviewKeyEvent true }
                        else -> {}
                    }
                    // 焦点已经进了控制区（按钮 / 进度条）→ 方向键交还焦点系统。
                    // 这一步是「控件能选中」的关键：不交还，←/→ 会被这里吃掉。
                    if (!rootActive) return@onPreviewKeyEvent false
                    when (e.keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> { tvSeekBy(-TvDimens.SEEK_STEP_MS); true }
                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> { tvSeekBy(TvDimens.SEEK_STEP_MS); true }
                        android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                        android.view.KeyEvent.KEYCODE_ENTER,
                        android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> { onTogglePlay(); flashHint(if (isPlaying) "⏸ 暂停" else "▶ 播放"); true }
                        // v2.4.1：↓ 不再直接切歌 —— 而是把焦点送进控制区（落在主播放键）。
                        // 理由：用户明确要求「控件焦点都要」，而焦点不进去就永远选不到按钮。
                        // 切歌改在控制区里按 ←/→ 走到上一首/下一首按钮，或用 CHANNEL+/-。
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (playFocus.requestFocusSafely()) {
                                flashHint("↓ 已进入控制区 · ↑ 返回")
                                true
                            } else false
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_UP -> { onPrev(); flashHint("⏮ 上一首"); true }
                        else -> false
                    }
                }
            )
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { /* 空白区点击：吞掉，不穿透到下层 */ }
    ) {
        if (track == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无播放", color = c.t3, fontSize = 14.wsp)
            }
            return@Box
        }

        // ── TV 操作提示 ──
        // 遥控器按键没有触觉反馈，必须给一个看得见的结果：
        // 「⏩ 5 秒 1:23 / 4:05」就是按一下右键之后屏幕中央的那句话。
        // 手机 / 车机 tvHint 恒为 null，这段不产生任何绘制。
        if (tvHint != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.wdp))
                        .background(Color(0xB3000000))
                        .padding(horizontal = 22.wdp, vertical = 14.wdp)
                ) {
                    Text(
                        text = tvHint!!,
                        color = Color.White,
                        fontSize = 20.wsp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // ── 沉浸背景：封面放大 + 高斯模糊 + 压暗 ──────────────────────────
        //
        // 纯色底 + 一个方形封面是十年前的播放器长相。把封面本身铺满整屏，
        // 画面就跟着这首歌走色 —— Apple Music / 主流播放器都是这么做的。
        //
        // 三个必须做的处理：
        // 1. **先放大 1.3 倍再模糊**：不放大，模糊边缘会露出秃边（画面四周发虚发黑）
        // 2. **盖一层蒙版**：不盖的话歌词和标题糊在花背景里，读不清
        // 3. **低版本降级**：Modifier.blur 官方注明只在 Android 12+ 生效，低版本会被静默忽略，
        //    那时把蒙版加重，退化成「低调的纯色氛围」，而不是变成一张刺眼的花图
        val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        // 只在深色（A）铺封面模糊。浅色（B）是纸面设计，模糊一层会变成脏灰，破坏纸感
        if (c.isDark && !track.coverUrl.isNullOrEmpty()) {
            // 背景图铺满全屏后又模糊 64dp，原图分辨率毫无意义：
            // 不限制解码尺寸的话，4K 封面会按原尺寸解出几十 MB 位图，每次换歌都白吃一次内存
            Box(Modifier.fillMaxSize()) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(track.coverUrl)
                        .size(720)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = 1.3f
                            scaleY = 1.3f
                        }
                        .then(
                            if (canBlur) Modifier.blur(64.wdp, BlurredEdgeTreatment.Unbounded)
                            else Modifier
                        )
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                when {
                                    // 不能模糊 → 直接压成纯色，不留封面残影
                                    !canBlur && c.isDark -> listOf(Color(0xF00B0E14), Color(0xF00B0E14))
                                    !canBlur -> listOf(Color(0xF0F5F6FA), Color(0xF0F5F6FA))
                                    // 能模糊 → 顶部几乎不压（让封面色彩和纹理透出来），
                                    // 底部压深（放控制按钮，必须有对比度）
                                    c.isDark -> listOf(Color(0x33000000), Color(0xCC000000))
                                    else -> listOf(Color(0x55FFFFFF), Color(0xE6FFFFFF))
                                }
                            )
                        )
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                // 背景已经铺到屏幕边缘（沉浸式），内容必须自己让开状态栏和手势条，
                // 否则「正在播放」会被状态栏压住、底部工具栏会被手势条盖掉。
                .statusBarsPadding()
                .safeBottomPadding()
                .padding(horizontal = 16.wdp)
        ) {
            // ===== 宽屏（平板 / 折叠屏展开）：跨两栏全局顶栏 + 左 40% 信息 / 右 60% 歌词 =====
            // 只在“宽屏 + 横屏”时启用：竖屏平板（如 800x1280dp）单列更合理，
            // 否则左栏会被拉成又窄又高的长条，封面下方全是空白。
            if (window.isWide && window.widthDp > window.heightDp && window.heightDp >= 380) {
                Spacer(Modifier.height(4.wdp))
                // 全局顶栏：一条横跨两栏，不拆成两份
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NavIcon(IconBack, requester = topBackFocus, down = playFocus) { onBack() }
                    Text("正在播放", color = c.t1, fontSize = 15.wsp, fontWeight = FontWeight.Medium)
                    NavIcon(
                        IconMore,
                        requester = topMoreFocus,
                        down = playFocus
                    ) { showMode = false; focusReturn.remember(topMoreFocus); showMore = true }
                }
                Row(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(top = 8.wdp, bottom = 8.wdp)
                ) {
                    // ── 宽屏重排：左栏只放黑胶（撑满高度），右栏上歌词、下信息与控制 ──
                    // 之前封面和文字/控制挤在同一栏，封面只剩 ~250dp，盘径被压到屏高 27%。
                    val compactH = window.heightDp < 520
                    BoxWithConstraints(
                        Modifier
                            .weight(if (compactH) 0.42f else 0.46f)
                            .fillMaxHeight()
                            .padding(end = 14.wdp),
                        contentAlignment = Alignment.Center
                    ) {
                        // 上限 460dp：再大盘心会贴到唱臂支点
                        val coverSide = minOf(minOf(maxWidth, maxHeight), 460.wdp)
                            .coerceAtLeast(96.wdp)
                        Box(
                            Modifier
                                .size(coverSide)
                                // B2（宽屏）：封面/歌词在宽屏是并排的，没有「横滑切过去」这个对象，
                                // 所以只保留**下滑收起**；横滑留给窄屏那套（封面 ⇄ 歌词互换）。
                                // 手势挂在封面盒上而不是整页：右侧歌词要上下滚，挂整页必抢。
                                .playerSwipeGestures(
                                    onSwipeToLyrics = {},
                                    onSwipeDown = { onBack() },
                                    horizontalEnabled = false
                                )
                                .tvFocus()
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() }
                                ) { showQuality = true }
                        ) {
                            VinylCover(
                                coverUrl = track.coverUrl,
                                isPlaying = isPlaying,
                                modifier = Modifier.fillMaxSize(),
                                showTonearm = coverSide >= 150.dp
                            )
                        }
                    }
                    CompositionLocalProvider(
                        LocalDensity provides Density(
                            density = LocalDensity.current.density * (if (compactH) 0.78f else 1f),
                            fontScale = LocalDensity.current.fontScale
                        )
                    ) {
                        Column(
                            Modifier
                                .weight(if (compactH) 0.58f else 0.54f)
                                .fillMaxHeight()
                        ) {
                            Box(
                                Modifier.weight(1f).fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                LyricsPane(
                                    lyrics = lyrics,
                                    currentIndex = currentLyricIndex,
                                    loading = loadingLyrics,
                                    offsetMs = lyricOffsetMs,
                                    positionProvider = positionProvider,
                                    fontScale = lyricFontScale,
                                    onSeekToMs = { ms ->
                                        if (durationMs > 0) onSeek((ms.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f))
                                    },
                                    translations = lyricTranslation,
                                    translating = translating,
                                    hasTranslation = hasTranslation,
                                    onTranslate = onTranslateLyrics,
                                    aiConfigured = aiConfigured,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            Spacer(Modifier.height(10.wdp))
                            Text(
                                text = track.name,
                                color = c.t1,
                                fontSize = (if (c.titleSerif) 23 else 20).wsp,
                                fontFamily = if (c.titleSerif) FontFamily.Serif else FontFamily.SansSerif,
                                fontWeight = if (c.titleSerif) FontWeight.Bold else FontWeight.SemiBold,
                                letterSpacing = if (c.titleSerif) 0.sp else (-0.017).sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (!compactH) {
                                Spacer(Modifier.height(5.wdp))
                                Text(
                                    text = track.artist + if (track.album.isNotEmpty()) " · ${track.album}" else "",
                                    color = c.t2,
                                    fontSize = 12.wsp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(Modifier.height(10.wdp))
                            SeekBar(
                                progressProvider = progressProvider,
                                onSeek = onSeek,
                                tvSeekBy = if (tv) tvSeekBy else null,
                                // v2.4.2：↑ 落到顶栏「⋯」，不再回根节点 ——
                                // 回根节点后顶栏就永远够不着（用户报「选不到三个点」）
                                up = topMoreFocus
                            )
                            Spacer(Modifier.height(6.wdp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                ElapsedTimeText(positionProvider = positionProvider, color = c.t3)
                                Text(formatMs(durationMs), color = c.t3, fontSize = 10.wsp)
                            }
                            Spacer(Modifier.height(8.wdp))
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CtrlButton(
                                    icon = playModeIcon(playMode),
                                    size = 40.wdp,
                                    iconSize = 16.wdp,
                                    tint = if (playMode == PlayMode.ORDER) null else c.accent
                                ) { showMode = true }
                                CtrlButton(icon = IconPrev, size = 46.wdp, iconSize = 16.wdp) { onPrev() }
                                CtrlButton(
                                    icon = if (isPlaying) IconPause else IconPlay,
                                    size = 54.wdp,
                                    iconSize = 16.dp,
                                    emphasized = true,
                                    // 缓冲超时不改图标（改了就又回到「播放/暂停乱翻」），只叠圈
                                    spinner = stalled,
                                    // ↓ 的落点由根节点指派（tvRootFocus(down = playFocus)）。
                                    // 这里**不设 up** —— 它正上方就是进度条，
                                    // 一旦钉死 up=rootFocus，↑ 会越过进度条直接回根节点，
                                    // 进度条就永远选不中了。
                                    requester = playFocus
                                ) { onTogglePlay() }
                                CtrlButton(icon = IconNext, size = 46.wdp, iconSize = 16.wdp) { onNext() }
                                CtrlButton(
                                    icon = IconHeart,
                                    size = 40.wdp,
                                    iconSize = 16.wdp,
                                    tint = if (isFavorite) c.accent else null
                                ) { onToggleFavorite() }
                            }
                            Spacer(Modifier.height(8.wdp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                ToolItem(
                                    IconQueue,
                                    if (queue.isEmpty()) "队列" else "队列 ${queue.size}",
                                    requester = queueFocus,
                                    onOpen = { focusReturn.remember(queueFocus) },
                                    onClick = { showQueue = true }
                                )
                                ToolItem(
                                    IconEqualizer, "音效",
                                    requester = effectsFocus,
                                    onOpen = { focusReturn.remember(effectsFocus) },
                                    onClick = { showEffects = true }
                                )
                                ToolItem(
                                    IconInfo, "详情",
                                    requester = infoFocus,
                                    onOpen = { focusReturn.remember(infoFocus) },
                                    onClick = { showInfo = true }
                                )
                            }
                        }
                    }
                }
            } else {
            Spacer(Modifier.height(4.wdp))
            // 导航
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                NavIcon(IconBack, requester = topBackFocus, down = playFocus) { onBack() }
                Text("正在播放", color = c.t1, fontSize = 15.wsp, fontWeight = FontWeight.Medium)
                NavIcon(
                    IconMore,
                    requester = topMoreFocus,
                    down = playFocus
                ) { showMode = false; focusReturn.remember(topMoreFocus); showMore = true }
            }

            // 中央方块：封面 ⇄ 歌词（同位置同大小）
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                // 封面必须同时受宽高约束：手机横屏（高 < 宽）时按最短边收缩，
                // 否则正方形会超出容器、压住下面的歌名/进度/按钮（表现为"图片和字重合"）
                val coverSide = minOf(maxWidth, maxHeight).coerceAtLeast(96.wdp)
                Box(
                    Modifier
                        .size(coverSide)
                        // B2 · 横滑切封面/歌词，下滑收起播放页。
                        // 只挂在这个 Box（封面/歌词），**不挂整页** —— 整页有进度条横拖
                        // 和歌词竖滚，整页挂手势就是主动制造误触。
                        .playerSwipeGestures(
                            onSwipeToLyrics = { toLyrics -> showLyrics = toLyrics },
                            onSwipeDown = { onBack() }
                        )
                        .tvFocus()
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { showLyrics = !showLyrics }
                ) {
                    Crossfade(
                        targetState = showLyrics,
                        animationSpec = tween(220),
                        label = "coverLyrics"
                    ) { lyricsMode ->
                        if (lyricsMode) {
                            LyricsPane(
                                lyrics = lyrics,
                                currentIndex = currentLyricIndex,
                                loading = loadingLyrics,
                                offsetMs = lyricOffsetMs,
                                positionProvider = positionProvider,
                                fontScale = lyricFontScale,
                                onSeekToMs = { ms ->
                                    if (durationMs > 0) onSeek((ms.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f))
                                },
                                translations = lyricTranslation,
                                translating = translating,
                                hasTranslation = hasTranslation,
                                onTranslate = onTranslateLyrics,
                                aiConfigured = aiConfigured,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            VinylCover(
                                coverUrl = track.coverUrl,
                                isPlaying = isPlaying,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }

            // 标题
            Text(
                text = track.name,
                color = c.t1,
                // 深色（A）：无衬线收字距，现代播放器；浅色（B）：衬线，像唱片内页标题
                fontSize = (if (c.titleSerif) 25 else 21).wsp,
                fontFamily = if (c.titleSerif) FontFamily.Serif else FontFamily.SansSerif,
                fontWeight = if (c.titleSerif) FontWeight.Bold else FontWeight.SemiBold,
                letterSpacing = if (c.titleSerif) 0.sp else (-0.017).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(16.wdp))
            Text(
                text = track.artist + if (track.album.isNotEmpty()) " · ${track.album}" else "",
                color = c.t2,
                fontSize = 13.wsp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(18.wdp))
            SeekBar(
                progressProvider = progressProvider,
                onSeek = onSeek,
                tvSeekBy = if (tv) tvSeekBy else null,
                // v2.4.2：↑ 落到顶栏「⋯」——见宽屏那处的说明
                up = topMoreFocus
            )
            Spacer(Modifier.height(16.wdp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ElapsedTimeText(positionProvider = positionProvider, color = c.t3)
                Text(formatMs(durationMs), color = c.t3, fontSize = 10.wsp)
            }

            Spacer(Modifier.height(18.wdp))
            // 控制排：深浅主题统一只留按钮，不再加底板。
            // 深色原来那层"玻璃胶囊"在浅色下没有对应物，两套主题像两个播放器；
            // 去掉之后按钮直接落在封面光晕上，靠间距分组，反而更干净。
            Row(
                // 两端留 6dp：车机屏有圆角/黑边，最外侧那颗（播放模式）贴边会被切掉
                Modifier.fillMaxWidth().padding(horizontal = 6.wdp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CtrlButton(
                    icon = playModeIcon(playMode),
                    size = 40.wdp,
                    iconSize = 16.wdp,
                    tint = if (playMode == PlayMode.ORDER) null else c.accent
                ) { showMode = true }
                CtrlButton(icon = IconPrev, size = 48.wdp, iconSize = 16.wdp) { onPrev() }
                CtrlButton(
                    icon = if (isPlaying) IconPause else IconPlay,
                    size = 56.wdp,
                    iconSize = 16.dp,
                    emphasized = true,
                    spinner = stalled,
                    requester = playFocus
                ) { onTogglePlay() }
                CtrlButton(icon = IconNext, size = 48.wdp, iconSize = 16.wdp) { onNext() }
                CtrlButton(
                    icon = IconHeart,
                    size = 40.wdp,
                    iconSize = 16.wdp,
                    tint = if (isFavorite) c.accent else null
                ) { onToggleFavorite() }
            }

            Spacer(Modifier.height(24.wdp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ToolItem(IconLyrics, if (showLyrics) "封面" else "歌词") { showLyrics = !showLyrics }
                ToolItem(IconQueue, if (queue.isEmpty()) "队列" else "队列 ${queue.size}") { showQueue = true }
                ToolItem(IconEqualizer, "音效") { showEffects = true }
                ToolItem(IconInfo, "详情") { showInfo = true }
            }
            Spacer(Modifier.height(16.wdp))
            }
        }

        // 播放模式
        if (showMode) {
            OptionSheet(
                title = "播放模式",
                options = PlayMode.entries.map { it.name to it.label },
                selected = playMode.name,
                onPick = { onPlayModeChange(PlayMode.of(it)); showMode = false },
                onDismiss = { showMode = false }
            )
        }

        // 音质
        if (showQuality) {
            OptionSheet(
                title = "音质",
                options = listOf(
                    "master" to "臻品母带 · 24bit/192kHz",
                    "hires" to "无损 FLAC",
                    "high" to "极高品 320k",
                    "standard" to "标准 128k"
                ),
                selected = qualityTier,
                onPick = { onQualityChange(it); showQuality = false },
                onDismiss = { showQuality = false }
            )
        }

        // 更多（下载 / 加入队列）
        if (showPlaylistPicker) {
            AddToPlaylistSheet(
                playlists = playlists,
                onPick = { pid -> onAddToPlaylist(pid); showPlaylistPicker = false },
                onCreateNew = { showPlaylistPicker = false; showNewPlaylist = true },
                onDismiss = { showPlaylistPicker = false }
            )
        }
        if (showNewPlaylist) {
            NewPlaylistDialog(
                onDismiss = { showNewPlaylist = false },
                onConfirm = { n -> onCreatePlaylistWithTrack(n); showNewPlaylist = false }
            )
        }


        // ── TV 操作提示（覆盖在所有面板之上，最后画） ──
        // 遥控器按键没有触觉反馈，必须给一个看得见的结果：
        // 「⏩ 5 秒 1:23 / 4:05」就是按一下右键之后屏幕中央的那句话。
        // 手机 / 车机 tvHint 恒为 null，这段不产生任何绘制。
        if (tvHint != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.wdp))
                        .background(Color(0xB3000000))
                        .padding(horizontal = 22.wdp, vertical = 14.wdp)
                ) {
                    Text(
                        text = tvHint!!,
                        color = Color.White,
                        fontSize = 20.wsp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        if (showMore) {
            MoreSheet(
                onDownload = { onDownload(); showMore = false },
                onAddToQueue = { onAddToQueue(); showMore = false },
                onAddToPlaylist = { showMore = false; showPlaylistPicker = true },
                qualityLabel = qualityName(qualityTier),
                onQualityClick = { showMore = false; showQuality = true },
                lyricOffsetMs = lyricOffsetMs,
                onNudgeOffset = onNudgeLyricOffset,
                onResetOffset = onResetLyricOffset,
                fontScale = lyricFontScale,
                onNudgeFontScale = onNudgeLyricFontScale,
                onResetFontScale = onResetLyricFontScale,
                onReportWrongLyric = onReportWrongLyric?.let { cb -> { showMore = false; cb() } },
                aiConfigured = aiConfigured,
                translating = translating,
                hasTranslation = hasTranslation,
                onTranslate = onTranslateLyrics,
                onDismiss = { showMore = false }
            )
        }

        // 队列面板（覆盖层）
        // 音效面板：均衡器 + 系统增强 + 睡眠定时，全部在这一页搞定
        if (showEffects) {
            EffectSheet(
                eqEnabled = eqEnabled,
                eqAvailable = eqAvailable,
                eqPresets = eqPresets,
                eqPreset = eqPreset,
                eqBands = eqBands,
                eqFreqs = eqFreqs,
                eqRange = eqRange,
                eqCustom = eqCustom,
                onEqEnabledChange = onEqEnabledChange,
                onEqPresetChange = onEqPresetChange,
                onEqBandChange = onEqBandChange,
                deviceProfiles = deviceProfiles,
                deviceProfileActiveId = deviceProfileActiveId,
                hasConfirmedAudioRoute = hasConfirmedAudioRoute,
                onSaveDeviceProfile = onSaveDeviceProfile,
                onApplyDeviceProfile = onApplyDeviceProfile,
                onDeleteDeviceProfile = onDeleteDeviceProfile,
                onBindDeviceProfile = onBindDeviceProfile,
                deviceProfileAuto = deviceProfileAuto,
                onDeviceProfileAutoChange = onDeviceProfileAutoChange,
                userEqPresets = userEqPresets,
                onSaveEqCurve = onSaveEqCurve,
                onApplyEqCurve = onApplyEqCurve,
                onDeleteEqCurve = onDeleteEqCurve,
                onRefreshEq = onRefreshEq,
                fxBass = fxBass, onFxBassChange = onFxBassChange,
                fxLoudness = fxLoudness, onFxLoudnessChange = onFxLoudnessChange,
                fxBassAvailable = fxBassAvailable,
                fxLoudnessAvailable = fxLoudnessAvailable,
                fxWantedCount = fxWantedCount,
                fxSampleRate = fxSampleRate,
                fxHeadroomDb = fxHeadroomDb,
                audioOffload = audioOffload,
                pureAudioMode = pureAudioMode,
                dsdPlaying = dsdPlaying,
                dsdLabel = dsdLabel,
                dsdBypassEq = dsdBypassEq,
                sleepTotalMin = sleepTotalMin,
                sleepRemainingSec = sleepRemainingSec,
                onSleepChange = onSleepChange,
                onDismiss = { showEffects = false }
            )
        }

        if (showInfo) {
            // 打开就把已有的导赏读出来：用户不该因为关了面板就看不到自己花过钱的内容
            LaunchedEffect(showInfo) { onOpenInfo() }
            InfoSheet(
                track = track,
                info = audioInfo,
                loudnessGainDb = loudnessGainDb,
                probeResult = probeResult,
                probeRunning = probeRunning,
                probeError = probeError,
                onProbe = onProbe,
                canProbe = track?.source == "local",
                aiConfigured = aiConfigured,
                aiText = aiText,
                aiLoading = aiLoading,
                aiError = aiError,
                aiComplete = aiComplete,
                onRequestAi = onRequestAi,
                onCancelAi = onCancelAi,
                onOpenAiSettings = { showInfo = false; onOpenAiSettings() },
                onDismiss = { showInfo = false; onCancelAi() }
            )
        }

        if (showQueue) {
            QueueSheet(
                queue = queue,
                currentIndex = currentQueueIndex,
                onSelect = { i -> onQueueSelect(i); showQueue = false },
                onRemove = onQueueRemove,
                onMove = onQueueMove,
                onDismiss = { showQueue = false },
                onEmpty = {
                    // 删空后关面板并把焦点还给「队列」入口（该按钮此刻仍在树上，
                    // 不会像被删的行那样连带摘掉焦点）。
                    showQueue = false
                    focusReturn.remember(queueFocus)
                    focusReturn.restore()
                }
            )
        }
    }
    }
}

/**
 * 播放队列：长按拖动排序，右侧 ✕ 移除，点选直接切歌。
 *
 * 拖动用「跟手位移 + 松手换算成目标下标」的做法：
 * 拖动过程中只动视觉，松手才提交一次 onMove —— 这样播放列表不会被反复重建。
 */
@Composable
private fun QueueSheet(
    queue: List<Track>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
    tvMode: Boolean = LocalIsTv.current,
    onEmpty: () -> Unit = {}
) {
    val content: @Composable () -> Unit = { QueueSheetContent(queue, currentIndex, onSelect, onRemove, onMove, onDismiss, tvMode, onEmpty) }
    if (tvMode) {
        // TV 面板必须有独立窗口/焦点子树；作为播放页兄弟 Column 时 requestFocus 会静默失败。
        Popup(onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) { content() }
    } else content()
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun QueueSheetContent(
    queue: List<Track>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
    tvMode: Boolean,
    onEmpty: () -> Unit
) {
    val c = moyinColors()
    val rowH = 56.wdp
    val rowPx = with(LocalDensity.current) { rowH.toPx() }
    var dragging by remember { mutableStateOf(-1) }
    var dragOffset by remember { mutableStateOf(0f) }
    val panelFocus = rememberTvPanelFocus()
    val panelRoot = remember { androidx.compose.ui.focus.FocusRequester() }
    val listState = rememberLazyListState()
    var tvIndex by remember { mutableIntStateOf(currentIndex.coerceAtLeast(0)) }
    var centerDownAt by remember { mutableLongStateOf(0L) }
    // 面板打开后焦点钉在容器上（多轮幂等，不看返回值）。
    LaunchedEffect(tvMode, queue.isEmpty()) {
        if (!tvMode || queue.isEmpty()) return@LaunchedEffect
        for (delayMs in longArrayOf(60L, 180L, 360L)) {
            kotlinx.coroutines.delay(delayMs)
            panelRoot.requestFocusSafely()
        }
    }
    // 索引移动时把目标行滚进视野。
    LaunchedEffect(tvIndex, queue.size) {
        if (tvMode && queue.isNotEmpty()) {
            runCatching { listState.animateScrollToItem(tvIndex.coerceIn(0, queue.lastIndex)) }
        }
    }
    if (tvMode) {
        LaunchedEffect(queue.size) {
            tvIndex = tvIndex.coerceIn(0, (queue.size - 1).coerceAtLeast(0))
        }
    }
    if (tvMode && queue.isEmpty()) {
        LaunchedEffect(Unit) { onEmpty() }
    }
    /**
     * 删除一行并把高亮交给接管的曲目。
     *
     * 删除后接管下标 = 原位置下一首、删末项则新末项（[QueueContinuation.focusAfterRemoval]）。
     * TV 路径上没有任何行是焦点节点，焦点始终钉在面板容器，所以这里只改索引。
     */
    fun deleteRowWithFocus(index: Int) {
        if (tvMode) {
            tvIndex = com.kmusic.apkhd.playback.QueueContinuation
                .focusAfterRemoval(index, queue.size - 1) ?: 0
        }
        onRemove(index)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            // 遮罩只负责点击关闭，不参与焦点（否则会跟面板里的真实控件抢）
            .focusProperties { canFocus = false }
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onDismiss() }
    ) {
        // 宽屏横屏：右侧抽屉；窄屏：底部面板
        val drawer = window.isWide && window.widthDp > window.heightDp && window.heightDp >= 380
        Column(
            Modifier
                .align(if (drawer) Alignment.CenterEnd else Alignment.BottomCenter)
                .then(
                    if (drawer) Modifier.width(400.wdp).fillMaxHeight()
                    else Modifier.fillMaxWidth().fillMaxHeight(0.66f)
                )
                .clip(
                    if (drawer) RoundedCornerShape(topStart = 18.wdp, bottomStart = 18.wdp)
                    else RoundedCornerShape(topStart = 18.wdp, topEnd = 18.wdp)
                )
                .background(if (c.isDark) c.s1 else Color(0xFFFFFFFF))
                // 用 pointerInput 吞掉空白区点击，**不要用 clickable**：
                // clickable 自带 focusable 语义，会给面板容器建一个可聚焦节点，
                // 配合下面的 tvPanelGroup 时这个空壳会自己抢到焦点 ——
                // 表现为「面板开了、里面一行高亮都没有、方向键乱跑」。
                .pointerInput(Unit) {
                    detectTapGestures { }
                }
                // v2.4.4：TV 面板改「单焦点根 + 索引高亮」。
                // 之前每行各自是焦点节点，删除时持焦行被摘掉，Popup 内后续所有
                // requestFocus 都静默失败（实测删除后无一行 hasFocus）→ 用户看到失焦。
                // 现在焦点永远钉在这个容器上，行只是高亮，删除只改索引。
                .then(
                    if (tvMode) Modifier
                        .focusRequester(panelRoot)
                        .focusable()
                        .onKeyEvent { ev ->
                            if (ev.nativeKeyEvent.keyCode == 23 || ev.nativeKeyEvent.keyCode == 66) {
                                if (ev.type == KeyEventType.KeyDown) {
                                    // 遥控器长按会持续发 repeatCount>0 的 KeyDown；
                                    // adb/部分盒子只发一次 DOWN，所以同时记时间戳兜底。
                                    if (ev.nativeKeyEvent.repeatCount == 0) {
                                        centerDownAt = System.currentTimeMillis()
                                    } else if (System.currentTimeMillis() - centerDownAt >= 600L) {
                                        if (queue.isNotEmpty()) deleteRowWithFocus(tvIndex)
                                        centerDownAt = 0L
                                    }
                                    return@onKeyEvent true
                                }
                                if (ev.type == KeyEventType.KeyUp) {
                                    val held = if (centerDownAt == 0L) 0L else System.currentTimeMillis() - centerDownAt
                                    centerDownAt = 0L
                                    if (queue.isNotEmpty()) {
                                        if (held >= 600L) deleteRowWithFocus(tvIndex) else onSelect(tvIndex)
                                    }
                                    return@onKeyEvent true
                                }
                            }
                            if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                            when (ev.nativeKeyEvent.keyCode) {
                                19 -> { if (tvIndex > 0) tvIndex--; true }        // DPAD_UP
                                20 -> { if (tvIndex < queue.lastIndex) tvIndex++; true } // DPAD_DOWN
                                21, 22 -> true                                   // DPAD 左右
                                4 -> { onDismiss(); true }                       // BACK
                                else -> false
                            }
                        }
                    else Modifier
                )
                // 底部让位：车机底栏不上报 inset，「随机播放」那一行会被压在图标栏下面
                .sheetBottomPadding(!drawer)
                .padding(top = 14.wdp, bottom = 8.wdp)
        ) {
            Text(
                "播放队列 · ${queue.size} 首",
                color = c.t1,
                fontSize = 14.wsp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 18.wdp)
            )
            Spacer(Modifier.height(4.wdp))
            Text(
                "长按拖动排序",
                color = c.t3,
                fontSize = 10.wsp,
                modifier = Modifier.padding(horizontal = 18.wdp)
            )
            Spacer(Modifier.height(8.wdp))
            if (queue.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("队列是空的", color = c.t3, fontSize = 13.wsp)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    itemsIndexed(queue, key = { _, t -> t.id }) { i, t ->
                        val on = i == currentIndex
                        val isDrag = dragging == i
                        // TV 模式下各行不再是焦点节点：高亮跟 tvIndex 走，按键由面板根接管。
                        val tvSel = tvMode && i == tvIndex
                        Row(
                            Modifier
                                .fillMaxWidth()
                                // 面板根的按键接管后，行不再是焦点节点。
                                .height(rowH)
                                .zIndex(if (isDrag) 1f else 0f)
                                .clip(RoundedCornerShape(8.wdp))
                                .then(if (tvSel) Modifier.background(c.accent.copy(alpha = 0.22f)) else Modifier)
                                .graphicsLayer {
                                    translationY = if (isDrag) dragOffset else 0f
                                    // 拖动中的那一行浮起来，给一点层级感
                                    scaleX = if (isDrag) 1.02f else 1f
                                    scaleY = if (isDrag) 1.02f else 1f
                                }
                                .background(
                                    when {
                                        isDrag -> if (c.isDark) Color(0x33FFFFFF) else Color(0x14000000)
                                        else -> Color.Transparent
                                    }
                                )
                                // TV：行不再可点焦点，点击/长按由面板根统一处理；
                                // 这里只留触摸点按（触屏或鼠标点选）。
                                .then(
                                    if (tvMode) Modifier.pointerInput(i, queue.size) {
                                        detectTapGestures { onSelect(i) }
                                    } else Modifier.clickable(
                                        indication = null,
                                        interactionSource = remember { MutableInteractionSource() }
                                    ) { if (dragging < 0) onSelect(i) }
                                )
                                // 长按拖动排序保留（触摸 / 鼠标用；遥控器没有长按语义）
                                .pointerInput(queue.size, i) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            dragging = i
                                            dragOffset = 0f
                                        },
                                        onDragEnd = {
                                            val from = dragging
                                            val steps = (dragOffset / rowPx).roundToInt()
                                            dragging = -1
                                            dragOffset = 0f
                                            if (from >= 0 && steps != 0) {
                                                onMove(
                                                    from,
                                                    (from + steps).coerceIn(0, queue.lastIndex)
                                                )
                                            }
                                        },
                                        onDragCancel = {
                                            dragging = -1
                                            dragOffset = 0f
                                        }
                                    ) { _, drag -> dragOffset += drag.y }
                                }
                                .padding(horizontal = 18.wdp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.weight(1f)) {
                                Column {
                                    Text(
                                        t.name,
                                        color = if (on) c.accent else c.t1,
                                        fontSize = 13.wsp,
                                        fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (t.artist.isNotEmpty()) {
                                        Text(
                                            t.artist,
                                            color = c.t3,
                                            fontSize = 11.wsp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                            if (on) {
                                MoyinIcon(icon = IconPlay, tint = c.accent, size = 14.wdp)
                                Spacer(Modifier.width(6.wdp))
                            }
                            Box(
                                Modifier
                                    .size(36.wdp)
                                    .clip(RoundedCornerShape(10.wdp))
                                    .then(
                                        if (tvMode) Modifier.pointerInput(i, queue.size) {
                                            detectTapGestures { deleteRowWithFocus(i) }
                                        } else Modifier
                                            .tvFocus(shape = RoundedCornerShape(10.wdp))
                                            .clickable(
                                                indication = null,
                                                interactionSource = remember { MutableInteractionSource() }
                                            ) { deleteRowWithFocus(i) }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                MoyinIcon(icon = IconClose, tint = c.t3, size = 14.wdp)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 歌词内容：与封面同尺寸的方块内滚动歌词。
 *
 * 偏移 / 字号 / 换源都收进了「更多」面板（播放页 ⋯ → 歌词），
 * 歌词区只负责唱，不再压两行调节条 —— 歌词本来就该占满这一块。
 */
@Composable
private fun LyricsPane(
    lyrics: List<LyricLine>?,
    currentIndex: Int,
    loading: Boolean,
    offsetMs: Long,
    /** 播放位置（毫秒）的提供者。用来算当前行唱到哪了（逐字扫光进度） */
    positionProvider: () -> Long = { 0L },
    /** 歌词字号倍率（0.8~1.8），在「更多 → 歌词」里调 */
    fontScale: Float = 1f,
    /** 行索引 → 译文。整篇都显示：只显示当前行的话，用户点了「译」几乎看不到变化 */
    translations: Map<Int, String> = emptyMap(),
    translating: Boolean = false,
    hasTranslation: Boolean = false,
    onTranslate: () -> Unit = {},
    aiConfigured: Boolean = false,
    /** 用户拖动歌词松手后跳转到对应时间（毫秒）。null 则禁用拖动 seek */
    onSeekToMs: ((Long) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val c = moyinColors()
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        // 底部不再有偏移/字号条，歌词占满整栏
        val viewportH = maxHeight.coerceAtLeast(120.wdp)
        when {
            loading -> Text("歌词加载中…", color = c.t3, fontSize = 13.wsp)
            lyrics.isNullOrEmpty() -> Text("暂无歌词", color = c.t3, fontSize = 13.wsp)
            else -> Column(Modifier.fillMaxSize()) {
                val listState = rememberLazyListState()
                // 只有「手指真的在拖」才算用户滚动；animateScrollToItem 的自动滚动不会置 true
                val isDragged by listState.interactionSource.collectIsDraggedAsState()
                // 最近一次「程序自动滚动」的时间：1.5 秒内不把它当成用户拖动去 seek
                var lastProgrammaticAt by remember { mutableStateOf(0L) }
                // 用户最后一次手动滚动的时间：3 秒内不让自动跟随把视图抢回去
                var userScrollAt by remember { mutableStateOf(0L) }

                // ── 当前行的「唱到哪了」进度（0~1），用于逐字扫光 ──────────────
                //
                // 拆成一个空渲染的子 Composable（LyricSweepDriver）：这里要读 positionProvider()，
                // 而播放位置每 100ms 变一次。读在 LyricsPane 这一层，整个歌词列表会跟着重组；
                // 关在它自己里面，重组范围就只有它一个。
                LyricSweepDriver(
                    positionProvider = positionProvider,
                    lyrics = lyrics,
                    currentIndex = currentIndex,
                    offsetMs = offsetMs
                )

                LaunchedEffect(currentIndex) {
                    // 正在拖动、或刚拖完 3 秒内：都不抢回视图（慢拖超过 3 秒也不会被拉回）
                    if (isDragged) return@LaunchedEffect
                    if (System.currentTimeMillis() - userScrollAt < 3000) return@LaunchedEffect
                    if (currentIndex in lyrics.indices) {
                        // 上下 contentPadding 各留 40% 视口高，滚到当前行即视觉居中
                        lastProgrammaticAt = System.currentTimeMillis()
                        runCatching { listState.animateScrollToItem(currentIndex) }
                    }
                }
                // 用户手动拖动歌词：停手 0.3s 后，取视口正中那一行的时间跳转（方案 A）
                if (onSeekToMs != null) {
                    LaunchedEffect(isDragged) {
                        if (isDragged) return@LaunchedEffect
                        // 手指松开：记录时间，随后 3 秒内自动跟随不抢视图
                        userScrollAt = System.currentTimeMillis()
                        // 自动滚动刚结束的不算用户拖动
                        if (System.currentTimeMillis() - lastProgrammaticAt < 1500) return@LaunchedEffect
                        kotlinx.coroutines.delay(300)
                        if (isDragged) return@LaunchedEffect
                        val info = listState.layoutInfo
                        val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2
                        val centerItem = info.visibleItemsInfo
                            .minByOrNull { kotlin.math.abs(it.offset + it.size / 2 - mid) }
                        val line = centerItem?.index?.let { lyrics.getOrNull(it) } ?: return@LaunchedEffect
                        if (line.timeMs > 0) onSeekToMs(line.timeMs)
                    }
                }
                LazyColumn(
                    state = listState,
                    // 歌词占满剩余高度；上下各留 40% 视口高，当前行才能停在正中
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(
                        start = 16.wdp,
                        end = 16.wdp,
                        // 让当前行「中心」落在视口正中：行首位置 = 50% - 半个行高
                        // coerceAtLeast(0) 防负数：歌词区极矮时负 padding 会让 Compose 直接抛异常（横屏闪退）
                        top = (viewportH * 0.5f - 16.wdp).coerceAtLeast(0.wdp),
                        bottom = viewportH * 0.5f
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.wdp)
                ) {
                    itemsIndexed(
                        lyrics,
                        // 稳定 key：歌词文本刷新 / 翻译返回时行不会整体重建，
                        // 用「下标:时间轴」——同一份歌词里两行时间相同也不会撞（下标兜底）
                        key = { i, line -> "$i:${line.timeMs}" }
                    ) { i, line ->
                        val on = i == currentIndex
                        // 字号走倍率：当前行 13sp / 相邻与远处 10sp，倍率同时作用于两者。
                        // 计算在 [lyricLineSp] 里（单测覆盖，那里面有括号位置的坑）。
                        // ⚠️ wsp 只接了 Int，所以 lyricLineSp 返回 Int。
                        val sp = lyricLineSp(on, fontScale).wsp
                        // 深色（A）：无衬线、中等字重，现代歌词；浅色（B）：衬线、粗体，唱片内页感
                        val lyricFamily = if (c.titleSerif) FontFamily.Serif else FontFamily.SansSerif
                        val lyricSpacing = if (c.titleSerif) 0.4.sp else 0.sp
                        val fw = if (on) {
                            if (c.titleSerif) FontWeight.Bold else FontWeight.Medium
                        } else FontWeight.Normal
                        val dim = when {
                            on -> c.t1
                            kotlin.math.abs(i - currentIndex) <= 1 -> c.t2
                            else -> c.t3
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            // 当前行直接 t1（近黑）：切行瞬间整行就是最深。
                            // 不再做「扫光」——扫光的本质是整句唱完才全黑，看起来就是高亮延迟。
                            Text(
                                text = line.text,
                                color = dim,
                                fontSize = sp,
                                fontFamily = lyricFamily,
                                letterSpacing = lyricSpacing,
                                fontWeight = fw,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                            // B1 · 当前行强调色下划线：强调色跟着封面走
                            if (on) {
                                Spacer(Modifier.height(4.wdp))
                                Box(
                                    Modifier
                                        .width(18.wdp)
                                        .height(2.wdp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(c.accentLine)
                                )
                            }

                        // 译文：每行只要有就显示，字号比原文小一档、颜色降一档
                        val tr = translations[i]
                        if (!tr.isNullOrBlank()) {
                            Spacer(Modifier.height(3.wdp))
                            Text(
                                text = tr,
                                color = c.t3,
                                fontSize = (sp.value * 0.8f).sp,
                                fontFamily = lyricFamily,
                                letterSpacing = lyricSpacing,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
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
 * 扫光进度的驱动：**只负责把播放位置读在叶子层**，本身不渲染任何东西。
 *
 * 为什么单独拆一个：positionProvider() 每 100ms 变一次，直接读在 LyricsPane 里，
 * 整个歌词列表（上百行）会跟着重组；读在它自己里面，重组范围就只有它一个。
 *
 * 动画用 Animatable 手动驱动，而不是 animateFloatAsState：
 * 换行那一刻上一行已经唱到 1.0、新行进度是 0，共用一个补间会让动画从 1.0 倒回 0 ——
 * 用户看到的是「整条亮着往前消退，再从第一个字重新唱」，非常怪。
 * 换行必须瞬移到新行进度，只有同一行内部才做 400ms 平滑追赶。
 */
@Composable
private fun LyricSweepDriver(
    positionProvider: () -> Long,
    lyrics: List<LyricLine>,
    currentIndex: Int,
    offsetMs: Long
) {
    // 基准必须和 VM 判定 currentIndex 时用的一致：播放位置 + offsetMs
    val curLine = lyrics.getOrNull(currentIndex)
    val nextLine = lyrics.getOrNull(currentIndex + 1)
    val rawProgress = when {
        curLine == null -> 0f
        nextLine == null || nextLine.timeMs <= curLine.timeMs -> 1f
        else -> {
            // 同一帧只取一次，保证算进度时分子分母用的是同一个瞬间
            val pos = positionProvider() + offsetMs
            ((pos - curLine.timeMs).toFloat() /
                (nextLine.timeMs - curLine.timeMs)).coerceIn(0f, 1f)
        }
    }
    val sweep = remember { Animatable(0f) }
    var sweepLine by remember { mutableIntStateOf(-1) }
    LaunchedEffect(currentIndex, rawProgress) {
        if (sweepLine != currentIndex) {
            sweepLine = currentIndex
            sweep.snapTo(rawProgress)
        } else {
            sweep.animateTo(rawProgress, tween(durationMillis = 400, easing = LinearEasing))
        }
    }
}

@Composable
private fun LyricOffsetChip(label: String, onClick: () -> Unit) {
    val c = moyinColors()
    Text(
        text = label,
        color = c.t3,
        fontSize = 10.wsp,
        modifier = Modifier
            .clip(RoundedCornerShape(99.dp))
            .tvFocus(shape = RoundedCornerShape(99.dp))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
            .padding(horizontal = 8.wdp, vertical = 5.wdp)
    )
}

/** 0 → 「歌词对齐」，否则显示 ±N.Ns */
private fun offsetLabel(ms: Long): String =
    if (ms == 0L) "歌词对齐" else String.format("%+.1fs", ms / 1000.0)

@Composable
private fun NavIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    /** v2.4.2：TV 上由播放页指派焦点入口 / 上下出口 */
    requester: androidx.compose.ui.focus.FocusRequester? = null,
    down: androidx.compose.ui.focus.FocusRequester? = null,
    up: androidx.compose.ui.focus.FocusRequester? = null,
    onClick: () -> Unit
) {
    val c = moyinColors()
    // 底色 + 主色图标：播放页背景是封面模糊图，深浅不可控，
    // 纯透明圆 + 次要灰的图标在亮封面上会直接消失。
    Box(
        Modifier
            .size(44.wdp)
            .clip(RoundedCornerShape(99.dp))
            .background(if (c.isDark) Color(0x33FFFFFF) else Color(0x14000000))
            // requester 必须在 clickable 之前：clickable 自己也会建焦点节点，
            // 顺序反了 requester 会绑到外层空节点上，requestFocus 落不到按钮
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .focusProperties {
                if (down != null) this.down = down
                if (up != null) this.up = up
            }
            .tvFocus(shape = RoundedCornerShape(99.dp))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        MoyinIcon(icon = icon, tint = c.t1, size = 18.wdp)
    }
}

@Composable
private fun CtrlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    size: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.Dp,
    emphasized: Boolean = false,
    tint: Color? = null,
    /** v2.4.1：TV 上由播放页指定焦点入口 / 上行出口 */
    requester: androidx.compose.ui.focus.FocusRequester? = null,
    up: androidx.compose.ui.focus.FocusRequester? = null,
    /** v2.4.8：缓冲超过 3 秒才叠加载圈；图标本身不跟着变（否则就是「乱翻」） */
    spinner: Boolean = false,
    onClick: () -> Unit
) {
    val c = moyinColors()
    // 主播放键做成实心强调色：整页只有这一个「实心块」，
    // 眼睛会自动落上去，比加光晕更有效（光晕会被外层玻璃胶囊的 clip 裁掉）。
    val bg = when {
        emphasized -> c.accentFill
        else -> Color.Transparent
    }
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(99.dp))
            .then(
                if (emphasized) Modifier.background(bg)
                else Modifier.background(if (c.isDark) Color(0x0DFFFFFF) else Color(0x09000000))
            )
            .border(
                width = if (emphasized) 0.dp else 1.dp,
                color = if (c.isDark) Color(0x24FFFFFF) else Color(0x1A000000),
                shape = RoundedCornerShape(99.dp)
            )
            // 焦点入口必须在 clickable 之前：clickable 也会建焦点节点，
            // 顺序反了 requester 绑到外层空节点上，requestFocus 就落不到按钮
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .focusProperties { if (up != null) this.up = up }
            .tvFocus()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        MoyinIcon(
            icon = icon,
            tint = when {
                emphasized -> c.onAccent
                tint != null -> tint
                else -> c.t2
            },
            size = iconSize
        )
        // 缓冲超时：图标不动，只在下面/上面叠一个小圈，避免「按钮一直在播放和暂停之间跳」
        if (spinner) {
            CircularProgressIndicator(
                modifier = Modifier.size(size * 0.9f),
                color = if (emphasized) c.onAccent else c.accent,
                strokeWidth = 1.5.dp
            )
        }
    }
}

@Composable
private fun ToolItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    /** v2.4.2：焦点入口；面板关闭后靠它把焦点还回来 */
    requester: androidx.compose.ui.focus.FocusRequester? = null,
    /** 面板打开前调用，记住「是谁开的」 */
    onOpen: () -> Unit = {},
    onClick: () -> Unit
) {
    val c = moyinColors()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(99.dp))
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .tvFocus(shape = RoundedCornerShape(99.dp))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onOpen(); onClick() }
            .padding(12.wdp)
    ) {
        MoyinIcon(icon = icon, tint = c.t3, size = 17.wdp)
        Spacer(Modifier.width(14.wdp))
        Text(label, color = c.t3, fontSize = 11.wsp)
    }
}

/**
 * 可拖动进度条。
 *
 * - 触摸区做到 24dp 高（视觉条只有 4dp），否则手指点不中
 * - 拖动中显示拖动位置，不被后台 100ms 轮询的播放位置覆盖
 * - 松手才 seek，拖动过程不 seek（避免频繁 seek 导致卡顿）
 * - v2.4.1：TV 上它自己是**可聚焦控件**，←/→ 调进度（与顶栏快捷键同频，±5 秒），
 *   ↑/↓ 交还焦点系统。之前它是纯 `Box + pointerInput`，
 *   物理上不是焦点目标，遥控器怎么按都选不中。
 */
@Composable
private fun SeekBar(
    /** 播放进度（0~1）的提供者。读在这一层，100ms 的轮询只会重组这条进度条 */
    progressProvider: () -> Float,
    onSeek: (Float) -> Unit,
    /** TV：进度条持焦时左右键的跳转动作（null = 纯展示，不接管按键） */
    tvSeekBy: ((Long) -> Unit)? = null,
    /** TV：按 ↑ 时把焦点交回去（一般是播放页根节点） */
    up: androidx.compose.ui.focus.FocusRequester? = null
) {
    val c = moyinColors()
    var barWidth by remember { mutableStateOf(1) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    // 拖动中显示拖动位置，不被后台轮询的播放进度覆盖（dragging 非空时不会去读 provider）
    val shown = (dragging ?: progressProvider()).coerceIn(0f, 1f)

    Box(
        Modifier
            .fillMaxWidth()
            .height(24.wdp)
            .onSizeChanged { barWidth = it.width.coerceAtLeast(1) }
            .then(
                if (tvSeekBy == null) Modifier else Modifier
                    .focusProperties { if (up != null) this.up = up }
                    .tvFocus(shape = RoundedCornerShape(8.dp), scale = 1f)
                    // tvFocus 只画描边、不建焦点节点；进度条没有 clickable，
                    // 必须显式 focusable() 它才是一个能被遥控器选中的目标
                    .focusable()
                    .onPreviewKeyEvent { ce ->
                        val e = ce.nativeKeyEvent
                        if (e.action != android.view.KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                        // 进度条自身没有"滚动"语义，左右键此刻唯一的合理含义就是调进度。
                        // 具体的 ±5 秒换算交给调用方（复用 TvSeek 的越界/时长未知判定）。
                        val dir = when (e.keyCode) {
                            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> -1L
                            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> 1L
                            else -> 0L
                        }
                        if (dir == 0L) return@onPreviewKeyEvent false
                        tvSeekBy(dir * TvDimens.SEEK_STEP_MS)
                        true
                    }
            )
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onSeek((offset.x / barWidth).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = (offset.x / barWidth).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        dragging?.let { onSeek(it) }
                        dragging = null
                    },
                    onDragCancel = { dragging = null }
                ) { change, _ ->
                    dragging = (change.position.x / barWidth).coerceIn(0f, 1f)
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        // 视觉条：深色（A）4dp 圆角粗条；浅色（B）1px 细线 + 菱形把手 —— 纸感全靠细线
        val paper = c.titleSerif
        val barH = if (paper) 1.wdp else 4.wdp
        val barShape = if (paper) RoundedCornerShape(0.dp) else RoundedCornerShape(12.wdp)
        Box(
            Modifier
                .fillMaxWidth()
                .height(barH)
                .background(if (c.isDark) Color(0x2EFFFFFF) else Color(0x1F000000))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(shown)
                    .fillMaxHeight()
                    .background(
                        // 选了马卡龙时，浅色纸面也用糖纸色（accentFill 与 accent 不同即代表选了预设）
                        if (c.isDark || c.accentFill != c.accent) c.accentFill else c.lineStrong
                    )
            )
        }
        // 拖动手柄：深色白圆点；浅色砖红菱形
        Box(
            Modifier
                .fillMaxWidth(shown)
                .height(24.wdp),
            contentAlignment = Alignment.CenterEnd
        ) {
            Box(
                Modifier
                    .size(if (dragging != null) 14.wdp else if (paper) 9.wdp else 10.wdp)
                    .graphicsLayer { if (paper) rotationZ = 45f }
                    .background(if (paper) c.accent else Color.White)
            )
        }
    }
}

private fun playModeIcon(mode: PlayMode) = when (mode) {
    PlayMode.ORDER -> IconPlayOrder
    PlayMode.LOOP -> IconPlayLoop
    PlayMode.SINGLE -> IconPlaySingle
    PlayMode.SHUFFLE -> IconPlayShuffle
}

internal fun qualityName(tier: String): String = when (tier) {
    "master" -> "母带"
    "hires" -> "无损"
    "high" -> "极高品"
    else -> "标准"
}

/** 通用底部选项面板（播放模式 / 音质共用） */
/**
 * 音效面板。
 *
 * 为什么放在播放页而不是设置页：调音效是「一边听一边拧」的动作，
 * 藏在设置里等于没有 —— 用户找不到（这条是用户实测反馈的）。
 *
 * 只显示设备真正支持的项：低端机常常只有均衡器，
 * 摆一个点了没反应的开关比不显示更糟。
 */
@Composable
private fun EffectSheet(
    eqEnabled: Boolean,
    eqAvailable: Boolean,
    eqPresets: List<String>,
    eqPreset: Int,
    eqBands: List<Int>,
    eqFreqs: List<Int>,
    eqRange: IntArray,
    eqCustom: Boolean,
    onEqEnabledChange: (Boolean) -> Unit,
    onEqPresetChange: (Int) -> Unit,
    onEqBandChange: (Int, Int) -> Unit,
    /** 用户自建曲线（A3） */
    deviceProfiles: List<com.kmusic.apkhd.data.DeviceEqProfile> = emptyList(),
    deviceProfileActiveId: String? = null,
    hasConfirmedAudioRoute: Boolean = false,
    onSaveDeviceProfile: (String, Boolean) -> Unit = { _, _ -> },
    onApplyDeviceProfile: (String?) -> Unit = {},
    onDeleteDeviceProfile: (String) -> Unit = {},
    onBindDeviceProfile: (String) -> Unit = {},
    deviceProfileAuto: Boolean = false,
    onDeviceProfileAutoChange: (Boolean) -> Unit = {},
    userEqPresets: List<com.kmusic.apkhd.playback.UserEqPresets.Preset> = emptyList(),
    onSaveEqCurve: (String) -> Unit = {},
    onApplyEqCurve: (com.kmusic.apkhd.playback.UserEqPresets.Preset) -> Unit = {},
    onDeleteEqCurve: (String) -> Unit = {},
    onRefreshEq: () -> Unit,
    fxBass: Int, onFxBassChange: (Int) -> Unit,
    fxLoudness: Int, onFxLoudnessChange: (Int) -> Unit,
    fxBassAvailable: Boolean,
    fxLoudnessAvailable: Boolean,
    /** 用户配置里开着几项（0..3） */
    fxWantedCount: Int = 0,
    /** 处理器接进管线后的采样率；0 = 还没起播 */
    fxSampleRate: Int = 0,
    /** 让给 EQ 正增益峰值的前级余量（dB） */
    fxHeadroomDb: Float = 0f,
    audioOffload: Boolean,
    pureAudioMode: Boolean,
    /** 正在播 DSD（音效面板据此提示「均衡器已自动旁路」） */
    dsdPlaying: Boolean = false,
    /** DSD 倍率标签 */
    dsdLabel: String? = null,
    /** 用户是否开启了 DSD 自动旁路 EQ */
    dsdBypassEq: Boolean = true,
    sleepTotalMin: Int,
    sleepRemainingSec: Int,
    onSleepChange: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val c = moyinColors()
    // 面板每次打开都重读一次设备频段：audio session 可能在面板没开的时候才建出来，
    // 只依赖 sessionVersion 会漏掉这种情况，用户看到的就是「只有开关没有滑块」
    LaunchedEffect(Unit) { onRefreshEq() }
    var expanded by remember { mutableStateOf<String?>(null) }
    // 5 段滑块默认收起：这些滑块常驻时，手指落在上面就等于改曲线（面板本身要上下滚）。
    // 注意只折滑块 —— 均衡器/预设/低音/响度这些行始终可见。
    var slidersOpen by remember { mutableStateOf(false) }
    // A3：保存曲线的命名浮层 + 当前生效的用户曲线名（只用于列表高亮）
    var activeCurveName by remember { mutableStateOf<String?>(null) }
    val panelFocus = rememberTvPanelFocus()
    val noInd = { _: MutableInteractionSource -> }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            // 遮罩只负责点击关闭，不参与焦点（否则会跟面板里的真实控件抢）
            .focusProperties { canFocus = false }
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onDismiss() }
    ) {
        // 宽屏横屏：右侧抽屉；窄屏：底部面板
        val drawer = window.isWide && window.widthDp > window.heightDp && window.heightDp >= 380
        Column(
            Modifier
                .align(if (drawer) Alignment.CenterEnd else Alignment.BottomCenter)
                .then(
                    if (drawer) Modifier.width(420.wdp).fillMaxHeight()
                    else Modifier.fillMaxWidth().heightIn(max = 540.wdp)
                )
                .clip(
                    if (drawer) RoundedCornerShape(topStart = 20.wdp, bottomStart = 20.wdp)
                    else RoundedCornerShape(topStart = 20.wdp, topEnd = 20.wdp)
                )
                .background(if (c.isDark) c.s1 else Color(0xFFFFFFFF))
                // 用 pointerInput 吞掉空白区点击，**不要用 clickable**：
                // clickable 自带 focusable 语义，会给面板容器建一个可聚焦节点，
                // 配合下面的 tvPanelGroup 时这个空壳会自己抢到焦点 ——
                // 表现为「面板开了、里面一行高亮都没有、方向键乱跑」。
                .pointerInput(Unit) {
                    detectTapGestures { }
                }
                // v2.4.3：面板整体成为焦点组，首项 ↑ / 末项 ↓ 不再跳出面板
                .tvPanelGroup()
                .sheetBottomPadding(!drawer)
                .verticalScroll(rememberScrollState())
                .padding(top = 16.wdp, bottom = 28.wdp)
        ) {
            // 标题行右侧给一个真正点得到的关闭键。
            // 之前只有「音效」两个字，用户想关面板只能点遮罩（容易误触到下层控件）或按返回键。
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 18.wdp, end = 10.wdp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "音效",
                    color = c.t1,
                    fontSize = 16.wsp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    Modifier
                        .size(40.wdp)
                        .clip(RoundedCornerShape(20.wdp))
                        .focusRequester(panelFocus)
                        .tvFocus(shape = RoundedCornerShape(20.wdp))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { onDismiss() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("关闭", color = c.t3, fontSize = 12.wsp)
                }
            }
            Spacer(Modifier.height(6.wdp))

            // ── 状态说明 ──
            // 这一行回答的必须是用户真正关心的问题：「我听到的效果生效了吗？
            // 没生效是为什么？」—— 不是内部概念。判定逻辑在 FxStatus 里可单测。
            run {
                val requestedDb = fxLoudness / 100f
                // 上下限与 FxChain.inputGainDb() 共用同一对常量 —— 界面显示的增益
                // 必须与处理器实际用的完全一致，但这里保持纯计算（FxChain 的实时值
                // 不是 Compose 快照状态，直接读会拿到过期值）。
                val appliedDb = (requestedDb - fxHeadroomDb)
                    .coerceIn(FxChain.INPUT_GAIN_MIN_DB, FxChain.INPUT_GAIN_MAX_DB)
                val status = FxStatus.resolve(
                    offload = audioOffload,
                    dsdPlaying = dsdPlaying,
                    dsdBypassEq = dsdBypassEq,
                    wanted = fxWantedCount,
                    attached = fxSampleRate > 0,
                    sampleRateHz = fxSampleRate.takeIf { it > 0 },
                    headroomDb = fxHeadroomDb,
                    loudnessAppliedDb = appliedDb,
                    loudnessRequestedDb = requestedDb,
                    pureAudioMode = pureAudioMode
                )
                val (line, tone) = when (status) {
                    is FxStatus.Active -> {
                        val sb = StringBuilder("音效生效中")
                        // 响度开着：说清「设定 9 dB 实际只有 3 dB」—— 这正是 headroom 的代价
                        if (status.loudnessRequestedDb > 0f &&
                            status.loudnessAppliedDb + 0.1f < status.loudnessRequestedDb
                        ) {
                            // 符号统一走 signedDb：曲线要的余量比响度还多时实际值会是负数，
                            // 手写 "+" 会拼出「+-7.8 dB」（实测就是这么出现的）。
                            //
                            // 实际为负时**不能**只说「其余让给 EQ 余量」—— 那听起来像是
                            // 「少加了一点」，而实际是整机被压低。必须把因果关系说反过来说：
                            // 是这条曲线要的余量比响度还大，所以整体被压到负值。
                            val applied = status.loudnessAppliedDb
                            if (applied < -0.05f) {
                                // 余量可能来自两条曲线（EQ 正增益 + 低音搁架），
                                // 不能只写「当前曲线」—— EQ 关着、低音开着时那句话是错的指引。
                                val need = when {
                                    fxBass > 0 && eqEnabled -> "EQ+低音"
                                    fxBass > 0 -> "低音"
                                    else -> "EQ"
                                }
                                // 面板宽度有限（横屏约 330dp），必须压成一行：
                                // 全称「响度设定 +9 dB，但 EQ 曲线与低音要 16.8 dB 余量…」会折成两行，
                                // 把下面的开关挤下去。语义仍完整：谁要的余量、整体实际多少。
                                sb.append(
                                    " · 响度 +${"%.0f".format(status.loudnessRequestedDb)} dB" +
                                        " 被 ${need} 的 ${
                                            "%.1f".format(status.headroomDb)
                                        } dB 余量抵掉，实际 ${signedDb(applied)} dB" +
                                        if (applied <= FxChain.INPUT_GAIN_MIN_DB + 0.05f) {
                                            "（余量已吃满前级下限，请减小提升量）"
                                        } else {
                                            ""
                                        }
                                )
                            } else {
                                sb.append(
                                    " · 响度实际 ${signedDb(applied)} dB" +
                                        "（设定 +${"%.0f".format(status.loudnessRequestedDb)} dB，" +
                                        "其余让给 EQ 余量）"
                                )
                            }
                        } else if (status.headroomDb > 0.05f) {
                            sb.append(" · 为 EQ 让出 ${"%.1f".format(status.headroomDb)} dB 余量")
                        }
                        status.sampleRateHz?.let { sb.append(" · ${it / 1000f} kHz") }
                        sb.toString() to c.t2
                    }
                    is FxStatus.Bypassed -> when (status.reason) {
                        FxStatus.Bypassed.Reason.OFFLOAD ->
                            "已旁路：请求省电播放（实际生效见详情）" to c.t3
                        FxStatus.Bypassed.Reason.PURE_PCM ->
                            "已旁路：纯净PCM（高精度请求，不保证最终输出）" to c.t3
                        FxStatus.Bypassed.Reason.DSD ->
                            "已旁路：DSD 解码中（设置已保留）" to c.t3
                        FxStatus.Bypassed.Reason.ALL_OFF ->
                            "三项全关" to c.t3
                    }
                    FxStatus.Waiting ->
                        "已配置，起播后立刻生效（音效跑在播放链上，不占系统音效槽）" to c.t3
                    FxStatus.Idle -> "全部关闭" to c.t3
                }
                Text(
                    line,
                    color = tone,
                    fontSize = 12.wsp,
                    lineHeight = 18.wsp,
                    modifier = Modifier.padding(horizontal = 18.wdp, vertical = 4.wdp)
                )
            }

            if (audioOffload || pureAudioMode) {
                // offload 时整条音效链都没挂上 —— 与其显示一堆点了没反应的开关，不如说清楚
                Text(
                    "当前模式不使用音效。选择“日常播放”即可使用，已保存的方案会保留。",
                    color = c.t3,
                    fontSize = 12.wsp,
                    lineHeight = 18.wsp,
                    modifier = Modifier.padding(horizontal = 18.wdp, vertical = 8.wdp)
                )
            }

            // DSD 播放中：均衡器被自动旁路，**必须留痕** ——
            // 用户设了 EQ 却发现没声音/没效果，不提示会以为 App 出 bug。
            if (dsdPlaying && dsdBypassEq && eqBands.isNotEmpty()) {
                Text(
                    "当前音频按默认方式播放，音效设置已保留。",
                    color = c.t3,
                    fontSize = 12.wsp,
                    lineHeight = 18.wsp,
                    modifier = Modifier.padding(horizontal = 18.wdp, vertical = 8.wdp)
                )
            }

            // 均衡器同样不做「挂不上就整块消失」：
            // 没频段时留一行说明，让用户知道是资源/设备问题，不是 App 没这个功能。
            // v2.3.5 起频段是内置的，eqBands 不会为空；这段留作防御 ——
            // 真为空说明初始化没跑完，说「加载中」比说「不支持」准确。
            if (eqBands.isEmpty()) {
                EffectRow("均衡器", "加载中", arrow = false) { }
            }
            if (eqBands.isNotEmpty()) {
                val eqBypassed = dsdPlaying && dsdBypassEq
                val presetLabel = eqPresets.getOrNull(eqPreset) ?: eqPresets.firstOrNull()
                val presetName = if (eqCustom) "自定义" else presetLabel
                // 音效行常显、点整行切开关 —— 和低音/响度保持一致。
                // 折叠只针对下面那排滑块：滑块常驻时手指落在上面很容易误拖。
                EffectRow(
                    "均衡器",
                    when {
                        eqBypassed -> "已旁路"
                        eqEnabled -> "开"
                        else -> "关"
                    },
                    arrow = false
                ) { onEqEnabledChange(!eqEnabled) }

                if (eqPresets.isNotEmpty()) {
                    EffectRow(
                        "预设",
                        presetName ?: "",
                        arrow = true,
                        arrowExpanded = expanded == "eq"
                    ) { expanded = if (expanded == "eq") null else "eq" }
                    if (expanded == "eq") {
                        // 分组展示（风格 / 场景 / 设备）：下标仍是全局唯一，
                        // 分组只影响排版 —— 见 EqPresets 的顺序锁死约束。
                        EqPresetGroupedChips(
                            selected = eqPreset,
                            customActive = eqCustom,
                            onPick = { idx ->
                                onEqPresetChange(idx)
                                expanded = null   // 选完就把 chip 行收起来，别一直占着
                            }
                        )
                    }
                }

                com.kmusic.apkhd.ui.components.SoundSchemeSection(
                    profiles = deviceProfiles, activeId = deviceProfileActiveId,
                    hasConfirmedRoute = hasConfirmedAudioRoute,
                    onSave = onSaveDeviceProfile, onApply = onApplyDeviceProfile,
                    onDelete = onDeleteDeviceProfile, onBind = onBindDeviceProfile,
                    deviceAutoEnabled = deviceProfileAuto, onDeviceAutoChange = onDeviceProfileAutoChange
                )
                if (userEqPresets.isNotEmpty()) {
                    UserCurveList(
                        presets = userEqPresets,
                        activeName = if (eqCustom) activeCurveName else null,
                        onApply = { onApplyEqCurve(it) },
                        onDelete = { name -> onDeleteEqCurve(name) }
                    )
                }

                // 只有这里那块是可折叠的：默认收起，展开后才出现。
                // v2.3.4 起 5 根竖条换成一条可拖曲线（EqCurve），形态只剩一种，
                // 不再「曲线 + 滑块」并存 —— 同一件事两套交互只会更难懂。
                EffectRow(
                    "频段调整",
                    if (slidersOpen) "收起" else "展开",
                    arrow = true,
                    arrowExpanded = slidersOpen,
                    subtitle = buildString {
                        append(if (eqCustom) "自定义" else presetLabel ?: "原声")
                        append(" · ${eqBands.size} 段")
                        if (!eqEnabled) append(" · 均衡器关闭中")
                    }
                ) { slidersOpen = !slidersOpen }
                if (slidersOpen) {
                    EqCurve(
                        bands = eqBands,
                        freqs = eqFreqs,
                        range = eqRange,
                        onBandChange = onEqBandChange
                    )
                    Text(
                        "拖动圆点调该频段，双击复位 0 dB。空白处上下滑是滚面板。",
                        color = c.t3,
                        fontSize = 11.wsp,
                        lineHeight = 15.wsp,
                        modifier = Modifier.padding(horizontal = 18.wdp, vertical = 2.wdp)
                    )
                }
            }

            // 增强项一律显示：不可用时灰掉并说明原因。
            // 以前是「不支持就整行不渲染」，结果同一台机器上出现「音效项莫名少两条」，
            // 用户会以为功能被删了 —— 功能缺失和功能不可用必须在界面上分得清。
            FxRow(
                title = "低音增强",
                value = fxStrengthLabel(fxBass),
                available = !audioOffload && !pureAudioMode,
                starved = false,
                expanded = expanded == "bass",
                onClick = { expanded = if (expanded == "bass") null else "bass" }
            ) {
                ChipRow(FX_STRENGTH_STEPS, fxBass.toString()) { onFxBassChange(it.toInt()) }
            }
            FxRow(
                title = "响度增强",
                value = if (fxLoudness <= 0) "关" else "+${fxLoudness / 100} dB",
                available = !audioOffload && !pureAudioMode,
                starved = false,
                expanded = expanded == "loud",
                onClick = { expanded = if (expanded == "loud") null else "loud" }
            ) {
                ChipRow(FX_LOUD_STEPS, fxLoudness.toString()) { onFxLoudnessChange(it.toInt()) }
            }

            // 池位占用那行已经删掉了（v2.3.5）：音效跑在播放链上，
            // 无论开几项都**不占系统音效槽**。继续写「占用 N 项」反而会让人以为
            // 还是老机制、还得操心池位。这里只留一句事实。
            if (!audioOffload && !pureAudioMode) {
                Text(
                    "均衡器 / 低音 / 响度都跑在播放链上，不占系统音效槽 —— " +
                        "开几项都不会影响别的 App，也不会有「资源被占满」。",
                    color = c.t3,
                    fontSize = 11.wsp,
                    lineHeight = 17.wsp,
                    modifier = Modifier.padding(horizontal = 18.wdp, vertical = 6.wdp)
                )
            }

            Spacer(Modifier.height(4.wdp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.wdp)
                    .height(1.dp)
                    .background(if (c.isDark) Color(0x14FFFFFF) else Color(0x0A000000))
            )
            Spacer(Modifier.height(4.wdp))

            EffectRow(
                "睡眠定时",
                if (sleepRemainingSec > 0) {
                    "还剩 %d:%02d".format(sleepRemainingSec / 60, sleepRemainingSec % 60)
                } else "关"
            ) { expanded = if (expanded == "sleep") null else "sleep" }
            if (expanded == "sleep") {
                ChipRow(SLEEP_STEPS, sleepTotalMin.toString()) { onSleepChange(it.toInt()) }
            }
        }
        // A3：命名浮层单独一层，盖在抽屉上（抽屉是 verticalScroll，塞在里面的输入框
        // 会被滚走，键盘弹出时也不好看）

    }
}

/**
 * 带符号保留一位小数：`+3.1` / `-7.8` / `0.0`。
 *
 * 不能写成 `"+${"%.1f".format(v)}"` —— 值为负时会拼出「+-7.8」，
 * 这在响度被 EQ 余量吃成负数时是常态，不是边界。
 */
private fun signedDb(v: Float): String {
    val rounded = String.format(java.util.Locale.US, "%.1f", v)
    return if (v > 0.05f) "+$rounded" else rounded
}

@Composable
private fun EffectRow(
    title: String,
    value: String,
    arrow: Boolean = true,
    /** 标题下面的一行小字说明（例如「摇滚 · 5 段」）；null 时不占高度 */
    subtitle: String? = null,
    /** 非空时箭头随展开状态旋转 90°（收起 → 展开），让「这一行能点开」看得出来 */
    arrowExpanded: Boolean? = null,
    onClick: () -> Unit
) {
    val c = moyinColors()
    val arrowAngle by animateFloatAsState(
        targetValue = if (arrowExpanded == true) 90f else 0f,
        animationSpec = tween(160),
        label = "effectRowArrow"
    )
    Row(
        Modifier
            .fillMaxWidth()
            .tvFocus(fullWidth = true)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
            .padding(horizontal = 18.wdp, vertical = 13.wdp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.t1, fontSize = 14.wsp)
            if (subtitle != null) {
                Text(
                    subtitle,
                    color = c.t3,
                    fontSize = 11.wsp,
                    lineHeight = 15.wsp,
                    modifier = Modifier.padding(top = 2.wdp)
                )
            }
        }
        Text(value, color = c.t3, fontSize = 13.wsp)
        if (arrow) {
            Spacer(Modifier.width(4.wdp))
            MoyinIcon(
                IconChevronRight,
                tint = c.t3,
                size = 13.wdp,
                modifier = if (arrowExpanded == null) Modifier
                else Modifier.graphicsLayer { rotationZ = arrowAngle }
            )
        }
    }
}

/**
 * 带「不可用」态的增强项行。
 *
 * 和 [EffectRow] 的区别：不可用时**照样占一行**，文字变灰、值写「不可用」、点了不展开。
 * 这样用户永远能看到「这台机器有哪些音效项」，不会误以为功能被删。
 * starved=true 时额外标「资源占用」，因为这种是能救回来的（见面板底部的重挂入口）。
 */
@Composable
private fun FxRow(
    title: String,
    value: String,
    available: Boolean,
    starved: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val c = moyinColors()
    val dim = !available
    Row(
        Modifier
            .fillMaxWidth()
            .tvFocus(fullWidth = true)
            .clickable(
                enabled = available,
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
            .padding(horizontal = 18.wdp, vertical = 13.wdp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            color = if (dim) c.t3.copy(alpha = 0.55f) else c.t1,
            fontSize = 14.wsp,
            modifier = Modifier.weight(1f)
        )
        Text(
            when {
                !available && starved -> "资源占用"
                // 从没建成过 = 这台机器的 ROM 没给这个 effect 的 per-session 实现，
                // 说「不可用」太含糊，用户会一直以为是临时故障、反复点。
                !available -> "本机不支持"
                else -> value
            },
            color = if (dim) c.t3.copy(alpha = 0.55f) else c.t3,
            fontSize = 13.wsp
        )
        if (available) {
            Spacer(Modifier.width(4.wdp))
            MoyinIcon(IconChevronRight, tint = c.t3, size = 13.wdp)
        }
    }
    if (available && expanded) content()
}

@Composable
private fun ChipRow(
    options: List<Pair<String, String>>,
    selected: String,
    onPick: (String) -> Unit
) {
    val c = moyinColors()
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 18.wdp, vertical = 2.wdp),
        horizontalArrangement = Arrangement.spacedBy(8.wdp)
    ) {
        options.forEach { (key, label) ->
            val on = key == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(99.dp))
                    .background(
                        if (on) c.accent
                        else if (c.isDark) Color(0x14FFFFFF) else Color(0x0A000000)
                    )
                    .tvFocus()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { onPick(key) }
                    .padding(horizontal = 13.wdp, vertical = 7.wdp)
            ) {
                Text(label, color = if (on) Color.White else c.t2, fontSize = 12.wsp)
            }
        }
    }
}

private val FX_STRENGTH_STEPS = listOf(
    "0" to "关", "250" to "弱", "500" to "中", "750" to "强", "1000" to "极强"
)
private val FX_LOUD_STEPS = listOf(
    "0" to "关", "300" to "+3 dB", "600" to "+6 dB", "900" to "+9 dB", "1200" to "+12 dB"
)
private val SLEEP_STEPS = listOf(
    "0" to "关", "15" to "15 分钟", "30" to "30 分钟",
    "45" to "45 分钟", "60" to "1 小时", "90" to "1.5 小时"
)

private fun fxStrengthLabel(v: Int) = when {
    v <= 0 -> "关"
    v < 375 -> "弱"
    v < 625 -> "中"
    v < 875 -> "强"
    else -> "极强"
}

@Composable
private fun OptionSheet(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val c = moyinColors()
    val panelFocus = rememberTvPanelFocus()
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            // 遮罩只负责点击关闭，不参与焦点（否则会跟面板里的真实控件抢）
            .focusProperties { canFocus = false }
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
                // 用 pointerInput 吞掉空白区点击，**不要用 clickable**：
                // clickable 自带 focusable 语义，会给面板容器建一个可聚焦节点，
                // 配合下面的 tvPanelGroup 时这个空壳会自己抢到焦点 ——
                // 表现为「面板开了、里面一行高亮都没有、方向键乱跑」。
                .pointerInput(Unit) {
                    detectTapGestures { }
                }
                // v2.4.3：面板整体成为焦点组，首项 ↑ / 末项 ↓ 不再跳出面板
                .tvPanelGroup()
                .sheetBottomPadding()
                .padding(top = 14.wdp, bottom = 14.wdp)
        ) {
            Text(
                title,
                color = c.t1,
                fontSize = 14.wsp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 18.wdp)
            )
            Spacer(Modifier.height(8.wdp))
            options.forEachIndexed { oi, (key, label) ->
                val on = key == selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(if (oi == 0) Modifier.focusRequester(panelFocus) else Modifier)
                        .tvFocus()
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { onPick(key) }
                        .padding(horizontal = 18.wdp, vertical = 12.wdp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        label,
                        color = if (on) c.accent else c.t1,
                        fontSize = 14.wsp,
                        fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                        modifier = Modifier.weight(1f)
                    )
                    if (on) MoyinIcon(icon = IconCheck, tint = c.accent, size = 16.wdp)
                }
            }
        }
    }
}

/** 更多操作：下载 / 加入播放队列 */
@Composable
private fun MoreSheet(
    onDownload: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    /** 当前音质短名（母带/无损/极高品/标准）；为空则不显示音质入口 */
    qualityLabel: String = "",
    onQualityClick: () -> Unit = {},
    // ── 歌词调节：从歌词区搬过来的（歌词区只负责唱）──
    lyricOffsetMs: Long = 0L,
    onNudgeOffset: (Long) -> Unit = {},
    onResetOffset: () -> Unit = {},
    fontScale: Float = 1f,
    onNudgeFontScale: (Float) -> Unit = {},
    onResetFontScale: () -> Unit = {},
    onReportWrongLyric: (() -> Unit)? = null,
    aiConfigured: Boolean = false,
    translating: Boolean = false,
    hasTranslation: Boolean = false,
    onTranslate: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val c = moyinColors()
    // B5：精确输入浮层（"offset" / "font"），null = 不显示
    var numericEdit by remember { mutableStateOf<String?>(null) }
    val panelFocus = rememberTvPanelFocus()
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            // 遮罩只负责点击关闭，不参与焦点（否则会跟面板里的真实控件抢）
            .focusProperties { canFocus = false }
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onDismiss() }
    ) {
        // 宽屏横屏：右侧抽屉；窄屏：底部面板
        val drawer = window.isWide && window.widthDp > window.heightDp && window.heightDp >= 380
        Column(
            Modifier
                .align(if (drawer) Alignment.CenterEnd else Alignment.BottomCenter)
                .then(
                    if (drawer) Modifier.width(400.wdp).fillMaxHeight()
                    else Modifier.fillMaxWidth()
                )
                .clip(
                    if (drawer) RoundedCornerShape(topStart = 18.wdp, bottomStart = 18.wdp)
                    else RoundedCornerShape(topStart = 18.wdp, topEnd = 18.wdp)
                )
                .background(if (c.isDark) c.s1 else Color(0xFFFFFFFF))
                // 用 pointerInput 吞掉空白区点击，**不要用 clickable**：
                // clickable 自带 focusable 语义，会给面板容器建一个可聚焦节点，
                // 配合下面的 tvPanelGroup 时这个空壳会自己抢到焦点 ——
                // 表现为「面板开了、里面一行高亮都没有、方向键乱跑」。
                .pointerInput(Unit) {
                    detectTapGestures { }
                }
                // v2.4.3：面板整体成为焦点组，首项 ↑ / 末项 ↓ 不再跳出面板
                .tvPanelGroup()
                .sheetBottomPadding(!drawer)
                .padding(top = 14.wdp, bottom = 14.wdp)
        ) {
            // 首项定焦：有音质行就落在音质，没有就落在下载。
            // 同一个 FocusRequester **只能挂一个节点**（挂两处会抛
            // "FocusRequester is already in use"），所以按条件二选一。
            // focus 参数必须显式命名 —— onClick 在它后面，位置传参会错位。
            val headOnQuality = qualityLabel.isNotEmpty()
            if (headOnQuality) {
                MoreRow(IconEqualizer, "音质：$qualityLabel", focus = panelFocus, onClick = onQualityClick)
            }
            MoreRow(
                IconDownload, "下载",
                focus = if (headOnQuality) null else panelFocus,
                onClick = onDownload
            )
            MoreRow(IconPlus, "加入播放队列", onClick = onAddToQueue)
            MoreRow(IconPlaylist, "加入歌单", onClick = onAddToPlaylist)

            // ── 歌词 ────────────────────────────────────────────
            // 放这里而不是歌词区：歌词区被两行 chip 压掉 ~110dp，横屏只剩几行词。
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.wdp)
                    .height(1.wdp)
                    .background(c.line.copy(alpha = 0.7f))
            )
            Text(
                "歌词",
                color = c.t3,
                fontSize = 11.wsp,
                modifier = Modifier.padding(start = 18.wdp, top = 10.wdp, bottom = 2.wdp)
            )
            MoreStepper(
                label = "时间轴",
                valueLabel = offsetLabel(lyricOffsetMs),
                valueActive = lyricOffsetMs != LYRIC_OFFSET_DEFAULT_MS,
                minusLabel = "延后",
                plusLabel = "提前",
                hint = "整首歌的歌词整体前后移动；点数值可直接输入秒数",
                onMinus = { onNudgeOffset(-500) },
                onPlus = { onNudgeOffset(500) },
                onReset = onResetOffset,
                onEdit = { numericEdit = "offset" }
            )
            MoreStepper(
                label = "字号",
                valueLabel = "${(fontScale * 100).toInt()}%",
                valueActive = fontScale != 1f,
                minusLabel = "小",
                plusLabel = "大",
                hint = "只影响歌词，不影响界面其它文字；点数值可直接输入百分比",
                onMinus = { onNudgeFontScale(-0.1f) },
                onPlus = { onNudgeFontScale(0.1f) },
                onReset = onResetFontScale,
                onEdit = { numericEdit = "font" }
            )
            if (aiConfigured) {
                MoreRow(
                    IconInfo,
                    when {
                        translating -> "歌词翻译中…"
                        hasTranslation -> "已翻译（重新译）"
                        else -> "AI 翻译歌词"
                    }
                ) { if (!translating) onTranslate() }
            }
            if (onReportWrongLyric != null) {
                MoreRow(IconInfo, "歌词不对？") { onReportWrongLyric() }
            }
        }

        // B5：精确输入浮层盖在「更多」面板之上
        when (numericEdit) {
            "offset" -> NumberInputDialog(
                title = "歌词时间轴",
                initial = "%.1f".format(lyricOffsetMs / 1000f),
                suffix = "秒",
                hint = "正数 = 歌词提前，负数 = 延后；直接输入想要的秒数",
                onDismiss = { numericEdit = null },
                onConfirm = { raw ->
                    val ms = (raw.toDouble() * 1000).toLong()
                        .coerceIn(-10_000L, 10_000L)
                    onNudgeOffset(ms - lyricOffsetMs)
                    numericEdit = null
                },
                onReset = { onResetOffset(); numericEdit = null }
            )
            "font" -> NumberInputDialog(
                title = "歌词字号",
                initial = "${(fontScale * 100).toInt()}",
                suffix = "%",
                hint = "范围 80% ~ 180%；100% 是标准大小",
                onDismiss = { numericEdit = null },
                onConfirm = { raw ->
                    val pct = raw.toDouble().coerceIn(80.0, 180.0)
                    onNudgeFontScale((pct / 100.0).toFloat() - fontScale)
                    numericEdit = null
                },
                onReset = { onResetFontScale(); numericEdit = null }
            )
        }
    }
}

/**
 * 「更多」里的 ± 调节行：左标签、右数值、两端各一个 chip。
 *
 * 数值本身可点 → 打开精确输入（B5）。以前点它是直接复位：想改小一点点
 * 只能连点，点歪一下还会被清回默认值。现在复位放进输入浮层里，
 * 两个动作不再抢同一个热区。
 */
@Composable
private fun MoreStepper(
    label: String,
    valueLabel: String,
    valueActive: Boolean,
    minusLabel: String,
    plusLabel: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    onReset: () -> Unit,
    /** 打开精确输入；null 时数值不可点（保持老行为） */
    onEdit: (() -> Unit)? = null,
    /** 行下方的一句说明，解释这一行到底影响什么 */
    hint: String? = null
) {
    val c = moyinColors()
    Column(Modifier.fillMaxWidth()) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.wdp, vertical = 7.wdp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = c.t1, fontSize = 14.wsp)
        Spacer(Modifier.weight(1f))
        MoreChip(minusLabel, onMinus)
        // 数值固定宽度：避免 "+0.5s" 和 "100%" 宽度不同把两边 chip 挤来挤去。
        // 现在点它是**精确输入**（复位在浮层里），不再是「点一下就清空」。
        Box(
            Modifier
                .width(58.wdp)
                .clip(RoundedCornerShape(99.dp))
                .tvFocus(shape = RoundedCornerShape(99.dp))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { if (onEdit != null) onEdit() }
                .padding(vertical = 6.wdp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                valueLabel,
                color = if (valueActive) c.accent else c.t3,
                fontSize = 12.wsp,
                fontWeight = if (valueActive) FontWeight.Medium else FontWeight.Normal
            )
        }
        MoreChip(plusLabel, onPlus)
    }
    if (hint != null) {
        Text(
            hint,
            color = c.t3,
            fontSize = 11.wsp,
            lineHeight = 15.wsp,
            modifier = Modifier.padding(start = 18.wdp, end = 18.wdp, bottom = 6.wdp)
        )
    }
    }
}

/** 「更多」里的小胶囊按钮：有底、有间距，手指点得准 */
@Composable
private fun MoreChip(label: String, onClick: () -> Unit) {
    val c = moyinColors()
    Text(
        text = label,
        color = c.t1,
        fontSize = 11.wsp,
        modifier = Modifier
            .padding(horizontal = 3.wdp)
            .clip(RoundedCornerShape(99.wdp))
            .background(if (c.isDark) Color(0x1FFFFFFF) else Color(0x0F000000))
            .tvFocus(shape = RoundedCornerShape(99.wdp))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
            .padding(horizontal = 12.wdp, vertical = 6.wdp)
    )
}

@Composable
private fun MoreRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    // focus 必须排在 onClick 之前：onClick 是尾随 lambda 的落点，
    // 放最后会把这个函数类型参数挤掉，调用处直接编译失败
    focus: androidx.compose.ui.focus.FocusRequester? = null,
    onClick: () -> Unit
) {
    val c = moyinColors()
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .tvFocus()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
            .padding(horizontal = 18.wdp, vertical = 12.wdp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MoyinIcon(icon = icon, tint = c.t2, size = 17.wdp)
        Spacer(Modifier.width(12.wdp))
        Text(label, color = c.t1, fontSize = 14.wsp)
    }
}

/**
 * 音质文案 —— 只有真母带才显示 24bit/192kHz。
 * 没有音质信息时返回空串（UI 不显示标签），避免所有歌挂同一个标签。
 */
internal fun qualityText(track: Track): String = when {
    track.isMaster -> "24bit / 192kHz"
    track.qualityLabel.isNotEmpty() -> track.qualityLabel
    else -> ""
}

internal fun formatMs(ms: Long): String {
    val total = (ms / 1000).toInt()
    return "%d:%02d".format(total / 60, total % 60)
}

/**
 * 已播放时间。位置读取单独关在这个叶子 Composable 里：
 * positionProvider() 每 100ms 变一次，读在 PlayerScreen 顶层会把整页（封面/歌词/按钮）拖去重组。
 */
@Composable
private fun ElapsedTimeText(
    positionProvider: () -> Long,
    color: Color
) {
    Text(formatMs(positionProvider()), color = color, fontSize = 10.wsp)
}

/**
 * 曲目详情：`导赏` / `音频信息` 两段。
 *
 * ## 为什么分成两段
 *
 * 原来技术参数在上、导赏在下同屏滚动：10 行参数占掉 260dp，抽屉上限 580dp，
 * 留给正文的不到 300dp —— 读一篇 400 字要全程滚。而「这首是什么格式」
 * 与「这首好在哪里」本来就是两件事，上下堆在一起等于每次看导赏都先翻一遍设备参数。
 *
 * 现在默认停在导赏，参数挪到第二段，两个都还是同一个入口（「详情」），不多占工具条。
 *
 * ## 导赏的排版
 *
 * 正文由 [GuideMarkup] 解析成标题/段落/引用/列表四类块，分别给不同字号与间距。
 * 解析是**容错**的：模型不守结构约定就退化成普通段落，绝不会整篇消失。
 * 模型偶尔吐 Markdown 标记（`###`、`---`、`**`）也因此不会漏到屏幕上。
 */
@Composable
private fun InfoSheet(
    track: Track?,
    info: AudioInfo?,
    loudnessGainDb: Float,
    probeResult: SpectrumProbe.Result?,
    probeRunning: Boolean,
    probeError: String?,
    onProbe: () -> Unit,
    canProbe: Boolean,
    aiConfigured: Boolean,
    aiText: String,
    aiLoading: Boolean,
    aiError: String?,
    /** 这篇是否写完了（false = 用户中途停止，只有半篇） */
    aiComplete: Boolean,
    onRequestAi: () -> Unit,
    onCancelAi: () -> Unit,
    onOpenAiSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    val c = moyinColors()
    val clipboard = LocalClipboardManager.current

    // 0 = 导赏，1 = 音频信息。默认导赏：用户点「详情」十次有九次是想看内容。
    var tab by remember { mutableStateOf(0) }
    val panelFocus = rememberTvPanelFocus()

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            // 遮罩只负责点击关闭，不参与焦点（否则会跟面板里的真实控件抢）
            .focusProperties { canFocus = false }
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onDismiss() }
    ) {
        // 宽屏横屏：右侧抽屉；窄屏：底部面板
        val drawer = window.isWide && window.widthDp > window.heightDp && window.heightDp >= 380
        // 抽屉比原来高：导赏是长文，580dp 在竖屏上只够看十几行
        val sheetMax = if (drawer) 0 else 640
        val scroll = rememberScrollState()

        Column(
            Modifier
                .align(if (drawer) Alignment.CenterEnd else Alignment.BottomCenter)
                .then(
                    if (drawer) Modifier.width(440.wdp).fillMaxHeight()
                    else Modifier.fillMaxWidth().heightIn(max = sheetMax.wdp)
                )
                .clip(
                    if (drawer) MoyinShape.drawer
                    else MoyinShape.sheetTop
                )
                .background(if (c.isDark) c.s1 else Color(0xFFFFFFFF))
                // 用 pointerInput 吞掉空白区点击，**不要用 clickable**：
                // clickable 自带 focusable 语义，会给面板容器建一个可聚焦节点，
                // 配合下面的 tvPanelGroup 时这个空壳会自己抢到焦点 ——
                // 表现为「面板开了、里面一行高亮都没有、方向键乱跑」。
                .pointerInput(Unit) {
                    detectTapGestures { }
                }
                // v2.4.3：面板整体成为焦点组，首项 ↑ / 末项 ↓ 不再跳出面板
                .tvPanelGroup()
                .sheetBottomPadding(!drawer)
        ) {
            // ── 顶部：标题 + 分段 ──
            Column(Modifier.padding(start = 18.wdp, end = 12.wdp, top = 14.wdp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "详情",
                            color = c.t1,
                            fontSize = 16.wsp,
                            fontWeight = FontWeight.Medium
                        )
                        if (track != null) {
                            Text(
                                track.name + if (track.artist.isNotBlank()) " · ${track.artist}" else "",
                                color = c.t3,
                                fontSize = 11.wsp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.wdp)
                            )
                        }
                    }
                    // 关闭按钮给足 40dp 命中区 —— 原来只有文字本身高度，实测点不到
                    Box(
                        Modifier
                            .size(40.wdp)
                            .clip(RoundedCornerShape(20.wdp))
                            .focusRequester(panelFocus)
                            .tvFocus(shape = RoundedCornerShape(20.wdp))
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { onDismiss() },
                        contentAlignment = Alignment.Center
                    ) {
                        MoyinIcon(IconChevronRight, tint = c.t3, size = 16.wdp)
                    }
                }
                Spacer(Modifier.height(12.wdp))

                // 分段控件：不用 Material TabRow —— 它的指示器颜色和高度很难跟上主题
                SegmentBar(
                    labels = listOf("导赏", "音频信息"),
                    selected = tab,
                    onSelect = { tab = it }
                )
                Spacer(Modifier.height(4.wdp))
            }

            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(scroll)
                    .padding(bottom = 26.wdp)
            ) {
                if (tab == 0) {
                    GuideSection(
                        track = track,
                        configured = aiConfigured,
                        text = aiText,
                        loading = aiLoading,
                        error = aiError,
                        complete = aiComplete,
                        onRequest = onRequestAi,
                        onCancel = onCancelAi,
                        onOpenSettings = onOpenAiSettings,
                        onCopy = { clipboard.setText(AnnotatedString(aiText)) }
                    )
                } else {
                    AudioInfoSection(
                        info = info,
                        loudnessGainDb = loudnessGainDb,
                        probeResult = probeResult,
                        probeRunning = probeRunning,
                        probeError = probeError,
                        onProbe = onProbe,
                        canProbe = canProbe
                    )
                }
            }
        }
    }
}

/**
 * 两段式切换条。
 *
 * 自绘而不是用 Material 的 TabRow：这里的配色全部来自 [MoyinColors]，
 * 浅色主题是「米白底 + 深色字 + 细下划线」，深色是「胶囊高亮」，
 * 用 TabRow 的默认指示器两套都对不上。
 */
@Composable
private fun SegmentBar(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val c = moyinColors()
    Row(horizontalArrangement = Arrangement.spacedBy(6.wdp)) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(99.dp))
                    .background(if (on) c.accentDim else Color.Transparent)
                    .tvFocus(shape = RoundedCornerShape(99.dp))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { onSelect(i) }
                    .padding(horizontal = 14.wdp, vertical = 7.wdp)
            ) {
                Text(
                    label,
                    color = if (on) c.accent else c.t3,
                    fontSize = 13.wsp,
                    fontWeight = if (on) FontWeight.Medium else FontWeight.Normal
                )
            }
        }
    }
}

/** 导赏段：空态 / 生成中 / 正文 / 出错 四种状态 */
@Composable
private fun GuideSection(
    track: Track?,
    configured: Boolean,
    text: String,
    loading: Boolean,
    error: String?,
    complete: Boolean,
    onRequest: () -> Unit,
    onCancel: () -> Unit,
    onOpenSettings: () -> Unit,
    onCopy: () -> Unit
) {
    val c = moyinColors()

    when {
        !configured -> {
            Text(
                "填入自己的 API Key 后才能生成。支持任何 OpenAI 兼容接口（默认 DeepSeek，也可以填 OpenAI / 通义 / 智谱）。",
                color = c.t3,
                fontSize = 12.wsp,
                lineHeight = 18.wsp,
                modifier = Modifier.padding(horizontal = 18.wdp, vertical = 6.wdp)
            )
            Spacer(Modifier.height(8.wdp))
            InfoAction("去设置填 Key", onOpenSettings)
        }

        text.isNotBlank() -> {
            GuideBody(text)
            Spacer(Modifier.height(10.wdp))
            Row(
                Modifier.padding(horizontal = 18.wdp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(13.wdp),
                        strokeWidth = 1.5.dp,
                        color = c.accent
                    )
                    Spacer(Modifier.width(8.wdp))
                    Text("正在生成…", color = c.t3, fontSize = 11.wsp)
                    Spacer(Modifier.weight(1f))
                    GuideTextAction("停止", onCancel)
                } else {
                    // 半篇和完篇要说清楚：用户点了「停止」以后不该以为自己拿到的是全篇
                    Text(
                        if (complete) "AI 生成 · 仅供鉴赏参考" else "已停止 · 内容未写完",
                        color = c.t3,
                        fontSize = 11.wsp
                    )
                    Spacer(Modifier.weight(1f))
                    GuideTextAction("复制", onCopy)
                    Spacer(Modifier.width(14.wdp))
                    GuideTextAction(if (complete) "重新生成" else "继续生成", onRequest)
                }
            }
        }

        loading -> {
            Row(
                Modifier.padding(horizontal = 18.wdp, vertical = 10.wdp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(15.wdp),
                    strokeWidth = 1.6.dp,
                    color = c.accent
                )
                Spacer(Modifier.width(10.wdp))
                Text("正在读歌词、组织文字…", color = c.t3, fontSize = 13.wsp)
                Spacer(Modifier.weight(1f))
                GuideTextAction("停止", onCancel)
            }
        }

        error != null -> {
            Text(
                error,
                color = c.t3,
                fontSize = 12.wsp,
                lineHeight = 18.wsp,
                modifier = Modifier.padding(horizontal = 18.wdp, vertical = 6.wdp)
            )
            Spacer(Modifier.height(8.wdp))
            InfoAction("重试", onRequest)
        }

        else -> {
            if (track != null) {
                Text(
                    "把这首歌的创作背景、词作意象与编曲细节讲清楚，约 300 字。",
                    color = c.t3,
                    fontSize = 12.wsp,
                    lineHeight = 18.wsp,
                    modifier = Modifier.padding(horizontal = 18.wdp, vertical = 6.wdp)
                )
                Spacer(Modifier.height(8.wdp))
            }
            InfoAction("生成导赏", onRequest)
        }
    }
}

/** 底部一行的轻量文字按钮：不抢视觉，但命中区撑到 36dp 高 */
@Composable
private fun GuideTextAction(label: String, onClick: () -> Unit) {
    val c = moyinColors()
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .tvFocus(shape = RoundedCornerShape(8.dp))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
            .padding(horizontal = 6.wdp, vertical = 8.wdp)
    ) {
        Text(label, color = c.accent, fontSize = 12.wsp, fontWeight = FontWeight.Medium)
    }
}

/**
 * 把 AI 正文按 [GuideMarkup] 的块渲染出来。
 *
 * 流式场景下每来一个 delta 就重新 parse 一次 —— 300~450 字的解析是线性的，
 * 每帧重算的开销远小于一次网络往返，没必要做增量。
 */
@Composable
private fun GuideBody(raw: String) {
    val c = moyinColors()
    val blocks = remember(raw) { GuideMarkup.parse(raw) }

    // 底部 16dp：正文最后一行不该贴着「复制 / 重新生成」那一行状态栏
    Column(Modifier.padding(start = 18.wdp, end = 18.wdp, top = 4.wdp, bottom = 16.wdp)) {
        blocks.forEachIndexed { i, block ->
            when (block) {
                is GuideMarkup.Block.Lead -> {
                    // 一句话主旨：比正文大一号，用主题强调色，作为整篇的入口
                    Text(
                        block.text,
                        color = c.t1,
                        fontSize = 19.wsp,
                        lineHeight = 27.wsp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = if (c.titleSerif) FontFamily.Serif else FontFamily.SansSerif,
                        modifier = Modifier.padding(top = if (i == 0) 6.wdp else 0.dp, bottom = 10.wdp)
                    )
                }

                is GuideMarkup.Block.Section -> {
                    // 复用 SectionHeader：深色套是小号大写灰字，浅色套是衬线粗体。
                    // SectionHeader 自带 20dp 内边距，小标题上再补一点，跟正文拉开呼吸感
                    SectionHeader(
                        text = block.title,
                        modifier = Modifier.padding(top = if (i == 0) 2.wdp else 10.wdp)
                    )
                }

                is GuideMarkup.Block.Para -> {
                    Text(
                        block.text,
                        color = c.t1,
                        fontSize = 14.wsp,
                        lineHeight = 24.wsp,
                        modifier = Modifier.padding(bottom = 12.wdp)
                    )
                }

                is GuideMarkup.Block.Quote -> {
                    // 金句：左侧 3dp 强调竖条 + 稍大的字号。歌词是这篇里最该被看见的东西，
                    // 混在正文段落里就白瞎了模型特意引用的那一句。
                    Row(
                        Modifier
                            .height(IntrinsicSize.Min)
                            .padding(bottom = 12.wdp)
                    ) {
                        Box(
                            Modifier
                                .width(3.wdp)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(2.dp))
                                .background(c.accent)
                        )
                        Spacer(Modifier.width(12.wdp))
                        Column {
                            block.lines.forEach { line ->
                                Text(
                                    "「$line」",
                                    color = c.t2,
                                    fontSize = 14.wsp,
                                    lineHeight = 23.wsp,
                                    fontFamily = if (c.titleSerif) FontFamily.Serif else FontFamily.SansSerif
                                )
                            }
                        }
                    }
                }

                is GuideMarkup.Block.Bullets -> {
                    // 列表项之间 4dp，块前后留 12dp —— 项间距跟段间距一样大会散成一堆独立段落
                    Column(Modifier.padding(top = 2.wdp, bottom = 12.wdp)) {
                        block.items.forEachIndexed { index, item ->
                            Row(Modifier.padding(top = if (index == 0) 0.dp else 4.wdp)) {
                                Text("·", color = c.accent, fontSize = 14.wsp)
                                Spacer(Modifier.width(8.wdp))
                                Text(
                                    item,
                                    color = c.t1,
                                    fontSize = 14.wsp,
                                    lineHeight = 22.wsp
                                )
                            }
                        }
                    }
                }

                GuideMarkup.Block.Rule -> {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.wdp)
                            .height(1.dp)
                            .background(if (c.isDark) Color(0x14FFFFFF) else Color(0x0F000000))
                    )
                }
            }
        }
    }
}

/** 音频信息段：技术参数 + 真实音质检测 */
@Composable
private fun AudioInfoSection(
    info: AudioInfo?,
    loudnessGainDb: Float,
    probeResult: SpectrumProbe.Result?,
    probeRunning: Boolean,
    probeError: String?,
    onProbe: () -> Unit,
    canProbe: Boolean
) {
    val c = moyinColors()
    Column(Modifier.padding(top = 8.wdp)) {
        if (info == null) {
            Text(
                "播放器还没有输出音频轨",
                color = c.t3,
                fontSize = 13.wsp,
                modifier = Modifier.padding(horizontal = 18.wdp, vertical = 6.wdp)
            )
        } else {
            InfoRow("格式", info.codec)
            if (info.requestedLabel != null) {
                InfoRow(
                    "请求档位",
                    if (info.downgraded) "${info.requestedLabel}（源站未提供无损）"
                    else info.requestedLabel
                )
            }
            InfoRow("源采样率", info.sampleRate?.let { fmtHz(it) } ?: "未知")
            InfoRow(
                "声道",
                info.channels?.let {
                    when (it) {
                        1 -> "单声道"
                        2 -> "双声道"
                        else -> "$it 声道"
                    }
                } ?: "未知"
            )
            InfoRow(
                "码率",
                info.bitrateKbps?.let {
                    if (info.bitrateFromSource) "$it kbps（源站标称）" else "$it kbps"
                } ?: "未知"
            )
            // 单曲体积（v2.4.10）：母带 5.4Mbps × 3:30 ≈ 140MB。
            // 用户看到这个数字才知道「母带」意味着什么，而不是只看到四个字。
            if (info.estimatedBytes > 0) {
                val mb = info.estimatedBytes / 1024f / 1024f
                InfoRow("单曲体积", "%.0f MB".format(mb))
            }
            InfoRow("应用输出", info.outputDescription ?: "未知（尚未确认AudioTrack）")
            InfoRow("当前路由", info.outputDevice ?: "未知")
            InfoRow("播放管线", if (info.pureAudioMode) "纯净PCM（高精度请求）" else "普通 / 省电偏好")
            InfoRow("硬件省电", info.offloadStatus)
            InfoRow("中断恢复", info.interruptionStatus)
            InfoRow("USB高保真", info.usbOutputStatus)
            InfoRow("true-peak", info.truePeakStatus)
            InfoRow("设备最终格式", "系统未可靠报告，不能据此断言重采样或无损直通")
            InfoRow("缓冲余量", "%.1f s".format(info.bufferedMs / 1000f))
            InfoRow("音源", info.sourceLabel)
            if (loudnessGainDb != 0f) {
                InfoRow(
                    "响度归一",
                    (if (loudnessGainDb > 0) "+" else "") + "%.1f dB".format(loudnessGainDb)
                )
            }
        }

        // ── 真实音质检测（只对本地文件）──
        Spacer(Modifier.height(10.wdp))
        if (canProbe) {
            if (probeResult != null) {
                InfoRow("实测截止", fmtHz(probeResult.cutoffHz))
                InfoRow("推断音质", probeResult.verdict.label)
                Spacer(Modifier.height(6.wdp))
                InfoAction("重新检测", onProbe)
            } else if (probeRunning) {
                Text(
                    "正在解码分析…（整首，约 2~4 秒）",
                    color = c.t3,
                    fontSize = 12.wsp,
                    modifier = Modifier.padding(horizontal = 18.wdp, vertical = 4.wdp)
                )
            } else {
                InfoAction("检测真实音质", onProbe)
            }
            probeError?.let {
                Text(
                    it,
                    color = c.t3,
                    fontSize = 11.wsp,
                    lineHeight = 16.wsp,
                    modifier = Modifier.padding(horizontal = 18.wdp, vertical = 2.wdp)
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val c = moyinColors()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.wdp, vertical = 6.wdp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = c.t3, fontSize = 13.wsp, modifier = Modifier.weight(1f))
        Text(
            value,
            color = c.t1,
            fontSize = 13.wsp,
            maxLines = 2,
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun InfoAction(text: String, onClick: () -> Unit) {
    val c = moyinColors()
    Box(
        Modifier
            .padding(horizontal = 18.wdp)
            .clip(RoundedCornerShape(12.wdp))
            .background(c.accentFill)
            .tvFocus(shape = RoundedCornerShape(12.wdp))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
            .padding(horizontal = 18.wdp, vertical = 10.wdp)
    ) {
        Text(text, color = c.onAccent, fontSize = 13.wsp, fontWeight = FontWeight.Medium)
    }
}

private fun fmtHz(hz: Int): String =
    if (hz % 1000 == 0) "${hz / 1000} kHz" else "%.1f kHz".format(hz / 1000f)
