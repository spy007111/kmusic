package com.kmusic.apkhd.ui.components

import kotlin.math.abs
import kotlin.math.roundToInt

import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.delay

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kmusic.apkhd.ui.theme.frosted
import com.kmusic.apkhd.ui.theme.window
import com.kmusic.apkhd.ui.theme.moyinColors
import com.kmusic.apkhd.ui.theme.SideRailWidth
import com.kmusic.apkhd.ui.theme.wdp
import com.kmusic.apkhd.ui.theme.wsp
import com.kmusic.apkhd.ui.theme.wspSp
import com.kmusic.apkhd.ui.tv.tvFocus

/**
 * 按压反馈：scale(0.97) + 100ms。
 * 设计稿中所有可点击元素都必须有按压态。
 */
@Composable
fun Modifier.pressScale(
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "pressScale")
    return this
        .scale(scale)
        .tvFocus()
        .clickable(
            enabled = enabled,
            interactionSource = interaction,
            indication = null,
            onClick = onClick
        )
}

/**
 * 底部 Tab 栏 —— 玻璃拟态（半透明 + 模糊由 Android 系统背景提供）。
 */
/**
 * 玻璃面板：半透明白底 + 顶部亮、底部暗的高光边。
 *
 * ⚠️ 它靠「半透明」让背后的东西透上来才有玻璃感。背后必须是**有内容**的地方
 * （图片、模糊封面、彩色卡片）；背后是纯色时看起来只是一块浅色圆角，白做。
 */
@Composable
fun GlassBox(
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(24.wdp),
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit
) {
    val c = moyinColors()
    Box(
        modifier
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = if (c.isDark) 0.15f else 0.66f),
                        Color.White.copy(alpha = if (c.isDark) 0.05f else 0.40f)
                    )
                )
            )
            .border(
                width = 1.wdp,
                brush = Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = if (c.isDark) 0.34f else 0.95f),
                        Color.White.copy(alpha = 0.03f)
                    )
                ),
                shape = shape
            ),
        content = content
    )
}


/**
 * 大标题 —— 深浅两套字体。
 *
 * 深色（A · 暗夜流光）：无衬线、负字距，现代播放器的表达。
 * 浅色（B · 纸感编辑）：衬线、正字距，像唱片内页的标题。
 */
@Composable
fun MoyinTitle(
    text: String,
    modifier: Modifier = Modifier,
    size: Int = 30,
    color: Color? = null
) {
    val c = moyinColors()
    Text(
        text = text,
        modifier = modifier,
        color = color ?: c.t1,
        fontSize = size.wsp,
        fontFamily = if (c.titleSerif) FontFamily.Serif else FontFamily.SansSerif,
        fontWeight = if (c.titleSerif) FontWeight.Bold else FontWeight.SemiBold,
        letterSpacing = if (c.titleSerif) 0.sp else (-0.8).sp,
        lineHeight = (size * 1.18f).roundToInt().wsp
    )
}

/**
 * 卡片容器。
 *
 * 深色：玻璃质感（半透明 + 高光边） + 20dp 圆角。
 * 浅色：纸面（近白）+ 可见细线 + 6dp 圆角 —— 纸感靠「线」而不是「影」撑起来。
 */
@Composable
fun MoyinCard(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit
) {
    val c = moyinColors()
    val shape = RoundedCornerShape(c.radiusCard)
    Box(
        modifier
            .clip(shape)
            .background(if (c.isDark) c.glass else c.s1)
            .border(
                width = 1.wdp,
                color = c.glassBorder,
                shape = shape
            ),
        content = content
    )
}

/**
 * 列表分割线。
 *
 * 深色几乎不可见（只有 12% 白）—— 靠间距分组；
 * 浅色是一条实线 —— 这是 B 套最核心的视觉语言，不能省。
 */
@Composable
fun MoyinDivider(modifier: Modifier = Modifier) {
    val c = moyinColors()
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(if (c.isDark) c.line else c.lineStrong.copy(alpha = 0.16f))
    )
}

