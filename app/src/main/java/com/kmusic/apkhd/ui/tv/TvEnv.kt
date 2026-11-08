package com.kmusic.apkhd.ui.tv

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * TV（遥控器）模式判定与全局开关。
 *
 * ## 为什么要单独判定，而不是「没有触摸屏就是 TV」
 *
 * 实测 Android TV x86 镜像（android-34, tv_1080p）**仍然声明 `android.hardware.touchscreen`**，
 * 所以 `!hasSystemFeature(FEATURE_TOUCHSCREEN)` 这条路在模拟器上直接把 TV 判成手机 —— 不能这么写。
 *
 * 可靠信号是 `FEATURE_LEANBACK` / `FEATURE_TELEVISION`：
 *  - 真机电视盒子（小米盒子、Shield、Google TV）两个都有；
 *  - 手机 / 车机都没有（车机可能有 `android.hardware.type.automotive`，那是另一条路）。
 *
 * ## 为什么不直接用 UiModeManager
 *
 * `Configuration.uiMode & UI_MODE_TYPE_MASK == UI_MODE_TYPE_TELEVISION` 是系统给的答案，
 * 但它跟着「系统设置里有没有声明 TV」走，个别魔改 ROM 不一定准。这里两条都看：
 * uiMode 命中 **或** 有 leanback 特性，就按 TV 处理。
 */
object TvEnv {

    /**
     * 只读一次并缓存：`PackageManager.hasSystemFeature` 是 binder 调用，
     * 放在组合里每帧问一遍没有意义。
     */
    @Volatile
    private var cached: Boolean? = null

    fun isTv(context: Context): Boolean {
        cached?.let { return it }
        val tv = runCatching {
            val pm = context.packageManager
            val leanback = pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
            val television = pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
            // uiMode 作为补充信号：某些 TV 固件只声明 uiMode 不声明 leanback
            val uiModeTv = (context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_TYPE_MASK) ==
                android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
            leanback || television || uiModeTv
        }.getOrDefault(false)
        cached = tv
        return tv
    }
}

/**
 * 当前是否处于 TV 模式。
 *
 * 所有 TV 专属的视觉（焦点描边、放大）和交互（DPAD 快捷键）都必须先问它，
 * 这样手机 / 车机的渲染路径一个字节都不变 —— 这是「不影响现有设备」的硬保证。
 */
val LocalIsTv: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { false }

/** 组合里取：`val isTv = isTvMode()` */
@Composable
fun isTvMode(): Boolean {
    val context = LocalContext.current
    return TvEnv.isTv(context)
}

/**
 * TV 上控件普遍偏小（手机 44dp 的按钮在 3 米外就是一个小点），
 * 这些尺寸只在 TV 模式下叠加，不影响手机。
 */
object TvDimens {
    /** 焦点描边宽度 */
    val focusBorder: Dp = 3.dp

    /** 焦点外扩（描边画在控件外侧，不挤占内容） */
    val focusPadding: Dp = 3.dp

    /** 获得焦点时的放大倍率 —— 1.06 足够看出「选中了」，又不会把相邻控件挤走 */
    const val FOCUS_SCALE: Float = 1.06f

    /** 进度条左右键一次跳多少毫秒 */
    const val SEEK_STEP_MS: Long = 5_000L
}
