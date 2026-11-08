package com.kmusic.apkhd.ui.tv

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kmusic.apkhd.ui.theme.moyinColors


/**
 * TV 遥控焦点视觉 —— 整个 TV 适配的唯一入口。
 *
 * ## v2.4.2：从「描边外框」改成「主题色高亮」
 *
 * 用户反馈两件事：一是**框和内容错位**，二是**框不好看**，要求「选中加深颜色即可，
 * 颜色跟随主题色」。这条反馈是对的 —— 描边这套做法本身有三个结构性问题：
 *
 * 1. **`scale = 1.06` 的放大动画**：描边跟着内容一起缩放，而父级背景不动，
 *    框就永远比卡片大 6%，视觉上「飘在外面」；动画期间还在持续漂移。
 * 2. **描边画在节点边界上，节点边界 ≠ 视觉边界**：`ToolItem` 带着 `.padding(12.dp)`、
 *    列表行带着 `.padding(vertical = 8.dp)`、满宽行更是整整一条 1750px 宽的带子 ——
 *    框把这些透明留白全圈进去，看着就是「一个跟内容对不上的空框」。
 * 3. 满宽元素被框成一条长带，像「页面被套了个框」，不像「这一项被选中」。
 *
 * 现在改成**只叠一层主题色**，几何上零位移：
 * - 不再有描边 → 没有「框比内容大一圈」这回事。
 * - 不再有缩放 → 动画期间的漂移消失。
 * - 用元素自己的边界着色，父级背景不动 → 视觉上就是「这一项亮了」。
 *
 * ## 为什么用 `drawWithContent` 而不是 `background`
 *
 * `Modifier.background(...)` 排在内容之前绘制，会被内容自己的 `.background()` 覆盖 ——
 * v2.4.0 手绘描边「只剩上下两条横线」就是这个坑的另一面。`drawWithContent` 里
 * **先 `drawContent()` 再画高亮**，一定压在内容之上，不需要关心调用点的修饰符顺序。
 *
 * 高亮 alpha：深色主题 0.22、浅色 0.16 —— 浅色底上同样的 alpha 会发糊。
 * 满宽整行额外在左侧画一条 3dp 竖条当锚点（[fullWidth]）。
 *
 * ## 手机 / 车机
 *
 * `enabled = false` 时**整条链原样返回**：不注册监听、不加图层、不读颜色，
 * 渲染结果与改造前逐像素一致（MuMu 实测 `isTv=false`）。
 */
@Composable
fun Modifier.tvFocus(
    shape: Shape = RoundedCornerShape(8.dp),
    enabled: Boolean = LocalIsTv.current,
    /** 已废弃：v2.4.2 起不再缩放。保留参数只为不动 100+ 个调用点。 */
    scale: Float = 1f,
    /**
     * 满宽整行（设置项、列表行）传 true：额外在左侧画一条竖条当锚点。
     * 卡片/按钮类保持 false，纯色块足够。
     */
    fullWidth: Boolean = false
): Modifier {
    if (!enabled) return this
    val colors = moyinColors()
    var focused by remember { mutableStateOf(false) }
    // 深色主题下底色深，同样 alpha 更「亮」；浅色主题要收一点，否则发糊。
    val alpha = if (colors.isDark) 0.22f else 0.16f
    val highlight = colors.accent.copy(alpha = alpha)
    return this
        .onFocusChanged { focused = it.isFocused }
        .clip(shape)
        .drawWithContent {
            drawContent()
            if (!focused) return@drawWithContent
            drawRect(color = highlight)
            if (fullWidth) drawFocusBar(colors.accent)
        }
}

