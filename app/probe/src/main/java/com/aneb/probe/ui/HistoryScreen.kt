package com.aneb.probe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aneb.probe.data.TestRun
import com.aneb.probe.ui.components.GradeChip
import com.aneb.probe.ui.components.pressable
import com.aneb.probe.ui.theme.AnebElevation
import com.aneb.probe.ui.theme.AnebShapes
import com.aneb.probe.ui.theme.AnebTheme
import com.aneb.probe.ui.theme.AnebType
import com.aneb.probe.ui.theme.Grade
import com.aneb.probe.ui.theme.lowConf
import com.aneb.probe.ui.theme.onGrade
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 历史页（重设计，设计稿 §历史，iOS 化）：Room TestRun 列表——每行 grade 色分数徽标（tabular）
 * + iOS soft grade chip + 时间/模式/传输 + 状态；点击进对应结果页，整卡按压缩放。
 * 数据全部来自 Room（本层不重算）。LazyColumn key 保留（runId）。
 */
@Composable
fun HistoryScreen(
    runs: List<TestRun>,
    onOpen: (String) -> Unit,
    onGenerateReport: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = AnebTheme.colors
    val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)
    Column(modifier = Modifier.fillMaxSize().background(colors.background).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(8.dp))
        GlassHeader("测试历史 (${runs.size})", onBack) {
            Text(
                text = "生成报告",
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.brand2,
                modifier = Modifier
                    .clip(AnebShapes.pill)
                    .background(colors.surfaceMuted)
                    .border(1.dp, colors.hairline, AnebShapes.pill)
                    .then(Modifier.pressable(onClick = onGenerateReport))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
        if (runs.isEmpty()) {
            Text("暂无历史记录", color = colors.muted, modifier = Modifier.padding(top = 24.dp))
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(top = 10.dp, bottom = 16.dp),
        ) {
            items(count = runs.size, key = { i -> runs[i].runId }) { i ->
                HistoryRow(runs[i], fmt, onOpen)
            }
        }
    }
}

@Composable
private fun HistoryRow(run: TestRun, fmt: SimpleDateFormat, onOpen: (String) -> Unit) {
    val colors = AnebTheme.colors
    val score = run.aqsScore
    val grade = score?.let { Grade.fromAqsScore(it) }
    val gradeColor = colors.gradeColor(grade)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .shadow(AnebElevation.level1, AnebShapes.card, clip = false)
            .clip(AnebShapes.card)
            .background(colors.surface)
            .border(1.dp, colors.hairline, AnebShapes.card)
            .then(Modifier.pressable(onClick = { onOpen(run.runId) }))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(44.dp).clip(AnebShapes.tile).background(gradeColor),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                score?.roundToInt()?.toString() ?: "—",
                style = AnebType.StatValue,
                fontSize = 17.sp,
                // 徽标底色为分级色：文字反色按底色亮度择近黑/近白，保证深浅主题对比（token 化）。
                color = colors.onGrade(grade),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    grade?.labelFriendly ?: "未完成",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (grade != null) gradeColor else colors.muted,
                )
                if (grade != null) {
                    Spacer(Modifier.width(7.dp))
                    GradeChip(grade)
                }
                if (run.aqsLowConfidence == true) {
                    Spacer(Modifier.width(6.dp))
                    LowConfChip()
                }
            }
            Text(
                "${fmt.format(Date(run.startedAtEpochMs))} · ${run.mode} · ${run.transport}",
                fontSize = 11.5.sp,
                color = colors.muted,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                "status=${run.status ?: "?"} report=${run.reportStatus ?: "—"}",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = colors.faint,
            )
        }
        Text("›", fontSize = 20.sp, color = colors.faint)
    }
}

/** 低置信 soft chip（iOS 柔和底角标；沿用结果页 fair 语义色）。 */
@Composable
private fun LowConfChip() {
    val colors = AnebTheme.colors
    Text(
        text = "低置信",
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = colors.lowConf,
        modifier = Modifier
            .clip(AnebShapes.xs)
            .background(colors.fairSoft)
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}
