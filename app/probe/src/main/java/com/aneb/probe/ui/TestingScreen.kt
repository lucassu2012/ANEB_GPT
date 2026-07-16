package com.aneb.probe.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aneb.probe.engine.LiveTelemetry
import com.aneb.probe.ui.components.AnebMetric
import com.aneb.probe.ui.components.AnebMetricTrio
import com.aneb.probe.ui.components.AnebScoreRing
import com.aneb.probe.ui.components.AnebSparkline
import com.aneb.probe.ui.components.AnebWordmark
import com.aneb.probe.ui.components.pressable
import com.aneb.probe.ui.theme.AnebTheme
import com.aneb.probe.ui.theme.LocalReducedMotion
import java.util.Locale
import kotlin.math.roundToInt

/** 测试中页：复刻 `testing.html`，所有读数仍来自真实 [LiveTelemetry]。 */
@Composable
fun TestingScreen(
    logs: List<String>,
    telemetry: LiveTelemetry,
    nodeLabel: String,
    radioEvidenceLimited: Boolean,
    onCancel: () -> Unit,
) {
    val colors = AnebTheme.colors
    val progress = remember(logs.size) { TestProgressParser.parse(logs) }
    val score = telemetry.aqsRunning?.roundToInt()
    val reducedMotion = LocalReducedMotion.current
    val gaugeMax = ((telemetry.streamTargetRatePerSec ?: 100.0) * 1.6).coerceAtLeast(20.0)
    val liveTargetFraction = telemetry.streamArrivalRatePerSec
        ?.div(gaugeMax)
        ?.toFloat()
        ?.coerceIn(0f, 1f)
        ?: 0f
    val animatedLiveFraction by animateFloatAsState(
        targetValue = liveTargetFraction,
        animationSpec = if (reducedMotion) tween(0) else spring(dampingRatio = 0.72f, stiffness = 260f),
        label = "live-arrival-gauge",
    )
    val displayedRate = if (telemetry.streamActive && telemetry.streamArrivalRatePerSec != null) {
        animatedLiveFraction * gaugeMax
    } else {
        null
    }
    val hasLiveRate = telemetry.streamActive && telemetry.streamArrivalRatePerSec != null
    val rateHistory = remember { mutableStateListOf<Float>() }
    LaunchedEffect(telemetry.streamArrivalRatePerSec, telemetry.streamActive, gaugeMax) {
        val sample = telemetry.streamArrivalRatePerSec
        if (telemetry.streamActive && sample != null) {
            rateHistory += (sample / gaugeMax).toFloat().coerceIn(0f, 1f)
            while (rateHistory.size > 40) rateHistory.removeAt(0)
        }
    }
    val chartPoints = if (rateHistory.size >= 2) rateHistory.toList() else telemetry.itlRecentMs.map {
        (1.0 - (it / (LiveTelemetry.LIVE_STALL_MS * 2.0)).coerceIn(0.0, 1.0)).toFloat()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(52.dp)) {
            Text(
                "×",
                fontSize = 28.sp,
                fontWeight = FontWeight.Light,
                color = colors.ink.copy(alpha = 0.78f),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .pressable(onClick = onCancel)
                    .padding(horizontal = 3.dp, vertical = 6.dp),
            )
            AnebWordmark(Modifier.align(Alignment.Center))
        }

        TopQualityMetrics(telemetry)
        AnebSparkline(
            values = chartPoints,
            color = colors.brand,
            modifier = Modifier.fillMaxWidth().height(31.dp).padding(top = 5.dp),
        )

        TestStepRow(progress)

        AnebScoreRing(
            score = displayedRate?.roundToInt() ?: score ?: if (telemetry.streamActive) null else (progress.fraction * 100).roundToInt(),
            fraction = if (hasLiveRate) animatedLiveFraction else score?.div(100f) ?: progress.fraction,
            accent = colors.brand,
            label = when {
                hasLiveRate -> "事件 / 秒"
                telemetry.streamActive -> "建立速率窗口"
                else -> "${(progress.fraction * 100).roundToInt()}%"
            },
            supporting = if (hasLiveRate) {
                "AI 流式到达速率 · 1 秒窗口"
            } else if (telemetry.streamActive) {
                "测试进度 · 等待首批流式事件"
            } else if (score != null) {
                "AI 体验分 · 测量中"
            } else {
                "测试进度 · ${progress.phaseName}"
            },
            modifier = Modifier.align(Alignment.CenterHorizontally).size(190.dp),
            needleFraction = if (hasLiveRate) animatedLiveFraction else null,
            speedometerLayout = telemetry.streamActive,
        )

        Row(
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 2.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LiveDot(colors.brand)
            Spacer(Modifier.width(7.dp))
            Text(
                if (telemetry.streamActive) "正在实时观察 ${progress.phaseName} 的流式到达" else "正在检查 ${progress.phaseName} 的稳定性",
                fontSize = 10.sp,
                color = colors.muted,
            )
        }

        ConnectionCard(
            title = telemetry.rat ?: "自动选择网络",
            subtitle = "$nodeLabel${if (radioEvidenceLimited) " · 无线归因低置信" else " · 真实测试节点"}",
        )

        Spacer(Modifier.height(12.dp))
        AnebMetricTrio(
            items = listOf(
                AnebMetric("首字响应", telemetry.ttftMs?.let { String.format(Locale.ROOT, "%.2f", it / 1_000.0) } ?: "—", "秒", colors.brand),
                AnebMetric("流式到达", displayedRate?.let { "${it.roundToInt()}" } ?: "—", "事件/秒", colors.excellent),
                AnebMetric("卡顿", telemetry.stallCount.toString(), "次", colors.ink),
            ),
        )

        if (telemetry.upMbps != null || telemetry.rsrp != null || telemetry.sinr != null) {
            Spacer(Modifier.height(12.dp))
            AnebMetricTrio(
                items = listOf(
                    AnebMetric("上行", telemetry.upMbps?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "—", "Mbps", colors.brand2),
                    AnebMetric("RSRP", telemetry.rsrp?.toString() ?: "—", "dBm"),
                    AnebMetric("SINR", telemetry.sinr?.toString() ?: "—", "dB"),
                ),
            )
        }
        Spacer(Modifier.height(22.dp))
    }
}