/** 列表编号：深色灰、浅色砖红衬线 —— B 套的「01 / 02」 */
@Composable
fun MoyinNum(index: Int, modifier: Modifier = Modifier) {
    val c = moyinColors()
    Text(
        text = (index + 1).toString().padStart(2, '0'),
        modifier = modifier,
        color = c.numeral,
        fontSize = if (c.titleSerif) 13.wsp else 11.wsp,
        fontFamily = if (c.titleSerif) FontFamily.Serif else FontFamily.SansSerif,
        fontWeight = if (c.titleSerif) FontWeight.Bold else FontWeight.Normal,
        letterSpacing = if (c.titleSerif) 0.sp else 0.6.sp
    )
}

@Composable
fun MoyinTabBar(
    items: List<TabItem>,
    current: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val c = moyinColors()

    val row: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.wdp, vertical = 2.wdp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { i, item ->
                val on = i == current
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .tvFocus()
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { onSelect(i) }
                        .padding(vertical = 10.wdp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    MoyinIcon(
                        icon = if (on) item.activeIcon else item.icon,
                        tint = if (on) c.t1 else c.t2,
                        size = 22.wdp
                    )
                    Spacer(Modifier.height(4.wdp))
                    Text(
                        text = item.label,
                        color = if (on) c.t1 else c.t2,
                        fontSize = 10.wsp,
                        fontFamily = if (c.titleSerif) FontFamily.Serif else FontFamily.SansSerif,
                        letterSpacing = if (c.titleSerif) 1.2.sp else 0.sp,
                        maxLines = 1
                    )
                }
            }
        }
    }

    // 不再画那条 1dp 实线。
    // 浅色下 lineStrong 几乎纯黑，横在底栏上像一条黑杠，和车机自带的导航栏观感不统一；
    // 深色那条 45% 白线也一起去掉。分层改由「玻璃面板 + 顶部 1px 亮边」完成。
    Column(
        modifier
            .fillMaxWidth()
            // 底栏接近不透明：Android 10 没有真模糊，透太多会把下面的歌名"糊"上来。
            // 纤维纹理 + 顶部亮边已经足够表达材质，可读性优先。
            .frosted(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
                alpha = if (c.isDark) 0.94f else 0.97f,
                // 与页面严格同色：分层只靠纤维纹理 + 顶部亮边，不靠色阶
                base = c.bg
            )
    ) {
        Box(Modifier.fillMaxWidth()) { row() }
    }
}

data class TabItem(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val activeIcon: androidx.compose.ui.graphics.vector.ImageVector = icon
)

/**
 * 平板 / 折叠屏展开时的竖向导航栏（替代底部 MoyinTabBar）。
 *
 * 为什么需要：宽屏下底部 Tab 有两个问题 ——
 *   1. 离手远（平板横屏时拇指够不到底边）；
 *   2. 内容区会横跨 1000dp+，单列列表显得极空。
 * 竖排放在左侧后，内容区宽度收窄，同时把底部空间完整让给内容 + 迷你播放条。
 *
 * 视觉与 MoyinTabBar 同源（同样的图标 / 文案 / 选中态），只是排布方向不同。
 */
@Composable
fun MoyinSideRail(
    items: List<TabItem>,
    current: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val c = moyinColors()

    Row(modifier = modifier) {
        Column(
            modifier = Modifier
                .width(SideRailWidth)
                .fillMaxHeight()
                .frosted(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
                    alpha = if (c.isDark) 0.94f else 0.97f,
                    base = c.bg
                )
                .padding(vertical = 10.wdp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            items.forEachIndexed { i, item ->
                val on = i == current
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.wdp, vertical = 2.wdp)
                        .clip(RoundedCornerShape(c.radiusCard))
                        .background(if (on) c.glass else Color.Transparent)
                        .tvFocus(shape = RoundedCornerShape(c.radiusCard))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { onSelect(i) }
                        .padding(vertical = 11.wdp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    MoyinIcon(
                        icon = if (on) item.activeIcon else item.icon,
                        tint = if (on) c.t1 else c.t2,
                        size = 22.wdp
                    )
                    Spacer(Modifier.height(5.wdp))
                    Text(
                        text = item.label,
                        color = if (on) c.t1 else c.t2,
                        fontSize = 11.wsp,
                        fontFamily = if (c.titleSerif) FontFamily.Serif else FontFamily.SansSerif,
                        letterSpacing = if (c.titleSerif) 1.0.sp else 0.sp,
                        maxLines = 1
                    )
                }
            }
        }
        // 右侧分隔线：让侧栏与内容区有明确边界（暗色主题下尤其需要）
        Box(
            Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(
                    if (c.isDark) c.glassBorder.copy(alpha = 0.45f)
                    else c.lineStrong.copy(alpha = 0.35f)
                )
        )
    }
}

