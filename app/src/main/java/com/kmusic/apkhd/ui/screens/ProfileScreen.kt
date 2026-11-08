package com.kmusic.apkhd.ui.screens


import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kmusic.apkhd.ui.components.EqBandSliders
import com.kmusic.apkhd.ui.components.EqCurve
import com.kmusic.apkhd.ui.components.EqPresetGroupedChips
import com.kmusic.apkhd.ui.components.MoyinIcon
import com.kmusic.apkhd.ui.components.MoyinTitle
import com.kmusic.apkhd.ui.components.MoyinSwitch
import com.kmusic.apkhd.ui.components.SectionHeader
import com.kmusic.apkhd.ui.icons.IconChevronRight
import com.kmusic.apkhd.ui.theme.AccentPreset
import com.kmusic.apkhd.ui.theme.FrostLevel
import com.kmusic.apkhd.ui.theme.ThemeMode
import com.kmusic.apkhd.ui.theme.ambientAlpha
import com.kmusic.apkhd.ui.theme.ambientGlow
import com.kmusic.apkhd.ui.theme.moyinColors
import com.kmusic.apkhd.ui.theme.wdp
import com.kmusic.apkhd.ui.theme.wsp
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.focus.focusProperties
import com.kmusic.apkhd.ui.tv.tvFocus

/** 主题选项 */
data class ThemeOption(val label: String, val mode: ThemeMode)

val themeOptions = listOf(
    ThemeOption("跟随系统", ThemeMode.SYSTEM),
    ThemeOption("深色", ThemeMode.DARK),
    ThemeOption("浅色", ThemeMode.LIGHT)
)

/** 音质档位（key 与 SettingsStore.defaultQuality 一致） */
private val qualityOptions = listOf(
    "master" to "臻品母带 · 24bit/192kHz",
    "hires" to "无损 FLAC",
    "high" to "极高品 320k",
    "standard" to "标准 128k"
)

/**
 * 我的 / 设置页。
 *
 * ⚠️ 这里**只放真正生效的设置** —— 拨了没反应的开关一律不上：
 * 用户看到开关就会以为它是真的，那不如下架。
 */
