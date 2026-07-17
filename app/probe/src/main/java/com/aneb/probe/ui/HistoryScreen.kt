package com.aneb.probe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aneb.probe.data.TestRun
import com.aneb.probe.data.NetworkComprehensiveResultEntity
import com.aneb.probe.data.RealtimeSimulationResultEntity
import com.aneb.probe.data.TokenSimulationResultEntity
import com.aneb.probe.ui.components.AnebGradientCard
import com.aneb.probe.ui.components.AnebPageIntro
import com.aneb.probe.ui.components.AnebSectionTitle
import com.aneb.probe.ui.components.AnebSparkline
import com.aneb.probe.ui.components.AnebTopBar
import com.aneb.probe.ui.components.pressable
import com.aneb.probe.ui.theme.AnebTheme
import com.aneb.probe.ui.theme.AnebType
import com.aneb.probe.ui.theme.Grade
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** 历史页：真实 Room 数据投影到 `history.html` 的趋势卡与记录行。 */
@Composable
fun HistoryScreen(
    runs: List<TestRun>,
    basicRuns: List<NetworkComprehensiveResultEntity>,
    tokenSimulationRuns: List<TokenSimulationResultEntity>,
    realtimeSimulationRuns: List<RealtimeSimulationResultEntity>,
    onOpen: (String) -> Unit,
    onOpenBasic: (String) -> Unit,
    onOpenTokenSimulation: (String) -> Unit,
    onOpenRealtimeSimulation: (String) -> Unit,
    onGenerateReport: () -> Unit,
    onBack: () -> Unit,
    showBack: Boolean = true,
) {
    val colors = AnebTheme.colors
    val ordered = remember(runs, basicRuns, tokenSimulationRuns, realtimeSimulationRuns) {
        buildList<HistoryEntry> {
            runs.forEach { add(HistoryEntry.Token(it)) }
            basicRuns.forEach { add(HistoryEntry.Basic(it)) }
            tokenSimulationRuns.forEach { add(HistoryEntry.TokenSimulation(it)) }
            realtimeSimulationRuns.forEach { add(HistoryEntry.RealtimeSimulation(it)) }
        }.sortedByDescending { it.startedAtEpochMs }
    }
    val scored = remember(runs, basicRuns, tokenSimulationRuns, realtimeSimulationRuns) {
        buildList {
            addAll(runs.mapNotNull { it.aqsScore })
            addAll(basicRuns.mapNotNull { it.totalScore })
            addAll(tokenSimulationRuns.mapNotNull { it.totalScore })
            addAll(realtimeSimulationRuns.mapNotNull { it.totalScore })
        }.filter { it.isFinite() }
    }
    val avg = scored.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
    val best = scored.maxOrNull()?.roundToInt()
    val worst = scored.minOrNull()?.roundToInt()
    val trend = scored.asReversed().map { (it / 100.0).toFloat().coerceIn(0f, 1f) }

    Column(Modifier.fillMaxSize().background(colors.background).padding(horizontal = 16.dp)) {
        AnebTopBar(showBack = showBack, onBack = onBack, showMenu = !showBack)
        AnebPageIntro(
            eyebrow = "RESULTS",
            title = "测试历史",
            subtitle = if (ordered.isEmpty()) {
                "完成首次测试后，这里会显示真实体验趋势。"
            } else {
                "共 ${ordered.size} 次测试，其中 ${scored.size} 个有效 AI 体验分。"
            },
            modifier = Modifier.padding(top = 3.dp, start = 1.dp, bottom = 14.dp),
        )

        TrendCard(avg = avg, best = best, worst = worst, trend = trend)

        AnebSectionTitle(
            text = "全部记录",
            action = "生成报告",
            onAction = onGenerateReport,
            modifier = Modifier.padding(top = 17.dp, start = 4.dp, end = 4.dp, bottom = 7.dp),
        )

        if (ordered.isEmpty()) {
            AnebGradientCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("暂无测试记录", fontSize = 13.sp, color = colors.ink)
                    Text("从“测试”页开始一次真实测量", fontSize = 10.sp, color = colors.muted, modifier = Modifier.padding(top = 5.dp))
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = if (showBack) 18.dp else 76.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(count = ordered.size, key = { ordered[it].key }) { index ->
                    when (val item = ordered[index]) {
                        is HistoryEntry.Token -> HistoryRecord(item.run, onOpen)
                        is HistoryEntry.Basic -> BasicHistoryRecord(item.result, onOpenBasic)
                        is HistoryEntry.TokenSimulation -> TokenSimulationHistoryRecord(item.result, onOpenTokenSimulation)
                        is HistoryEntry.RealtimeSimulation -> RealtimeSimulationHistoryRecord(item.result, onOpenRealtimeSimulation)
                    }
                }
            }
        }
    }
}