/**
 * 图标封装 —— 颜色由 tint 传入。
 *
 * ⚠️ 不用 Material3 的 Icon：它用 colorFilter 着色，只对 fill 路径生效，
 * 对 stroke 路径无效（会保持路径里写死的颜色）。这里用 Brush 直接着色，
 * fill 和 stroke 路径都能正确跟随 tint。
 */
@Composable
fun MoyinIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    size: Dp = 18.wdp,
    modifier: Modifier = Modifier
) {
    Image(
        imageVector = icon,
        contentDescription = null,
        modifier = modifier.size(size),
        // Brush 着色同时作用于 fill 与 stroke（路径内颜色必须是 Color.Unspecified）
        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(tint)
    )
}

/**
 * 圆形图标按钮 —— 导航栏用，无底色仅图标。
 */
@Composable
fun IconCircleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 30.dp,
    background: Color? = null
) {
    val c = moyinColors()
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(99.dp))
            .then(
                if (background != null) Modifier.background(background)
                else Modifier
            )
            .tvFocus()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick },
        contentAlignment = Alignment.Center
    ) {
        MoyinIcon(icon = icon, tint = c.t2, size = size * 0.45f)
    }
}

/**
 * 主操作按钮 —— 实心 indigo；次按钮为深面无边。
 */
@Composable
fun MoyinButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    val c = moyinColors()
    Row(
        modifier = modifier
            .height(44.wdp)
            .clip(RoundedCornerShape(99.dp))
            .then(
                if (primary) Modifier.background(c.accentFill)
                else Modifier.background(c.s2)
            )
            .pressScale { onClick() }
            .padding(horizontal = 20.wdp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            MoyinIcon(
                icon = icon,
                tint = if (primary) c.onAccent else c.t1,
                size = 17.wdp
            )
            Spacer(Modifier.width(8.wdp))
        }
        Text(
            text = text,
            color = if (primary) c.onAccent else c.t1,
            fontSize = 14.wsp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 开关 —— 设计稿样式：打开 indigo，关闭中性灰。
 */
@Composable
fun MoyinSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val c = moyinColors()
    val offset by animateFloatAsState(if (checked) 1f else 0f, label = "sw")
    Box(
        modifier = modifier
            .width(44.wdp)
            .height(26.wdp)
            .clip(RoundedCornerShape(99.dp))
            .background(if (checked) c.accent else Color(0x24000000).let { if (c.isDark) Color(0x24FFFFFF) else it })
            .tvFocus(shape = RoundedCornerShape(99.dp))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onCheckedChange(!checked) }
    ) {
        Box(
            Modifier
                .padding(horizontal = 3.wdp, vertical = 3.wdp)
                .size(20.wdp)
                .clip(RoundedCornerShape(99.dp))
                .background(Color.White)
                .then(
                    if (offset > 0.5f) Modifier.offset(x = 21.wdp) else Modifier
                )
        )
    }
}

/**
 * 分组标题。
 *
 * [trailing] 放标题行右侧的操作（如「换一批」刷新按钮）。
 * 用 Row + weight 把 Text 挤到左边，trailing 贴右 —— 不要用
 * `Modifier.align(Alignment.End)`，那是 Box 作用域的，Row 里不生效。
 */