/**
 * 只覆盖**节点顶部一块矩形区域**的焦点高亮（v2.4.5）。
 *
 * ## 为什么需要它
 *
 * 用户反馈「选中色跟着整张卡走，跟卡片圆角视觉上不搭」。卡片是「封面 + 标题 + 曲目数」
 * 三行的 Column，[tvFocus] 按整列外接矩形上色：封面自己被 `clip()` 切了圆角，
 * 高亮却在圆角外面露出四个直角底色，文字区也不该被染色。
 *
 * 直接改结构（把 `tvFocus` 下沉到封面 Box）**不可行** —— 实测焦点直接丢失：
 * `tvFocus` 只画高亮、不带可聚焦语义，封面 Box 拿到 `focusable()` 后就成了
 * 嵌套焦点节点，外层 Column 的 `combinedClickable` 还在，方向键寻址分裂成两层。
 *
 * ## 做法
 *
 * 焦点节点**保持原样**（仍是整列 Column，[tvFocus] 的位置、`focusProperties`
 * 连线、点击/长按全部不变），只把**绘制范围**裁到顶部 [height]、圆角 [cornerRadius]。
 * 于是：封面位置/大小/圆角与高亮逐像素重合，标题文字区一点不染。
 *
 * 路径裁剪而不是 `clipRect`：圆角是必须的，`clipRect` 只会裁出直角。
 */
@Composable
fun Modifier.tvFocusArea(
    height: androidx.compose.ui.unit.Dp,
    cornerRadius: androidx.compose.ui.unit.Dp,
    enabled: Boolean = LocalIsTv.current
): Modifier {
    if (!enabled) return this
    val colors = moyinColors()
    var focused by remember { mutableStateOf(false) }
    val alpha = if (colors.isDark) 0.22f else 0.16f
    val highlight = colors.accent.copy(alpha = alpha)
    return this
        .onFocusChanged { focused = it.isFocused }
        .drawWithContent {
            drawContent()
            if (!focused) return@drawWithContent
            val h = height.toPx().coerceAtMost(size.height)
            val r = cornerRadius.toPx()
            val path = androidx.compose.ui.graphics.Path().apply {
                addRoundRect(
                    androidx.compose.ui.geometry.RoundRect(
                        androidx.compose.ui.geometry.Rect(0f, 0f, size.width, h),
                        androidx.compose.ui.geometry.CornerRadius(r, r)
                    )
                )
            }
            clipPath(path) { drawRect(color = highlight) }
        }
}

/**
 * 满宽行的左侧焦点竖条。
 *
 * 高度取元素高度的 60%（最少 20dp），垂直居中 —— 整条贴满会像分隔线，
 * 只占中段才像「这一行被点亮了」的锚点。
 */
private fun DrawScope.drawFocusBar(accent: Color) {
    val barW = 3.dp.toPx()
    val barH = (size.height * 0.6f).coerceAtLeast(20.dp.toPx())
    val top = (size.height - barH) / 2f
    drawRect(
        color = accent,
        topLeft = androidx.compose.ui.geometry.Offset(0f, top),
        size = androidx.compose.ui.geometry.Size(barW, barH)
    )
}

/**
 * 给「整页只有按键、没有可点控件」的页面用的根焦点。
 *
 * 播放页是典型场景：整页没有任何按钮需要遥控器选中（左右键是 seek、上下是切歌），
 * 但 Compose 的按键事件**只发给有焦点的节点及其祖先** —— 页内没人持焦点时，
 * 连 `onPreviewKeyEvent` 都不会被调用。所以根节点必须自己 `focusable()` 并主动
 * `requestFocus()`，按键才有地方落。
 *
 * 加 `focusable()` 而不加 `clickable()` 是刻意的：不要焦点框、不要按压态，
 * 只是把「按键的接收者」占住。
 *
 * [down] 给根节点指定「按下键去哪」。[FocusRequester] 比几何搜索可靠 ——
 * 播放页的控件在封面下方，几何搜索会先撞上那些不可聚焦的文字块，
 * 实测容易原地不动（用户感受就是「按下去没反应」）。
 */
fun Modifier.tvRootFocus(
    requester: androidx.compose.ui.focus.FocusRequester,
    enabled: Boolean,
    down: androidx.compose.ui.focus.FocusRequester? = null
): Modifier = if (!enabled) this else this
    .focusRequester(requester)
    .focusProperties { if (down != null) this.down = down }
    .focusable()