@Composable
private fun TopQualityMetrics(telemetry: LiveTelemetry) {
    val colors = AnebTheme.colors
    val entries = listOf(
        "Ping" to (telemetry.rttMs?.let { "${it.roundToInt()} ms" } ?: "—"),
        "抖动" to (telemetry.jitterMs?.let { String.format(Locale.ROOT, "%.1f ms", it) } ?: "—"),
        "丢包" to "—",
    )
    Row(Modifier.fillMaxWidth()) {
        entries.forEachIndexed { index, entry ->
            if (index > 0) Box(Modifier.width(1.dp).height(35.dp).background(colors.hairline))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(entry.first, fontSize = 10.sp, color = colors.muted)
                Text(entry.second, fontSize = 12.sp, fontWeight = FontWeight(560), color = colors.ink, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun TestStepRow(progress: TestProgressParser.LiveProgress) {
    val colors = AnebTheme.colors
    val active = when {
        progress.finished -> 2
        progress.scenarioIndex >= 2 -> 2
        else -> 1
    }
    val labels = listOf("连接", "流式", "上传")
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { index, label ->
            Text(
                label,
                fontSize = 9.sp,
                color = if (index == active) colors.brand else colors.faint,
            )
            if (index < labels.lastIndex) {
                Box(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 7.dp)
                        .height(1.dp)
                        .background(if (index < active) colors.brand else colors.hairline),
                )
            }
        }
    }
}

@Composable
private fun ConnectionCard(title: String, subtitle: String) {
    val colors = AnebTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xB811162C))
            .border(1.dp, colors.hairline, RoundedCornerShape(14.dp))
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp).clip(CircleShape).border(1.dp, colors.hairline, CircleShape), contentAlignment = Alignment.Center) {
            Text("◎", fontSize = 11.sp, color = colors.muted)
        }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.ink)
            Text(subtitle, fontSize = 10.sp, color = colors.muted, maxLines = 1)
        }
        Text("节点", fontSize = 10.sp, color = colors.brand, modifier = Modifier.border(1.dp, colors.brand.copy(alpha = 0.18f), CircleShape).padding(horizontal = 9.dp, vertical = 6.dp))
    }
}