@Composable
fun SectionHeader(
    text: String,
    hint: String = "",
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    val c = moyinColors()
    Row(
        modifier = modifier.padding(vertical = 12.wdp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Text(
            // B 套（浅色）用衬线 + 实色，像杂志小节；A 套（深色）保持小号大写灰字
            text = if (c.titleSerif) text else text.uppercase(),
            color = if (c.titleSerif) c.t1 else c.t3,
            fontSize = if (c.titleSerif) 13.wsp else 12.wsp,
            fontFamily = if (c.titleSerif) FontFamily.Serif else FontFamily.SansSerif,
            fontWeight = if (c.titleSerif) FontWeight.Bold else FontWeight.Normal,
            letterSpacing = if (c.titleSerif) 1.2.sp else 0.3.wspSp
        )
        if (hint.isNotEmpty()) {
            Spacer(Modifier.width(8.wdp))
            Text(
                text = hint,
                color = c.accent,
                fontSize = 10.wsp,
                modifier = Modifier
                    .clip(RoundedCornerShape(99.dp))
                    .background(c.accentDim)
                    .padding(horizontal = 6.wdp, vertical = 1.wdp)
            )
        }
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            trailing()
        }
    }
}

/**
 * 均衡器频段滑块。
 *
 * 两套布局，同一份数据、同一套回调：
 *
 * - **宽屏（车机 / Pad / 折叠屏展开）→ 横向**：每个频段一行，
 *   「频率标签 │ 横向滑条 │ 实时 dB」。行程能到 300dp 以上，
 *   ±15dB 全程拖得动；再配一条 0dB 中位刻度，一眼看出偏了多少。
 * - **竖屏手机 → 竖排**：Material 的横向 Slider 旋转 -90° 立起来。
 *
 * ⚠️ **竖排这里有个必须用 `requiredWidth` 的坑**（v2.2.3 修）：
 * Slider 旋转前是「宽 = 行程、高 = 粗细」。它被放进 `Box(width = 24.wdp)` 里时，
 * 父容器的最大宽度约束会把 `Modifier.width(150.wdp)` **压成 24dp** ——
 * 旋转后行程就只剩 36px，±15dB 摊到 30 个值时每 dB 只有 1.2px，
 * 手指根本落不到想要的数字上（实测 uiautomator 报的触控区就是 66×70px）。
 * `requiredWidth` 会**忽略父约束**，行程才是真的 190dp（285px，每 dB ≈ 9.5px）。
 *
 * 为什么竖排不改成横排：竖排一屏能同时看到 5 段的相对形状（就是 EQ 曲线的意思），
 * 横排要 5 行 × 48dp 高，面板会被撑得很长。行程够长之后竖排是能拖准的。
 */
@Composable
fun EqBandSliders(
    bands: List<Int>,
    freqs: List<Int>,
    range: IntArray,
    onBandChange: (Int, Int) -> Unit
) {
    val c = moyinColors()
    val lo = (range.getOrNull(0) ?: -1500).toFloat()
    val hi = (range.getOrNull(1) ?: 1500).toFloat()
    val wide = window.isWide && window.widthDp > window.heightDp

    if (wide) {
        EqBandSlidersHorizontal(bands, freqs, lo, hi, onBandChange)
        return
    }

    // 竖排的行程：之前 150dp 被父约束压成 24dp，实测只有 36px。
    // 现在用 requiredWidth 让行程真的到 190dp，同时把整个可触区加宽到 48dp
    // （M3 Slider 的最小交互尺寸就是 48dp，比它窄还会被系统再压一次）。
    val travel = 190.wdp

    // 拖动读数：拖的时候盖在这一排滑块顶上，松手后停留 1.2s 再消失。
    var dragIdx by remember { mutableIntStateOf(-1) }
    var dragLevel by remember { mutableIntStateOf(0) }
    var dragActive by remember { mutableStateOf(false) }
    var idleTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(dragActive, idleTick) {
        if (!dragActive && dragIdx >= 0) {
            delay(1200)
            dragIdx = -1
        }
    }

    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.wdp, vertical = 6.wdp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            bands.forEachIndexed { i, level ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "%+.0f".format(level / 100f),
                        color = if (level == 0) c.t3 else c.accent,
                        fontSize = 11.wsp,
                        fontWeight = if (level == 0) FontWeight.Normal else FontWeight.Medium
                    )
                    VerticalBandSlider(
                        idx = i,
                        level = level,
                        lo = lo,
                        hi = hi,
                        height = travel,
                        onChange = { onBandChange(i, it) },
                        onDrag = { _, v, active ->
                            dragIdx = i
                            dragLevel = v
                            dragActive = active
                            if (!active) idleTick++
                        }
                    )
                    Text(
                        text = formatBandFreq(freqs.getOrNull(i) ?: 0),
                        color = c.t3,
                        fontSize = 9.wsp
                    )
                }
            }
        }
        if (dragIdx >= 0) {
            // 贴着面板顶部居中，不挡手柄
            Box(Modifier.align(Alignment.TopCenter)) {
                EqReadoutChip(freqs.getOrNull(dragIdx) ?: 0, dragLevel)
            }
        }
    }
}

