package com.kmusic.apkhd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kmusic.apkhd.ui.theme.moyinColors
import com.kmusic.apkhd.ui.theme.wdp
import com.kmusic.apkhd.ui.theme.wsp
import com.kmusic.apkhd.ui.tv.tvFocus

/**
 * 数值精确输入浮层（B5）。
 *
 * 「更多」里那两行 ± 步进（歌词时间轴 0.5 s、字号 10%）在**微调**时很好用，
 * 但想直接设成「+2.3 s」「120%」就得连点十几下，而且点偏了就过头。
 * 这里给一个直接输数字的入口：点数值本身打开，里面同时保留「复位」，
 * 不把原来的复位能力弄丢。
 *
 * @param initial 当前值（已换算成显示单位）
 * @param suffix 单位后缀，显示在输入框右侧（"ms" / "%" / "dB"）
 * @param scale 显示单位 → 存储单位的倍率（例如 1000 ms/s、100 %/1.0f）
 */
@Composable
fun NumberInputDialog(
    title: String,
    initial: String,
    suffix: String,
    hint: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    onReset: (() -> Unit)? = null
) {
    val c = moyinColors()
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun commit() {
        val t = text.trim()
        if (t.isNotEmpty() && t.toDoubleOrNull() != null) onConfirm(t)
    }

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
                .padding(horizontal = 40.wdp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (c.isDark) c.s1 else Color(0xFFFFFFFF))
                .tvFocus(shape = RoundedCornerShape(16.dp))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { }
                .padding(18.wdp)
        ) {
            Text(title, color = c.t1, fontSize = 15.wsp, fontWeight = FontWeight.Medium)
            if (hint != null) {
                Spacer(Modifier.height(4.wdp))
                Text(hint, color = c.t3, fontSize = 11.wsp)
            }
            Spacer(Modifier.height(12.wdp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    // 小数用 Decimal：时间轴允许 2.5 s，不允许小数键盘就输不进去
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { commit() }),
                    textStyle = TextStyle(color = c.t1, fontSize = 15.wsp),
                    cursorBrush = SolidColor(c.accent),
                    modifier = Modifier.weight(1f).focusRequester(focus)
                ) { inner ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(c.s2)
                            .padding(horizontal = 12.wdp, vertical = 10.wdp)
                    ) {
                        if (text.isEmpty()) {
                            Text("0", color = c.t3, fontSize = 15.wsp)
                        }
                        inner()
                    }
                }
                Spacer(Modifier.width(8.wdp))
                Text(suffix, color = c.t3, fontSize = 12.wsp)
            }
            Spacer(Modifier.height(16.wdp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onReset != null) {
                    Text(
                        "复位",
                        color = c.t3,
                        fontSize = 13.wsp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(99.dp))
                            .tvFocus(shape = RoundedCornerShape(99.dp))
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { onReset() }
                            .padding(horizontal = 10.wdp, vertical = 6.wdp)
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "取消",
                    color = c.t3,
                    fontSize = 13.wsp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(99.dp))
                        .tvFocus(shape = RoundedCornerShape(99.dp))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { onDismiss() }
                        .padding(horizontal = 10.wdp, vertical = 6.wdp)
                )
                Spacer(Modifier.width(4.wdp))
                val ok = text.trim().toDoubleOrNull() != null
                Text(
                    "确定",
                    color = if (ok) c.accent else c.t3,
                    fontSize = 13.wsp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(99.dp))
                        .tvFocus(shape = RoundedCornerShape(99.dp))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { if (ok) commit() }
                        .padding(horizontal = 10.wdp, vertical = 6.wdp)
                )
            }
        }
    }
}
