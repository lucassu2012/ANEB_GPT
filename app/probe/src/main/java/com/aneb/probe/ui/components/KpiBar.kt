package com.aneb.probe.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aneb.probe.ui.theme.AnebTheme
import com.aneb.probe.ui.theme.AnebType
import com.aneb.probe.ui.theme.Grade

/**
 * AQS 子分横条（专业视图"AQS 子分与权重"表的一行）。与设计稿 .kpirow 一致：
 * 名称（含权重）+ 语义色填充条（填到 fraction）+ 右对齐分值。
 *
 * @param label 左侧名称，如 "T1 首字 20%"
 * @param fraction 填充比例 0f..1f（通常 = subScore / 100）
 * @param grade 决定填充色的分级（null → 中性灰）
 * @param valueText 右侧数值文本，如 "97.3"
 */
@Composable
fun KpiBar(
    label: String,
    fraction: Float,
    grade: Grade?,
    valueText: String,
    modifier: Modifier = Modifier,
) {
    val colors = AnebTheme.colors
    val fillColor = colors.gradeColor(grade)
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, fontSize = 12.sp, color = colors.muted, modifier = Modifier.width(80.dp))
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(colors.surfaceMuted),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .clip(RoundedCornerShape(4.dp))
                    .background(fillColor),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = valueText,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            style = AnebType.StatValue,
            color = colors.ink,
            textAlign = TextAlign.End,
            modifier = Modifier.width(56.dp),
        )
    }
}