/**
 * 自绘的竖向频段滑块。
 *
 * **为什么不用旋转的 Material Slider**（v2.2.3 换掉）：
 * 旋转方案有两个绕不过去的问题 ——
 * 1. 布局约束会把行程压扁（`width()` 顺从父 Box，得靠 `requiredWidth` 才顶得住）；
 * 2. **旋转之后 thumb 的命中区域和视觉位置对不上**。实测点击轨道 y=983（理论 +6dB）
 *    落到 +14dB。graphicsLayer 的旋转只改绘制，pointerInput 的坐标变换是另一套。
 *
 * **为什么按下不再直接改值**（v2.3.2 二改）：
 * 旧实现 `awaitFirstDown()` 之后立刻 `dragValue = valueAt(y)` —— 手指落在这一列任意位置
 * 都会当场改曲线，而这一列 48dp 宽、190dp 高，5 列几乎铺满面板，面板又能上下滚。
 * 想滚面板 → 手指落在滑块上 → 音效曲线被改，用户甚至不知道。
 * 现在**只有按在手柄命中区（48×44dp）里才算抓到手柄**；没抓到就一个事件都不消费，
 * 面板照常滚动。抓到之后事件全部 consume，面板不会跟着一起滚。
 * 抓取时保留抓取偏移并随移动衰减，避免"一按手柄就跳 1.7dB"。
 */
