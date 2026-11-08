package com.kmusic.apkhd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kmusic.apkhd.ui.theme.moyinColors
import com.kmusic.apkhd.ui.theme.sheetBottomPadding
import com.kmusic.apkhd.ui.theme.wdp
import com.kmusic.apkhd.ui.theme.wsp
import com.kmusic.apkhd.ui.tv.tvFocus

/**
 * 「歌词不对？」反馈面板。
 *
 * 存在的理由：歌词配错**无法自动检测**。实测过两个自动判据都不成立 ——
 *   1. 时长差：正确的词末尾常有大段纯音乐（实测差 75s 仍是对的），不能否决
 *   2. 歌名出现在正文里：'再次重逢的世界'、'Oh!' 这些**正确**的词也不含歌名，会误杀
 * 所以只能让用户说，然后按反馈换源重试。
 *
 * 选项不是摆设：点哪一项都会把对应源加进跳过集合，下次取词真的换源。
 */
@Composable
fun WrongLyricSheet(
    trackName: String,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    val c = moyinColors()
    val options = listOf(
        "完全是别的歌" to "wrong_song",
        "歌词对但时间轴偏了" to "offset_only",
        "是别的歌手唱的版本" to "wrong_version",
        "歌词不全 / 缺段落" to "incomplete"
    )

    Box(
        Modifier.fillMaxSize().background(c.bg.copy(alpha = 0.6f)),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 20.wdp, topEnd = 20.wdp))
                .background(c.s1)
                .sheetBottomPadding()
                .padding(horizontal = 20.wdp, vertical = 18.wdp)
        ) {
            Text(
                "歌词不对？",
                color = c.t1,
                fontSize = 16.wsp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "《$trackName》",
                color = c.t3,
                fontSize = 11.wsp,
                modifier = Modifier.padding(top = 3.wdp)
            )
            Spacer(Modifier.height(14.wdp))

            options.forEach { (label, code) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.wdp)
                        .clip(RoundedCornerShape(12.wdp))
                        .background(c.s2)
                        .tvFocus(shape = RoundedCornerShape(12.wdp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onSubmit(code) }
                        .padding(horizontal = 14.wdp, vertical = 13.wdp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(label, color = c.t1, fontSize = 13.wsp)
                }
            }

            Spacer(Modifier.height(6.wdp))
            // 「时间轴偏了」和换源无关，给个明确指引，别让用户以为选了就能修
            Text(
                "选「时间轴偏了」请到「更多 → 歌词」里微调时间轴；" +
                    "其它选项会换一个歌词源重新查找。",
                color = c.t3,
                fontSize = 10.wsp,
                lineHeight = 15.wsp,
                modifier = Modifier.padding(bottom = 10.wdp)
            )

            Row(
                Modifier.fillMaxWidth().padding(bottom = 6.wdp),
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    "取消",
                    color = c.t2,
                    fontSize = 13.wsp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.wdp))
                        .tvFocus(shape = RoundedCornerShape(10.wdp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onDismiss() }
                        .padding(horizontal = 20.wdp, vertical = 8.wdp)
                )
            }
        }
    }
}