/**
 * 横向列表里**单个卡片**的焦点边界。
 *
 * ## 为什么不在列表容器上判断
 *
 * 第一版在 `LazyRow` 外层用 `onPreviewKeyEvent` 读 `LazyListState` 判断
 * 「是不是已经在末尾」，实测**失效**（焦点照样飞到行外的「换一批」/「收藏的歌单」）：
 * `firstVisibleItemIndex` 说的是「滚到哪了」，不是「焦点在第几张」——
 * 一屏能放下 3 张时，焦点在第 1 张而 firstVisibleItemIndex 已经是 2（末尾），
 * 判断条件瞬间为真/为假，两种情况都对不上。
 *
 * ## 正确做法：把边界钉在卡片上
 *
 * 每张卡自己知道「我是第几张、一共几张」，于是：
 * - 最后一张的 `right = Cancel`（再按右键，焦点原地不动，绝不飞出列表）
 * - 第一张**不钉左边界** —— 见下面 `left` 的注释
 *
 * `Cancel` 是 Compose 里「明确表示这里没有下一个焦点」的语义，
 * 比 `focusGroup` 的隐式边界可靠 —— 后者只管组内顺序，不管组边界处往外搜。
 *
 * ⚠️ v2.4.1 修正：以前 index 0 也写了 `left = Cancel`，本意是「别飞出横排」，
 * 结果把焦点**锁死在行内** —— 首页每行最左一张按左键毫无反应，
 * 只有滚到最底部、焦点落在不在 `LazyRow` 里的元素上，才能往左进导航栏。
 * 用户报的「首页要选到最下才能回导航栏」就是这个。左边界交给几何搜索即可。
 *
 * 手机 / 车机：`enabled=false` 时原样返回。
 */
@Composable
fun Modifier.tvRowItemEdge(
    index: Int,
    count: Int,
    enabled: Boolean = LocalIsTv.current
): Modifier {
    if (!enabled) return this
    return this.focusProperties {
        if (index >= count - 1) right = FocusRequester.Cancel
    }
}

/**
 * 横向列表（`LazyRow`）的焦点容器 —— 只做分组，不做边界。
 *
 * 边界交给 [tvRowItemEdge] 挂在每张卡上；这里只负责让左右键优先在组内移动，
 * 避免几何搜索直接跳到别的行。
 */
@Composable
fun Modifier.tvRowFocus(
    enabled: Boolean = LocalIsTv.current
): Modifier = if (!enabled) this else this
// 保持空实现 = 保持 v2.4.1 的行为。
//
// v2.4.2 一度改成 `this.focusGroup()`（觉得「空实现像漏写」），实测首页方向键
// 大面积失灵：`focusGroup` 会把整组当成**一个**焦点单元，组内子节点从焦点搜索里
// 摘掉，而这几个 LazyRow 外面还有 LazyColumn 要纵向走位，两者叠加后上下左右
// 常常找不到落点。横排「右键不飞出本行」由 [tvRowItemEdge]（末项 right = Cancel）
// 负责，不需要 focusGroup。

/**
 * 弹层（底部面板 / 抽屉）的焦点分组 + 上下边界。
 *
 * ## 为什么需要（v2.4.3）
 *
 * 用户报「队列 / 音效 / 详情，进去之后向上按焦点直接丢失」。根因是这几个面板
 * 只给遮罩和容器设了 `canFocus = false`，**没有把面板本身变成焦点组** ——
 * 焦点在面板首项按 ↑ 时，几何搜索会跳出面板、命中背后播放页里的控件
 * （比如顶栏的 ⋯）。那个高亮被遮罩盖着，用户看不到，感受就是「焦点没了」，
 * 而且从此方向键全无反应（焦点其实在遮罩背后）。
 *
 * `focusGroup()` 把面板变成一个焦点单元：组内移动照常走几何搜索，
 * 走到组边缘时**不会再往外搜**，焦点原地停住 —— 正是「按到顶就停」的期望。
 *
 * ## 为什么面板能用、首页的 LazyRow 不能用
 *
 * 面板是平铺 `Column`（`verticalScroll` 的也全部量过），组内每一项都是真实
 * 布局节点，几何搜索有完整坐标。首页那几行是 `LazyRow` 套在 `LazyColumn` 里，
 * 一旦标成组，卡片会被从焦点搜索里摘出去、上下走位全废（v2.4.2 踩过）。
 * 结论：**只给弹层容器用，横向懒加载列表禁止用**。
 */
@Composable
fun Modifier.tvPanelGroup(enabled: Boolean = LocalIsTv.current): Modifier =
    if (!enabled) this else this.focusGroup()