@Composable
private fun VerticalBandSlider(
    idx: Int,
    level: Int,
    lo: Float,
    hi: Float,
    height: Dp,
    onChange: (Int) -> Unit,
    onDrag: (Int, Int, Boolean) -> Unit = { _, _, _ -> }
) {
    val c = moyinColors()
    val span = (hi - lo).coerceAtLeast(1f)
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(level.toFloat()) }
    // ⚠️ pointerInput 只以 (lo,hi,height) 为 key，闭包里直接读 `level` 会**永远读到首次组合的旧值**：
    // 值改到 +14 后手柄已经移到顶部，命中判定却还按 +4 的位置算，于是「抓不到手柄 → 拖不动」。
    // 用 rememberUpdatedState 把最新值带进手势闭包。
    val latestLevel by rememberUpdatedState(level)
    val shown = if (dragging) dragValue else level.toFloat()
    val shownFrac = ((shown - lo) / span).coerceIn(0f, 1f)

    val trackW = 10.wdp
    val thumbH = 26.wdp
    val thumbW = 40.wdp
    // 命中区比视觉手柄大一圈：手指按不准 26dp 高的圆角条
    val hitHalfH = 22.wdp
    val lastTap = remember { mutableLongStateOf(0L) }

    Box(
        modifier = Modifier
            .width(48.wdp)
            .height(height)
            // ⚠️ 只能有一个 pointerInput。之前 tap 和 drag 分成两个 pointerInput，
            // 两个协程同时从同一个 PointerInputScope 收事件、谁先返回谁吃掉 ——
            // 结果就是「点一下生效、再点就没反应」，日志里数值忽大忽小。
            .pointerInput(lo, hi, height) {
                val total = size.height.toFloat().coerceAtLeast(1f)
                val halfHitPx = hitHalfH.toPx()
                fun valueAt(y: Float): Int {
                    val f = (1f - (y / total)).coerceIn(0f, 1f)   // 顶部 = hi
                    val raw = lo + f * span
                    return ((raw / 100f).roundToInt() * 100).coerceIn(lo.toInt(), hi.toInt())
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val curFrac = ((if (dragging) dragValue else latestLevel.toFloat()) - lo) / span
                    val center = total * (1f - curFrac.coerceIn(0f, 1f))
                    if (abs(down.position.y - center) > halfHitPx) {
                        // 没抓到手柄：不消费、不改值，事件留给面板滚动
                        return@awaitEachGesture
                    }
                    dragging = true
                    dragValue = latestLevel.toFloat()
                    onDrag(idx, latestLevel, true)
                    var offset = down.position.y - center
                    var maxDy = 0f
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (ch.pressed) {
                            maxDy = maxOf(maxDy, abs(ch.position.y - down.position.y))
                            val v = valueAt(ch.position.y - offset)
                            offset *= 0.82f   // 抓取偏移快速衰减 → 很快变成绝对跟随
                            if (v != dragValue.toInt()) {
                                dragValue = v.toFloat()
                                onChange(v)
                            }
                            onDrag(idx, dragValue.toInt(), true)
                            ch.consume()
                        } else {
                            ch.consume()
                            break
                        }
                    }
                    dragging = false
                    if (maxDy <= viewConfiguration.touchSlop) {
                        // 只是点了一下手柄：双击复位到 0 dB
                        val now = System.currentTimeMillis()
                        if (now - lastTap.longValue < 320L) {
                            if (latestLevel != 0) onChange(0)
                            lastTap.longValue = 0L
                        } else {
                            lastTap.longValue = now
                        }
                    }
                    onDrag(idx, dragValue.toInt(), false)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // 轨道底：整条灰色
        Box(
            Modifier
                .fillMaxHeight()
                .width(trackW)
                .clip(RoundedCornerShape(trackW / 2))
                .background(c.t3.copy(alpha = 0.16f))
        )
        // 已填充段（从 0dB 到当前值，或从底部到当前值）
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // 全程用 Dp 算：换算成 px 再转回来会引入密度误差，
            // 而且 BoxWithConstraintsScope 里的 maxHeight 本来就是 Dp。
            val total = maxHeight
            val zeroFrac = ((0f - lo) / span).coerceIn(0f, 1f)
            val top = maxOf(shownFrac, zeroFrac)
            val bottom = minOf(shownFrac, zeroFrac)
            Box(
                Modifier
                    // ⚠️ 必须 TopStart + 纯 offset。用 CenterStart 时 align 会先把
                    // 组件垂直居中，offset 再叠一半 —— 所有绘制位置整体偏半个高度，
                    // 于是"视觉 0dB"和"手势算出的 0dB"差 157px，怎么调都对不齐。
                    .align(Alignment.TopStart)
                    .offset(y = total * (1f - top))
                    .height((total * (top - bottom)).coerceAtLeast(0.dp))
                    .width(trackW)
                    .clip(RoundedCornerShape(trackW / 2))
                    .background(c.accent)
            )
        }
        // 0dB 中位刻线
        if (lo < 0 && hi > 0) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val total = maxHeight
                val zeroFrac = ((0f - lo) / span).coerceIn(0f, 1f)
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .offset(y = total * (1f - zeroFrac) - 0.5.dp)
                        .fillMaxWidth()
                        .height(1.wdp)
                        .background(c.t3.copy(alpha = 0.5f))
                )
            }
        }
        // thumb：抓到手柄才跟手
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val total = maxHeight
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .offset(y = total * (1f - shownFrac) - thumbH / 2)
                    .width(thumbW)
                    .height(thumbH)
                    .clip(RoundedCornerShape(thumbH / 2))
                    .background(if (c.isDark) Color(0xFF2A2A2E) else Color.White)
                    .border(
                        width = if (dragging) 3.wdp else 2.wdp,
                        color = if (dragging) c.accent else c.t3.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(thumbH / 2)
                    )
            )
        }
    }
}

/**
 * 频段拖动读数浮层：拖动时盖在整排滑块上方，显示「60Hz · +11 dB」。
 *
 * 为什么要有它：手指按在手柄上会把 11sp 的小数字盖住，光看手柄位置判断不出拖到哪；
 * 而且 ±15dB 摊在 190dp 上，1dB ≈ 6.3dp，靠肉眼对位置本来就不准。
 */
