package com.kmusic.apkhd.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kmusic.apkhd.model.PlayMode
import com.kmusic.apkhd.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "moyin_settings")

/**
 * 轻量设置存储：主题模式 + 默认音质 + Navidrome 凭据。
 * 只放必须跨会话保留的少量偏好，曲库/播放队列等大对象后续用 Room。
 *
 * ⚠️ 凭据明文存于 App 私有 DataStore，不上传、不写日志。
 */
/** 默认把歌词提前 0.5s：各家歌词源普遍「词晚到」，和网易云/QQ 音乐的默认补偿一致 */
const val LYRIC_OFFSET_DEFAULT_MS = 500L

class SettingsStore(private val context: Context) {

    private val themeKey = stringPreferencesKey("theme_mode")
    private val accentPresetKey = stringPreferencesKey("accent_preset")
    private val accentAlphaKey = floatPreferencesKey("accent_alpha")
    private val safeBottomKey = intPreferencesKey("safe_bottom_dp")
    private val frostKey = stringPreferencesKey("frost_level")
    private val darkStyleKey = stringPreferencesKey("dark_style")
    private val davUrlKey = stringPreferencesKey("dav_url")
    private val davUserKey = stringPreferencesKey("dav_user")
    private val davPassKey = stringPreferencesKey("dav_pass")
    private val davUaKey = stringPreferencesKey("dav_ua")
    private val qualityKey = stringPreferencesKey("default_quality")
    /** 播放缓存容量（GB）。0 = 关闭，默认 1GB */
    private val cacheSizeKey = intPreferencesKey("cache_size_gb")
    private val ndUrlKey = stringPreferencesKey("nd_url")
    private val ndUserKey = stringPreferencesKey("nd_user")
    private val ndPassKey = stringPreferencesKey("nd_pass")
    private val searchHistoryKey = stringPreferencesKey("search_history")
    private val playModeKey = stringPreferencesKey("play_mode")
    private val losslessFallbackKey = booleanPreferencesKey("lossless_fallback")
    private val wifiOnlyKey = booleanPreferencesKey("wifi_only_download")
    /** 母带仅在 Wi-Fi 下播放（默认关：流量也照播母带，用户自己开） */
    private val masterWifiOnlyKey = booleanPreferencesKey("master_wifi_only")
    private val lyricOffsetKey = longPreferencesKey("lyric_offset_ms")
    private val lyricFontKey = floatPreferencesKey("lyric_font_scale")
    private val eqEnabledKey = booleanPreferencesKey("eq_enabled")
    private val eqPresetKey = intPreferencesKey("eq_preset")
    // 系统音效链：低音增强 / 响度增强。0 表示关闭
    //
    // v2.3.2 起环绕、混响、动态范围三项已删除（合并进统一音效实例后它们没有载体了）。
    // 旧键 fx_virtual / fx_reverb / fx_dynamics 不再读取，DataStore 里残留的值不影响任何行为。
    private val fxBassKey = intPreferencesKey("fx_bass")
    private val fxLoudnessKey = intPreferencesKey("fx_loudness")
    /** 响度归一：off / track / album */
    private val loudnessModeKey = stringPreferencesKey("loudness_mode")
    /** 省电播放（offload）。开了就没有音效 */
    private val offloadKey = booleanPreferencesKey("audio_offload")
    private val pureAudioModeKey = booleanPreferencesKey("pure_audio_mode")
    private val usbHighFidelityKey = booleanPreferencesKey("usb_high_fidelity")
    private val callAutoResumeKey = booleanPreferencesKey("call_auto_resume")
    private val audioInterruptionResumeKey = booleanPreferencesKey("audio_interruption_resume")
    private val truePeakEnabledKey = booleanPreferencesKey("true_peak_enabled")
    private val truePeakCeilingKey = floatPreferencesKey("true_peak_ceiling_db")
    /** DSD 输出采样率：44100 / 88200 */
    private val dsdOutRateKey = intPreferencesKey("dsd_out_rate")
    /** DSD 自动旁路均衡器 */
    private val dsdBypassEqKey = booleanPreferencesKey("dsd_bypass_eq")
    /** 冷启动后自动接着播（默认关：重开停在原位等用户按播放） */
    private val autoResumeKey = booleanPreferencesKey("auto_resume_playback")
    private val bluetoothAutoResumeKey = booleanPreferencesKey("bluetooth_auto_resume")