@Composable
fun ProfileScreen(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    /** 马卡龙主题色；null = 默认强调色 */
    accentPreset: AccentPreset? = null,
    onAccentPresetChange: (AccentPreset?) -> Unit = {},
    /** 填充面不透明度 0.3~1.0，默认 0.8 */
    accentAlpha: Float = 0.8f,
    onAccentAlphaChange: (Float) -> Unit = {},
    /** 底部安全区：-1 自动，>=0 手动 dp */
    safeBottomSetting: Int = -1,
    onSafeBottomChange: (Int) -> Unit = {},
    /** 玻璃质感档位 */
    frostLevel: FrostLevel = FrostLevel.LIGHT,
    onFrostLevelChange: (FrostLevel) -> Unit = {},
    /** 深色风格："NAVY" 藏蓝（默认）/ "BLACK" 纯黑 */
    darkStyle: String = "NAVY",
    onDarkStyleChange: (String) -> Unit = {},
    // WebDAV
    davUrl: String = "",
    davUser: String = "",
    davPass: String = "",
    davTestResult: String? = null,
    davChecking: Boolean = false,
    onSaveDav: (String, String, String) -> Unit = { _, _, _ -> },
    onTestDav: () -> Unit = {},
    davUa: String = "",
    onSaveDavUa: (String) -> Unit = {},
    davDiagText: String = "",
    davDiagRunning: Boolean = false,
    onDiagnoseDav: () -> Unit = {},
    lastPlayError: String = "",
    defaultQuality: String,
    onQualityChange: (String) -> Unit,
    losslessFallback: Boolean,
    onLosslessFallbackChange: (Boolean) -> Unit,
    // 播放缓存
    cacheSizeGb: Int = 1,
    cacheBytes: Long = 0L,
    onCacheSizeChange: (Int) -> Unit = {},
    onClearCache: () -> Unit = {},
    /** 响度归一：off / track / album */
    loudnessMode: String = "track",
    onLoudnessModeChange: (String) -> Unit = {},
    /** 省电播放（offload）：开了音效失效 */
    audioOffload: Boolean = false,
    onAudioOffloadChange: (Boolean) -> Unit = {},
    audioOffloadStatus: String = "等待输出确认",
    audioOutputMode: String = "processed",
    onAudioOutputModeChange: (String) -> Unit = {},
    audioInterruptionResume: Boolean = true,
    onAudioInterruptionResumeChange: (Boolean) -> Unit = {},
    audioInterruptionStatus: String = "等待音频中断",
    callAutoResume: Boolean = true,
    onCallAutoResumeChange: (Boolean) -> Unit = {},
    callMonitorStatus: String = "未授权",
    onRequestCallPermission: () -> Unit = {},
    truePeakEnabled: Boolean = false,
    truePeakCeilingDb: Float = -1f,
    truePeakStatus: String = "关闭",
    onTruePeakEnabledChange: (Boolean) -> Unit = {},
    onTruePeakCeilingChange: (Float) -> Unit = {},
    usbOutputStatus: String = "未连接USB设备",
    usbModeAvailable: Boolean = false,
    deviceProfiles: List<com.kmusic.apkhd.data.DeviceEqProfile> = emptyList(),
    deviceProfileAuto: Boolean = false,
    deviceProfileStatus: String = "全局音效",
    deviceProfileActiveId: String? = null,
    hasConfirmedAudioRoute: Boolean = false,
    onBindDeviceProfile: (String) -> Unit = {},
    devicePreGainDb: Float = 0f,
    onDevicePreGainChange: (Float) -> Unit = {},
    onDeviceProfileAutoChange: (Boolean) -> Unit = {},
    onSaveDeviceProfile: (String, Boolean) -> Unit = { _, _ -> },
    onApplyDeviceProfile: (String?) -> Unit = {},
    onDeleteDeviceProfile: (String) -> Unit = {},
    onRequestDevicePermission: () -> Unit = {},
    pureAudioMode: Boolean = false,
    onPureAudioModeChange: (Boolean) -> Unit = {},
    bluetoothAutoResume: Boolean = false,
    onBluetoothAutoResumeChange: (Boolean) -> Unit = {},
    /** DSD 输出采样率：44100 / 88200 */
    dsdOutRate: Int = 44100,
    onDsdOutRateChange: (Int) -> Unit = {},
    /** DSD 播放时自动旁路均衡器 */
    dsdBypassEq: Boolean = true,
    onDsdBypassEqChange: (Boolean) -> Unit = {},
    wifiOnlyDownload: Boolean,
    onWifiOnlyDownloadChange: (Boolean) -> Unit,
    /** 母带仅在 Wi-Fi 下播放（默认关：流量也照播母带） */
    masterWifiOnly: Boolean = false,
    onMasterWifiOnlyChange: (Boolean) -> Unit = {},
    /** 冷启动后自动接着播（默认关：停在原位等用户按播放） */
    autoResumePlayback: Boolean = false,
    onAutoResumePlaybackChange: (Boolean) -> Unit = {},
    /** 导出诊断日志（歌词排查用）。为空则不显示入口 */
    onExportDiagLog: (() -> Unit)? = null,
    onClearDiagLog: (() -> Unit)? = null,
    // AI 导赏配置（key 由用户自己填，走 DataStore，不进仓库）
    aiEndpoint: String = "https://api.deepseek.com/v1",
    aiApiKey: String = "",
    aiModel: String = "deepseek-chat",
    onAiEndpointChange: (String) -> Unit = {},
    onAiApiKeyChange: (String) -> Unit = {},
    onAiModelChange: (String) -> Unit = {},
    eqEnabled: Boolean = false,
    eqAvailable: Boolean = false,
    onEqEnabledChange: (Boolean) -> Unit = {},
    onRefreshEq: () -> Unit = {},
    eqPresets: List<String> = emptyList(),
    eqPreset: Int = 0,
    eqBands: List<Int> = emptyList(),
    eqFreqs: List<Int> = emptyList(),
    eqRange: IntArray = intArrayOf(-1500, 1500),
    /** 用户拖过滑块，曲线已不是预设 */
    eqCustom: Boolean = false,
    onEqPresetChange: (Int) -> Unit = {},
    onEqBandChange: (Int, Int) -> Unit = { _, _ -> },
    /** 用户自建曲线（A3） */
    userEqPresets: List<com.kmusic.apkhd.playback.UserEqPresets.Preset> = emptyList(),
    onSaveEqCurve: (String) -> Unit = {},
    onApplyEqCurve: (com.kmusic.apkhd.playback.UserEqPresets.Preset) -> Unit = {},
    onDeleteEqCurve: (String) -> Unit = {},
    // 增强音效（低音/响度）。0 为关闭
    //
    // v2.3.2 删掉环绕/混响/动态范围；v2.3.5 起三项全部搬进自研处理器，
    // 不占系统音效池位 —— 于是「可用性」「资源占用」「重挂」这些概念一并退场。
    fxBass: Int = 0,
    onFxBassChange: (Int) -> Unit = {},
    fxLoudness: Int = 0,
    onFxLoudnessChange: (Int) -> Unit = {},
    /** 用户配置里开着几项（0..3），用于「还没起播」的判断 */
    fxWantedCount: Int = 0,
    /** 处理器接进管线后的采样率；0 = 还没起播 */
    fxSampleRate: Int = 0,
    sleepTotalMin: Int = 0,
    sleepRemainingSec: Int = 0,
    onSleepChange: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val c = moyinColors()
    var themeExpanded by remember { mutableStateOf(false) }
    var frostExpanded by remember { mutableStateOf(false) }
    // WebDAV 编辑缓存：改一项就把三项一起存，避免半套配置
    var davUrlLocal by remember(davUrl) { mutableStateOf(davUrl) }
    var davUserLocal by remember(davUser) { mutableStateOf(davUser) }
    var davPassLocal by remember(davPass) { mutableStateOf(davPass) }
    var davEditing by remember { mutableStateOf<String?>(null) }
    var darkStyleExpanded by remember { mutableStateOf(false) }
    // 分组折叠：设置页太长，外观/音效默认展开，点标题可收起
    var appearanceOpen by remember { mutableStateOf(true) }
    var effectsOpen by remember { mutableStateOf(true) }
    // 「逐跳诊断」默认不显示：排障入口不该占设置页。连点 WebDAV 分组标题 5 次才出现
    var davHeaderTaps by remember { mutableStateOf(0) }
    var davDiagUnlocked by remember { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var qualityExpanded by remember { mutableStateOf(false) }
    var cacheExpanded by remember { mutableStateOf(false) }
    var loudnessExpanded by remember { mutableStateOf(false) }
    var outputModeExpanded by remember { mutableStateOf(false) }
    var eqExpanded by remember { mutableStateOf(false) }
    // 均衡器详情区（预设 + 频段滑块）默认收起 —— 设置页以前滑块常驻，太长
    var eqDetailOpen by remember { mutableStateOf(false) }
    var legacyCurvesOpen by remember { mutableStateOf(false) }
    var deletingLegacyCurve by remember { mutableStateOf<String?>(null) }
    val currentMode = AudioSettingsPresentation.currentMode(audioOutputMode)
    // "endpoint" / "key" / "model" —— 非空时弹出输入框
    var aiEditing by remember { mutableStateOf<String?>(null) }
    var fxBassExpanded by remember { mutableStateOf(false) }
    var fxLoudExpanded by remember { mutableStateOf(false) }
    var sleepExpanded by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(c.bg)
            // 顶部氛围光：画在背景之上、列表内容之下，滚动时不跟着走 —— 像一层固定光。
            // 实现见 AmbientGlow.kt：椭圆纵向半轴压在光带之内，底边不会留横向接缝。
            .ambientGlow(
                tint = c.accent,
                bg = c.bg,
                alpha = c.ambientAlpha * 0.85f,
                baseHeight = 340.wdp
            )
            .padding(horizontal = 16.wdp),
        // 底部要留出「迷你播放条 + Tab 栏」的高度：它们是 overlay 在内容之上的，
        // 不留就会把最后几项压在栏下面，既看不见也点不到
        contentPadding = PaddingValues(top = 10.wdp, bottom = 142.wdp)
    ) {
        item {
            MoyinTitle(text = "我的", size = 30)
        }

        // 「我喜欢的音乐」「最近播放」「我的歌单」都不在这里 ——
        // 它们在「媒体库」，那边是唯一入口。设置页只放「设置」，
        // 同一份内容两处入口只会让用户两边各找一遍、还容易看到不同的数。
        // （AI 建歌单已挪到「媒体库 → 我的歌单」的列表上方。）

        item {
            Box(
                Modifier
                    .fillMaxWidth()
                    .tvFocus(fullWidth = true)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { appearanceOpen = !appearanceOpen }
            ) {
                SectionHeader(text = "外观", modifier = Modifier.padding(top = 16.wdp))
            }
        }
        if (appearanceOpen) {
        item {
            SettingRow(
                title = "主题",
                subtitle = themeOptions.firstOrNull { it.mode == themeMode }?.label ?: "跟随系统",
                onClick = { themeExpanded = !themeExpanded }
            )
        }
        if (themeExpanded) {
            item {
                InlineOptions(
                    options = themeOptions.map { it.mode.name to it.label },
                    selected = themeMode.name,
                    onPick = { onThemeModeChange(ThemeMode.valueOf(it)); themeExpanded = false }
                )
            }
        }

        // ── 主题色（马卡龙）─────────────────────────────────────
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.wdp, bottom = 2.wdp)
            ) {
                Text("主题色", color = c.t1, fontSize = 14.wsp)
                Spacer(Modifier.height(10.wdp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.wdp)
                ) {
                    AccentSwatch(
                        label = "默认",
                        color = c.accent,
                        selected = accentPreset == null
                    ) { onAccentPresetChange(null) }
                    AccentPreset.entries.forEach { p ->
                        AccentSwatch(
                            label = p.label,
                            color = Color(p.soft),
                            selected = accentPreset == p
                        ) { onAccentPresetChange(p) }
                    }
                }
            }
        }

        // 不透明度：只对马卡龙生效（默认色不需要，免得把原生配色改淡）
        if (accentPreset != null) {
            item {
                Column(Modifier.fillMaxWidth().padding(top = 4.wdp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("填充不透明度", color = c.t1, fontSize = 14.wsp)
                        Spacer(Modifier.weight(1f))
                        Text("${(accentAlpha * 100).toInt()}%", color = c.accent, fontSize = 12.wsp)
                    }
                    Slider(
                        value = accentAlpha,
                        onValueChange = onAccentAlphaChange,
                        valueRange = 0.3f..1f,
                        steps = 11
                    )
                }
            }
        }

        // ── 玻璃质感 ─────────────────────────────────────────
        item {
            SettingRow(
                title = "玻璃质感",
                subtitle = when (frostLevel) {
                    FrostLevel.OFF -> "关闭：纯色面板，最省电"
                    FrostLevel.LIGHT -> "轻：面板半透明 + 细纤维（默认）"
                    FrostLevel.STRONG -> "强：更透、颗粒更明显"
                },
                value = frostLevel.label,
                onClick = { frostExpanded = !frostExpanded }
            )
        }
        if (frostExpanded) {
            item {
                InlineOptions(
                    options = FrostLevel.entries.map { it.name to it.label },
                    selected = frostLevel.name,
                    onPick = { onFrostLevelChange(FrostLevel.valueOf(it)); frostExpanded = false }
                )
            }
        }

        // ── 深色风格：藏蓝 / 纯黑 ────────────────────────────
        item {
            SettingRow(
                title = "深色风格",
                subtitle = if (darkStyle == "BLACK") "纯黑：省电，层次靠描边" else "藏蓝：有层次，不糊成一片黑（默认）",
                value = if (darkStyle == "BLACK") "纯黑" else "藏蓝",
                onClick = { darkStyleExpanded = !darkStyleExpanded }
            )
        }
        if (darkStyleExpanded) {
            item {
                InlineOptions(
                    options = listOf("NAVY" to "藏蓝（默认）", "BLACK" to "纯黑"),
                    selected = darkStyle,
                    onPick = { onDarkStyleChange(it); darkStyleExpanded = false }
                )
            }
        }

        // ── 底部安全区（车机悬浮栏兜底）───────────────────────
        item {
            Column(Modifier.fillMaxWidth().padding(top = 6.wdp)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        // v2.4.1：整行可聚焦，开关自身退出焦点竞争（同上）
                        .tvFocus()
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { onSafeBottomChange(if (safeBottomSetting < 0) 56 else -1) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("底部安全区", color = c.t1, fontSize = 14.wsp)
                        Text(
                            if (safeBottomSetting < 0) "自动（车机 56dp，其它 0）" else "手动 ${safeBottomSetting}dp",
                            color = c.t3, fontSize = 11.wsp
                        )
                    }
                    MoyinSwitch(
                        checked = safeBottomSetting < 0,
                        onCheckedChange = { auto ->
                            onSafeBottomChange(if (auto) -1 else 56)
                        },
                        modifier = Modifier
                            .tvFocus(enabled = false)
                            .focusProperties { canFocus = false }
                    )
                }
                if (safeBottomSetting >= 0) {
                    Slider(
                        value = safeBottomSetting.toFloat(),
                        onValueChange = { onSafeBottomChange(it.toInt()) },
                        valueRange = 0f..96f,
                        steps = 11
                    )
                }
            }
        }

        // ── WebDAV ─────────────────────────────────────────────
        item {
            SectionHeader(
                text = "网络与存储",
                modifier = Modifier
                    .padding(top = 16.wdp)
                    .tvFocus()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) {
                        davHeaderTaps++
                        if (davHeaderTaps >= 5) davDiagUnlocked = true
                    }
            )
        }
        item {
            SettingRow(
                title = "服务器地址",
                subtitle = davUrlLocal.ifEmpty { "未配置，例如 http://192.168.1.10:8115/dav/" },
                onClick = { davEditing = "url" }
            )
        }
        item {
            SettingRow(
                title = "用户名",
                subtitle = davUserLocal.ifEmpty { "未填写" },
                onClick = { davEditing = "user" }
            )
        }
        item {
            SettingRow(
                title = "密码",
                subtitle = if (davPassLocal.isEmpty()) "未填写" else "已保存（点此修改）",
                onClick = { davEditing = "pass" }
            )
        }
        item {
            SettingRow(
                title = "User-Agent",
                subtitle = davUa.ifEmpty { "默认 KMusic/1.5.0 (Android)" },
                onClick = { davEditing = "ua" }
            )
        }
        item {
            SettingRow(
                title = "测试连接",
                subtitle = when {
                    davChecking -> "正在连接…"
                    !davTestResult.isNullOrEmpty() -> davTestResult
                    else -> "保存后点这里验证"
                },
                value = "测试",
                onClick = onTestDav
            )
        }
        // 排障入口：默认隐藏（连点「网络与存储」标题 5 次才出现）
        if (davDiagUnlocked) {
            item {
                SettingRow(
                    title = "逐跳诊断",
                    subtitle = when {
                        davDiagRunning -> "正在跑：列目录 → 302 链 → CDN…"
                        davDiagText.isNotEmpty() -> "已完成，结果见下方（点击可复制）"
                        else -> "点一下把「列目录 / 每一跳 302」都打出来"
                    },
                    value = if (davDiagRunning) "…" else "诊断",
                    onClick = onDiagnoseDav
                )
            }
        }
        if (lastPlayError.isNotEmpty()) {
            item {
                CopyBlock(
                    label = "最近一次播放错误",
                    text = lastPlayError,
                    onCopy = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(lastPlayError))
                        android.widget.Toast.makeText(ctx, "已复制", android.widget.Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
        if (davDiagUnlocked && davDiagText.isNotEmpty()) {
            item {
                CopyBlock(
                    label = "诊断结果",
                    text = davDiagText,
                    onCopy = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(davDiagText))
                        android.widget.Toast.makeText(ctx, "已复制", android.widget.Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
        if (davEditing != null) {
            item {
                val editing = davEditing
                AiInputDialog(
                    title = when (editing) {
                        "url" -> "WebDAV 地址"
                        "user" -> "用户名"
                        "ua" -> "User-Agent"
                        else -> "密码"
                    },
                    initial = when (editing) {
                        "url" -> davUrlLocal
                        "user" -> davUserLocal
                        "ua" -> davUa
                        else -> davPassLocal
                    },
                    hint = when (editing) {
                        "url" -> "http://host:port/dav/"
                        "user" -> "留空表示匿名"
                        "ua" -> "留空用默认 KMusic/1.5.0 (Android)"
                        else -> "Basic 认证密码"
                    },
                    mask = editing == "pass",
                    onDismiss = { davEditing = null },
                    onConfirm = { v ->
                        if (editing == "ua") {
                            onSaveDavUa(v.trim())
                        } else {
                            val u = if (editing == "url") v.trim() else davUrlLocal
                            val us = if (editing == "user") v.trim() else davUserLocal
                            val pw = if (editing == "pass") v else davPassLocal
                            davUrlLocal = u; davUserLocal = us; davPassLocal = pw
                            onSaveDav(u, us, pw)
                        }
                        davEditing = null
                    }
                )
            }
        }

        }

        item { SectionHeader(text = "播放与音质", modifier = Modifier.padding(top = 16.wdp)) }
        item {
            SettingRow(
                title = "默认音质",
                subtitle = qualityOptions.firstOrNull { it.first == defaultQuality }?.second ?: "",
                onClick = { qualityExpanded = !qualityExpanded }
            )
        }
        if (qualityExpanded) {
            item {
                InlineOptions(
                    options = qualityOptions,
                    selected = defaultQuality,
                    onPick = { onQualityChange(it); qualityExpanded = false }
                )
            }
        }
        item {
            SettingSwitchRow(
                title = "无损降级",
                subtitle = if (losslessFallback) {
                    "所选音质拿不到时自动往下降"
                } else {
                    "拿不到所选音质就直接报错，不偷偷换"
                },
                checked = losslessFallback,
                onCheckedChange = onLosslessFallbackChange
            )
        }
        item {
            SettingRow(
                title = "响度归一",
                subtitle = when (loudnessMode) {
                    "off" -> "关闭：保持原曲音量，切歌可能忽大忽小"
                    "album" -> "使用专辑标签；无标签回退单曲（仅衰减、本地文件）"
                    else -> "按单曲降低过响音量，不放大安静歌曲（仅本地文件）"
                },
                value = when (loudnessMode) {
                    "off" -> "关闭"
                    "album" -> "专辑"
                    else -> "单曲"
                },
                onClick = { loudnessExpanded = !loudnessExpanded }
            )
        }
        if (loudnessExpanded) {
            item {
                InlineOptions(
                    options = listOf(
                        "track" to "单曲",
                        "album" to "专辑",
                        "off" to "关闭"
                    ),
                    selected = loudnessMode,
                    onPick = { onLoudnessModeChange(it); loudnessExpanded = false }
                )
            }
        }
        item(key = "playback-mode") {
            SettingRow(title = "播放模式", subtitle = currentMode.description,
                value = currentMode.label.removeSuffix("（推荐）"),
                onClick = { outputModeExpanded = !outputModeExpanded })
        }
        if (outputModeExpanded) item {
            InlineOptions(com.kmusic.apkhd.playback.AudioModeDefaults.visibleModes(usbModeAvailable, audioOutputMode)
                    .map { it to AudioSettingsPresentation.currentMode(it).label },
                selected = audioOutputMode,
                onPick = { onAudioOutputModeChange(it); outputModeExpanded = false })
        }
        item {
            SettingRow(
                title = "播放缓存",
                subtitle = "听过的歌存本地，二次播放不耗流量",
                value = if (cacheSizeGb <= 0) "关闭" else "$cacheSizeGb GB",
                onClick = { cacheExpanded = !cacheExpanded }
            )
        }
        if (cacheExpanded) {
            item {
                InlineOptions(
                    options = listOf(
                        "0" to "关闭",
                        "1" to "1 GB",
                        "2" to "2 GB",
                        "4" to "4 GB"
                    ),
                    selected = cacheSizeGb.toString(),
                    onPick = { onCacheSizeChange(it.toInt()); cacheExpanded = false }
                )
            }
        }
        item {
            SettingRow(
                title = "缓存占用",
                subtitle = formatCacheBytes(cacheBytes),
                value = "清空",
                onClick = onClearCache
            )
        }

        // 输入对话框：挂在 item 里也能正常弹出，Dialog 不受列表裁剪影响
        item {
        aiEditing?.let { which ->
            when (which) {
                "endpoint" -> AiInputDialog(
                    title = "接口地址",
                    initial = aiEndpoint,
                    hint = "https://api.deepseek.com/v1",
                    mask = false,
                    onDismiss = { aiEditing = null },
                    onConfirm = { onAiEndpointChange(it); aiEditing = null }
                )
                "key" -> AiInputDialog(
                    title = "API Key",
                    initial = aiApiKey,
                    hint = "sk-…",
                    mask = true,
                    onDismiss = { aiEditing = null },
                    onConfirm = { onAiApiKeyChange(it); aiEditing = null }
                )
                else -> AiInputDialog(
                    title = "模型",
                    initial = aiModel,
                    hint = "deepseek-chat",
                    mask = false,
                    onDismiss = { aiEditing = null },
                    onConfirm = { onAiModelChange(it); aiEditing = null }
                )
            }
        }
        }

        // ── AI 导赏 ──
        item { SectionHeader(text = "AI 导赏", modifier = Modifier.padding(top = 16.wdp)) }
        item {
            SettingRow(
                title = "接口地址",
                subtitle = aiEndpoint,
                onClick = { aiEditing = "endpoint" }
            )
        }
        item {
            SettingRow(
                title = "API Key",
                subtitle = if (aiApiKey.isBlank()) "未填写 —— 填入后才能生成导赏" else "已填写 · 点击修改",
                onClick = { aiEditing = "key" }
            )
        }
        item {
            SettingRow(
                title = "模型",
                subtitle = aiModel,
                onClick = { aiEditing = "model" }
            )
        }
        item {
            Text(
                "需填你自己的 Key（默认 DeepSeek）。任何 OpenAI 兼容接口都行；Key 只存在本机，不会上传。",
                color = moyinColors().t3,
                fontSize = 11.wsp,
                lineHeight = 17.wsp,
                modifier = Modifier.padding(horizontal = 18.wdp, vertical = 2.wdp)
            )
        }

        // 设备真给了频段才显示均衡器段；没频段但设备有均衡器时留一行说明，
        // 不让「音效」整块凭空消失（池满/未起播都会造成这种情况）
        if (eqBands.isNotEmpty()) {
        item {
            Box(
                Modifier
                    .fillMaxWidth()
                    .tvFocus(fullWidth = true)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { effectsOpen = !effectsOpen }
            ) {
                SectionHeader(text = "音效", modifier = Modifier.padding(top = 16.wdp))
            }
        }
        if (effectsOpen) {
        item {
            SettingSwitchRow(
                title = "均衡器",
                subtitle = when {
                    !eqEnabled -> "关闭 · 打开后按预设调整频响（十段，不占系统音效位）"
                    eqPresets.isEmpty() -> "已开启"
                    else -> "已开启 · 正在按「${eqPresets.getOrNull(eqPreset) ?: "原声"}」调整频响"
                },
                checked = eqEnabled,
                onCheckedChange = onEqEnabledChange
            )
        }
        item {
            com.kmusic.apkhd.ui.components.SoundSchemeSection(
                profiles = deviceProfiles,
                activeId = deviceProfileActiveId,
                hasConfirmedRoute = hasConfirmedAudioRoute,
                onSave = onSaveDeviceProfile,
                onApply = onApplyDeviceProfile,
                onDelete = onDeleteDeviceProfile,
                onBind = onBindDeviceProfile,
                deviceAutoEnabled = deviceProfileAuto,
                onDeviceAutoChange = onDeviceProfileAutoChange
            )
        }
        item {
            SettingRow(title = "使用原声", subtitle = "暂不使用音效；已保存的方案不会删除。",
                onClick = { onAudioOutputModeChange("pure") })
        }
        if (userEqPresets.isNotEmpty()) item {
            SettingRow(title = "以前保存的曲线", subtitle = "仍可使用，保存新音效请用我的音效方案。",
                value = "选择", onClick = { legacyCurvesOpen = true })
        }
        // 预设行常显；只把频段区折起来 —— 滑块/曲线常驻时手指落在上面等于改曲线。
        if (eqPresets.isNotEmpty()) {
            item {
                SettingRow(
                    title = "预设",
                    subtitle = buildString {
                        append(if (eqCustom) "自定义" else eqPresets.getOrNull(eqPreset) ?: "原声")
                        if (!eqEnabled) append(" · 均衡器关闭中")
                    },
                    value = if (eqExpanded) "收起" else "展开",
                    onClick = { eqExpanded = !eqExpanded }
                )
            }
            if (eqExpanded) {
                item {
                    // A3：按 风格 / 场景 / 设备 分组，下标仍全局唯一
                    EqPresetGroupedChips(
                        selected = eqPreset,
                        customActive = eqCustom,
                        onPick = { onEqPresetChange(it); eqExpanded = false }
                    )
                }
            }
        }
        // 频段曲线：设备给几段画几段。默认收起，展开后只有圆点能拖。
        if (eqBands.isNotEmpty()) {
            item {
                SettingRow(
                    title = "频段调整",
                    subtitle = buildString {
                        append(if (eqCustom) "自定义" else eqPresets.getOrNull(eqPreset) ?: "原声")
                        append(" · ${eqBands.size} 段")
                    },
                    value = if (eqDetailOpen) "收起" else "展开",
                    onClick = { eqDetailOpen = !eqDetailOpen }
                )
            }
            if (eqDetailOpen) {
                item {
                    EqCurve(
                        bands = eqBands,
                        freqs = eqFreqs,
                        range = eqRange,
                        onBandChange = onEqBandChange
                    )
                }
                item {
                    Text(
                        text = "拖动圆点调整该频段；双击圆点复位 0 dB。改动后不再跟随预设。",
                        color = moyinColors().t3,
                        fontSize = 11.wsp,
                        modifier = Modifier.padding(horizontal = 18.wdp, vertical = 2.wdp)
                    )
                }
            }
        }
        }

        // 防御：频段是内置的，正常不会为空。真为空说明初始化没跑完。
        if (eqBands.isEmpty()) {
            item { SectionHeader(text = "音效", modifier = Modifier.padding(top = 16.wdp)) }
            item { SettingRow(title = "均衡器", subtitle = "加载中", onClick = {}) }
        }

        // ── 增强音效 ──
        // v2.3.5：三项都在自研处理器里，没有「本机不支持」也没有「资源占用」，
        // 所以这里不再需要按可用性隐藏或置灰 —— 一律常显、一律可调。
        item { SectionHeader(text = "增强音效", modifier = Modifier.padding(top = 16.wdp)) }

        item {
            SettingRow(
                title = "低音增强",
                subtitle = if (fxBass <= 0) {
                    "关 · 110Hz 以下整体抬升，中频不动"
                } else {
                    strengthLabel(fxBass) + " · 110Hz 以下整体抬升，中频不动"
                },
                onClick = { fxBassExpanded = !fxBassExpanded }
            )
        }
        if (fxBassExpanded) {
            item {
                InlineOptions(
                    options = STRENGTH_STEPS,
                    selected = fxBass.toString(),
                    onPick = { onFxBassChange(it.toInt()); fxBassExpanded = false }
                )
            }
        }

        item {
            SettingRow(
                title = "响度增强",
                subtitle = if (fxLoudness <= 0) {
                    "关 · 抬整体音量，EQ 正增益会先扣掉同等余量"
                } else {
                    "+${fxLoudness / 100} dB · EQ 正增益会先扣掉同等余量"
                },
                onClick = { fxLoudExpanded = !fxLoudExpanded }
            )
        }
        if (fxLoudExpanded) {
            item {
                InlineOptions(
                    options = LOUD_STEPS,
                    selected = fxLoudness.toString(),
                    onPick = { onFxLoudnessChange(it.toInt()); fxLoudExpanded = false }
                )
            }
        }

        item {
            Text(
                text = if (fxSampleRate > 0) {
                    "音效跑在播放链上（${fxSampleRate / 1000f} kHz），不占系统音效槽 ——" +
                        "开几项都不会影响别的 App。"
                } else {
                    "音效跑在播放链上，不占系统音效槽 —— 开几项都不会影响别的 App。"
                },
                color = moyinColors().t3,
                fontSize = 11.wsp,
                lineHeight = 17.wsp,
                modifier = Modifier.padding(horizontal = 18.wdp, vertical = 2.wdp)
            )
        }

        }

        item { SectionHeader(text = "播放", modifier = Modifier.padding(top = 16.wdp)) }
        item {
            SettingSwitchRow(
                title = "自动接着播",
                subtitle = if (autoResumePlayback) {
                    "已开启：打开 App 就从上次断开的位置继续播"
                } else {
                    "打开 App 时恢复到上次的歌单和进度，停在那儿等你按播放"
                },
                checked = autoResumePlayback,
                onCheckedChange = onAutoResumePlaybackChange
            )
        }
        item {
            SettingRow(
                title = "睡眠定时",
                subtitle = if (sleepRemainingSec > 0) {
                    val m = sleepRemainingSec / 60
                    val s = sleepRemainingSec % 60
                    "还剩 %d:%02d".format(m, s)
                } else "关闭",
                onClick = { sleepExpanded = !sleepExpanded }
            )
        }
        if (sleepExpanded) {
            item {
                InlineOptions(
                    options = SLEEP_STEPS,
                    selected = sleepTotalMin.toString(),
                    onPick = { onSleepChange(it.toInt()); sleepExpanded = false }
                )
            }
        }

        if (onExportDiagLog != null) {
            item { SectionHeader(text = "诊断", modifier = Modifier.padding(top = 16.wdp)) }
            item {
                SettingRow(
                    title = "导出诊断日志",
                    subtitle = "记录歌词匹配全过程，排歌词问题用",
                    onClick = onExportDiagLog
                )
            }
            item {
                SettingRow(
                    title = "清空日志",
                    subtitle = "只影响日志，不影响播放和收藏",
                    onClick = onClearDiagLog ?: {}
                )
            }
        }

        // 下载队列本身在「媒体库 → 下载管理」，这里只留限制条件 ——
        // 两处都能看队列的话，用户在设置页点一下会以为跳到了别的地方
        item { SectionHeader(text = "下载与流量", modifier = Modifier.padding(top = 16.wdp)) }
        item {
            SettingSwitchRow(
                title = "仅 Wi-Fi 下载",
                subtitle = "移动网络下点下载会明确提示，不静默排队",
                checked = wifiOnlyDownload,
                onCheckedChange = onWifiOnlyDownloadChange
            )
        }
        // 跟「仅 Wi-Fi 下载」放一起：两个都是「流量下别干这件事」的开关。
        // 母带一首 140MB（5.4Mbps），比无损大 4 倍，值不值得为它烧流量由用户定。
        item {
            SettingSwitchRow(
                title = "母带仅 Wi-Fi 播放",
                subtitle = if (masterWifiOnly) {
                    "已开启：流量下用 GD 无损（约 35MB/首），Wi-Fi 才用母带（约 140MB/首）"
                } else {
                    "关闭：流量下也照播母带。一首约 140MB，听十首约 1.4GB"
                },
                checked = masterWifiOnly,
                onCheckedChange = onMasterWifiOnlyChange
            )
        }
    }

    if (legacyCurvesOpen) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { legacyCurvesOpen = false },
            title = { Text("以前保存的曲线") },
            text = {
                androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 360.wdp)) {
                    items(userEqPresets, key = { it.name }) { preset ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.TextButton(modifier = Modifier.weight(1f), onClick = {
                                onAudioOutputModeChange("processed")
                                onApplyDeviceProfile(null)
                                onApplyEqCurve(preset)
                                legacyCurvesOpen = false
                            }) { Text(preset.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            androidx.compose.material3.TextButton(onClick = { deletingLegacyCurve = preset.name }) { Text("删除") }
                        }
                    }
                }
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { legacyCurvesOpen = false }) { Text("完成") } }
        )
    }
    deletingLegacyCurve?.let { name ->
        androidx.compose.material3.AlertDialog(onDismissRequest = { deletingLegacyCurve = null },
            title = { Text("删除曲线？") }, text = { Text("删除「$name」不会影响歌曲或其他方案。") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { onDeleteEqCurve(name); deletingLegacyCurve = null }) { Text("删除") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { deletingLegacyCurve = null }) { Text("取消") } })
    }

}

