package com.aneb.probe.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
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
    val view = LocalView.current
    DisposableEffect(view) {
        val prev = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = prev }
    }
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

        if (telemetry.isResponsivenessMode) {
            LiveBufferbloatOscilloscopeCard(
                idlePingMs = telemetry.pingMs,
                liveLoadedRttMs = telemetry.liveLoadedRttMs,
                historyMs = telemetry.loadedRttHistoryMs,
                phase = telemetry.phase,
            )
            Spacer(Modifier.height(12.dp))
        }

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
private fun LiveBufferbloatOscilloscopeCard(
    idlePingMs: Double?,
    liveLoadedRttMs: Double?,
    historyMs: List<Double>,
    phase: BasicSpeedPhase,
) {
    val colors = AnebTheme.colors
    val deltaMs = if (idlePingMs != null && liveLoadedRttMs != null) liveLoadedRttMs - idlePingMs else null
    val statusColor = when {
        deltaMs == null -> colors.brand
        deltaMs <= 40.0 -> colors.excellent
        deltaMs <= 120.0 -> colors.fair
        else -> colors.poor
    }
    val deltaBadge = when {
        deltaMs == null -> "等待满载回声…"
        else -> String.format(Locale.ROOT, "%+.0f ms 膨胀", deltaMs)
    }
    val phasePurpose = when (phase) {
        BasicSpeedPhase.IDLE, BasicSpeedPhase.PREPARING, BasicSpeedPhase.LATENCY ->
            "阶段 1/3 · 测空闲基准：记录网络空闲时的正常交互反应时间"
        BasicSpeedPhase.DOWNLOAD ->
            "阶段 2/3 · 模拟「边满载下载/刷高清视频边聊 AI」：观察延迟是否暴涨"
        BasicSpeedPhase.UPLOAD ->
            "阶段 3/3 · 模拟「边传大图/同步网盘边打 AI 语音」：检验上行排队卡顿"
        else ->
            "正在汇总高负载并发结论：对比空闲基准与满载延迟膨胀 Δ"
    }
    AnebGradientCard(Modifier.fillMaxWidth(), radius = 16.dp) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "高负载并发延迟监控（Bufferbloat）",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.ink,
                    )
                    Text(
                        "绿虚线=空闲基线 ${idlePingMs.oneOrDash()} ms · 实线=${if (phase == BasicSpeedPhase.UPLOAD) "上传" else "下载"}满载实时 RTT",
                        fontSize = 9.sp,
                        color = colors.muted,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Text(
                    deltaBadge,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = statusColor,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(statusColor.copy(alpha = 0.14f))
                        .border(1.dp, statusColor.copy(alpha = 0.35f), CircleShape)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            Text(
                phasePurpose,
                fontSize = 9.5.sp,
                lineHeight = 14.sp,
                color = colors.ink,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0x66121A30))
                    .border(1.dp, statusColor.copy(alpha = 0.22f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 9.dp, vertical = 6.dp),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(70.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0x99070B17))
                    .border(1.dp, colors.hairline, RoundedCornerShape(10.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val maxMs = maxOf(
                        120.0,
                        (idlePingMs ?: 0.0) * 2.5,
                        (historyMs.maxOrNull() ?: 0.0) * 1.15,
                    )
                    if (idlePingMs != null && idlePingMs.isFinite()) {
                        val baseY = size.height * (1f - (idlePingMs / maxMs).toFloat().coerceIn(0f, 1f))
                        drawLine(
                            color = colors.excellent.copy(alpha = 0.7f),
                            start = Offset(0f, baseY),
                            end = Offset(size.width, baseY),
                            strokeWidth = 1.2.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 6f)),
                        )
                    }
                    if (historyMs.size >= 2) {
                        val path = Path()
                        val stepX = size.width / (historyMs.size - 1).coerceAtLeast(1)
                        historyMs.forEachIndexed { idx, v ->
                            val x = idx * stepX
                            val y = size.height * (1f - (v / maxMs).toFloat().coerceIn(0.04f, 1f))
                            if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(
                            path = path,
                            color = statusColor,
                            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                        )
                    } else if (historyMs.size == 1) {
                        val y = size.height * (1f - (historyMs.first() / maxMs).toFloat().coerceIn(0.04f, 1f))
                        drawCircle(statusColor, 3.dp.toPx(), Offset(size.width / 2f, y))
                    }
                }
                if (historyMs.isEmpty()) {
                    Text(
                        "进入满载下载/上传阶段后实时绘制延迟膨胀轨迹",
                        fontSize = 9.sp,
                        color = colors.faint,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }
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
fun BasicSpeedResultScreen(
    result: BasicSpeedResult,
    onBack: () -> Unit,
    onOpenAppCompanion: (() -> Unit)? = null,
) {
    if (result.profileId == com.aneb.probe.engine.NetworkSpeedEngine.RESPONSIVENESS_PROFILE_ID) {
        LoadedResponsivenessScreen(result, onBack)
        return
    }
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
            val douyinVerdict = remember(result.downloadMbps, result.pingMs, result.requestLossRate) {
                BusinessExperienceEvaluator.evaluateDouyin(result)
            }
            Text(
                "抖音短视频体验评估（P40 Pro 实测拐点校准）",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.ink,
                modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
            )
            ExperienceVerdictCard(
                verdict = douyinVerdict,
                actionLabel = if (onOpenAppCompanion != null) "打开抖音 / 豆包对照实测 ›" else null,
                onAction = onOpenAppCompanion,
            )
            val verdicts = remember(result.downloadMbps, result.uploadMbps, result.pingMs, result.jitterMs) {
                AiScenarioAdvisor.advise(
                    downMbps = result.downloadMbps,
                    upMbps = result.uploadMbps,
                    rttMs = result.pingMs,
                    jitterMs = result.jitterMs,
                )
            }
            Text(
                "AI 场景适配（按基础测速实测 · 良级门限）",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.ink,
                modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
            )
            AnebGradientCard(Modifier.fillMaxWidth().padding(bottom = 8.dp), radius = 14.dp) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    verdicts.forEach { v ->
                        val statusColor = when (v.suitable) {
                            true -> colors.excellent
                            false -> colors.poor
                            null -> colors.neutral
                        }
                        val statusText = when (v.suitable) {
                            true -> "✓ 适合"
                            false -> "✗ 不适合"
                            null -> "— 无法判定"
                        }
                        Column(Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("${v.code} ${v.title}", fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = colors.ink)
                                Text(statusText, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = statusColor)
                            }
                            Text(v.requirement, fontSize = 9.5.sp, lineHeight = 14.sp, color = colors.faint, modifier = Modifier.padding(top = 2.dp))
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
    profileId = profileId,
)