    // ── AI 导赏（OpenAI 兼容接口，key 由用户自己填）──
    private val aiEndpointKey = stringPreferencesKey("ai_endpoint")
    private val aiApiKeyKey = stringPreferencesKey("ai_api_key")
    private val aiModelKey = stringPreferencesKey("ai_model")

    /**
     * AI 导赏接口地址。
     *
     * 默认 DeepSeek：中文乐评质量好，价格比 GPT-4o-mini 低一个量级。
     * 只要对方兼容 OpenAI 的 /chat/completions 就能用（OpenAI / 通义 / 智谱 / 本地 vLLM…）。
     */
    val aiEndpoint: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[aiEndpointKey]?.takeIf { it.isNotBlank() } ?: "https://api.deepseek.com/v1"
    }

    val aiApiKey: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[aiApiKeyKey].orEmpty()
    }

    val aiModel: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[aiModelKey]?.takeIf { it.isNotBlank() } ?: "deepseek-chat"
    }

    suspend fun setAiEndpoint(v: String) { context.dataStore.edit { it[aiEndpointKey] = v.trim() } }
    suspend fun setAiApiKey(v: String) { context.dataStore.edit { it[aiApiKeyKey] = v.trim() } }
    suspend fun setAiModel(v: String) { context.dataStore.edit { it[aiModelKey] = v.trim() } }

    /** 播放模式（顺序/列表循环/单曲循环/随机），默认顺序播放 */
    /** 马卡龙主题色（枚举名）；"DEFAULT" 表示用原生强调色 */
    val accentPreset: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[accentPresetKey] ?: "DEFAULT"
    }

    suspend fun setAccentPreset(name: String) { context.dataStore.edit { it[accentPresetKey] = name } }

    /** 填充面不透明度，默认 0.8（80% 不透明） */
    val accentAlpha: Flow<Float> = context.dataStore.data.map { prefs ->
        prefs[accentAlphaKey] ?: 0.8f
    }

    suspend fun setAccentAlpha(v: Float) {
        context.dataStore.edit { it[accentAlphaKey] = v.coerceIn(0.3f, 1f) }
    }

    /** 玻璃质感档位：OFF / LIGHT / STRONG，默认 LIGHT */
    val frostLevel: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[frostKey] ?: "LIGHT"
    }

    suspend fun setFrostLevel(v: String) { context.dataStore.edit { it[frostKey] = v } }

    /** 深色风格："NAVY" 藏蓝（默认）/ "BLACK" 纯黑 */
    val darkStyle: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[darkStyleKey] ?: "NAVY"
    }

    suspend fun setDarkStyle(v: String) { context.dataStore.edit { it[darkStyleKey] = v } }

    /** 底部安全区（dp）。-1 = 自动：车机 56dp，其它 0 */
    val safeBottomDp: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[safeBottomKey] ?: -1
    }

    suspend fun setSafeBottomDp(v: Int) { context.dataStore.edit { it[safeBottomKey] = v } }

    val playMode: Flow<PlayMode> = context.dataStore.data.map { prefs ->
        PlayMode.of(prefs[playModeKey])
    }

    suspend fun setPlayMode(mode: PlayMode) {
        context.dataStore.edit { it[playModeKey] = mode.name }
    }

    /** 无损降级：没有所选音质时，是否自动往下降级（关掉就直接报错，不偷偷换音质） */
    val losslessFallback: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[losslessFallbackKey] ?: true
    }

    suspend fun setLosslessFallback(on: Boolean) {
        context.dataStore.edit { it[losslessFallbackKey] = on }
    }

    /** 仅 Wi-Fi 下载（默认关）—— 母带单曲上百 MB，默认不限能避免「点了没反应」的困惑 */
    val wifiOnlyDownload: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[wifiOnlyKey] ?: false
    }

    suspend fun setWifiOnlyDownload(on: Boolean) {
        context.dataStore.edit { it[wifiOnlyKey] = on }
    }

    /**
     * 母带仅在 Wi-Fi 下播放。**默认关**。
     *
     * 关掉＝流量也照播母带：一首 130–210MB、5.4Mbps，听十首就是一个 G。
     * 但这是明确的产品选择 —— 用户要的是「别替我降质」，不是「替我心疼流量」。
     * 心疼流量的自己去设置打开，打开后移动网络下自动用 GD 无损（≈35MB/首）。
     */
    val masterWifiOnly: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[masterWifiOnlyKey] ?: false
    }

    suspend fun setMasterWifiOnly(on: Boolean) {
        context.dataStore.edit { it[masterWifiOnlyKey] = on }
    }

    /** 歌词时间轴偏移（毫秒）。正值 = 歌词提前。对不准时手动调 */
    val lyricOffsetMs: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[lyricOffsetKey] ?: LYRIC_OFFSET_DEFAULT_MS
    }

    suspend fun setLyricOffset(ms: Long) {
        context.dataStore.edit { it[lyricOffsetKey] = ms }
    }

    /**
     * 歌词字号倍率。1.0 = 默认（当前行 13sp / 其它行 10sp）。
     *
     * 存倍率而不是绝对字号：歌词行的「当前行 / 相邻行 / 远处行」是**三级**字号，
     * 只有倍率能同时缩放三者并保住层级差。存绝对值就得分别存三个数，
     * 而且以后改默认值时旧设置会错乱。
     */
    val lyricFontScale: Flow<Float> = context.dataStore.data.map { prefs ->
        prefs[lyricFontKey]?.coerceIn(0.8f, 1.8f) ?: 1.0f
    }

    suspend fun setLyricFontScale(scale: Float) {
        context.dataStore.edit { it[lyricFontKey] = scale.coerceIn(0.8f, 1.8f) }
    }

    /** 均衡器开关 + 预设下标 */
    val eqEnabled: Flow<Boolean> = context.dataStore.data.map { it[eqEnabledKey] ?: false }
    val eqPreset: Flow<Int> = context.dataStore.data.map { it[eqPresetKey] ?: 0 }

    suspend fun setEqEnabled(on: Boolean) {
        context.dataStore.edit { it[eqEnabledKey] = on }
    }

    suspend fun setEqPreset(index: Int) {
        context.dataStore.edit { it[eqPresetKey] = index }
    }

    val fxBass: Flow<Int> = context.dataStore.data.map { it[fxBassKey] ?: 0 }
    val fxLoudness: Flow<Int> = context.dataStore.data.map { it[fxLoudnessKey] ?: 0 }

    suspend fun setFxBass(v: Int) { context.dataStore.edit { it[fxBassKey] = v.coerceIn(0, 1000) } }
    suspend fun setFxLoudness(v: Int) { context.dataStore.edit { it[fxLoudnessKey] = v.coerceIn(0, 1500) } }

    /**
     * 响度归一模式。
     *
     * track = 每首歌拉到同一响度；album = 按整张专辑的增益走（保留专辑内的强弱起伏）。
     * 默认 track：车机上切歌忽大忽小最难受。
     */
    val loudnessMode: Flow<String> = context.dataStore.data.map { it[loudnessModeKey] ?: "track" }

    suspend fun setLoudnessMode(mode: String) {
        context.dataStore.edit { it[loudnessModeKey] = if (mode in setOf("off", "track", "album")) mode else "track" }
    }

    /** 请求硬件offload；设备/格式不支持时回退普通输出，软件音效仍旁路。 */
    val audioOffload: Flow<Boolean> = context.dataStore.data.map { it[offloadKey] ?: false }

    /**
     * DSD 输出采样率：44100（默认）或 88200。
     *
     * DSD 的原生采样率是 2.8224 MHz，必须抽取到 PCM 采样率。
     * 44.1 kHz 是 CD 规格、兼容性最好；88.2 kHz 能留住更多高频（可到 40 kHz），
     * 但设备必须支持 88.2 kHz 输出 —— 不支持时会自己降级到 44.1。
     */
    val dsdOutRate: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[dsdOutRateKey]?.takeIf { it == 44100 || it == 88200 } ?: 44100
    }

    suspend fun setDsdOutRate(rate: Int) {
        context.dataStore.edit { it[dsdOutRateKey] = if (rate == 88200) 88200 else 44100 }
    }

    /**
     * DSD 播放时是否自动旁路均衡器。
     *
     * DSD 的码率极高，解码链已经吃满 CPU，再挂 EQ 会挤掉音频线程导致卡顿；
     * 而且 DSD 本身频谱延伸到 100 kHz 以上，EQ 那 5 个频点（60Hz~14kHz）
     * 对它的实际影响有限。用户设置**保留**，切回普通格式立刻恢复。
     */
    val dsdBypassEq: Flow<Boolean> = context.dataStore.data.map { it[dsdBypassEqKey] ?: true }

    suspend fun setDsdBypassEq(on: Boolean) {
        context.dataStore.edit { it[dsdBypassEqKey] = on }
    }

    suspend fun setAudioOffload(on: Boolean) {
        context.dataStore.edit {
            it[offloadKey] = on
            if (on) {
                it[pureAudioModeKey] = false
                it[usbHighFidelityKey] = false
            }
        }
    }

    /** 纯净PCM：不挂软件音效，优先float；实际格式取决于解码器和设备，不代表bit-perfect。 */
    val pureAudioMode: Flow<Boolean> = context.dataStore.data.map { it[pureAudioModeKey] ?: false }

    suspend fun setPureAudioMode(on: Boolean) {
        context.dataStore.edit {
            it[pureAudioModeKey] = on
            if (on) {
                it[offloadKey] = false
                it[usbHighFidelityKey] = false
            }
        }
    }

    val usbHighFidelity: Flow<Boolean> = context.dataStore.data.map { it[usbHighFidelityKey] ?: false }
    val audioInterruptionResume: Flow<Boolean> = context.dataStore.data.map { it[audioInterruptionResumeKey] ?: true }
    suspend fun setAudioInterruptionResume(on: Boolean) { context.dataStore.edit { it[audioInterruptionResumeKey] = on } }
    val callAutoResume: Flow<Boolean> = context.dataStore.data.map { it[callAutoResumeKey] ?: true }
    val truePeakEnabled: Flow<Boolean> = context.dataStore.data.map { it[truePeakEnabledKey] ?: false }
    val truePeakCeilingDb: Flow<Float> = context.dataStore.data.map {
        it[truePeakCeilingKey]?.takeIf(Float::isFinite)?.coerceIn(-6f, -0.5f) ?: -1f
    }
    val audioOutputMode: Flow<String> = context.dataStore.data.map {
        when {
            it[usbHighFidelityKey] == true -> "usb"
            it[pureAudioModeKey] == true -> "pure"
            it[offloadKey] == true -> "offload"
            else -> "processed"
        }
    }

    suspend fun setAudioOutputMode(mode: String) {
        require(mode in setOf("processed", "offload", "pure", "usb"))
        val resolved = com.kmusic.apkhd.playback.AudioModeDefaults.resolve(mode)
        context.dataStore.edit {
            it[offloadKey] = resolved.audioOffload
            it[pureAudioModeKey] = resolved.pureAudioMode
            it[usbHighFidelityKey] = resolved.usbHighFidelity
        }
    }
    suspend fun setUsbHighFidelity(on: Boolean) {
        if (on) setAudioOutputMode("usb") else context.dataStore.edit { it[usbHighFidelityKey] = false }
    }
    suspend fun setCallAutoResume(on: Boolean) { context.dataStore.edit { it[callAutoResumeKey] = on } }
    suspend fun setTruePeakEnabled(on: Boolean) { context.dataStore.edit { it[truePeakEnabledKey] = on } }
    suspend fun setTruePeakCeilingDb(value: Float) {
        require(value.isFinite())
        context.dataStore.edit { it[truePeakCeilingKey] = value.coerceIn(-6f, -0.5f) }
    }

    /**
     * 冷启动后自动接着播。
     *
     * 默认**关**：重开 App 时停在原位等用户按播放 —— 一进 App 就外放会吓人
     * （尤其在办公室/车里接蓝牙时）。想要「像没退出过一样」的用户可以自己打开。
     */
    val autoResumePlayback: Flow<Boolean> = context.dataStore.data.map { it[autoResumeKey] ?: false }

    suspend fun setAutoResumePlayback(on: Boolean) {
        context.dataStore.edit { it[autoResumeKey] = on }
    }

    /** 同设备短时蓝牙重连可选续播，仅服务存活时；默认关闭。 */
    val bluetoothAutoResume: Flow<Boolean> = context.dataStore.data.map { it[bluetoothAutoResumeKey] ?: false }

    suspend fun setBluetoothAutoResume(on: Boolean) {
        context.dataStore.edit { it[bluetoothAutoResumeKey] = on }
    }

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        when (prefs[themeKey]) {
            "dark" -> ThemeMode.DARK
            "light" -> ThemeMode.LIGHT
            else -> ThemeMode.SYSTEM
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { prefs ->
            prefs[themeKey] = when (mode) {
                ThemeMode.SYSTEM -> "system"
                ThemeMode.DARK -> "dark"
                ThemeMode.LIGHT -> "light"
            }
        }
    }

    val defaultQuality: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[qualityKey] ?: "master"
    }

    /** 播放缓存容量（GB），0 = 关闭 */
    val cacheSizeGb: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[cacheSizeKey] ?: 1
    }

    suspend fun setCacheSizeGb(v: Int) {
        context.dataStore.edit { it[cacheSizeKey] = v.coerceIn(0, 8) }
    }

    suspend fun setDefaultQuality(q: String) {
        context.dataStore.edit { it[qualityKey] = q }
    }

    /** Navidrome 凭据 (url, username, password)，未配置时为空串 */
    val navidrome: Flow<Triple<String, String, String>> = context.dataStore.data.map { prefs ->
        Triple(prefs[ndUrlKey] ?: "", prefs[ndUserKey] ?: "", prefs[ndPassKey] ?: "")
    }

    /** WebDAV：url + 用户名 + 密码。密码只存本地，不写日志 */
    val webdav: Flow<Triple<String, String, String>> = context.dataStore.data.map { prefs ->
        Triple(prefs[davUrlKey] ?: "", prefs[davUserKey] ?: "", prefs[davPassKey] ?: "")
    }

    suspend fun saveWebDav(url: String, user: String, pass: String) {
        context.dataStore.edit { prefs ->
            if (url.isBlank()) {
                prefs.remove(davUrlKey); prefs.remove(davUserKey); prefs.remove(davPassKey)
            } else {
                prefs[davUrlKey] = url.trim()
                prefs[davUserKey] = user.trim()
                prefs[davPassKey] = pass
            }
        }
    }

    /** 自定义 UA；空串 = 用默认的 KMusic/x.y.z (Android) */
    val davUa: Flow<String> = context.dataStore.data.map { it[davUaKey] ?: "" }

    suspend fun saveDavUa(ua: String) {
        context.dataStore.edit { prefs ->
            if (ua.isBlank()) prefs.remove(davUaKey) else prefs[davUaKey] = ua.trim()
        }
    }

    suspend fun saveNavidrome(url: String, user: String, pass: String) {
        context.dataStore.edit { prefs ->
            if (url.isBlank() || user.isBlank()) {
                prefs.remove(ndUrlKey); prefs.remove(ndUserKey); prefs.remove(ndPassKey)
            } else {
                prefs[ndUrlKey] = url.trim()
                prefs[ndUserKey] = user.trim()
                prefs[ndPassKey] = pass
            }
        }
    }

    suspend fun clearNavidrome() {
        context.dataStore.edit { prefs ->
            prefs.remove(ndUrlKey); prefs.remove(ndUserKey); prefs.remove(ndPassKey)
        }
    }

    /** 搜索历史（最新在前，最多 20 条，逗号分隔） */
    val searchHistory: Flow<List<String>> = context.dataStore.data.map { prefs ->
        prefs[searchHistoryKey]?.split('\u0001')?.filter { it.isNotBlank() } ?: emptyList()
    }

    suspend fun addSearchHistory(keyword: String) {
        val k = keyword.trim()
        if (k.isEmpty()) return
        context.dataStore.edit { prefs ->
            val cur = prefs[searchHistoryKey]?.split('\u0001')?.filter { it.isNotBlank() } ?: emptyList()
            val next = (listOf(k) + cur.filter { it != k }).take(20)
            prefs[searchHistoryKey] = next.joinToString("\u0001")
        }
    }

    suspend fun removeSearchHistory(keyword: String) {
        context.dataStore.edit { prefs ->
            val cur = prefs[searchHistoryKey]?.split('\u0001')?.filter { it.isNotBlank() } ?: emptyList()
            prefs[searchHistoryKey] = cur.filter { it != keyword }.joinToString("\u0001")
        }
    }

    suspend fun clearSearchHistory() {
        context.dataStore.edit { it.remove(searchHistoryKey) }
    }
}
