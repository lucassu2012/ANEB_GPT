package com.aneb.probe.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import com.aneb.probe.engine.TokenConfidence
import com.aneb.probe.data.TokenSimulationResultEntity
import com.aneb.probe.engine.TokenSimulationPhase
import com.aneb.probe.engine.TokenSimulationResult
import com.aneb.probe.engine.TokenSimulationTelemetry
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

/** SpeedTest-level event-driven view: all geometry is fed by measured telemetry. */
@Composable
fun TokenSimulationTestingScreen(
    telemetry: TokenSimulationTelemetry,
    nodeLabel: String,
    onCancel: () -> Unit,
) {
    val colors = AnebTheme.colors
    val reducedMotion = LocalReducedMotion.current
    var nowNanos by remember { mutableLongStateOf(System.nanoTime()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(250)
            nowNanos = System.nanoTime()
        }
    }
    val fresh = telemetry.updatedAtNanos?.let { nowNanos - it <= 1_500_000_000L } == true
    val liveTps = telemetry.liveTokenPerSecond.takeIf { fresh }
    val liveUpload = telemetry.liveUploadMbps.takeIf { fresh }
    val liveDownload = telemetry.liveDownloadMbps.takeIf { fresh }
    val liveValue = when (telemetry.phase) {
        TokenSimulationPhase.UPLOADING -> liveUpload
        TokenSimulationPhase.DOWNLOADING -> liveDownload
        TokenSimulationPhase.STREAMING -> liveTps
        else -> null
    }
    val gaugeMax = when (telemetry.phase) {
        TokenSimulationPhase.UPLOADING, TokenSimulationPhase.DOWNLOADING -> speedCeiling(liveValue)
        else -> tokenCeiling(liveValue)
    }
    val target = liveValue?.div(gaugeMax)?.toFloat()?.coerceIn(0f, 1f) ?: 0f
    val needle by animateFloatAsState(
        targetValue = target,
        animationSpec = if (reducedMotion) tween(0) else spring(dampingRatio = 0.62f, stiffness = 210f),
        label = "token-sim-needle",
    )
    val accent = when (telemetry.phase) {
        TokenSimulationPhase.UPLOADING -> colors.brand2
        TokenSimulationPhase.DOWNLOADING -> colors.excellent
        else -> colors.brand
    }
    val historyMax = telemetry.tokenHistory.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0

    Column(
        Modifier.fillMaxSize().background(colors.background).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(52.dp)) {
            Text("×", fontSize = 28.sp, fontWeight = FontWeight.Light, color = colors.ink.copy(alpha = 0.78f), modifier = Modifier.align(Alignment.CenterStart).pressable(onClick = onCancel).padding(6.dp))
            AnebWordmark(Modifier.align(Alignment.Center))
            Text("仿真", fontSize = 9.sp, color = colors.brand, modifier = Modifier.align(Alignment.CenterEnd))
        }
        AnebMetricTrio(
            listOf(
                AnebMetric("仿真 Token", liveTps.oneOrDash(), "token/s", colors.brand),
                AnebMetric("应用 RTT", telemetry.liveRttMs.oneOrDash(), "ms"),
                AnebMetric("准时率", telemetry.liveOnTimeRatio.percentOrDash(), "%"),
            ),
        )
        AnebSparkline(
            values = telemetry.tokenHistory.map { (it / historyMax).toFloat().coerceIn(0f, 1f) },
            color = colors.brand,
            modifier = Modifier.fillMaxWidth().height(46.dp).padding(top = 9.dp),
            fill = true,
        )
        TokenPhaseRow(telemetry.phase)
        AnebScoreRing(
            score = null,
            valueText = liveValue?.let(::oneDecimal) ?: "—",
            fraction = if (liveValue != null) needle else telemetry.progress.toFloat(),
            accent = accent,
            label = when (telemetry.phase) {
                TokenSimulationPhase.UPLOADING, TokenSimulationPhase.DOWNLOADING -> "Mbps"
                TokenSimulationPhase.STREAMING -> "仿真 Token/s"
                else -> tokenPhaseLabel(telemetry.phase)
            },
            supporting = if (liveValue == null) "正在${tokenPhaseLabel(telemetry.phase)}" else "1 秒真实到达窗口 · 250 ms 刷新",
            modifier = Modifier.align(Alignment.CenterHorizontally).size(228.dp),
            needleFraction = liveValue?.let { needle },
            speedometerLayout = true,
        )
        Text(
            if (fresh || telemetry.updatedAtNanos == null) "无样本时不以 0 驱动仪表" else "实时窗口已过期，等待新事件",
            fontSize = 9.sp,
            color = colors.faint,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp),
        )
        AnebGradientCard(Modifier.fillMaxWidth(), radius = 14.dp) {
            Column(Modifier.padding(13.dp)) {
                Text("${workloadLabel(telemetry.workloadKind)} · ${telemetry.taskIndex}/${telemetry.taskCount}", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.ink)
                Text("$nodeLabel · 自建仿真节点", fontSize = 9.sp, color = colors.muted, modifier = Modifier.padding(top = 3.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        AnebMetricTrio(
            listOf(
                AnebMetric("上行", liveUpload.oneOrDash(), "Mbps", colors.brand2),
                AnebMetric("下行", liveDownload.oneOrDash(), "Mbps", colors.excellent),
                AnebMetric("进度", oneDecimal(telemetry.progress * 100.0), "%"),
            ),
        )
        Text(
            "模型为产品假设，不代表 Kimi、DeepSeek 或千问真实性能",
            fontSize = 9.sp,
            lineHeight = 14.sp,
            textAlign = TextAlign.Center,
            color = colors.faint,
            modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
        )
    }
}

@Composable
fun TokenSimulationResultScreen(result: TokenSimulationResult, onBack: () -> Unit) {
    val colors = AnebTheme.colors
    val score = result.score
    val accent = when (score.verdict) {
        TokenVerdict.PASS -> colors.excellent
        TokenVerdict.FAIL -> colors.poor
        TokenVerdict.INCONCLUSIVE -> colors.fair
        TokenVerdict.INVALID -> colors.muted
    }
    Column(Modifier.fillMaxSize().background(colors.background).verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 14.dp)) {
            Text("‹", fontSize = 30.sp, color = colors.ink, modifier = Modifier.align(Alignment.CenterStart).pressable(onClick = onBack).padding(6.dp))
            AnebWordmark(Modifier.align(Alignment.Center))
            Text("Token 仿真", fontSize = 10.sp, color = colors.brand, modifier = Modifier.align(Alignment.CenterEnd))
        }
        Column(Modifier.padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("多模态 AI 网络体验", fontSize = 22.sp, fontWeight = FontWeight.Light, color = colors.ink, modifier = Modifier.align(Alignment.Start))
            Text(
                "${result.variant.uppercase()} · ${result.evidence.tasks.size} 个任务 · ${score.confidence.name}",
                fontSize = 10.sp,
                color = colors.muted,
                modifier = Modifier.align(Alignment.Start).padding(top = 4.dp),
            )
            AnebScoreRing(
                score = score.totalScore?.toInt(),
                valueText = score.totalScore?.let(::oneDecimal) ?: "—",
                fraction = ((score.totalScore ?: 0.0) / 100.0).toFloat(),
                accent = accent,
                label = score.grade?.let { "$it 级" } ?: "不可评分",
                supporting = "${score.verdict.name} · ${confidenceLabel(score.confidence)}",
                modifier = Modifier.size(214.dp).padding(top = 10.dp),
            )
            AnebMetricTrio(
                listOf(
                    AnebMetric("Token 准时", score.metrics["TOK-B07"].ratioText(), "%", colors.brand),
                    AnebMetric("RTT 达标", score.metrics["TOK-N03"].ratioText(), "%"),
                    AnebMetric("上行达标", score.metrics["TOK-N06"].ratioText(), "%", colors.brand2),
                ),
            )
            Text("测试结论", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.ink, modifier = Modifier.align(Alignment.Start).padding(top = 20.dp, bottom = 8.dp))
            score.conclusions.forEach { conclusion ->
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
                    Text("上行突发 · 低时延启动 · Token 连续性 · 可选大文件下行", fontSize = 10.sp, lineHeight = 16.sp, color = colors.muted, modifier = Modifier.padding(top = 4.dp))
                    Text("${result.behaviorModelId}@${result.behaviorModelVersion} · ${result.calibrationStatus}", fontSize = 9.sp, color = colors.faint, modifier = Modifier.padding(top = 6.dp))
                }
            }
            Text(
                "评分 token-sim-score-v1 · 锚点 compliance-anchors-v1\n范围仅限本机到当前 ANEB 自建节点；不代表第三方 AI 服务性能",
                fontSize = 9.sp,
                lineHeight = 14.sp,
                textAlign = TextAlign.Center,
                color = colors.faint,
                modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
            )
        }
    }
}

