package com.kmusic.apkhd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.kmusic.apkhd.playback.EqPresetGroup
import com.kmusic.apkhd.playback.EqPresets
import com.kmusic.apkhd.playback.EqBands
import com.kmusic.apkhd.playback.UserEqPresets
import com.kmusic.apkhd.ui.theme.moyinColors
import com.kmusic.apkhd.ui.theme.wdp
import com.kmusic.apkhd.ui.theme.wsp
import com.kmusic.apkhd.ui.tv.tvFocus

/**
 * 预设胶囊，按 风格 / 场景 / 设备 分组排（A3）。
 *
 * 下标仍然是全局唯一的 —— [EqPresets] 那 7 套老预设顺序锁死，
 * 分组只影响排版，不影响 `eq_preset` 存的数字。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EqPresetGroupedChips(
    selected: Int,
    customActive: Boolean,
    onPick: (Int) -> Unit
) {
    val c = moyinColors()
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.wdp, vertical = 2.wdp)) {
        // 自定义生效时把「自定义」也画成一个选中态胶囊，否则用户看不到自己
        // 现在到底处在哪条曲线上 —— 顶部那一行显示「自定义」但下面没有任何
        // 一个是选中的，会让人以为是 bug。
        if (customActive) {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.wdp)) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(99.dp))
                        .background(c.accent)
                        .padding(horizontal = 13.wdp, vertical = 7.wdp)
                ) {
                    Text("自定义", color = Color.White, fontSize = 12.wsp)
                }
            }
        }
        EqPresets.grouped().forEach { (group, items) ->
            Text(
                group.label,
                color = c.t3,
                fontSize = 11.wsp,
                modifier = Modifier.padding(top = 6.wdp, bottom = 4.wdp)
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.wdp),
                verticalArrangement = Arrangement.spacedBy(6.wdp)
            ) {
                items.forEach { iv ->
                    val on = iv.index == selected && !customActive
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(99.dp))
                            .background(
                                when {
                                    on -> c.accent
                                    c.isDark -> Color(0x14FFFFFF)
                                    else -> Color(0x0A000000)
                                }
                            )
                            .tvFocus()
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { onPick(iv.index) }
                            .padding(horizontal = 13.wdp, vertical = 7.wdp)
                    ) {
                        Text(
                            iv.value.name,
                            color = if (on) Color.White else c.t2,
                            fontSize = 12.wsp
                        )
                    }
                }
            }
        }
    }
}

/**
 * 用户自建曲线列表（A3）。
 *
 * 只给「套用 / 删除」两个动作，不做重命名 —— 想改名就再存一次同名覆盖。
 * 界面里不摆开关，用户要的就是一条能一键回到的曲线。
 */
@Composable
fun UserCurveList(
    presets: List<UserEqPresets.Preset>,
    activeName: String?,
    onApply: (UserEqPresets.Preset) -> Unit,
    onDelete: (String) -> Unit
) {
    val c = moyinColors()
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.wdp, vertical = 2.wdp)) {
        presets.forEach { p ->
            val active = p.name == activeName
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .tvFocus(shape = RoundedCornerShape(10.dp))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { onApply(p) }
                    .padding(vertical = 9.wdp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    p.name,
                    color = if (active) c.accent else c.t1,
                    fontSize = 13.wsp,
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    p.gains.joinToString(" / ") { "${it / 100}" },
                    color = c.t3,
                    fontSize = 10.wsp
                )
                Spacer(Modifier.width(10.wdp))
                Text(
                    "删除",
                    color = c.t3,
                    fontSize = 11.wsp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(99.dp))
                        .tvFocus(shape = RoundedCornerShape(99.dp))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { onDelete(p.name) }
                        .padding(horizontal = 8.wdp, vertical = 4.wdp)
                )
            }
        }
    }
}

/** 默认名字：不逼用户想名字，但要让他能改 */
fun suggestedCurveName(custom: Boolean, presetName: String?): String =
    if (custom || presetName.isNullOrBlank()) "我的曲线" else "$presetName 副本"

/**
 * 保存曲线时的命名浮层。
 *
 * 用自绘浮层而不是 AlertDialog —— 这个 App 的圆角/底色都走自己的
 * moyinColors，系统对话框会带出另一套观感（和 NewPlaylistDialog 同口径）。
 */
@Composable
fun SaveCurveDialog(
    suggestion: String,
    /** 当前曲线的段数（十段）。文案里说出来，用户才知道存的是几个点 */
    bandCount: Int = EqBands.COUNT,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val c = moyinColors()
    var text by remember { mutableStateOf(suggestion) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .tvFocus()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 36.wdp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (c.isDark) c.s1 else Color(0xFFFFFFFF))
                .tvFocus(shape = RoundedCornerShape(16.dp))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { }
                .padding(18.wdp)
        ) {
            Text("保存曲线", color = c.t1, fontSize = 15.wsp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.wdp))
            Text(
                "只存这 $bandCount 段增益，不含低音/响度。同名会覆盖。",
                color = c.t3,
                fontSize = 11.wsp
            )
            Spacer(Modifier.height(12.wdp))
            BasicTextField(
                value = text,
                onValueChange = { text = it.take(UserEqPresets.MAX_NAME_LEN) },
                singleLine = true,
                textStyle = TextStyle(color = c.t1, fontSize = 14.wsp),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    val n = text.trim()
                    if (n.isNotEmpty()) onConfirm(n)
                }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus)
            ) { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(c.s2)
                        .padding(horizontal = 12.wdp, vertical = 10.wdp)
                ) {
                    if (text.isEmpty()) {
                        Text("曲线名", color = c.t3, fontSize = 14.wsp)
                    }
                    inner()
                }
            }
            Spacer(Modifier.height(16.wdp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "取消",
                    color = c.t3,
                    fontSize = 14.wsp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(99.dp))
                        .tvFocus(shape = RoundedCornerShape(99.dp))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { onDismiss() }
                        .padding(horizontal = 12.wdp, vertical = 6.wdp)
                )
                Spacer(Modifier.width(8.wdp))
                val ok = text.isNotBlank()
                Text(
                    "保存",
                    color = if (ok) c.accent else c.t3,
                    fontSize = 14.wsp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(99.dp))
                        .tvFocus(shape = RoundedCornerShape(99.dp))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { if (ok) onConfirm(text.trim()) }
                        .padding(horizontal = 12.wdp, vertical = 6.wdp)
                )
            }
        }
    }
}

/** 分组小标题（B5 用，音效/播放分组统一口径） */
@Composable
fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = moyinColors().t3,
        fontSize = 11.wsp,
        fontWeight = FontWeight.Medium,
        modifier = modifier.padding(start = 18.wdp, end = 18.wdp, top = 14.wdp, bottom = 4.wdp)
    )
}