@Composable
private fun LiveDot(color: Color) {
    val reduced = LocalReducedMotion.current
    val transition = rememberInfiniteTransition(label = "testing-live")
    val pulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(750, easing = LinearEasing), RepeatMode.Reverse),
        label = "testing-live-alpha",
    )
    Box(Modifier.size(6.dp).clip(CircleShape).background(color.copy(alpha = if (reduced) 1f else pulse)))
}

/** run 进度派生；只解析既有日志合同，不改测量语义。 */
object TestProgressParser {
    data class LiveProgress(
        val runId: String?,
        val scenarioIndex: Int,
        val totalScenarios: Int,
        val phaseName: String,
        val fraction: Float,
        val ttftMs: Double?,
        val stallCount: Int,
        val finished: Boolean,
        val finishedRunId: String?,
    ) {
        val liveHint: String get() = "正在测：${phaseName}的 token 流是否顺滑"
        val stallTickPositions: List<Int>
            get() = if (stallCount <= 0) emptyList() else (1..stallCount).map {
                ((it.toFloat() / (stallCount + 1)) * 60f * fraction).toInt().coerceIn(0, 59)
            }
    }

    private val profileNames = mapOf(
        "s1_chat" to "闲聊对话",
        "s2_coding_agent" to "编码 Agent 流",
        "s3_multimodal" to "多模态上传",
    )

    fun parse(logs: List<String>): LiveProgress {
        var runId: String? = null
        var total = 3
        var scenarioIndex = 0
        var currentProfile: String? = null
        var completedKpis = 0
        var latestTtft: Double? = null
        var stalls = 0
        var finished = false
        var finishedRunId: String? = null
        for (line in logs) {
            when {
                line.startsWith("RUN_START ") -> runId = field(line, "run_id")
                line.startsWith("ORDER ") -> field(line, "order")?.let { total = it.split(',').size.coerceAtLeast(1) }
                line.startsWith("SCENARIO_START ") -> {
                    scenarioIndex = field(line, "order_index")?.toIntOrNull() ?: scenarioIndex
                    currentProfile = field(line, "scenario")?.substringBefore('#')
                }
                line.startsWith("SCENARIO_KPI ") -> {
                    completedKpis++
                    field(line, "t1_ms")?.toDoubleOrNull()?.let { latestTtft = it }
                    val t3 = field(line, "t3")?.toDoubleOrNull()
                    if (t3 != null && t3 > 0.0) stalls++
                }
                line.startsWith("RUN_END ") -> {
                    finished = true
                    finishedRunId = field(line, "run_id") ?: runId
                }
            }
        }
        val fraction = ((completedKpis.toFloat() + if (finished) 0f else 0.5f) / total).coerceIn(0f, 1f)
        return LiveProgress(
            runId = runId,
            scenarioIndex = scenarioIndex.coerceIn(0, (total - 1).coerceAtLeast(0)),
            totalScenarios = total,
            phaseName = profileNames[currentProfile] ?: "网络场景",
            fraction = fraction,
            ttftMs = latestTtft,
            stallCount = stalls,
            finished = finished,
            finishedRunId = finishedRunId,
        )
    }

    private fun field(line: String, key: String): String? =
        Regex("(?:^|\\s)${Regex.escape(key)}=(\\S+)").find(line)?.groupValues?.get(1)
}
