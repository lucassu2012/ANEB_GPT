package com.aneb.probe.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.aneb.probe.ui.theme.AnebTheme

/**
 * 分区小标题（专业视图各块的大写弱化标签，如 "AQS 子分与权重"）。
 * 与设计稿 .blk .bt 一致：小字、加宽字距、faint 色、半粗。
 *
 * @param title 标题文本
 * @param trailing 可选右侧说明（如样本数 "671 样本 · 1Hz"），muted 色右对齐
 */
@Composable
fun SectionLabel(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
) {
    val colors = AnebTheme.colors
    Row(
        modifier = modifier.padding(top = 12.dp, bottom = 7.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = title,
            fontSize = 11.sp,
            fontWeight = FontWeight(640),
            letterSpacing = 0.09.em,
            color = colors.faint,
        )
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            Text(text = trailing, fontSize = 11.sp, color = colors.muted, textAlign = TextAlign.End)
        }
    }
}
