package com.aneb.probe.ui

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aneb.probe.engine.BasicSpeedPhase
import com.aneb.probe.engine.BasicSpeedResult
import com.aneb.probe.engine.BasicSpeedTelemetry
import com.aneb.probe.data.NetworkComprehensiveResultEntity
import com.aneb.probe.engine.NetworkMetricEvidence
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
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** SpeedTest 风格的网络综合实时页；loaded RTT 是固定主状态，吞吐仪表是阶段辅指标。 */
@Composable
fun BasicSpeedTestingScreen(
    telemetry: BasicSpeedTelemetry,
    nodeLabel: String,
    onCancel: () -> Unit,
) {
    val colors = AnebTheme.colors
    val reducedMotion = LocalReducedMotion.current
    val live = telemetry.currentMbps
    val gaugeMax = speedometerCeiling(live ?: telemetry.phaseAverageMbps)
    val target = live?.div(gaugeMax)?.toFloat()?.coerceIn(0f, 1f) ?: 0f
    val needle by animateFloatAsState(
        targetValue = target,
        animationSpec = if (reducedMotion) tween(0) else spring(dampingRatio = 0.66f, stiffness = 220f),
        label = "basic-speed-needle",
    )
    val phaseColor = if (telemetry.phase == BasicSpeedPhase.UPLOAD) colors.brand2 else colors.brand
    val rttCeiling = latencyGaugeCeiling(telemetry.historyLoadedRttMs.maxOrNull() ?: telemetry.loadedRttMs)
    val history = telemetry.historyLoadedRttMs.map { (it / rttCeiling).toFloat().coerceIn(0f, 1f) }

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
                modifier = Modifier.align(Alignment.CenterStart).pressable(onClick = onCancel).padding(6.dp),
            )
            AnebWordmark(Modifier.align(Alignment.Center))
        }

        AnebMetricTrio(
            listOf(
                AnebMetric("负载 RTT", telemetry.loadedRttMs.oneOrDash(), "ms", colors.brand),
                AnebMetric("下载", telemetry.downloadMbps.oneOrDash(), "Mbps"),
                AnebMetric("上传", telemetry.uploadMbps.oneOrDash(), "Mbps", colors.brand2),
            ),
        )
        AnebSparkline(
            values = history,
            color = colors.brand,
            modifier = Modifier.fillMaxWidth().height(42.dp).padding(top = 9.dp),
            fill = true,
        )
        BasicPhaseRow(telemetry.phase)

        AnebScoreRing(
            score = null,
            valueText = live?.let(::oneDecimal) ?: "—",
            fraction = if (live != null) needle else telemetry.progress.toFloat(),
            accent = phaseColor,
            label = if (live != null) "Mbps" else phaseLabel(telemetry.phase),
            supporting = if (live != null) {
                "${phaseLabel(telemetry.phase)} · 1 秒实时窗口"
            } else {
                "正在${phaseLabel(telemetry.phase)}"
            },
            modifier = Modifier.align(Alignment.CenterHorizontally).size(228.dp),
            needleFraction = live?.let { needle },
            speedometerLayout = true,
        )

        Text(
            "负载 RTT 每 ${if (telemetry.phase in setOf(BasicSpeedPhase.DOWNLOAD, BasicSpeedPhase.UPLOAD)) "250" else "—"} ms 刷新 · 速率指针每 100 ms 刷新",
            fontSize = 9.sp,
            color = colors.faint,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp),
        )

        AnebGradientCard(Modifier.fillMaxWidth(), radius = 14.dp) {
            Column(Modifier.padding(horizontal = 13.dp, vertical = 11.dp)) {
                Text("ANEB 自建测试节点", fontSize = 9.sp, color = colors.muted)
                Text(nodeLabel, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = colors.ink)
            }
        }
        Spacer(Modifier.height(12.dp))
        AnebMetricTrio(
            listOf(
                AnebMetric("当前", live.oneOrDash(), "Mbps", phaseColor),
                AnebMetric("时延增量", telemetry.latencyDeltaMs.oneOrDash(), "ms"),
                AnebMetric("低速窗口", telemetry.lowSpeedWindowRatio.percentOrDash(), "%"),
            ),
        )
        Spacer(Modifier.height(22.dp))
    }
}

@Composable
private fun BasicPhaseRow(phase: BasicSpeedPhase) {
    val colors = AnebTheme.colors
    val active = when (phase) {
        BasicSpeedPhase.IDLE, BasicSpeedPhase.PREPARING, BasicSpeedPhase.HANDSHAKE -> 0
        BasicSpeedPhase.LATENCY -> 1
        BasicSpeedPhase.DOWNLOAD -> 2
        BasicSpeedPhase.UPLOAD -> 3
        BasicSpeedPhase.DATAGRAM -> 4
        else -> 5
    }
    val labels = listOf("握手", "空闲", "下载", "上传", "UDP", "结论")
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        labels.forEachIndexed { index, label ->
            Text(label, fontSize = 9.sp, color = if (index == active) colors.brand else colors.faint)
            if (index < labels.lastIndex) {
                Box(
                    Modifier.weight(1f).padding(horizontal = 6.dp).height(1.dp)
                        .background(if (index < active) colors.brand else colors.hairline),
                )
            }
        }
    }
}

