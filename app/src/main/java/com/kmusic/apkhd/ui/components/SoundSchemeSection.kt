package com.kmusic.apkhd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.kmusic.apkhd.data.DeviceEqProfile
import com.kmusic.apkhd.ui.theme.moyinColors
import com.kmusic.apkhd.ui.theme.wdp
import com.kmusic.apkhd.ui.theme.wsp
import com.kmusic.apkhd.ui.tv.tvFocus
import kotlinx.coroutines.delay

@Composable
fun SoundSchemeSection(
    profiles: List<DeviceEqProfile>,
    activeId: String?,
    hasConfirmedRoute: Boolean = false,
    onSave: (name: String, bind: Boolean) -> Unit,
    onApply: (String?) -> Unit,
    onDelete: (String) -> Unit,
    onBind: (String) -> Unit = {},
    deviceAutoEnabled: Boolean = false,
    onDeviceAutoChange: (Boolean) -> Unit = {}
) {
    val c = moyinColors()
    val saveFocus = remember { FocusRequester() }
    var dialog by remember { mutableStateOf<String?>(null) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var originFocus by remember { mutableStateOf<FocusRequester?>(null) }
    var restoreFocus by remember { mutableStateOf<FocusRequester?>(null) }
    val selected = profiles.firstOrNull { it.id == selectedId }
    fun dismiss() {
        dialog = null
        restoreFocus = originFocus
    }
    LaunchedEffect(dialog, restoreFocus) {
        if (dialog == null && restoreFocus != null) {
            delay(50)
            runCatching { restoreFocus?.requestFocus() }
            restoreFocus = null
        }
    }
    LaunchedEffect(dialog, selectedId, selected) {
        if ((dialog == "manage" || dialog == "delete") && selected == null) dismiss()
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 18.wdp, vertical = 6.wdp)) {
        Text("我的音效方案", color = c.t1, fontSize = 14.wsp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.wdp))
        SchemeAction("保存当前音效", modifier = Modifier.fillMaxWidth().focusRequester(saveFocus)) {
            originFocus = saveFocus
            dialog = "save"
        }
        if (profiles.isEmpty()) {
            Text("调好音效后保存，随时一键使用。", color = c.t3, fontSize = 11.wsp,
                modifier = Modifier.padding(vertical = 6.wdp))
        } else {
            SchemeAction("使用默认音效", modifier = Modifier.fillMaxWidth(), selected = activeId == null) { onApply(null) }
            profiles.forEach { profile ->
                key(profile.id) {
                    val moreFocus = remember { FocusRequester() }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        SchemeAction(profile.name, selected = activeId == profile.id,
                            modifier = Modifier.weight(1f)) { onApply(profile.id) }
                        SchemeAction("更多", modifier = Modifier.focusRequester(moreFocus)) {
                            selectedId = profile.id
                            originFocus = moreFocus
                            dialog = "manage"
                        }
                    }
                }
            }
            if (profiles.any { it.bindingKey != null } || deviceAutoEnabled) {
                SchemeAction("按设备自动使用 · ${if (deviceAutoEnabled) "开启" else "关闭"}") {
                    onDeviceAutoChange(!deviceAutoEnabled)
                }
            }
        }
    }

    if (dialog == "save") {
        SchemeSaveDialog(hasConfirmedRoute, onDismiss = ::dismiss) { name, bind ->
            onSave(name, bind)
            dismiss()
        }
    } else if (dialog == "manage" && selected != null) {
        val first = remember(selected.id) { FocusRequester() }
        SchemeDialog(selected.name, first, ::dismiss) {
            SchemeAction("应用", modifier = Modifier.fillMaxWidth().focusRequester(first)) {
                onApply(selected.id)
                dismiss()
            }
            if (hasConfirmedRoute) {
                SchemeAction("用于当前设备", modifier = Modifier.fillMaxWidth()) {
                    onBind(selected.id)
                    dismiss()
                }
            }
            SchemeAction("删除方案", modifier = Modifier.fillMaxWidth()) { dialog = "delete" }
            SchemeAction("关闭", modifier = Modifier.fillMaxWidth()) { dismiss() }
        }
    } else if (dialog == "delete" && selected != null) {
        val cancel = remember(selected.id) { FocusRequester() }
        SchemeDialog("删除「${selected.name}」？", cancel, ::dismiss) {
            Text("删除后无法恢复。", color = c.t3, fontSize = 12.wsp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                SchemeAction("取消", modifier = Modifier.focusRequester(cancel)) { dismiss() }
                SchemeAction("删除") {
                    onDelete(selected.id)
                    originFocus = saveFocus
                    dismiss()
                }
            }
        }
    }
}

@Composable
private fun SchemeAction(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val c = moyinColors()
    val shape = RoundedCornerShape(10.dp)
    Text(text, color = if (selected) c.accent else if (enabled) c.t1 else c.t3,
        fontSize = 13.wsp, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier.clip(shape).tvFocus(shape = shape, fullWidth = true)
            .clickable(enabled = enabled, indication = null,
                interactionSource = remember { MutableInteractionSource() }, onClick = onClick)
            .padding(horizontal = 10.wdp, vertical = 10.wdp))
}

@Composable
private fun SchemeDialog(
    title: String,
    initialFocus: FocusRequester,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    val c = moyinColors()
    Dialog(onDismissRequest = onDismiss) {
        LaunchedEffect(initialFocus) {
            delay(50)
            runCatching { initialFocus.requestFocus() }
        }
        Column(Modifier.widthIn(max = 420.dp).fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)).background(c.s1)
            .verticalScroll(rememberScrollState()).padding(18.wdp)) {
            Text(title, color = c.t1, fontSize = 15.wsp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(10.wdp))
            content()
        }
    }
}

@Composable
private fun SchemeSaveDialog(
    hasConfirmedRoute: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, Boolean) -> Unit
) {
    val c = moyinColors()
    val inputFocus = remember { FocusRequester() }
    var name by remember { mutableStateOf("我的方案") }
    var bind by remember { mutableStateOf(false) }
    SchemeDialog("保存当前音效", inputFocus, onDismiss) {
        BasicTextField(value = name, onValueChange = { value ->
            name = value.filterNot(Char::isISOControl).take(80)
        }, singleLine = true, textStyle = TextStyle(color = c.t1, fontSize = 14.wsp),
            cursorBrush = SolidColor(c.accent),
            modifier = Modifier.fillMaxWidth().focusRequester(inputFocus)
                .clip(RoundedCornerShape(10.dp)).background(c.s2).padding(12.wdp))
        if (hasConfirmedRoute) {
            SchemeAction("用于当前设备 · ${if (bind) "是" else "否"}") { bind = !bind }
        }
        Spacer(Modifier.height(10.wdp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            SchemeAction("取消") { onDismiss() }
            SchemeAction("保存", enabled = name.isNotBlank()) {
                onConfirm(name.trim(), bind && hasConfirmedRoute)
            }
        }
    }
}