/** 行内展开的单选列表（主题 / 音质共用） */
@Composable
private fun InlineOptions(
    options: List<Pair<String, String>>,
    selected: String,
    onPick: (String) -> Unit
) {
    val c = moyinColors()
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 28.wdp, bottom = 4.wdp)
    ) {
        options.forEach { (key, label) ->
            val on = key == selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .tvFocus(fullWidth = true)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { onPick(key) }
                    .padding(vertical = 8.wdp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(10.wdp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(if (on) c.accent else c.line)
                )
                Spacer(Modifier.width(8.wdp))
                Text(
                    text = label,
                    color = if (on) c.t1 else c.t2,
                    fontSize = 14.wsp
                )
            }
        }
    }
}

@Composable
internal fun AiInputDialog(
    title: String,
    initial: String,
    hint: String,
    mask: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var v by remember { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 16.wsp) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = v,
                onValueChange = { v = it },
                placeholder = { Text(hint, fontSize = 13.wsp) },
                singleLine = true,
                visualTransformation = if (mask) {
                    androidx.compose.ui.text.input.PasswordVisualTransformation()
                } else {
                    androidx.compose.ui.text.input.VisualTransformation.None
                }
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onConfirm(v.trim()) }) {
                Text("保存")
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String?,
    value: String? = null,
    onClick: () -> Unit
) {
    val c = moyinColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // v2.4.2：满宽整行用 fullWidth —— 左侧 3dp 竖条当锚点，
            // 否则一整条 1750px 宽的淡色带子很难看出「焦点在哪一行」
            .tvFocus(fullWidth = true)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
            .padding(vertical = 10.wdp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.t1, fontSize = 14.wsp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Spacer(Modifier.height(2.wdp))
                Text(subtitle, color = c.t3, fontSize = 11.wsp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (value != null) {
            Text(value, color = c.t3, fontSize = 12.wsp)
            Spacer(Modifier.width(8.wdp))
        }
        MoyinIcon(icon = IconChevronRight, tint = c.t3, size = 15.wdp)
    }
}

private fun formatCacheBytes(bytes: Long): String {
    if (bytes <= 0) return "暂无缓存"
    val mb = bytes / 1024.0 / 1024.0
    return if (mb >= 1024) String.format("%.1f GB", mb / 1024) else String.format("%.0f MB", mb)
}

/**
 * 带开关的设置行。
 *
 * ⚠️ v2.4.1：整行必须**自己可聚焦**。
 *
 * 以前这一行只有 [MoyinSwitch] 内部有 `tvFocus`，行长 300dp、开关只有 44×26dp，
 * 遥控器几何搜索几乎命不中那个小方块 —— 用户的感受就是「无损降级 / 省电播放 /
 * DSD / 均衡器 这一整行选不上」，而同页纯文本行（[SettingRow]）却正常。
 *
 * 现在：整行一个焦点目标（`tvFocus` + `clickable` 整行切换），开关本体
 * `canFocus = false` 退出焦点竞争 —— 否则每行要按两次方向键（一次到行、一次到开关）。
 */
@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val c = moyinColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // v2.4.2：满宽整行 + 左侧锚点竖条，同 SettingRow
            .tvFocus(fullWidth = true)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onCheckedChange(!checked) }
            .padding(vertical = 10.wdp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.t1, fontSize = 14.wsp, maxLines = 1)
            if (subtitle != null) {
                Spacer(Modifier.height(2.wdp))
                Text(subtitle, color = c.t3, fontSize = 11.wsp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        MoyinSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            // 整行已经是焦点目标，开关不再单独参与遥控焦点
            modifier = Modifier
                .tvFocus(enabled = false)
                .focusProperties { canFocus = false }
        )
    }
}