/** 网络综合结果页：冻结独立分数、证据置信度与版本化结论。 */
@Composable
fun BasicSpeedResultScreen(result: BasicSpeedResult, onBack: () -> Unit) {
    val colors = AnebTheme.colors
    val accent = when (result.verdict) {
        TokenVerdict.PASS -> colors.excellent
        TokenVerdict.FAIL -> colors.poor
        TokenVerdict.INCONCLUSIVE -> colors.fair
        TokenVerdict.INVALID -> colors.muted
    }
    Column(
        modifier = Modifier.fillMaxSize().background(colors.background).verticalScroll(rememberScrollState()),
    ) {
        Box(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 14.dp)) {
            Text("‹", fontSize = 30.sp, color = colors.ink, modifier = Modifier.align(Alignment.CenterStart).pressable(onClick = onBack).padding(6.dp))
            AnebWordmark(Modifier.align(Alignment.Center))
            Text("网络综合", fontSize = 10.sp, color = colors.brand, modifier = Modifier.align(Alignment.CenterEnd))
        }
        Column(Modifier.padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("AI 业务路径综合能力", fontSize = 22.sp, fontWeight = FontWeight.Light, color = colors.ink, modifier = Modifier.align(Alignment.Start))
            Text("${result.variant.uppercase()} · ${confidenceLabel(result.confidence)}", fontSize = 10.sp, color = colors.muted, modifier = Modifier.align(Alignment.Start).padding(top = 4.dp))
            AnebScoreRing(
                score = result.totalScore?.toInt(),
                valueText = result.totalScore?.let(::oneDecimal) ?: "—",
                fraction = ((result.totalScore ?: 0.0) / 100.0).toFloat(),
                accent = accent,
                label = result.grade?.let { "$it 级" } ?: "不可评分",
                supporting = "${result.verdict.name} · ${confidenceLabel(result.confidence)}",
                modifier = Modifier.size(214.dp).padding(top = 10.dp),
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                BigMetric("↓", "下载 P5", result.downloadMbps.oneOrDash(), "Mbps", colors.brand, Modifier.weight(1f))
                BigMetric("↑", "上传 P5", result.uploadMbps.oneOrDash(), "Mbps", colors.brand2, Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            AnebMetricTrio(
                listOf(
                    AnebMetric("空闲 RTT P95", result.pingMs.oneOrDash(), "ms"),
                    AnebMetric("负载 RTT P95", result.loadedRttMs.oneOrDash(), "ms", colors.brand),
                    AnebMetric("时延增量", result.latencyDeltaMs.oneOrDash(), "ms"),
                ),
            )
            Spacer(Modifier.height(8.dp))
            AnebMetricTrio(listOf(
                AnebMetric("请求失败", result.requestLossRate.percentOrDash(), "%"),
                AnebMetric("吞吐波动", result.throughputRobustCv.percentOrDash(), "%"),
                AnebMetric("UDP 未返回", result.udpNonReturnRate.percentOrDash(), "%"),
            ))

            Text("测试结论", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.ink, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
            result.conclusions.forEach { conclusion ->
                AnebGradientCard(Modifier.fillMaxWidth().padding(bottom = 8.dp), radius = 14.dp) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                        Box(Modifier.padding(top = 5.dp).size(7.dp).clip(CircleShape).background(accent))
                        Spacer(Modifier.width(9.dp))
                        Text(conclusion, fontSize = 10.sp, lineHeight = 16.sp, color = colors.muted)
                    }
                }
            }
            AnebGradientCard(Modifier.fillMaxWidth().padding(top = 4.dp), radius = 14.dp) {
                Column(Modifier.padding(12.dp)) {
                    Text("业务行为特征", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = colors.ink)
                    Text("持续下行 · 持续上行 · 负载响应性 · 路径稳定性", fontSize = 10.sp, color = colors.muted, modifier = Modifier.padding(top = 4.dp))
                }
            }
            Text(
                "评分 ${result.scorePolicyId} · 锚点 ${result.scoreAnchorPolicyId}\n范围仅限本机到当前 ANEB 节点，不代表运营商全网评级",
                fontSize = 9.sp,
                lineHeight = 14.sp,
                textAlign = TextAlign.Center,
                color = colors.faint,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun BigMetric(symbol: String, label: String, value: String, unit: String, accent: Color, modifier: Modifier = Modifier) {
    val colors = AnebTheme.colors
    AnebGradientCard(modifier, radius = 16.dp) {
        Column(Modifier.padding(14.dp)) {
            Text("$symbol  $label", fontSize = 10.sp, color = accent)
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 8.dp)) {
                Text(value, fontSize = 28.sp, fontWeight = FontWeight.Light, color = colors.ink)
                Spacer(Modifier.width(4.dp))
                Text(unit, fontSize = 9.sp, color = colors.muted, modifier = Modifier.padding(bottom = 5.dp))
            }
        }
    }
}

private fun speedometerCeiling(value: Double?): Double {
    val v = value ?: return 100.0
    return listOf(10.0, 25.0, 50.0, 100.0, 250.0, 500.0, 1_000.0, 2_000.0)
        .firstOrNull { v <= it * 0.92 } ?: 5_000.0
}

private fun phaseLabel(phase: BasicSpeedPhase) = when (phase) {
    BasicSpeedPhase.IDLE, BasicSpeedPhase.PREPARING -> "准备测试"
    BasicSpeedPhase.HANDSHAKE -> "测量握手"
    BasicSpeedPhase.LATENCY -> "测量空闲时延"
    BasicSpeedPhase.DOWNLOAD -> "下载与负载时延"
    BasicSpeedPhase.UPLOAD -> "上传与负载时延"
    BasicSpeedPhase.DATAGRAM -> "测量 UDP 稳定性"
    BasicSpeedPhase.FINALIZING -> "生成结论"
    BasicSpeedPhase.COMPLETE -> "测试完成"
    BasicSpeedPhase.FAILED -> "测试失败"
}

private fun Double?.oneOrDash(): String = this?.let(::oneDecimal) ?: "—"
private fun Double?.percentOrDash(): String = this?.let { String.format(Locale.ROOT, "%.1f", it * 100.0) } ?: "—"
private fun oneDecimal(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

private fun latencyGaugeCeiling(value: Double?): Double {
    val v = value ?: return 300.0
    return listOf(50.0, 100.0, 200.0, 300.0, 500.0, 1_000.0, 2_000.0).firstOrNull { v <= it * 0.92 } ?: 5_000.0
}

private fun confidenceLabel(value: TokenConfidence) = when (value) {
    TokenConfidence.HIGH -> "高置信"
    TokenConfidence.MEDIUM -> "中置信"
    TokenConfidence.LOW -> "低置信"
    TokenConfidence.INVALID -> "证据无效"
}

internal fun NetworkComprehensiveResultEntity.toDomain(): BasicSpeedResult = BasicSpeedResult(
    runId = runId,
    startedAtEpochMs = startedAtEpochMs,
    serverBase = serverBase,
    claimScope = claimScope,
    profileId = profileId,
    profileVersion = profileVersion,
    variant = variant,
    scorePolicyId = scorePolicyId,
    scoreAnchorPolicyId = scoreAnchorPolicyId,
    conclusionPolicyId = conclusionPolicyId,
    status = status,
    totalScore = totalScore,
    grade = grade,
    verdict = runCatching { TokenVerdict.valueOf(verdict) }.getOrDefault(TokenVerdict.INVALID),
    confidence = runCatching { TokenConfidence.valueOf(confidence) }.getOrDefault(TokenConfidence.INVALID),
    downloadMbps = downloadMbps,
    uploadMbps = uploadMbps,
    pingMs = idleRttMs,
    loadedRttMs = loadedRttMs,
    latencyDeltaMs = latencyDeltaMs,
    jitterMs = jitterMs,
    requestLossRate = requestLossRate,
    throughputRobustCv = throughputRobustCv,
    udpNonReturnRate = udpNonReturnRate,
    postLoadPingMs = postLoadPingMs,
    downloadBytes = downloadBytes,
    uploadBytes = uploadBytes,
    transferErrors = transferErrors.lines().filter { it.isNotBlank() },
    metrics = parseNetworkMetrics(metricsJson),
    groupScores = parseDoubleMap(groupScoresJson),
    conclusions = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(conclusionsJson).jsonArray.map { it.jsonPrimitive.content } }.getOrDefault(emptyList()),
    evidenceJson = evidenceJson,
)

private fun parseNetworkMetrics(raw: String): Map<String, NetworkMetricEvidence> = runCatching {
    kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonObject.mapValues { (id, element) ->
        val obj = element.jsonObject
        NetworkMetricEvidence(
            metricId = id,
            value = obj["value"]?.jsonPrimitive?.doubleOrNull,
            complianceRatio = obj["compliance_ratio"]?.jsonPrimitive?.doubleOrNull,
            sampleCount = obj["sample_count"]?.jsonPrimitive?.intOrNull ?: 0,
            minimumSampleCount = obj["minimum_sample_count"]?.jsonPrimitive?.intOrNull ?: 0,
            score = obj["score"]?.jsonPrimitive?.doubleOrNull,
        )
    }
}.getOrDefault(emptyMap())

private fun parseDoubleMap(raw: String): Map<String, Double> = runCatching {
    kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonObject.mapValues { it.value.jsonPrimitive.double }
}.getOrDefault(emptyMap())
