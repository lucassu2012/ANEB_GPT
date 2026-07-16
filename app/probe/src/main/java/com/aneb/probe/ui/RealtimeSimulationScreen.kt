package com.aneb.probe.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aneb.probe.data.RealtimeSimulationResultEntity
import com.aneb.probe.engine.RealtimeMetricEvidence
import com.aneb.probe.engine.RealtimeSimulationPhase
import com.aneb.probe.engine.RealtimeSimulationResult
import com.aneb.probe.engine.RealtimeSimulationTelemetry
import com.aneb.probe.engine.TokenConfidence
import com.aneb.probe.engine.TokenVerdict
import com.aneb.probe.ui.components.AnebGradientCard
import com.aneb.probe.ui.components.AnebMetric
import com.aneb.probe.ui.components.AnebMetricTrio
import com.aneb.probe.ui.components.AnebScoreRing
import com.aneb.probe.ui.components.AnebSparkline
import com.aneb.probe.ui.components.AnebWordmark
import com.aneb.probe.ui.components.pressable
import com.aneb.probe.ui.theme.AnebTheme
import com.aneb.probe.ui.theme.LocalReducedMotion
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 关键业务指标是 2 秒窗口内的 150 ms 音频帧限时送达率。 */
@Composable
fun RealtimeSimulationTestingScreen(
    telemetry: RealtimeSimulationTelemetry,
    nodeLabel: String,
    onCancel: () -> Unit,
) {
    val colors = AnebTheme.colors
    val reducedMotion = LocalReducedMotion.current
    var nowNanos by remember { mutableLongStateOf(System.nanoTime()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(100)
            nowNanos = System.nanoTime()
        }
    }
    val fresh = telemetry.updatedAtNanos?.let { nowNanos - it <= 1_500_000_000L } == true
    val onTime = telemetry.liveOnTimeRatio.takeIf { fresh }
    val target = onTime?.toFloat()?.coerceIn(0f, 1f) ?: telemetry.progress.toFloat().coerceIn(0f, 1f)
    val needle by animateFloatAsState(
        targetValue = target,
        animationSpec = if (reducedMotion) tween(0) else spring(dampingRatio = 0.62f, stiffness = 205f),
        label = "realtime-deadline-needle",
    )
    val accent = when (telemetry.phase) {
        RealtimeSimulationPhase.SPEAKING -> colors.brand2
        RealtimeSimulationPhase.PLAYING -> colors.excellent
        RealtimeSimulationPhase.BARGE_IN -> colors.fair
        else -> colors.brand
    }

    Column(
        Modifier.fillMaxSize().background(colors.background).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(52.dp)) {
            Text("×", fontSize = 28.sp, fontWeight = FontWeight.Light, color = colors.ink.copy(alpha = 0.78f), modifier = Modifier.align(Alignment.CenterStart).pressable(onClick = onCancel).padding(6.dp))
            AnebWordmark(Modifier.align(Alignment.Center))
            Text("LIVE 仿真", fontSize = 9.sp, color = colors.brand, modifier = Modifier.align(Alignment.CenterEnd))
        }
        AnebMetricTrio(
            listOf(
                AnebMetric("准时帧", onTime.percentOrDash(), "%", colors.brand),
                AnebMetric("播放余量", telemetry.liveHeadroomMs.oneOrDash(), "ms", colors.excellent),
                AnebMetric("应用 RTT", telemetry.liveRttMs.oneOrDash(), "ms"),
            ),
        )
        AnebSparkline(
            values = telemetry.waveform.map { it.toFloat().coerceIn(0f, 1f) },
            color = accent,
            modifier = Modifier.fillMaxWidth().height(48.dp).padding(top = 9.dp),
            fill = true,
        )
        RealtimePhaseRow(telemetry.phase)
        AnebScoreRing(
            score = null,
            valueText = onTime?.let { oneDecimal(it * 100.0) } ?: "—",
            fraction = needle,
            accent = accent,
            label = if (onTime == null) realtimePhaseLabel(telemetry.phase) else "150 ms 准时帧 %",
            supporting = if (onTime == null) "正在${realtimePhaseLabel(telemetry.phase)}" else "2 秒到达窗口 · 100 ms 动态刷新",
            modifier = Modifier.align(Alignment.CenterHorizontally).size(228.dp),
            needleFraction = onTime?.let { needle },
            speedometerLayout = true,
        )
        Text(
            if (fresh || telemetry.updatedAtNanos == null) "缺失样本显示为 —，不以 0 驱动仪表" else "实时窗口已过期，等待新事件",
            fontSize = 9.sp,
            color = colors.faint,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp),
        )
        AnebGradientCard(Modifier.fillMaxWidth(), radius = 14.dp) {
            Column(Modifier.padding(13.dp)) {
                Text(
                    "会话 ${telemetry.sessionIndex}/${telemetry.sessionCount} · 轮次 ${telemetry.turnIndex}/${telemetry.turnCount}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.ink,
                )
                Text("$nodeLabel · 双向实时音频行为仿真", fontSize = 9.sp, color = colors.muted, modifier = Modifier.padding(top = 3.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        AnebMetricTrio(
            listOf(
                AnebMetric("上行", telemetry.liveUpKbps.oneOrDash(), "kbps", colors.brand2),
                AnebMetric("下行", telemetry.liveDownKbps.oneOrDash(), "kbps", colors.excellent),
                AnebMetric("首帧响应", telemetry.lastResponseMs.oneOrDash(), "ms"),
            ),
        )
        Text(
            "固定负载用于隔离网络影响；产品行为模型为假设，不代表 GPT Live 或其他真实 AI 服务性能",
            fontSize = 9.sp,
            lineHeight = 14.sp,
            textAlign = TextAlign.Center,
            color = colors.faint,
            modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
        )
    }
}

@Composable
fun RealtimeSimulationResultScreen(result: RealtimeSimulationResult, onBack: () -> Unit) {
    RealtimeResultContent(
        titleSuffix = "AI 实时交互",
        variant = result.variant,
        scoreValue = result.score.totalScore,
        grade = result.score.grade,
        verdict = result.score.verdict,
        confidence = result.score.confidence,
        metrics = result.score.metrics,
        conclusions = result.score.conclusions,
        modelLine = "${result.behaviorModelId}@${result.behaviorModelVersion} · ${result.calibrationStatus}",
        onBack = onBack,
    )
}

/** 历史页只读取冻结结果，不以当前版本算法重算。 */
@Composable
fun RealtimeSimulationStoredResultScreen(result: RealtimeSimulationResultEntity, onBack: () -> Unit) {
    val verdict = runCatching { TokenVerdict.valueOf(result.verdict) }.getOrDefault(TokenVerdict.INVALID)
    val confidence = runCatching { TokenConfidence.valueOf(result.confidence) }.getOrDefault(TokenConfidence.INVALID)
    val metricsJson = remember(result.metricsJson) {
        runCatching { Json.parseToJsonElement(result.metricsJson).jsonObject }.getOrNull()
    }
    val conclusions = remember(result.conclusionsJson) {
        runCatching { Json.parseToJsonElement(result.conclusionsJson).jsonArray.map { it.jsonPrimitive.content } }
            .getOrDefault(listOf("历史结论数据不可读取；原始数据库行仍保留。"))
    }
    fun metric(id: String): RealtimeMetricEvidence? {
        val raw = metricsJson?.get(id)?.jsonObject ?: return null
        return RealtimeMetricEvidence(
            metricId = id,
            value = raw["value"]?.jsonPrimitive?.doubleOrNull,
            complianceRatio = raw["compliance_ratio"]?.jsonPrimitive?.doubleOrNull,
            sampleCount = raw["sample_count"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            minimumSampleCount = raw["minimum_sample_count"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            targetComplianceRatio = raw["target_compliance_ratio"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            score = raw["score"]?.jsonPrimitive?.doubleOrNull,
        )
    }
    val metrics = listOf("LIVE-B05", "LIVE-N02", "LIVE-B08").associateWith(::metric).mapNotNull { (id, value) -> value?.let { id to it } }.toMap()
    RealtimeResultContent(
        titleSuffix = "LIVE 历史",
        variant = result.variant,
        scoreValue = result.totalScore,
        grade = result.grade,
        verdict = verdict,
        confidence = confidence,
        metrics = metrics,
        conclusions = conclusions,
        modelLine = "${result.behaviorModelId}@${result.behaviorModelVersion} · ${result.calibrationStatus}",
        onBack = onBack,
    )
}

@Composable
private fun RealtimeResultContent(
    titleSuffix: String,
    variant: String,
    scoreValue: Double?,
    grade: String?,
    verdict: TokenVerdict,
    confidence: TokenConfidence,
    metrics: Map<String, RealtimeMetricEvidence>,
    conclusions: List<String>,
    modelLine: String,
    onBack: () -> Unit,
) {
    val colors = AnebTheme.colors
    val accent = when (verdict) {
        TokenVerdict.PASS -> colors.excellent
        TokenVerdict.FAIL -> colors.poor
        TokenVerdict.INCONCLUSIVE -> colors.fair
        TokenVerdict.INVALID -> colors.muted
    }
    Column(Modifier.fillMaxSize().background(colors.background).verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 14.dp)) {
            Text("‹", fontSize = 30.sp, color = colors.ink, modifier = Modifier.align(Alignment.CenterStart).pressable(onClick = onBack).padding(6.dp))
            AnebWordmark(Modifier.align(Alignment.Center))
            Text(titleSuffix, fontSize = 10.sp, color = colors.brand, modifier = Modifier.align(Alignment.CenterEnd))
        }
        Column(Modifier.padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("AI 实时交互网络体验", fontSize = 22.sp, fontWeight = FontWeight.Light, color = colors.ink, modifier = Modifier.align(Alignment.Start))
            Text("${variant.uppercase()} · ${confidenceLabel(confidence)}", fontSize = 10.sp, color = colors.muted, modifier = Modifier.align(Alignment.Start).padding(top = 4.dp))
            AnebScoreRing(
                score = scoreValue?.toInt(),
                valueText = scoreValue?.let(::oneDecimal) ?: "—",
                fraction = ((scoreValue ?: 0.0) / 100.0).toFloat(),
                accent = accent,
                label = grade?.let { "$it 级" } ?: "不可评分",
                supporting = "${verdict.name} · ${confidenceLabel(confidence)}",
                modifier = Modifier.size(214.dp).padding(top = 10.dp),
            )
            AnebMetricTrio(
                listOf(
                    AnebMetric("准时帧", metrics["LIVE-B05"].ratioText(), "%", colors.brand),
                    AnebMetric("RTT 达标", metrics["LIVE-N02"].ratioText(), "%"),
                    AnebMetric("打断达标", metrics["LIVE-B08"].ratioText(), "%", colors.brand2),
                ),
            )
            Text("测试结论", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.ink, modifier = Modifier.align(Alignment.Start).padding(top = 20.dp, bottom = 8.dp))
            conclusions.forEach { conclusion ->
                AnebGradientCard(Modifier.fillMaxWidth().padding(bottom = 8.dp), radius = 14.dp) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                        Box(Modifier.padding(top = 5.dp).size(7.dp).clip(CircleShape).background(accent))
                        Text(conclusion, fontSize = 10.sp, lineHeight = 16.sp, color = colors.muted, modifier = Modifier.padding(start = 9.dp))
                    }
                }
            }
            AnebGradientCard(Modifier.fillMaxWidth().padding(top = 4.dp), radius = 14.dp) {
                Column(Modifier.padding(12.dp)) {
                    Text("业务行为特征", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = colors.ink)
                    Text("持续双向小包 · 低尾时延 · 低到达变动 · 稳定长连接 · 可打断播放", fontSize = 10.sp, lineHeight = 16.sp, color = colors.muted, modifier = Modifier.padding(top = 4.dp))
                    Text(modelLine, fontSize = 9.sp, color = colors.faint, modifier = Modifier.padding(top = 6.dp))
                }
            }
            Text(
                "评分 realtime-interaction-score-v1 · 锚点 compliance-anchors-v1\n范围仅限本机到当前 ANEB 自建节点；不代表第三方 AI 服务性能",
                fontSize = 9.sp,
                lineHeight = 14.sp,
                textAlign = TextAlign.Center,
                color = colors.faint,
                modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
            )
        }
    }
}

@Composable
private fun RealtimePhaseRow(phase: RealtimeSimulationPhase) {
    val colors = AnebTheme.colors
    val active = when (phase) {
        RealtimeSimulationPhase.IDLE, RealtimeSimulationPhase.PREPARING, RealtimeSimulationPhase.CONNECTING, RealtimeSimulationPhase.CLOCK_SYNC -> 0
        RealtimeSimulationPhase.SPEAKING, RealtimeSimulationPhase.WAITING -> 1
        RealtimeSimulationPhase.PLAYING -> 2
        RealtimeSimulationPhase.BARGE_IN -> 3
        else -> 4
    }
    val labels = listOf("连接", "说话", "播放", "打断", "结论")
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        labels.forEachIndexed { index, label ->
            Text(label, fontSize = 9.sp, color = if (index == active) colors.brand else colors.faint)
            if (index < labels.lastIndex) {
                Box(Modifier.weight(1f).padding(horizontal = 5.dp).height(1.dp).background(if (index < active) colors.brand else colors.hairline))
            }
        }
    }
}

