package com.aneb.probe.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aneb.probe.ui.theme.AnebElevation
import com.aneb.probe.ui.theme.AnebShapes
import com.aneb.probe.ui.theme.AnebTheme

/**
 * 分段控件（结果页顶部"简洁 / 专业"切换普通/开发者视图）。与设计稿 .modeseg 一致：
 * 胶囊底 + 选中段抬升面色。泛型 [T] 便于承载 UI 视图模式枚举。
 *
 * @param options 段选项（有序）
 * @param selected 当前选中项
 * @param onSelect 选中回调
 * @param label 段 → 显示文案
 */
@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
) {
    val colors = AnebTheme.colors
    val innerShape = RoundedCornerShape(7.dp)
    Row(
        modifier = modifier
            .clip(AnebShapes.xs)
            .background(colors.surfaceMuted)
            .padding(2.dp),
    ) {
        options.forEach { option ->
            val on = option == selected
            Text(
                text = label(option),
                fontSize = 11.sp,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                color = if (on) colors.ink else colors.muted,
                modifier = Modifier
                    .then(if (on) Modifier.shadow(AnebElevation.level1, innerShape, clip = false) else Modifier)
                    .clip(innerShape)
                    .background(if (on) colors.surface else Color.Transparent)
                    .clickable { onSelect(option) }
                    .padding(horizontal = 9.dp, vertical = 4.dp),
            )
        }
    }
}