@Composable
private fun EqReadoutChip(freq: Int, level: Int) {
    val c = moyinColors()
    Box(
        Modifier
            .clip(RoundedCornerShape(99.dp))
            .background(if (c.isDark) Color(0xE61E1E22) else Color(0xF2FFFFFF))
            .border(1.wdp, c.accent.copy(alpha = 0.45f), RoundedCornerShape(99.dp))
            .padding(horizontal = 12.wdp, vertical = 6.wdp)
    ) {
        Text(
            text = "${formatBandFreq(freq)} · %+.0f dB".format(level / 100f),
            color = c.accent,
            fontSize = 13.wsp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * 宽屏横向频段滑块：每频段一行 = 频率 │ 滑条 │ 增益值。
 *
 * 行程是竖排的 2 倍以上，配合 0dB 中位刻线，调 ±15dB 不用来回拖。
 * 和竖排一样是**自绘 + 只认手柄**：Material Slider 默认「点轨道就跳值」，
 * 在车机/平板上滚列表时同样会误改曲线，所以这里不用它。
 */
@Composable
private fun EqBandSlidersHorizontal(
    bands: List<Int>,
    freqs: List<Int>,
    lo: Float,
    hi: Float,
    onBandChange: (Int, Int) -> Unit
) {
    val c = moyinColors()
    var dragIdx by remember { mutableIntStateOf(-1) }
    var dragLevel by remember { mutableIntStateOf(0) }
    var dragActive by remember { mutableStateOf(false) }
    var idleTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(dragActive, idleTick) {
        if (!dragActive && dragIdx >= 0) {
            delay(1200)
            dragIdx = -1
        }
    }

    Box {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.wdp, vertical = 6.wdp),
            verticalArrangement = Arrangement.spacedBy(2.wdp)
        ) {
            bands.forEachIndexed { i, level ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatBandFreq(freqs.getOrNull(i) ?: 0),
                        color = c.t3,
                        fontSize = 11.wsp,
                        modifier = Modifier.width(52.wdp)
                    )
                    HorizontalBandSlider(
                        idx = i,
                        level = level,
                        lo = lo,
                        hi = hi,
                        onChange = { v -> onBandChange(i, v) },
                        onDrag = { _, v, active ->
                            dragIdx = i; dragLevel = v; dragActive = active
                            if (!active) idleTick++
                        },
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "%+.0f dB".format(level / 100f),
                        color = if (level == 0) c.t3 else c.accent,
                        fontSize = 11.wsp,
                        modifier = Modifier.width(56.wdp)
                    )
                }
            }
        }
        if (dragIdx >= 0) {
            Box(Modifier.align(Alignment.TopCenter)) {
                EqReadoutChip(freqs.getOrNull(dragIdx) ?: 0, dragLevel)
            }
        }
    }
}

/**
 * 宽屏横向自绘滑块：40dp 高的可触区，轨道 6dp，手柄 26×34dp。
 * 只认手柄（横向命中区 ±22dp），点轨道不改值。
 */