private sealed interface HistoryEntry {
    val startedAtEpochMs: Long
    val key: String

    data class Token(val run: TestRun) : HistoryEntry {
        override val startedAtEpochMs = run.startedAtEpochMs
        override val key = "token:${run.runId}"
    }

    data class Basic(val result: NetworkComprehensiveResultEntity) : HistoryEntry {
        override val startedAtEpochMs = result.startedAtEpochMs
        override val key = "basic:${result.runId}"
    }

    data class TokenSimulation(val result: TokenSimulationResultEntity) : HistoryEntry {
        override val startedAtEpochMs = result.startedAtEpochMs
        override val key = "token-v2:${result.runId}"
    }

    data class RealtimeSimulation(val result: RealtimeSimulationResultEntity) : HistoryEntry {
        override val startedAtEpochMs = result.startedAtEpochMs
        override val key = "realtime-v1:${result.runId}"
    }
}

@Composable
private fun TrendCard(avg: Int?, best: Int?, worst: Int?, trend: List<Float>) {
    val colors = AnebTheme.colors
    AnebGradientCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("体验分趋势", fontSize = 11.sp, color = colors.muted)
                Spacer(Modifier.weight(1f))
                Text(
                    if (avg == null) "尚无有效成绩" else "平均 $avg",
                    fontSize = 12.sp,
                    fontWeight = FontWeight(560),
                    color = if (avg == null) colors.muted else colors.excellent,
                )
            }
            AnebSparkline(
                values = trend,
                color = colors.excellent,
                fill = true,
                modifier = Modifier.fillMaxWidth().height(78.dp).padding(top = 10.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 9.dp).border(0.dp, colors.hairline),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf("最佳" to best, "平均" to avg, "最低" to worst).forEachIndexed { index, item ->
                    if (index > 0) Box(Modifier.width(1.dp).height(42.dp).background(colors.hairline))
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(item.first, fontSize = 10.sp, color = colors.muted)
                        Text(
                            item.second?.toString() ?: "—",
                            style = AnebType.StatValue,
                            fontSize = 18.sp,
                            fontWeight = FontWeight(540),
                            color = when (item.first) { "最佳" -> colors.excellent; "最低" -> colors.fair; else -> colors.ink },
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRecord(run: TestRun, onOpen: (String) -> Unit) {
    val colors = AnebTheme.colors
    val score = run.aqsScore
    val grade = score?.let { Grade.fromAqsScore(it) }
    val accent = colors.gradeColor(grade)
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.US) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(61.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(androidx.compose.ui.graphics.Color(0xB811162C))
            .border(1.dp, colors.hairline, RoundedCornerShape(14.dp))
            .pressable(onClick = { onOpen(run.runId) })
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(41.dp).clip(CircleShape).border(1.dp, accent.copy(alpha = 0.48f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(score?.roundToInt()?.toString() ?: "—", style = AnebType.StatValue, fontSize = 15.sp, color = accent)
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                NetworkLabel.forRun(run).substringBeforeLast(" · "),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = colors.ink,
                maxLines = 1,
            )
            Text(
                "${fmt.format(Date(run.startedAtEpochMs))} · ${grade?.labelFriendly ?: "未完成"}${if (run.aqsLowConfidence == true) " · 低置信" else ""}",
                fontSize = 10.sp,
                color = colors.muted,
                modifier = Modifier.padding(top = 3.dp),
                maxLines = 1,
            )
        }
        Text("›", fontSize = 18.sp, color = colors.faint)
    }
}

@Composable
private fun BasicHistoryRecord(result: NetworkComprehensiveResultEntity, onOpen: (String) -> Unit) {
    val colors = AnebTheme.colors
    val accent = when (result.status) {
        "completed" -> colors.brand
        "partial" -> colors.fair
        else -> colors.poor
    }
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.US) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(61.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(androidx.compose.ui.graphics.Color(0xB811162C))
            .border(1.dp, colors.hairline, RoundedCornerShape(14.dp))
            .pressable(onClick = { onOpen(result.runId) })
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(41.dp).clip(CircleShape).border(1.dp, accent.copy(alpha = 0.48f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("↕", style = AnebType.StatValue, fontSize = 16.sp, color = accent)
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${if (result.syntheticImpairment) "合成弱网" else "网络综合"} · ${ProbeNodeCatalog.labelForUrl(result.serverBase)}",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = colors.ink,
                maxLines = 1,
            )
            val down = result.downloadMbps?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "—"
            val up = result.uploadMbps?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "—"
            Text(
                "${fmt.format(Date(result.startedAtEpochMs))} · ${result.totalScore?.let { String.format(Locale.ROOT, "%.1f 分", it) } ?: "不可评分"} · ↓$down ↑$up",
                fontSize = 10.sp,
                color = colors.muted,
                modifier = Modifier.padding(top = 3.dp),
                maxLines = 1,
            )
        }
        Text("›", fontSize = 18.sp, color = colors.faint)
    }
}

@Composable
private fun TokenSimulationHistoryRecord(result: TokenSimulationResultEntity, onOpen: (String) -> Unit) {
    val colors = AnebTheme.colors
    val accent = when (result.verdict) {
        "PASS" -> colors.excellent
        "FAIL" -> colors.poor
        "INCONCLUSIVE" -> colors.fair
        else -> colors.muted
    }
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.US) }
    Row(
        modifier = Modifier.fillMaxWidth().height(61.dp).clip(RoundedCornerShape(14.dp))
            .background(androidx.compose.ui.graphics.Color(0xB811162C))
            .border(1.dp, colors.hairline, RoundedCornerShape(14.dp))
            .pressable(onClick = { onOpen(result.runId) })
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(41.dp).clip(CircleShape).border(1.dp, accent.copy(alpha = 0.48f), CircleShape), contentAlignment = Alignment.Center) {
            Text(result.totalScore?.roundToInt()?.toString() ?: "—", style = AnebType.StatValue, fontSize = 15.sp, color = accent)
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text("Token 仿真 · ${result.variant.uppercase()}", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.ink, maxLines = 1)
            Text(
                "${fmt.format(Date(result.startedAtEpochMs))} · ${result.verdict} · ${result.confidence}",
                fontSize = 10.sp,
                color = colors.muted,
                modifier = Modifier.padding(top = 3.dp),
                maxLines = 1,
            )
        }
        Text("›", fontSize = 18.sp, color = colors.faint)
    }
}

