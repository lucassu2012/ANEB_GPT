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
import com.aneb.probe.data.BasicSpeedResultEntity
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

/** SpeedTest 风格的基础网络实时页；所有数字来自 [BasicSpeedTelemetry]，无样本时显示破折号。 */
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
    val history = telemetry.historyMbps.map { (it / gaugeMax).toFloat().coerceIn(0f, 1f) }

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
                AnebMetric("Ping", telemetry.pingMs.oneOrDash(), "ms"),
                AnebMetric("抖动", telemetry.jitterMs.oneOrDash(), "ms"),
                AnebMetric("请求失败", telemetry.requestLossRate.percentOrDash(), "%"),
            ),
        )
        AnebSparkline(
            values = history,
            color = phaseColor,
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
            "刻度上限 ${gaugeMax.toInt()} Mbps · 指针每 100 ms 刷新",
            fontSize = 9.sp,
            color = colors.faint,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp),
        )

        AnebGradientCard(Modifier.fillMaxWidth(), radius = 14.dp) {
            Column(Modifier.padding(horizontal = 13.dp, vertical = 11.dp)) {
                Text("真实测试节点", fontSize = 9.sp, color = colors.muted)
                Text(nodeLabel, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = colors.ink)
            }
        }
        Spacer(Modifier.height(12.dp))
        AnebMetricTrio(
            listOf(
                AnebMetric("当前", live.oneOrDash(), "Mbps", phaseColor),
                AnebMetric("阶段平均", telemetry.phaseAverageMbps.oneOrDash(), "Mbps"),
                AnebMetric("进度", oneDecimal(telemetry.progress * 100.0), "%"),
            ),
        )
        Spacer(Modifier.height(22.dp))
    }
}

@Composable
private fun BasicPhaseRow(phase: BasicSpeedPhase) {
    val colors = AnebTheme.colors
    val active = when (phase) {
        BasicSpeedPhase.IDLE, BasicSpeedPhase.PREPARING, BasicSpeedPhase.LATENCY -> 0
        BasicSpeedPhase.DOWNLOAD -> 1
        BasicSpeedPhase.UPLOAD -> 2
        else -> 3
    }
    val labels = listOf("Ping", "下载", "上传", "结论")
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

/** 基础测速结果页：分离“测量事实”与版本化产品结论。 */
@Composable
fun BasicSpeedResultScreen(result: BasicSpeedResult, onBack: () -> Unit) {
    val colors = AnebTheme.colors
    val conclusions = BasicSpeedConclusions.build(result)
    Column(
        modifier = Modifier.fillMaxSize().background(colors.background).verticalScroll(rememberScrollState()),
    ) {
        Box(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 14.dp)) {
            Text("‹", fontSize = 30.sp, color = colors.ink, modifier = Modifier.align(Alignment.CenterStart).pressable(onClick = onBack).padding(6.dp))
            AnebWordmark(Modifier.align(Alignment.Center))
            Text("基础测速", fontSize = 10.sp, color = colors.brand, modifier = Modifier.align(Alignment.CenterEnd))
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("网络基本能力", fontSize = 22.sp, fontWeight = FontWeight.Light, color = colors.ink)
            Text("下载、上传、Ping、抖动与应用层请求失败", fontSize = 10.sp, color = colors.muted, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                BigMetric("↓", "下载", result.downloadMbps.oneOrDash(), "Mbps", colors.brand, Modifier.weight(1f))
                BigMetric("↑", "上传", result.uploadMbps.oneOrDash(), "Mbps", colors.brand2, Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            AnebMetricTrio(
                listOf(
                    AnebMetric("Ping", result.pingMs.oneOrDash(), "ms"),
                    AnebMetric("抖动", result.jitterMs.oneOrDash(), "ms"),
                    AnebMetric("请求失败", result.requestLossRate.percentOrDash(), "%"),
                ),
            )
            result.postLoadPingMs?.let {
                Text("负载后 Ping ${oneDecimal(it)} ms", fontSize = 10.sp, color = colors.muted, modifier = Modifier.padding(top = 9.dp))
            }

            Text("测试结论", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.ink, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
            conclusions.forEach { item ->
                val accent = when (item.tone) {
                    BasicSpeedConclusions.Tone.GOOD -> colors.excellent
                    BasicSpeedConclusions.Tone.CAUTION -> colors.fair
                    BasicSpeedConclusions.Tone.POOR -> colors.poor
                    BasicSpeedConclusions.Tone.NEUTRAL -> colors.muted
                }
                AnebGradientCard(Modifier.fillMaxWidth().padding(bottom = 8.dp), radius = 14.dp) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(accent).padding(top = 3.dp))
                        Spacer(Modifier.width(9.dp))
                        Column {
                            Text(item.title, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.ink)
                            Text(item.body, fontSize = 10.sp, lineHeight = 15.sp, color = colors.muted, modifier = Modifier.padding(top = 3.dp))
                        }
                    }
                }
            }
            Text(
                "结论策略 ${BasicSpeedConclusions.POLICY_ID} · 测量范围仅限 ANEB 应用到当前节点",
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
    BasicSpeedPhase.LATENCY -> "测量 Ping"
    BasicSpeedPhase.DOWNLOAD -> "测量下载"
    BasicSpeedPhase.UPLOAD -> "测量上传"
    BasicSpeedPhase.FINALIZING -> "生成结论"
    BasicSpeedPhase.COMPLETE -> "测试完成"
    BasicSpeedPhase.FAILED -> "测试失败"
}

private fun Double?.oneOrDash(): String = this?.let(::oneDecimal) ?: "—"
private fun Double?.percentOrDash(): String = this?.let { String.format(Locale.ROOT, "%.1f", it * 100.0) } ?: "—"
private fun oneDecimal(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

internal fun BasicSpeedResultEntity.toDomain(): BasicSpeedResult = BasicSpeedResult(
    runId = runId,
    startedAtEpochMs = startedAtEpochMs,
    serverBase = serverBase,
    profileVersion = profileVersion,
    status = status,
    downloadMbps = downloadMbps,
    uploadMbps = uploadMbps,
    pingMs = pingMs,
    jitterMs = jitterMs,
    requestLossRate = requestLossRate,
    postLoadPingMs = postLoadPingMs,
    downloadBytes = downloadBytes,
    uploadBytes = uploadBytes,
    transferErrors = transferErrors.lines().filter { it.isNotBlank() },
)
