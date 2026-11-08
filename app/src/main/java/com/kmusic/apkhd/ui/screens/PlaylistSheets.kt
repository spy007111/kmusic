package com.kmusic.apkhd.ui.screens

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kmusic.apkhd.model.UserPlaylist
import com.kmusic.apkhd.ui.icons.IconClose
import com.kmusic.apkhd.ui.icons.IconPlus
import com.kmusic.apkhd.ui.components.MoyinIcon
import com.kmusic.apkhd.ui.theme.moyinColors
import com.kmusic.apkhd.ui.theme.sheetBottomPadding
import com.kmusic.apkhd.ui.theme.wdp
import com.kmusic.apkhd.ui.theme.wsp
import com.kmusic.apkhd.ui.tv.tvFocus

/**
 * 新建歌单。
 *
 * 用自绘浮层而不是 material3 的 AlertDialog：这个 App 的圆角/底色都走自己的
 * moyinColors，系统对话框会带出一套不一样的观感。
 */
@Composable
fun NewPlaylistDialog(
    title: String = "新建歌单",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val c = moyinColors()
    var text by remember { mutableStateOf("") }
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
            Text(title, color = c.t1, fontSize = 15.wsp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(12.wdp))
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = TextStyle(color = c.t1, fontSize = 14.wsp),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    val n = text.trim()
                    if (n.isNotEmpty()) onConfirm(n)
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
            ) { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(c.s2)
                        .padding(horizontal = 12.wdp, vertical = 10.wdp)
                ) {
                    if (text.isEmpty()) {
                        Text("歌单名", color = c.t3, fontSize = 14.wsp)
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
                    "创建",
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

/**
 * 「加入歌单」选择面板。
 *
 * 面板里直接带「新建歌单」——否则用户第一次用时一个歌单都没有，
 * 会卡在「没有歌单可选」的死路上。
 */
@Composable
fun AddToPlaylistSheet(
    playlists: List<UserPlaylist>,
    onPick: (String) -> Unit,
    onCreateNew: () -> Unit,
    onDismiss: () -> Unit
) {
    val c = moyinColors()
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .tvFocus()
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
                .tvFocus(shape = RoundedCornerShape(topStart = 18.wdp, topEnd = 18.wdp))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { }
                .sheetBottomPadding()
                .padding(top = 14.wdp, bottom = 14.wdp)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.wdp, vertical = 6.wdp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "加入歌单",
                    color = c.t1,
                    fontSize = 15.wsp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    Modifier
                        .size(32.wdp)
                        .tvFocus()
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { onDismiss() },
                    contentAlignment = Alignment.Center
                ) {
                    MoyinIcon(icon = IconClose, tint = c.t3, size = 13.wdp)
                }
            }

            SheetRow(icon = IconPlus, label = "新建歌单", tint = c.accent) { onCreateNew() }

            if (playlists.isEmpty()) {
                Text(
                    "还没有歌单，先新建一个",
                    color = c.t3,
                    fontSize = 12.wsp,
                    modifier = Modifier.padding(horizontal = 18.wdp, vertical = 10.wdp)
                )
            } else {
                LazyColumn(Modifier.heightIn(max = 320.wdp)) {
                    items(playlists) { p ->
                        SheetRow(
                            label = p.name,
                            sub = "${p.tracks.size} 首"
                        ) { onPick(p.id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetRow(
    label: String,
    sub: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    tint: Color? = null,
    onClick: () -> Unit
) {
    val c = moyinColors()
    Row(
        Modifier
            .fillMaxWidth()
            .tvFocus(fullWidth = true)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
            .padding(horizontal = 18.wdp, vertical = 12.wdp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            MoyinIcon(icon = icon, tint = tint ?: c.t2, size = 17.wdp)
            Spacer(Modifier.width(12.wdp))
        }
        Text(
            text = label,
            color = tint ?: c.t1,
            fontSize = 14.wsp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (sub != null) {
            Spacer(Modifier.width(8.wdp))
            Text(sub, color = c.t3, fontSize = 12.wsp)
        }
    }
}
