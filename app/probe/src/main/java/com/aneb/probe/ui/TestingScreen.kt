package com.aneb.probe.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

/**
 * AI 对话 / 编码 / 多模态实时测试剧场页：
 * 所有动效、打字机推进与卡顿高亮均严格由真实 [LiveTelemetry] 观测通道驱动（R-16 只读投影，绝不造假、绝不反压测量热路径）。
 */
@Composable
fun TestingScreen(
    logs: List<String>,
    telemetry: LiveTelemetry,
    nodeLabel: String,
    radioEvidenceLimited: Boolean,
    onCancel: () -> Unit,
) {
    val view = LocalView.current
    DisposableEffect(view) {
        val prev = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = prev }
    }
    val colors = AnebTheme.colors
    val progress = remember(logs.size) { TestProgressParser.parse(logs) }
    val activeProfileId = remember(telemetry.phase, progress.currentProfileId) {
        telemetry.phase?.substringBefore('#')?.takeIf { it.isNotBlank() } ?: progress.currentProfileId
    }
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
        Box(Modifier.fillMaxWidth().height(50.dp)) {
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
            Text(
                "${(progress.fraction * 100).roundToInt()}%",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.brand,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }

        TopQualityMetrics(telemetry)
        ScenarioPipelineStepper(activeProfileId = activeProfileId, progress = progress)

        // ---- 核心沉浸区：按当前实测场景自动切换「AI 对话打字机 / 编码 Agent 代码流 / 多模态上传」----
        LiveAiWorkloadTheater(
            profileId = activeProfileId,
            telemetry = telemetry,
            displayedRate = displayedRate,
            phaseName = progress.phaseName,
        )

        Spacer(Modifier.height(10.dp))

        // ---- 紧凑实时速率仪表 + 示波折线 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xC011162C))
                .border(1.dp, colors.hairline, RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnebScoreRing(
                score = displayedRate?.roundToInt() ?: score ?: if (telemetry.streamActive) null else (progress.fraction * 100).roundToInt(),
                fraction = if (hasLiveRate) animatedLiveFraction else score?.div(100f) ?: progress.fraction,
                accent = colors.brand,
                label = when {
                    hasLiveRate -> "事件/秒"
                    telemetry.streamActive -> "建窗中"
                    else -> "总进度"
                },
                supporting = if (hasLiveRate) "1s 滑窗" else progress.phaseName,
                modifier = Modifier.size(112.dp),
                needleFraction = if (hasLiveRate) animatedLiveFraction else null,
                speedometerLayout = telemetry.streamActive,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LiveDot(if ((telemetry.liveStreamGapMs ?: 0.0) > 250.0 && telemetry.streamActive) colors.poor else colors.brand)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (telemetry.streamActive) "实时流式速率曲线" else "正在执行 ${progress.phaseName}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    "已收 ${telemetry.tokensReceived + telemetry.liveStreamEvents} 帧 SSE 事件 · 仅展示真实到达节奏",
                    fontSize = 9.sp,
                    color = colors.muted,
                    modifier = Modifier.padding(top = 3.dp),
                )
                Spacer(Modifier.height(6.dp))
                AnebSparkline(
                    values = chartPoints,
                    color = if ((telemetry.liveStreamGapMs ?: 0.0) > 250.0 && telemetry.streamActive) colors.fair else colors.brand,
                    modifier = Modifier.fillMaxWidth().height(34.dp),
                    fill = true,
                )
                ItlLiveBars(
                    itlMs = telemetry.itlRecentMs,
                    modifier = Modifier.fillMaxWidth().height(16.dp).padding(top = 4.dp),
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        AnebMetricTrio(
            items = listOf(
                AnebMetric("首字响应", telemetry.ttftMs?.let { String.format(Locale.ROOT, "%.2f", it / 1_000.0) } ?: "—", "秒", colors.brand),
                AnebMetric("流式到达", displayedRate?.let { "${it.roundToInt()}" } ?: "—", "事件/秒", colors.excellent),
                AnebMetric("实时卡顿", telemetry.stallCount.toString(), "次", if (telemetry.stallCount > 0) colors.poor else colors.ink),
            ),
        )

        if (telemetry.upMbps != null || telemetry.rsrp != null || telemetry.sinr != null) {
            Spacer(Modifier.height(10.dp))
            AnebMetricTrio(
                items = listOf(
                    AnebMetric("上行吞吐", telemetry.upMbps?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "—", "Mbps", colors.brand2),
                    AnebMetric("RSRP", telemetry.rsrp?.toString() ?: "—", "dBm"),
                    AnebMetric("SINR", telemetry.sinr?.toString() ?: "—", "dB"),
                ),
            )
        }

        Spacer(Modifier.height(10.dp))
        ConnectionCard(
            title = telemetry.rat ?: "自动选择网络",
            subtitle = "$nodeLabel${if (radioEvidenceLimited) " · 无线归因低置信" else " · 真实测试节点"}",
        )
        Spacer(Modifier.height(22.dp))
    }
}

/**
 * 实时 AI 业务可视剧场：
 * 根据当前正在运行的真实 profile（`s1_chat` / `s2_coding_agent` / `s3_multimodal`）展示对应的
 * 实时对话打字机、编码 Agent 代码流、或多模态载荷上传+流式理解画面。
 * 字符推进严格按 `telemetry.liveStreamEvents` 驱动——网络停顿则光标真实停顿并亮出卡顿提示。
 */
@Composable
private fun LiveAiWorkloadTheater(
    profileId: String?,
    telemetry: LiveTelemetry,
    displayedRate: Double?,
    phaseName: String,
) {
    val colors = AnebTheme.colors
    val gapMs = telemetry.liveStreamGapMs
    val stallingNow = telemetry.streamActive && gapMs != null && gapMs >= 250.0
    val stageAccent = when (profileId) {
        "s2_coding_agent" -> colors.brand2
        "s3_multimodal" -> colors.excellent
        else -> colors.brand
    }
    val stageTitle = when (profileId) {
        "s1_chat" -> "💬 AI 对话流实时预览（类豆包 / DeepSeek）"
        "s2_coding_agent" -> "💻 编码 Agent 工具流实时预览"
        "s3_multimodal" -> "🖼️ 多模态文件上传 + 流式理解"
        else -> "✨ AI 实时流式工作负载预览"
    }
    val script = when (profileId) {
        "s2_coding_agent" -> CODING_PREVIEW_SCRIPT
        "s3_multimodal" -> MULTIMODAL_PREVIEW_SCRIPT
        else -> CHAT_PREVIEW_SCRIPT
    }
    // 严格由真实到达的 SSE 事件数映射可见字符数：事件不动，打字机绝不自己往前跑！
    val totalEvents = telemetry.liveStreamEvents
    val revealedChars = if (telemetry.streamActive && totalEvents > 0) {
        ((totalEvents * 2).coerceAtMost(script.length))
    } else if (!telemetry.streamActive && telemetry.tokensReceived > 0) {
        (script.length / 2).coerceAtMost(script.length)
    } else {
        0
    }
    val shownText = if (revealedChars > 0) script.substring(0, revealedChars) else ""

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xE8161C36), Color(0xF00A0E1F)),
                ),
            )
            .border(
                1.dp,
                if (stallingNow) colors.poor.copy(alpha = 0.6f) else stageAccent.copy(alpha = 0.34f),
                RoundedCornerShape(18.dp),
            )
            .padding(13.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stageTitle,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = stageAccent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            val statusBadge = when {
                stallingNow -> "停顿 ${gapMs?.roundToInt()}ms"
                telemetry.streamActive && displayedRate != null -> "吐字 ${displayedRate.roundToInt()} ev/s"
                telemetry.streamActive -> "等待首字 TTFT…"
                profileId == "s3_multimodal" -> "多模态上行/同步中"
                else -> "$phaseName 准备中"
            }
            val badgeColor = when {
                stallingNow -> colors.poor
                telemetry.streamActive -> colors.excellent
                else -> colors.fair
            }
            Text(
                statusBadge,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                color = badgeColor,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(badgeColor.copy(alpha = 0.14f))
                    .border(1.dp, badgeColor.copy(alpha = 0.35f), CircleShape)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }

        Spacer(Modifier.height(9.dp))

        if (profileId == "s3_multimodal") {
            // 多模态载荷可视化卡片：真实展示图片/文档上行状态与流式理解阶段
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MultimodalAssetChip(
                    icon = "🖼️",
                    title = "多模态图像载荷",
                    status = telemetry.upMbps?.let { "上行 ${String.format(Locale.ROOT, "%.1f", it)} Mbps" }
                        ?: if (telemetry.streamActive) "上传完成" else "正在上行突发…",
                    active = !telemetry.streamActive,
                    accent = colors.excellent,
                    modifier = Modifier.weight(1f),
                )
                MultimodalAssetChip(
                    icon = "✨",
                    title = "流式视觉理解",
                    status = if (telemetry.streamActive) "已收 $totalEvents 事件" else "待生成",
                    active = telemetry.streamActive,
                    accent = colors.brand,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
        } else {
            // 用户提问胶囊
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    if (profileId == "s2_coding_agent") {
                        "优化这段移动端流式解析代码并执行工具检查"
                    } else {
                        "帮我实时诊断当前 Wi-Fi / 5G 下的 AI 对话流畅度"
                    },
                    fontSize = 10.sp,
                    color = colors.ink,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp, 12.dp, 3.dp, 12.dp))
                        .background(stageAccent.copy(alpha = 0.18f))
                        .border(1.dp, stageAccent.copy(alpha = 0.28f), RoundedCornerShape(12.dp, 12.dp, 3.dp, 12.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        // AI 流式输出视窗（字符严格跟随真实 SSE 到达推进）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (profileId == "s2_coding_agent") Color(0xFF070B14) else Color(0xB80C1226))
                .border(1.dp, colors.hairline, RoundedCornerShape(12.dp))
                .padding(horizontal = 11.dp, vertical = 9.dp),
        ) {
            if (shownText.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LiveDot(stageAccent)
                    Spacer(Modifier.width(7.dp))
                    Text(
                        if (telemetry.streamActive) {
                            val waitText = gapMs?.let { "${it.roundToInt()} ms" } ?: "…"
                            "正在等待节点返回首个 SSE Token（已等待 $waitText）"
                        } else {
                            "正在建立时钟对齐 / 上行握手，准备接收真实流式输出…"
                        },
                        fontSize = 10.sp,
                        color = colors.muted,
                    )
                }
            } else {
                Text(
                    text = shownText + if (telemetry.streamActive) " ▍" else "",
                    fontSize = if (profileId == "s2_coding_agent") 9.5.sp else 10.5.sp,
                    lineHeight = 15.5.sp,
                    fontFamily = if (profileId == "s2_coding_agent") FontFamily.Monospace else FontFamily.Default,
                    color = if (profileId == "s2_coding_agent") Color(0xFF8BE9FD) else colors.ink,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            "演示文本仅按真实 SSE 到达事件同步推进，网络停顿即实时冻结光标；不代表大模型计费内容。",
            fontSize = 8.5.sp,
            color = colors.faint,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun MultimodalAssetChip(
    icon: String,
    title: String,
    status: String,
    active: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val colors = AnebTheme.colors
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) accent.copy(alpha = 0.14f) else Color(0x880B1022))
            .border(1.dp, if (active) accent.copy(alpha = 0.45f) else colors.hairline, RoundedCornerShape(12.dp))
            .padding(horizontal = 9.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(icon, fontSize = 14.sp)
        Spacer(Modifier.width(7.dp))
        Column {
            Text(title, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = colors.ink, maxLines = 1)
            Text(status, fontSize = 8.5.sp, color = if (active) accent else colors.muted, maxLines = 1)
        }
    }
}

@Composable
private fun ItlLiveBars(itlMs: List<Double>, modifier: Modifier = Modifier) {
    val colors = AnebTheme.colors
    Canvas(modifier = modifier) {
        if (itlMs.isEmpty()) return@Canvas
        val samples = itlMs.takeLast(32)
        val slotW = size.width / samples.size.coerceAtLeast(1)
        val barW = (slotW * 0.65f).coerceAtLeast(2f)
        samples.forEachIndexed { idx, value ->
            val norm = (value / 300.0).toFloat().coerceIn(0.12f, 1f)
            val barH = size.height * norm
            val x = idx * slotW + (slotW - barW) / 2f
            val color = when {
                value >= LiveTelemetry.LIVE_STALL_MS -> colors.poor
                value >= 150.0 -> colors.fair
                else -> colors.excellent
            }
            drawLine(
                color = color,
                start = Offset(x + barW / 2f, size.height),
                end = Offset(x + barW / 2f, size.height - barH),
                strokeWidth = barW,
            )
        }
    }
}

@Composable
private fun ScenarioPipelineStepper(
    activeProfileId: String?,
    progress: TestProgressParser.LiveProgress,
) {
    val colors = AnebTheme.colors
    val steps = listOf(
        "s1_chat" to "① AI 对话流",
        "s2_coding_agent" to "② 编码 Agent",
        "s3_multimodal" to "③ 多模态上传",
    )
    val activeIdx = steps.indexOfFirst { it.first == activeProfileId }.takeIf { it >= 0 }
        ?: progress.scenarioIndex.coerceIn(0, 2)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        steps.forEachIndexed { idx, (_, label) ->
            val isActive = idx == activeIdx && !progress.finished
            val isDone = idx < activeIdx || progress.finished
            val accent = when {
                isActive -> colors.brand
                isDone -> colors.excellent
                else -> colors.faint
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isActive) colors.brand.copy(alpha = 0.14f) else Color(0x700E1326))
                    .border(
                        1.dp,
                        if (isActive) colors.brand.copy(alpha = 0.45f) else colors.hairline,
                        RoundedCornerShape(10.dp),
                    )
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (isDone) "✓ ${label.substring(2)}" else label,
                    fontSize = 9.sp,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                    color = accent,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun TopQualityMetrics(telemetry: LiveTelemetry) {
    val colors = AnebTheme.colors
    val entries = listOf(
        "Ping" to (telemetry.rttMs?.let { "${it.roundToInt()} ms" } ?: "—"),
        "抖动" to (telemetry.jitterMs?.let { String.format(Locale.ROOT, "%.1f ms", it) } ?: "—"),
        "ITL 中位" to (telemetry.itlMedianMs?.let { "${it.roundToInt()} ms" } ?: "—"),
    )
    Row(Modifier.fillMaxWidth()) {
        entries.forEachIndexed { index, entry ->
            if (index > 0) Box(Modifier.width(1.dp).height(33.dp).background(colors.hairline))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(entry.first, fontSize = 9.5.sp, color = colors.muted)
                Text(entry.second, fontSize = 12.sp, fontWeight = FontWeight(560), color = colors.ink, modifier = Modifier.padding(top = 3.dp))
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
        Text("节点", fontSize = 10.sp, color = colors.brand, modifier = Modifier.border(1.dp, colors.brand.copy(alpha = 0.18f), CircleShape).padding(horizontal = 9.dp, vertical = 5.dp))
    }
}

@Composable
private fun LiveDot(color: Color) {
    val reduced = LocalReducedMotion.current
    val alpha = if (reduced) {
        1f
    } else {
        val transition = rememberInfiniteTransition(label = "testing-live")
        transition.animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(750, easing = LinearEasing), RepeatMode.Reverse),
            label = "testing-live-alpha",
        ).value
    }
    Box(Modifier.size(6.dp).clip(CircleShape).background(color.copy(alpha = alpha)))
}

private const val CHAT_PREVIEW_SCRIPT =
    "正在实时监测首字响应 TTFT 与逐字吐字间隔 ITL。当前链路的每一次 SSE 事件到达都在实时推进这段诊断流：若网络平稳，文字会保持均匀顺滑；若空口或路由器出现排队批化，吐字会立刻停顿并触发卡顿计数与波形尖峰。"

private const val CODING_PREVIEW_SCRIPT =
    "suspend fun streamAgentPatch(session: SseSession) {\n  val firstTokenMs = session.awaitFirstChunk()\n  session.collectDeltas { delta ->\n    toolLoop.verifyAndApply(delta)\n  }\n}"

private const val MULTIMODAL_PREVIEW_SCRIPT =
    "已完成多模态图像与上下文上行突发传输，正在流式接收视觉理解结果。ANEB 会同时记录上行吞吐 U1、首字等待 T1 与流式卡顿率 T3，用于精准判定传图、传文档与多模态问答体验。"

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
        val currentProfileId: String? = null,
    ) {
        val liveHint: String get() = "正在测：${phaseName}的 token 流是否顺滑"
        val stallTickPositions: List<Int>
            get() = if (stallCount <= 0) emptyList() else (1..stallCount).map {
                ((it.toFloat() / (stallCount + 1)) * 60f * fraction).toInt().coerceIn(0, 59)
            }
    }

    private val profileNames = mapOf(
        "s1_chat" to "AI 对话流",
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
            currentProfileId = currentProfile,
        )
    }

    private fun field(line: String, key: String): String? =
        Regex("(?:^|\\s)${Regex.escape(key)}=(\\S+)").find(line)?.groupValues?.get(1)
}