/**
 * 弹层里的「到顶 / 到底」边界：按到第一个就不再往上飞出面板。
 *
 * 与 [tvPanelGroup] 配套使用 —— 分组挡住「横向」逃逸，这个钉住「纵向」端点。
 * 挂在面板的**首项**上（`tvEdgeUp`）和**末项**上（`tvEdgeDown`）。
 */
@Composable
fun Modifier.tvEdgeUp(enabled: Boolean = LocalIsTv.current): Modifier =
    if (!enabled) this else this.focusProperties { up = FocusRequester.Cancel }

@Composable
fun Modifier.tvEdgeDown(enabled: Boolean = LocalIsTv.current): Modifier =
    if (!enabled) this else this.focusProperties { down = FocusRequester.Cancel }

/**
 * 面板关闭后把焦点还给「打开它的那个按钮」。
 *
 * ## 为什么需要
 *
 * 面板一关，原来持焦的菜单项节点就从树上消失了，Compose 不会自动把焦点
 * 送回触发按钮 —— 焦点悬空，遥控器上下左右全无反应，用户看到的就是
 * 「关掉面板之后焦点丢了」。
 *
 * 用法：面板打开时把触发者的 requester 记进 [remember]，面板的 `DisposableEffect`
 * 在 `onDispose` 里调 [restore]。
 */
class TvFocusReturn {
    private var target: FocusRequester? = null

    /** 面板打开前调用：记住触发按钮 */
    fun remember(requester: FocusRequester) {
        target = requester
    }

    /**
     * 面板关闭后调用。延迟一帧等面板节点真正从树上摘掉，
     * 否则 requestFocus 可能被弹层自己的节点抢回去。
     */
    fun restore() {
        val t = target ?: return
        target = null
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
            { t.requestFocusSafely() },
            50L
        )
    }
}

/**
 * 弹层（底部面板 / 抽屉 / 对话框）打开时把焦点抢到面板里。
 *
 * ## 为什么必须显式抢
 *
 * 面板是叠在页面上的**兄弟节点**，打开时 Compose 不会自动把焦点搬进去 ——
 * 焦点还留在「音效」那个按钮上。于是遥控器上下左右动的仍然是背后那一层，
 * 面板里的选项看着有高亮框却永远选不中。歌单右上角「⋯」弹出的
 * 播放全部 / 下载全部 / 收藏歌单 就是这么失效的。
 *
 * 用法：`val panelFocus = rememberTvPanelFocus()`，然后挂到**第一项**上
 * （`Modifier.focusRequester(panelFocus)`，必须在 `clickable` 之前）。
 *
 * 延迟 60ms 是因为面板首帧还没挂到焦点树上，立刻 requestFocus 会抛
 * `IllegalStateException: FocusRequester is not initialized`。
 * 手机 / 车机返回的 requester 永远不会被 request，等于不存在。
 */
@Composable
fun rememberTvPanelFocus(enabled: Boolean = LocalIsTv.current): FocusRequester {
    val fr = remember { FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        // 首行 requester 在面板进入组合后才挂得上焦点树，而 `requestFocus()`
        // 对未挂上 / 跨子树的 requester 会**静默失败**（不抛异常），所以
        // 不能靠返回值提前 return —— 固定打几轮。真正的跨子树问题由
        // `Modifier.tvPanelRoot()` 的根接力解决（见那个函数的注释）。
        for (delayMs in longArrayOf(60L, 180L, 360L, 700L)) {
            kotlinx.coroutines.delay(delayMs)
            fr.requestFocusSafely()
        }
    }
    return fr
}


/**
 * `requestFocus()` 的安全版：节点还没挂到焦点树上时不抛异常，返回 false。
 *
 * 遥控器按键是随时可能到达的（面板刚开、页面刚切），直接 `requestFocus()`
 * 会抛 `IllegalStateException: FocusRequester is not initialized`，
 * 在 `onPreviewKeyEvent` 里抛出会直接把这次按键吞掉，用户看到的就是「按了没反应」。
 */
fun FocusRequester.requestFocusSafely(): Boolean =
    runCatching { requestFocus() }.isSuccess
