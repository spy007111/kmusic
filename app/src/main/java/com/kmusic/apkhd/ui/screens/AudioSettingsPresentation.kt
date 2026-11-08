package com.kmusic.apkhd.ui.screens

/** Display copy only; runtime capability filtering and mode defaults belong to AudioModeDefaults. */
object AudioSettingsPresentation {
    data class Mode(val key: String, val label: String, val description: String)

    private val modes = listOf(
        Mode("processed", "日常播放（推荐）", "适合日常听歌，可使用均衡器和音效方案。"),
        Mode("offload", "省电播放", "设备支持时减少耗电，暂不使用音效。"),
        Mode("pure", "原声播放", "暂不使用音效，按歌曲原有声音播放。"),
        Mode("usb", "USB高保真", "连接支持的USB音频设备时使用。")
    )

    fun currentMode(key: String): Mode = modes.firstOrNull { it.key == key } ?: modes.first()
    fun advancedItems(): List<String> = listOf("playback-mode")
}