@Composable
private fun RealtimeSimulationHistoryRecord(result: RealtimeSimulationResultEntity, onOpen: (String) -> Unit) {
    val colors = AnebTheme.colors
    val accent = when (result.verdict) {
        "PASS" -> colors.excellent
        "FAIL" -> colors.poor
        "INCONCLUSIVE" -> colors.fair
        else -> colors.muted
    }
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.US) }
    Row(
        modifier = Modifier.fillMaxWidth().height(61.dp).clip(RoundedCornerShape(14.dp))
            .background(androidx.compose.ui.graphics.Color(0xB811162C))
            .border(1.dp, colors.hairline, RoundedCornerShape(14.dp))
            .pressable(onClick = { onOpen(result.runId) })
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(41.dp).clip(CircleShape).border(1.dp, accent.copy(alpha = 0.48f), CircleShape), contentAlignment = Alignment.Center) {
            Text(result.totalScore?.roundToInt()?.toString() ?: "—", style = AnebType.StatValue, fontSize = 15.sp, color = accent)
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text("AI 实时交互 · ${result.variant.uppercase()}", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.ink, maxLines = 1)
            Text(
                "${fmt.format(Date(result.startedAtEpochMs))} · ${result.verdict} · ${result.confidence}",
                fontSize = 10.sp,
                color = colors.muted,
                modifier = Modifier.padding(top = 3.dp),
                maxLines = 1,
            )
        }
        Text("›", fontSize = 18.sp, color = colors.faint)
    }
}
