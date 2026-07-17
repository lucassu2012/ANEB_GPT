package com.aneb.probe.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aneb.probe.ui.components.AnebGradientCard
import com.aneb.probe.ui.theme.AnebTheme

/** Common result-evidence action surface used by every Profile-backed test family. */
@Composable
internal fun UnifiedResultExportActions(
    enabled: Boolean,
    status: String?,
    onExport: () -> Unit,
    onShare: () -> Unit,
) {
    val colors = AnebTheme.colors
    AnebGradientCard(Modifier.fillMaxWidth().padding(top = 12.dp), radius = 14.dp) {
        Column(Modifier.padding(12.dp)) {
            Text("可审计结果", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = colors.ink)
            Text(
                if (enabled) "保存原始证据、评分审计和 Profile 指纹；导出时不会重新计算。"
                else "这条旧记录没有统一结果信封，无法生成可验证 JSONL。",
                fontSize = 9.sp,
                lineHeight = 14.sp,
                color = if (enabled) colors.muted else colors.fair,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IosSoftButton("保存 JSONL", onExport, Modifier.weight(1f), enabled = enabled)
                IosFilledButton("分享证据", onShare, Modifier.weight(1f), enabled = enabled)
            }
            if (!status.isNullOrBlank()) {
                Text(
                    status,
                    fontSize = 9.sp,
                    lineHeight = 14.sp,
                    color = if (status.startsWith("失败") || status.startsWith("无法")) colors.poor else colors.brand2,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}
