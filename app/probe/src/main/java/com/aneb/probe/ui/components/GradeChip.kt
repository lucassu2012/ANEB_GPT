package com.aneb.probe.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aneb.probe.ui.theme.AnebShapes
import com.aneb.probe.ui.theme.AnebTheme
import com.aneb.probe.ui.theme.Grade

/**
 * 四级色分级 chip（优/良/可/差角标）。底色为分级语义色的低透明度垫色、文字为语义色本色
 * ——与设计稿 .gchip / .tg 一致。null 分级（值缺失/无门限）→ 中性灰"—"（R-10）。
 *
 * @param useFriendly true 用长标签（优秀/良好/一般/较差），false 用紧凑角标（优/良/可/差）
 */
@Composable
fun GradeChip(
    grade: Grade?,
    modifier: Modifier = Modifier,
    useFriendly: Boolean = false,
) {
    val colors = AnebTheme.colors
    val color = colors.gradeColor(grade)
    val label = when {
        grade == null -> "—"
        useFriendly -> grade.labelFriendly
        else -> grade.labelCn
    }
    Text(
        text = label,
        modifier = modifier
            .clip(AnebShapes.xs)
            .background(colors.gradeSoft(grade))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
    )
}