// ── 增强音效档位 ──
// 用固定档位而不是连续滑块：拖动滑块很难对齐到「想要的那一档」，
// 而且这些效果的听感本来就不是线性的，中间值意义不大。
private val STRENGTH_STEPS = listOf(
    "0" to "关", "250" to "弱", "500" to "中", "750" to "强", "1000" to "极强"
)
private val LOUD_STEPS = listOf(
    "0" to "关", "300" to "+3 dB", "600" to "+6 dB", "900" to "+9 dB", "1200" to "+12 dB"
)

private fun strengthLabel(v: Int) = when {
    v <= 0 -> "关闭"
    v < 375 -> "弱"
    v < 625 -> "中"
    v < 875 -> "强"
    else -> "极强"
}

private val SLEEP_STEPS = listOf(
    "0" to "关闭", "15" to "15 分钟", "30" to "30 分钟",
    "45" to "45 分钟", "60" to "1 小时", "90" to "1.5 小时"
)

/** 主题色圆点：选中时套一圈描边 + 打勾点 */
@Composable
private fun AccentSwatch(
    label: String,
    color: Color,
    selected: Boolean,
    onClick: () -> Unit
) {
    val c = moyinColors()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.wdp))
            .tvFocus(shape = RoundedCornerShape(12.wdp))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
            .padding(vertical = 4.wdp, horizontal = 4.wdp)
    ) {
        Box(
            Modifier
                .size(30.wdp)
                .clip(RoundedCornerShape(99.wdp))
                .background(color)
                .then(
                    if (selected) Modifier.border(2.wdp, c.t1, RoundedCornerShape(99.wdp))
                    else Modifier.border(1.wdp, c.line, RoundedCornerShape(99.wdp))
                ),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Box(
                    Modifier
                        .size(9.wdp)
                        .clip(RoundedCornerShape(99.wdp))
                        .background(c.t1)
                )
            }
        }
        Spacer(Modifier.height(5.wdp))
        Text(
            label,
            color = if (selected) c.t1 else c.t3,
            fontSize = 10.wsp,
            maxLines = 1
        )
    }
}


/** 一段可复制的多行文本（诊断结果 / 最近错误） */
@Composable
private fun CopyBlock(label: String, text: String, onCopy: () -> Unit) {
    val c = moyinColors()
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.wdp, vertical = 6.wdp)
            .clip(RoundedCornerShape(c.radiusCard))
            .background(c.s2)
            .clickable { onCopy() }
            .padding(12.wdp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = c.t2, fontSize = 12.wsp, modifier = Modifier.weight(1f))
            Text("复制", color = c.accent, fontSize = 11.wsp)
        }
        Spacer(Modifier.height(6.wdp))
        Text(text, color = c.t3, fontSize = 11.wsp)
    }
}