/** Process-death-safe history projection; values are loaded from the frozen Room result, never rescored. */
@Composable
fun TokenSimulationStoredResultScreen(result: TokenSimulationResultEntity, onBack: () -> Unit) {
    val colors = AnebTheme.colors
    val verdict = runCatching { TokenVerdict.valueOf(result.verdict) }.getOrDefault(TokenVerdict.INVALID)
    val confidence = runCatching { TokenConfidence.valueOf(result.confidence) }.getOrDefault(TokenConfidence.INVALID)
    val accent = when (verdict) {
        TokenVerdict.PASS -> colors.excellent
        TokenVerdict.FAIL -> colors.poor
        TokenVerdict.INCONCLUSIVE -> colors.fair
        TokenVerdict.INVALID -> colors.muted
    }
    val metrics = remember(result.metricsJson) {
        runCatching { Json.parseToJsonElement(result.metricsJson).jsonObject }.getOrNull()
    }
    val conclusions = remember(result.conclusionsJson) {
        runCatching {
            Json.parseToJsonElement(result.conclusionsJson).jsonArray.map { it.jsonPrimitive.content }
        }.getOrDefault(listOf("历史结论数据不可读取；原始数据库行仍保留。"))
    }
    fun ratio(id: String): String = metrics?.get(id)?.jsonObject?.get("compliance_ratio")
        ?.jsonPrimitive?.doubleOrNull?.let { oneDecimal(it * 100.0) } ?: "—"

    Column(Modifier.fillMaxSize().background(colors.background).verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 14.dp)) {
            Text("‹", fontSize = 30.sp, color = colors.ink, modifier = Modifier.align(Alignment.CenterStart).pressable(onClick = onBack).padding(6.dp))
            AnebWordmark(Modifier.align(Alignment.Center))
            Text("Token 历史", fontSize = 10.sp, color = colors.brand, modifier = Modifier.align(Alignment.CenterEnd))
        }
        Column(Modifier.padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("多模态 AI 网络体验", fontSize = 22.sp, fontWeight = FontWeight.Light, color = colors.ink, modifier = Modifier.align(Alignment.Start))
            Text("${result.variant.uppercase()} · ${confidenceLabel(confidence)}", fontSize = 10.sp, color = colors.muted, modifier = Modifier.align(Alignment.Start).padding(top = 4.dp))
            AnebScoreRing(
                score = result.totalScore?.toInt(),
                valueText = result.totalScore?.let(::oneDecimal) ?: "—",
                fraction = ((result.totalScore ?: 0.0) / 100.0).toFloat(),
                accent = accent,
                label = result.grade?.let { "$it 级" } ?: "不可评分",
                supporting = "${result.verdict} · ${confidenceLabel(confidence)}",
                modifier = Modifier.size(214.dp).padding(top = 10.dp),
            )
            AnebMetricTrio(
                listOf(
                    AnebMetric("Token 准时", ratio("TOK-B07"), "%", colors.brand),
                    AnebMetric("RTT 达标", ratio("TOK-N03"), "%"),
                    AnebMetric("上行达标", ratio("TOK-N06"), "%", colors.brand2),
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
            Text(
                "${result.behaviorModelId}@${result.behaviorModelVersion} · ${result.calibrationStatus}\n${result.scorePolicyId} · ${result.scoreAnchorPolicyId}",
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
private fun TokenPhaseRow(phase: TokenSimulationPhase) {
    val colors = AnebTheme.colors
    val active = when (phase) {
        TokenSimulationPhase.IDLE, TokenSimulationPhase.PREPARING, TokenSimulationPhase.LATENCY -> 0
        TokenSimulationPhase.UPLOADING -> 1
        TokenSimulationPhase.PROCESSING, TokenSimulationPhase.STREAMING -> 2
        else -> 3
    }
    val labels = listOf("RTT", "上传", "Token", "结论")
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        labels.forEachIndexed { index, label ->
            Text(label, fontSize = 9.sp, color = if (index == active) colors.brand else colors.faint)
            if (index < labels.lastIndex) {
                Box(Modifier.weight(1f).padding(horizontal = 6.dp).height(1.dp).background(if (index < active) colors.brand else colors.hairline))
            }
        }
    }
}

private fun tokenPhaseLabel(phase: TokenSimulationPhase) = when (phase) {
    TokenSimulationPhase.IDLE, TokenSimulationPhase.PREPARING -> "校验模型"
    TokenSimulationPhase.LATENCY -> "测量 RTT"
    TokenSimulationPhase.UPLOADING -> "模拟上传"
    TokenSimulationPhase.PROCESSING -> "模拟 AI 处理"
    TokenSimulationPhase.STREAMING -> "接收 Token"
    TokenSimulationPhase.DOWNLOADING -> "接收返回文件"
    TokenSimulationPhase.FINALIZING -> "生成结论"
    TokenSimulationPhase.COMPLETE -> "测试完成"
    TokenSimulationPhase.FAILED -> "测试失败"
}

private fun workloadLabel(kind: String?) = when (kind) {
    "text" -> "文本对话"
    "document" -> "文档上传"
    "image" -> "图片理解"
    "video" -> "视频压力"
    else -> "准备任务"
}

private fun tokenCeiling(value: Double?): Double = listOf(10.0, 20.0, 40.0, 80.0, 120.0, 200.0).firstOrNull { (value ?: 0.0) <= it * 0.92 } ?: 400.0
private fun speedCeiling(value: Double?): Double = listOf(5.0, 10.0, 25.0, 50.0, 100.0, 250.0, 500.0).firstOrNull { (value ?: 0.0) <= it * 0.92 } ?: 1_000.0
private fun Double?.oneOrDash(): String = this?.let(::oneDecimal) ?: "—"
private fun Double?.percentOrDash(): String = this?.let { oneDecimal(it * 100.0) } ?: "—"
private fun com.aneb.probe.engine.TokenMetricEvidence?.ratioText(): String = this?.complianceRatio?.let { oneDecimal(it * 100.0) } ?: "—"
private fun oneDecimal(value: Double): String = String.format(Locale.ROOT, "%.1f", value)
private fun confidenceLabel(value: TokenConfidence) = when (value) {
    TokenConfidence.HIGH -> "高置信"
    TokenConfidence.MEDIUM -> "中置信"
    TokenConfidence.LOW -> "低置信"
    TokenConfidence.INVALID -> "证据无效"
}