@Composable
private fun HorizontalBandSlider(
    idx: Int,
    level: Int,
    lo: Float,
    hi: Float,
    onChange: (Int) -> Unit,
    onDrag: (Int, Int, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val c = moyinColors()
    val span = (hi - lo).coerceAtLeast(1f)
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(level.toFloat()) }
    // 同竖排：pointerInput 的 key 不含 level，必须用 rememberUpdatedState 才能拿到最新值
    val latestLevel by rememberUpdatedState(level)
    val shownFrac = (((if (dragging) dragValue else level.toFloat()) - lo) / span).coerceIn(0f, 1f)
    val trackH = 6.wdp
    val thumbW = 26.wdp
    val thumbH = 34.wdp
    val hitHalfW = 22.wdp
    val lastTap = remember { mutableLongStateOf(0L) }

    Box(
        modifier = modifier
            .height(44.wdp)
            .pointerInput(lo, hi) {
                val total = size.width.toFloat().coerceAtLeast(1f)
                val halfHitPx = hitHalfW.toPx()
                fun valueAt(x: Float): Int {
                    val f = (x / total).coerceIn(0f, 1f)
                    val raw = lo + f * span
                    return ((raw / 100f).roundToInt() * 100).coerceIn(lo.toInt(), hi.toInt())
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val curFrac = (((if (dragging) dragValue else latestLevel.toFloat()) - lo) / span)
                        .coerceIn(0f, 1f)
                    val center = total * curFrac
                    if (abs(down.position.x - center) > halfHitPx) return@awaitEachGesture

                    // 方向锁：面板是纵向滚动的，手指从手柄上起手往上下滑，是在滚面板
                    // 而不是调频段（实测过：滚面板时 14k 被蹭成 +3dB）。
                    // 这里先不动值，等第一次移动方向确定 —— 横向为主才算抓到手柄。
                    var dx = 0f
                    var dy = 0f
                    while (abs(dx) <= viewConfiguration.touchSlop &&
                        abs(dy) <= viewConfiguration.touchSlop
                    ) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                        if (!ch.pressed) {
                            // 没移动就抬手 = 点了一下手柄：双击复位 0 dB
                            val now = System.currentTimeMillis()
                            if (now - lastTap.longValue < 320L) {
                                if (latestLevel != 0) onChange(0)
                                lastTap.longValue = 0L
                            } else {
                                lastTap.longValue = now
                            }
                            return@awaitEachGesture
                        }
                        dx = ch.position.x - down.position.x
                        dy = ch.position.y - down.position.y
                    }
                    if (abs(dy) >= abs(dx)) return@awaitEachGesture

                    dragging = true
                    dragValue = latestLevel.toFloat()
                    onDrag(idx, latestLevel, true)
                    var offset = dx
                    var maxDx = 0f
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (ch.pressed) {
                            maxDx = maxOf(maxDx, abs(ch.position.x - down.position.x))
                            val v = valueAt(ch.position.x - offset)
                            offset *= 0.82f
                            if (v != dragValue.toInt()) {
                                dragValue = v.toFloat()
                                onChange(v)
                            }
                            onDrag(idx, dragValue.toInt(), true)
                            ch.consume()
                        } else {
                            ch.consume()
                            break
                        }
                    }
                    dragging = false
                    if (maxDx <= viewConfiguration.touchSlop) {
                        val now = System.currentTimeMillis()
                        if (now - lastTap.longValue < 320L) {
                            if (latestLevel != 0) onChange(0)
                            lastTap.longValue = 0L
                        } else {
                            lastTap.longValue = now
                        }
                    }
                    onDrag(idx, dragValue.toInt(), false)
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val total = maxWidth
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth()
                    .height(trackH)
                    .clip(RoundedCornerShape(trackH / 2))
                    .background(c.t3.copy(alpha = 0.16f))
            )
            if (lo < 0 && hi > 0) {
                val zeroFrac = ((0f - lo) / span).coerceIn(0f, 1f)
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = total * zeroFrac - 0.5.dp)
                        .width(1.wdp)
                        .height(18.wdp)
                        .background(c.t3.copy(alpha = 0.45f))
                )
            }
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = total * shownFrac - thumbW / 2)
                    .width(thumbW)
                    .height(thumbH)
                    .clip(RoundedCornerShape(thumbH / 3))
                    .background(if (c.isDark) Color(0xFF2A2A2E) else Color.White)
                    .border(
                        width = if (dragging) 3.wdp else 2.wdp,
                        color = if (dragging) c.accent else c.t3.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(thumbH / 3)
                    )
            )
        }
    }
}

/**
 * 频段中心频率标签。
 *
 * 输入是**真 Hz**（见 [AudioEffectHolder.bandFreqs]：平台返回 mHz，这里已除以 1000）。
 * 写法上避免出现 "140.0k" 这种尾巴带 .0 的丑标签：
 * 3.6k / 14k / 60 / 230。
 */
internal fun formatBandFreq(hz: Int): String = when {
    hz <= 0 -> "—"
    hz >= 1000 -> {
        val k = hz / 1000f
        if (k >= 10f) "${k.toInt()}k" else {
            val t = (k * 10f).toInt()
            if (t % 10 == 0) "${t / 10}k" else "${t / 10}.${t % 10}k"
        }
    }
    else -> "$hz"
}