private fun realtimePhaseLabel(phase: RealtimeSimulationPhase) = when (phase) {
    RealtimeSimulationPhase.IDLE, RealtimeSimulationPhase.PREPARING -> "校验模型"
    RealtimeSimulationPhase.CONNECTING -> "建立会话"
    RealtimeSimulationPhase.CLOCK_SYNC -> "同步时钟"
    RealtimeSimulationPhase.SPEAKING -> "模拟说话"
    RealtimeSimulationPhase.WAITING -> "等待首帧"
    RealtimeSimulationPhase.PLAYING -> "连续播放"
    RealtimeSimulationPhase.BARGE_IN -> "测试打断"
    RealtimeSimulationPhase.FINALIZING -> "生成结论"
    RealtimeSimulationPhase.COMPLETE -> "测试完成"
    RealtimeSimulationPhase.FAILED -> "测试失败"
}

private fun Double?.oneOrDash(): String = this?.takeIf { it.isFinite() }?.let(::oneDecimal) ?: "—"
private fun Double?.percentOrDash(): String = this?.takeIf { it.isFinite() }?.let { oneDecimal(it * 100.0) } ?: "—"
private fun RealtimeMetricEvidence?.ratioText(): String = this?.complianceRatio?.takeIf { it.isFinite() }?.let { oneDecimal(it * 100.0) } ?: "—"
private fun oneDecimal(value: Double): String = String.format(Locale.ROOT, "%.1f", value)
private fun confidenceLabel(value: TokenConfidence) = when (value) {
    TokenConfidence.HIGH -> "高置信"
    TokenConfidence.MEDIUM -> "中置信"
    TokenConfidence.LOW -> "低置信"
    TokenConfidence.INVALID -> "证据无效"
}
