package com.aneb.probe.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aneb.probe.R
import com.aneb.probe.engine.LiveTelemetry
import com.aneb.probe.ui.components.AnebGlyph
import com.aneb.probe.ui.components.AnebIcon
import com.aneb.probe.ui.components.AnebMetric
import com.aneb.probe.ui.components.AnebMetricTrio
import com.aneb.probe.ui.components.AnebWordmark
import com.aneb.probe.ui.components.pressable
import com.aneb.probe.ui.theme.AnebTheme
import com.aneb.probe.ui.theme.LocalReducedMotion
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * AI 对话 / 编码 Agent / 多模态巡检实时测试工作台：
 * - 上半区：22Hz 高频网络与 SSE 实时遥测控制台（自动缩放微抖动脉冲柱 + 250ms 瞬时速率曲线，
 *   从启动首 150ms 即实时跳动，清晰放大真实网络波动与卡顿尖峰）；
 * - 下半区：严格固定高度（368dp）自动滚动的真实业务作业窗口（商业决策深度对话 /
 *   Cursor 级 Coding Agent Diff + 工具执行轨 / 4K 真实机房光路 AI 视觉巡检），绝不因内容增长抖动高度；
 * - 100% R-16 只读投影，零 emoji，零测量路径干预。
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

    // 优先使用 250ms 高敏短窗瞬时 SSE 到达速率，大幅提升刷新灵敏度与波动幅度表现
    val instantRate = telemetry.fastStreamRatePerSec ?: telemetry.streamArrivalRatePerSec
    val rateHistory = remember { mutableStateListOf<Double>() }
    LaunchedEffect(
        instantRate,
        telemetry.upMbps,
        telemetry.liveRecentIntervalsMs.size,
        telemetry.liveStreamEvents,
        telemetry.liveSubPhase,
    ) {
        val sample = when {
            telemetry.streamActive && instantRate != null -> instantRate
            telemetry.liveSubPhase == "upload" && telemetry.upMbps != null -> telemetry.upMbps
            telemetry.liveRecentIntervalsMs.isNotEmpty() -> telemetry.liveRecentIntervalsMs.last()
            else -> null
        }
        if (sample != null && sample.isFinite() && sample >= 0.0) {
            rateHistory += sample
            while (rateHistory.size > 48) rateHistory.removeAt(0)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp),
    ) {
        // ---- 顶栏 ----
        Box(Modifier.fillMaxWidth().height(46.dp)) {
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
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = colors.brand,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }

        // ---- 场景流水线指示 ----
        ScenarioPipelineStepper(activeProfileId = activeProfileId, progress = progress)

        // ---- 上半区：高频实时指标与动态放大波形示波台（位置固定在业务窗上方）----
        TopQualityMetrics(telemetry = telemetry, activeProfileId = activeProfileId)
        Spacer(Modifier.height(8.dp))
        LiveHighFreqTelemetryConsole(
            telemetry = telemetry,
            activeProfileId = activeProfileId,
            instantRate = instantRate,
            rateHistory = rateHistory.toList(),
        )

        Spacer(Modifier.height(10.dp))

        // ---- 下半区：严格固定高度（368dp）真实业务场景滚动视窗（大小恒定不跳动）----
        AuthenticWorkloadViewport(
            profileId = activeProfileId,
            telemetry = telemetry,
            instantRate = instantRate,
            phaseName = progress.phaseName,
        )

        // ---- 底部紧凑链路与节点信息条 ----
        Spacer(Modifier.height(8.dp))
        FooterStatusStrip(
            nodeLabel = nodeLabel,
            telemetry = telemetry,
            radioEvidenceLimited = radioEvidenceLimited,
        )
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun ScenarioPipelineStepper(
    activeProfileId: String?,
    progress: TestProgressParser.LiveProgress,
) {
    val colors = AnebTheme.colors
    val steps = listOf(
        Triple("s1_chat", AnebGlyph.Chat, "01 AI 对话"),
        Triple("s2_coding_agent", AnebGlyph.Code, "02 编码 Agent"),
        Triple("s3_multimodal", AnebGlyph.Image, "03 多模态巡检"),
    )
    val activeIdx = steps.indexOfFirst { it.first == activeProfileId }.takeIf { it >= 0 }
        ?: progress.scenarioIndex.coerceIn(0, 2)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        steps.forEachIndexed { idx, (_, glyph, label) ->
            val current = idx == activeIdx
            val done = idx < activeIdx || progress.finished
            val accent = when {
                current -> colors.brand
                done -> colors.excellent
                else -> colors.faint
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (current) accent.copy(alpha = 0.14f) else Color(0x660B1020))
                    .border(1.dp, if (current) accent.copy(alpha = 0.55f) else colors.hairline, RoundedCornerShape(10.dp))
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                AnebIcon(glyph = if (done && !current) AnebGlyph.Check else glyph, tint = accent, size = 12.dp)
                Spacer(Modifier.width(4.dp))
                Text(
                    label,
                    fontSize = 9.sp,
                    fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (current || done) colors.ink else colors.faint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TopQualityMetrics(
    telemetry: LiveTelemetry,
    activeProfileId: String?,
) {
    val colors = AnebTheme.colors
    val thirdMetric = if (activeProfileId == "s3_multimodal" && telemetry.upMbps != null) {
        AnebMetric("上行吞吐", String.format(Locale.ROOT, "%.1f", telemetry.upMbps), "Mbps", colors.excellent)
    } else if (telemetry.ttftMs != null) {
        AnebMetric("首字 TTFT", "${telemetry.ttftMs.roundToInt()}", "ms", colors.brand2)
    } else if (telemetry.liveStreamGapMs != null && telemetry.streamActive) {
        AnebMetric("实时间隔", "${telemetry.liveStreamGapMs.roundToInt()}", "ms", colors.brand2)
    } else {
        AnebMetric("首字 TTFT", "—", "ms")
    }
    AnebMetricTrio(
        listOf(
            AnebMetric("链路 RTT", telemetry.rttMs?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "—", "ms", colors.brand),
            AnebMetric("实时抖动", telemetry.jitterMs?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "—", "ms"),
            thirdMetric,
        ),
    )
}

/**
 * 22Hz 高频实时网络与 SSE 示波台：
 * - 上方主读数以 250ms 高敏短窗速率实时跳动；
 * - 116dp 固定示波窗同时绘制「36 柱毫秒级到达间隔微脉冲（自动缩放放大波动幅度）」+
 *   「自动缩放高速率折线 + 末尾发光扫描点」，确保任何 2–50ms 网络波动与停顿瞬间肉眼可见。
 */
@Composable
private fun LiveHighFreqTelemetryConsole(
    telemetry: LiveTelemetry,
    activeProfileId: String?,
    instantRate: Double?,
    rateHistory: List<Double>,
) {
    val colors = AnebTheme.colors
    val gapMs = telemetry.liveStreamGapMs
    val stallingNow = telemetry.streamActive && gapMs != null && gapMs >= 250.0
    val subPhase = telemetry.liveSubPhase
    val accent = when {
        stallingNow -> colors.poor
        activeProfileId == "s2_coding_agent" -> colors.brand2
        activeProfileId == "s3_multimodal" -> colors.excellent
        else -> colors.brand
    }

    val (metricTitle, metricValue, metricUnit) = when {
        telemetry.streamActive && instantRate != null -> Triple(
            "SSE 实时到达速率（250ms 瞬时窗）",
            String.format(Locale.ROOT, "%.1f", instantRate),
            "ev/s",
        )
        subPhase == "upload" && telemetry.upMbps != null -> Triple(
            "多模态载荷实时上行速率",
            String.format(Locale.ROOT, "%.1f", telemetry.upMbps),
            "Mbps",
        )
        subPhase == "tool_loop" -> Triple(
            "Agent 工具链往返调用",
            "${telemetry.liveToolRound}/${telemetry.liveToolTotalRounds.coerceAtLeast(1)}",
            "rounds",
        )
        telemetry.rttMs != null -> Triple(
            "链路 Echo 实时时延探测",
            String.format(Locale.ROOT, "%.1f", telemetry.rttMs),
            "ms",
        )
        else -> Triple("SSE 实时到达速率", "—", "ev/s")
    }

    val intervals = telemetry.liveRecentIntervalsMs
    val peakInterval = intervals.maxOrNull()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF0A1020))
            .border(1.dp, if (stallingNow) colors.poor.copy(alpha = 0.6f) else accent.copy(alpha = 0.34f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LivePulseDot(accent)
                    Spacer(Modifier.width(6.dp))
                    Text(metricTitle, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, color = colors.ink, maxLines = 1)
                }
                Text(
                    buildString {
                        append("事件 ${telemetry.liveStreamEvents.takeIf { it > 0 } ?: telemetry.tokensReceived}")
                        if (telemetry.stallCount > 0) append(" · 累计卡顿 ${telemetry.stallCount}")
                        peakInterval?.let { append(" · 峰值间隔 ${it.roundToInt()}ms") }
                    },
                    fontSize = 8.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.muted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    metricValue,
                    fontSize = 24.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    metricUnit,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.muted,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // 116dp 固定高度双层高动态示波窗（脉冲柱 + 自动缩放高速率曲线）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(116.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    Brush.verticalGradient(listOf(Color(0xFF050914), Color(0xFF0A1328))),
                )
                .border(1.dp, accent.copy(alpha = 0.24f), RoundedCornerShape(10.dp))
                .padding(horizontal = 7.dp, vertical = 6.dp),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                // 背景刻度网格
                listOf(0.25f, 0.50f, 0.75f).forEach { frac ->
                    drawLine(
                        color = Color(0x226890C8),
                        start = Offset(0f, h * frac),
                        end = Offset(w, h * frac),
                        strokeWidth = 0.8.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                    )
                }

                // 第 1 层：毫秒级到达间隔脉冲柱（自动以 min..max 动态拉伸，放大真实微抖动！）
                val bars = intervals.takeLast(36)
                if (bars.isNotEmpty()) {
                    val bMin = (bars.minOrNull() ?: 0.0) * 0.75
                    val bMax = maxOf(bMin + 8.0, (bars.maxOrNull() ?: 25.0) * 1.08)
                    val span = (bMax - bMin).coerceAtLeast(1.0)
                    val gap = 2.dp.toPx()
                    val barW = ((w - gap * (bars.size - 1)) / bars.size.coerceAtLeast(1)).coerceIn(2f, 10f)
                    val offsetX = (w - bars.size * (barW + gap) + gap).coerceAtLeast(0f)
                    bars.forEachIndexed { idx, v ->
                        val norm = ((v - bMin) / span).toFloat().coerceIn(0.10f, 0.94f)
                        val barH = h * norm
                        val x = offsetX + idx * (barW + gap)
                        val isSpike = v >= 120.0
                        val barColor = if (isSpike) colors.poor else accent.copy(alpha = 0.42f)
                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                listOf(barColor.copy(alpha = 0.85f), barColor.copy(alpha = 0.12f)),
                                startY = h - barH,
                                endY = h,
                            ),
                            topLeft = Offset(x, h - barH),
                            size = Size(barW, barH),
                            cornerRadius = CornerRadius(barW * 0.35f, barW * 0.35f),
                        )
                    }
                }

                // 第 2 层：250ms 高敏瞬时速率/时延发光折线（自动围绕活跃区间 min..max 缩放，充满 75% 纵深！）
                if (rateHistory.size >= 2) {
                    val rMinRaw = rateHistory.minOrNull() ?: 0.0
                    val rMaxRaw = rateHistory.maxOrNull() ?: 10.0
                    val pad = maxOf((rMaxRaw - rMinRaw) * 0.22, maxOf(rMaxRaw * 0.08, 2.5))
                    val lo = (rMinRaw - pad).coerceAtLeast(0.0)
                    val hi = rMaxRaw + pad
                    val span = (hi - lo).coerceAtLeast(1.0)
                    val stepX = w / (rateHistory.size - 1).coerceAtLeast(1)

                    val strokePath = Path()
                    val fillPath = Path()
                    var lastX = 0f
                    var lastY = h * 0.5f
                    rateHistory.forEachIndexed { idx, v ->
                        val x = idx * stepX
                        val norm = ((v - lo) / span).toFloat().coerceIn(0.08f, 0.94f)
                        val y = h * (1f - norm)
                        lastX = x
                        lastY = y
                        if (idx == 0) {
                            strokePath.moveTo(x, y)
                            fillPath.moveTo(x, h)
                            fillPath.lineTo(x, y)
                        } else {
                            strokePath.lineTo(x, y)
                            fillPath.lineTo(x, y)
                        }
                    }
                    fillPath.lineTo(lastX, h)
                    fillPath.close()
                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            listOf(accent.copy(alpha = 0.25f), Color.Transparent),
                        ),
                    )
                    drawPath(
                        path = strokePath,
                        color = Color(0xFFF2FBFF),
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                    )
                    // 右端发光实时扫描点
                    drawCircle(accent.copy(alpha = 0.35f), radius = 6.5.dp.toPx(), center = Offset(lastX, lastY))
                    drawCircle(Color.White, radius = 2.8.dp.toPx(), center = Offset(lastX, lastY))
                }
            }

            // 角标 HUD
            Row(
                modifier = Modifier.align(Alignment.TopStart),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    "22Hz TELEMETRY",
                    fontSize = 7.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xCC060A16))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
                if (stallingNow) {
                    Text(
                        "流式停顿 ${gapMs?.roundToInt()} ms",
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = colors.poor,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(colors.poor.copy(alpha = 0.18f))
                            .border(1.dp, colors.poor.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 1.5.dp),
                    )
                }
            }
            if (rateHistory.size < 2 && intervals.isEmpty()) {
                Text(
                    "正在建立测试链路并采集首个 Echo / SSE 到达样本…",
                    fontSize = 9.sp,
                    color = colors.faint,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

/**
 * 固定高度（368dp）真实业务场景滚动视窗：
 * - 框体高度严格锁定 368dp，切换场景或持续吐字时绝不撑开、绝不跳动；
 * - 内部独立垂直滚动并随真实 SSE 到达自动平滑跟底，呈现真实生产级对话 / IDE / 视觉巡检体验。
 */
@Composable
private fun AuthenticWorkloadViewport(
    profileId: String?,
    telemetry: LiveTelemetry,
    instantRate: Double?,
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
    val glyph = when (profileId) {
        "s2_coding_agent" -> AnebGlyph.Code
        "s3_multimodal" -> AnebGlyph.Image
        else -> AnebGlyph.Chat
    }
    val headerTitle = when (profileId) {
        "s2_coding_agent" -> "ConnectionPoolGuard.kt · Coding Agent"
        "s3_multimodal" -> "机房光路 AI 视觉巡检 · 多模态工作台"
        else -> "AI 对话助手 · DeepThink 实时会话"
    }

    val statusText = when {
        stallingNow -> "流式停顿 ${gapMs?.roundToInt()} ms"
        telemetry.streamActive && instantRate != null -> "生成中 · ${instantRate.roundToInt()} ev/s"
        telemetry.liveSubPhase == "upload" -> telemetry.liveUploadProgress?.let { "上传 ${(it * 100).roundToInt()}%" } ?: "上行传输中"
        telemetry.liveSubPhase == "tool_loop" -> "工具调用 ${telemetry.liveToolRound}/${telemetry.liveToolTotalRounds.coerceAtLeast(1)}"
        telemetry.liveSubPhase == "echo" -> "Echo 时延校准"
        telemetry.liveSubPhase == "think" -> "深度推理中…"
        else -> "$phaseName 就绪"
    }
    val statusColor = when {
        stallingNow -> colors.poor
        telemetry.streamActive -> colors.excellent
        else -> stageAccent
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(368.dp) // 严格固定高度：内容在内部自动滚动，外框大小永不变动
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0A0E1B))
            .border(
                1.dp,
                if (stallingNow) colors.poor.copy(alpha = 0.65f) else stageAccent.copy(alpha = 0.38f),
                RoundedCornerShape(16.dp),
            ),
    ) {
        // ---- 仿真实应用固定顶栏 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF10172B))
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                AnebIcon(glyph = glyph, tint = stageAccent, size = 15.dp)
                Spacer(Modifier.width(7.dp))
                Text(
                    headerTitle,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                statusText,
                fontSize = 8.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = statusColor,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(statusColor.copy(alpha = 0.14f))
                    .border(1.dp, statusColor.copy(alpha = 0.4f), CircleShape)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))

        // ---- 内部独立滚动工作区（自动跟随最新流式输出滚动）----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(12.dp),
        ) {
            when (profileId) {
                "s2_coding_agent" -> CodingWorkspaceBody(telemetry = telemetry)
                "s3_multimodal" -> MultimodalWorkspaceBody(telemetry = telemetry)
                else -> ChatWorkspaceBody(telemetry = telemetry)
            }
        }
    }
}

@Composable
private fun ChatWorkspaceBody(telemetry: LiveTelemetry) {
    val colors = AnebTheme.colors
    val bodyScroll = rememberScrollState()
    val revealed = revealedCount(telemetry, REALISTIC_CHAT_ANSWER.length, charsPerEvent = 6)
    val shown = if (revealed > 0) REALISTIC_CHAT_ANSWER.substring(0, revealed) else ""
    val thinkRevealed = when {
        telemetry.streamActive || revealed > 0 -> REALISTIC_CHAT_THINKING.length
        telemetry.liveSubPhase == "think" -> (REALISTIC_CHAT_THINKING.length * 0.72f).roundToInt()
        telemetry.rttMs != null -> (REALISTIC_CHAT_THINKING.length * 0.35f).roundToInt()
        else -> 0
    }

    LaunchedEffect(revealed, thinkRevealed) {
        bodyScroll.scrollTo(bodyScroll.maxValue)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(bodyScroll),
    ) {
        // 用户真实业务提问气泡
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                "对比深圳前海与新加坡作为东南亚 SaaS 区域总部的三年综合成本、跨境专线时延与合规落地建议，输出可直接上会的结论。",
                fontSize = 10.5.sp,
                lineHeight = 15.5.sp,
                color = colors.ink,
                modifier = Modifier
                    .fillMaxWidth(0.88f)
                    .clip(RoundedCornerShape(13.dp, 13.dp, 3.dp, 13.dp))
                    .background(Color(0xFF1A294A))
                    .border(1.dp, colors.brand.copy(alpha = 0.28f), RoundedCornerShape(13.dp, 13.dp, 3.dp, 13.dp))
                    .padding(horizontal = 11.dp, vertical = 8.dp),
            )
        }

        Spacer(Modifier.height(9.dp))

        // 真实深度思考块（类似 DeepSeek / 豆包深度思考折叠区）
        if (thinkRevealed > 0) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF0D1426))
                    .border(1.dp, colors.hairline, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            ) {
                Text(
                    if (revealed > 0) "已完成深度推演 · 检索 3 项跨境网络与税制基准" else "深度推演中 · 正在校准跨境海缆与合规模型…",
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.brand,
                )
                Text(
                    REALISTIC_CHAT_THINKING.substring(0, thinkRevealed),
                    fontSize = 9.sp,
                    lineHeight = 13.5.sp,
                    color = colors.faint,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            Spacer(Modifier.height(9.dp))
        }

        // AI 流式回复正文（按真实 SSE 到达推进，自动跟底滚动）
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(colors.brand.copy(alpha = 0.16f))
                    .border(1.dp, colors.brand.copy(alpha = 0.55f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("AI", fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = colors.brand)
            }
            Spacer(Modifier.width(8.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(4.dp, 13.dp, 13.dp, 13.dp))
                    .background(Color(0xFF101930))
                    .border(1.dp, colors.hairline, RoundedCornerShape(4.dp, 13.dp, 13.dp, 13.dp))
                    .padding(horizontal = 11.dp, vertical = 9.dp),
            ) {
                if (shown.isEmpty()) {
                    Text(
                        "正在等待首字流式响应到达…",
                        fontSize = 10.sp,
                        color = colors.muted,
                    )
                } else {
                    Text(
                        buildAnnotatedString {
                            append(shown)
                            if (telemetry.streamActive) {
                                withStyle(SpanStyle(color = colors.brand, fontWeight = FontWeight.Bold)) {
                                    append(" |")
                                }
                            }
                        },
                        fontSize = 10.5.sp,
                        lineHeight = 16.5.sp,
                        color = colors.ink,
                    )
                }
            }
        }
    }
}

@Composable
private fun CodingWorkspaceBody(telemetry: LiveTelemetry) {
    val colors = AnebTheme.colors
    val bodyScroll = rememberScrollState()
    val totalLines = REALISTIC_CODE_DIFF.size
    val revealedLines = when {
        telemetry.liveStreamEvents > 0 -> (telemetry.liveStreamEvents / 2 + 3).coerceIn(3, totalLines)
        telemetry.tokensReceived > 0 -> (telemetry.tokensReceived / 4 + 3).coerceIn(3, totalLines)
        telemetry.streamActive -> 4
        else -> 3
    }
    val toolRound = telemetry.liveToolRound
    val toolSteps = listOf(
        "grep_search(\"QuicSessionPool\") -> 4 处命中",
        "read_file(\"ConnectionPoolGuard.kt:84-136\")",
        "apply_patch(ConnectionPoolGuard.kt +14 -2)",
        "run_tests(\":gateway:testQuicFallback\") -> 14/14 PASS",
    )
    val completedTools = when {
        toolRound > 0 -> toolRound.coerceIn(1, toolSteps.size)
        revealedLines >= totalLines - 2 -> 3
        telemetry.streamActive -> 2
        telemetry.rttMs != null -> 1
        else -> 0
    }

    LaunchedEffect(revealedLines, completedTools) {
        bodyScroll.scrollTo(bodyScroll.maxValue)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(bodyScroll),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 真实 Agent 工具执行流水线
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF0C1223))
                .border(1.dp, colors.brand2.copy(alpha = 0.28f), RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "AGENT TOOL CHAIN · branch fix/quic-0rtt-fallback",
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.brand2,
                )
                Text(
                    "$completedTools/${toolSteps.size}",
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.muted,
                )
            }
            toolSteps.forEachIndexed { idx, step ->
                val done = idx < completedTools
                val active = idx == completedTools
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AnebIcon(
                        glyph = if (done) AnebGlyph.Check else AnebGlyph.Code,
                        tint = when {
                            done -> colors.excellent
                            active -> colors.brand2
                            else -> colors.faint
                        },
                        size = 11.dp,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        step,
                        fontSize = 8.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (done || active) colors.ink else colors.faint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // 真实 IDE 行号 + 语法高亮 Diff 代码窗口
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF070B16))
                .border(1.dp, colors.hairline, RoundedCornerShape(10.dp))
                .padding(vertical = 6.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "gateway/src/main/kotlin/ConnectionPoolGuard.kt",
                    fontSize = 8.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "+14  -2",
                    fontSize = 8.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = colors.excellent,
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
            Spacer(Modifier.height(4.dp))
            REALISTIC_CODE_DIFF.take(revealedLines).forEachIndexed { idx, (marker, line) ->
                val bg = when (marker) {
                    '+' -> Color(0x2629D99A)
                    '-' -> Color(0x26FF5B73)
                    else -> Color.Transparent
                }
                val markerColor = when (marker) {
                    '+' -> colors.excellent
                    '-' -> colors.poor
                    else -> colors.faint
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(bg)
                        .padding(horizontal = 8.dp, vertical = 1.8.dp),
                ) {
                    Text(
                        String.format(Locale.ROOT, "%3d", 84 + idx),
                        fontSize = 8.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colors.faint,
                        modifier = Modifier.width(24.dp),
                    )
                    Text(
                        marker.toString(),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = markerColor,
                        modifier = Modifier.width(12.dp),
                    )
                    Text(
                        highlightKotlinLine(line, colors.ink, colors.brand2, colors.brand, colors.fair),
                        fontSize = 9.sp,
                        lineHeight = 13.5.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
private fun MultimodalWorkspaceBody(telemetry: LiveTelemetry) {
    val colors = AnebTheme.colors
    val bodyScroll = rememberScrollState()
    val reducedMotion = LocalReducedMotion.current
    val upFrac = when {
        telemetry.liveUploadProgress != null -> telemetry.liveUploadProgress
        telemetry.streamActive || telemetry.upMbps != null -> 1f
        else -> 0.12f
    }
    val isUploading = telemetry.liveSubPhase == "upload" || (upFrac < 0.99f && !telemetry.streamActive)
    val revealed = revealedCount(telemetry, REALISTIC_MULTIMODAL_ANSWER.length, charsPerEvent = 6)
    val shown = if (revealed > 0) REALISTIC_MULTIMODAL_ANSWER.substring(0, revealed) else ""

    val scanPos = if (isUploading && !reducedMotion) {
        val transition = rememberInfiniteTransition(label = "rack-upload-scan")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Reverse),
            label = "rack-scan-line",
        ).value
    } else {
        upFrac
    }

    LaunchedEffect(revealed) {
        bodyScroll.scrollTo(bodyScroll.maxValue)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(bodyScroll),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        // 真实 4K 机房光路巡检摄影照片 + AI 视觉识别框 + 激光扫描幕布
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(146.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, colors.excellent.copy(alpha = 0.45f), RoundedCornerShape(12.dp)),
        ) {
            Image(
                painter = painterResource(id = R.drawable.multimodal_dc_rack),
                contentDescription = "机房光路巡检现场高清实拍图",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // 上传幕布 & 激光扫描线，上传完成后叠加 AI 视觉检测标注框
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                if (isUploading) {
                    val splitY = h * upFrac.coerceIn(0.05f, 0.98f)
                    drawRect(
                        color = Color(0x99050A16),
                        topLeft = Offset(0f, splitY),
                        size = Size(w, h - splitY),
                    )
                    val laserY = h * scanPos.coerceIn(0.04f, 0.96f)
                    drawLine(
                        color = colors.excellent,
                        start = Offset(0f, laserY),
                        end = Offset(w, laserY),
                        strokeWidth = 2.dp.toPx(),
                    )
                } else {
                    // AI 视觉目标识别检测框（对应照片内真实 SFP28 端口阵列、右侧告警指示灯、OM4/OS2 光纤束）
                    val boxStroke = Stroke(width = 1.5.dp.toPx())
                    drawRoundRect(
                        color = colors.excellent,
                        topLeft = Offset(w * 0.44f, h * 0.43f),
                        size = Size(w * 0.25f, h * 0.24f),
                        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                        style = boxStroke,
                    )
                    drawRoundRect(
                        color = colors.fair,
                        topLeft = Offset(w * 0.60f, h * 0.44f),
                        size = Size(w * 0.11f, h * 0.22f),
                        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                        style = boxStroke,
                    )
                    drawRoundRect(
                        color = colors.brand,
                        topLeft = Offset(w * 0.43f, h * 0.65f),
                        size = Size(w * 0.38f, h * 0.24f),
                        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                        style = boxStroke,
                    )
                }
            }
            // 左上真实拍摄元信息
            Text(
                "DC_B04_U28_FIBER_4K.jpg · 4032×2268",
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(7.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color(0xC8050A16))
                    .padding(horizontal = 6.dp, vertical = 2.5.dp),
            )
            // 右上状态角标
            Text(
                if (isUploading) {
                    val mbpsTxt = telemetry.upMbps?.let { " · ${String.format(Locale.ROOT, "%.1f", it)} Mbps" }.orEmpty()
                    "上行 ${(upFrac * 100).roundToInt()}%$mbpsTxt"
                } else {
                    "视觉检测 3 处目标 · 置信 98.4%"
                },
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = if (isUploading) colors.brand else colors.excellent,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(7.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color(0xCC050A16))
                    .padding(horizontal = 6.dp, vertical = 2.5.dp),
            )
            // 底部实时进度条
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color(0x88050A16)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(upFrac.coerceIn(0.04f, 1f))
                        .fillMaxHeight()
                        .background(colors.excellent),
                )
            }
        }

        // AI 视觉多模态诊断流式报告
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF0E162B))
                .border(1.dp, colors.hairline, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 9.dp),
        ) {
            Text(
                "AI Ops 现场光路视觉巡检报告",
                fontSize = 9.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.excellent,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (shown.isNotEmpty()) {
                    shown + if (telemetry.streamActive) " |" else ""
                } else if (isUploading) {
                    "正在上传 4K 机柜巡检原图至视觉多模态推理节点，上传完毕自动启动端口状态与光纤理线诊断…"
                } else {
                    "原图已送达，正在执行 SFP28 指示灯与光纤弯曲半径视觉分析…"
                },
                fontSize = 10.sp,
                lineHeight = 15.5.sp,
                color = if (shown.isNotEmpty()) colors.ink else colors.muted,
            )
        }
    }
}

@Composable
private fun FooterStatusStrip(
    nodeLabel: String,
    telemetry: LiveTelemetry,
    radioEvidenceLimited: Boolean,
) {
    val colors = AnebTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(Color(0x77101629))
            .border(1.dp, colors.hairline, RoundedCornerShape(11.dp))
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            "节点 $nodeLabel · ${telemetry.rat ?: if (radioEvidenceLimited) "网络基线" else "实时链路"}",
            fontSize = 8.5.sp,
            color = colors.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            telemetry.rsrp?.let { "RSRP $it dBm" } ?: "R-16 只读遥测",
            fontSize = 8.5.sp,
            fontFamily = FontFamily.Monospace,
            color = colors.faint,
        )
    }
}

private fun highlightKotlinLine(
    line: String,
    ink: Color,
    kwColor: Color,
    typeColor: Color,
    litColor: Color,
) = buildAnnotatedString {
    val keywords = setOf("override", "fun", "val", "var", "if", "return", "try", "catch", "while", "true", "throw", "private", "suspend")
    val types = setOf("QuicSession", "IOException", "Duration", "Endpoint", "ConnectionResult")
    val tokens = Regex("(\"[^\"]*\")|(\\b[A-Za-z_][A-Za-z0-9_]*\\b)|([^A-Za-z0-9_\"]+)").findAll(line)
    for (m in tokens) {
        val tok = m.value
        val color = when {
            tok.startsWith("\"") -> litColor
            tok in keywords -> kwColor
            tok in types -> typeColor
            else -> ink
        }
        withStyle(SpanStyle(color = color)) { append(tok) }
    }
}

private fun revealedCount(telemetry: LiveTelemetry, maxLen: Int, charsPerEvent: Int = 5): Int {
    val events = when {
        telemetry.liveStreamEvents > 0 -> telemetry.liveStreamEvents
        telemetry.tokensReceived > 0 -> telemetry.tokensReceived
        else -> 0
    }
    if (events <= 0) return 0
    return (events * charsPerEvent).coerceAtMost(maxLen)
}

@Composable
private fun LivePulseDot(color: Color) {
    val reducedMotion = LocalReducedMotion.current
    val alpha = if (reducedMotion) {
        1f
    } else {
        val transition = rememberInfiniteTransition(label = "testing-live-dot")
        transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(650, easing = LinearEasing), RepeatMode.Reverse),
            label = "testing-live-alpha",
        ).value
    }
    Box(Modifier.size(6.dp).clip(CircleShape).background(color.copy(alpha = alpha)))
}

private const val REALISTIC_CHAT_THINKING =
    "对比维度拆解：1) 3 年总拥有成本（办公租金 + 研发/商务人力 + 企业所得税 15% vs 17% 阶梯减免）；" +
        "2) 东南亚主流 POP 节点海缆实测 RTT（前海经香港 IPLC 至新加坡 ~36ms，新加坡本地 <4ms）；" +
        "3) 跨境数据出境安全评估与 PDPA 合规周期。"

private const val REALISTIC_CHAT_ANSWER =
    "一、高管决策摘要（推荐「前海研发 + 新加坡结算双核」架构）\n" +
        "若首年团队 35–60 人且核心研发在国内，纯新加坡总部 3 年综合成本约高出 58%；建议采用「深圳前海承接核心产研与 AI 算法（享 15% 优惠税率）+ 新加坡设立区域商务与数据合规主体」的组合方案。\n\n" +
        "二、关键指标对比（3 年口径）\n" +
        "• 综合人力与办公成本：前海约 1,850 万 RMB；新加坡约 2,920 万 RMB（差额主要来自高级工程薪资与核心区租金）。\n" +
        "• 东南亚客户网络时延：新加坡核心机房直连雅加达/曼谷/吉隆坡 RTT 8–22 ms；前海通过深港 IPLC 专线接入新加坡 POP 实测 RTT 34–39 ms，满足实时 SaaS 与 AI 流式交互要求。\n" +
        "• 合规与资金通道：新加坡主体直接对接 PDPA 与多币种收单，前海主体通过标准合同完成非敏感脱敏数据回传，首阶段 6 周内即可完成双主体落地。\n\n" +
        "三、90 天落地里程碑\n" +
        "1. 第 1–3 周：完成新加坡 ACRA 注册与新加坡 AWS/阿里云边缘 POP 压测；\n" +
        "2. 第 4–6 周：开通深港双路由 IPLC 专线，配置 AI 网关多活故障切换（目标 RTO < 15 秒）；\n" +
        "3. 第 7–12 周：上线首个印尼/马来西亚企业客户并固化月度 SLA 巡检报表。"

private val REALISTIC_CODE_DIFF = listOf(
    ' ' to "suspend fun acquireSession(endpoint: Endpoint): ConnectionResult {",
    ' ' to "    val cached = quicPool.peekHealthy(endpoint)",
    '-' to "    if (cached != null) return ConnectionResult.Ready(cached)",
    '-' to "    return ConnectionResult.Ready(quicPool.openZeroRtt(endpoint))",
    '+' to "    if (cached != null && !cached.isStalled(maxGapMs = 280L)) {",
    '+' to "        return ConnectionResult.Ready(cached)",
    '+' to "    }",
    '+' to "    var attempt = 0",
    '+' to "    while (attempt < maxFallbackRetries) {",
    '+' to "        try {",
    '+' to "            val session = if (attempt == 0) quicPool.openZeroRtt(endpoint)",
    '+' to "                else quicPool.openH2Fallback(endpoint)",
    '+' to "            metrics.recordHandshake(endpoint, attempt, session.rttMs)",
    '+' to "            return ConnectionResult.Ready(session)",
    '+' to "        } catch (e: IOException) {",
    '+' to "            attempt++",
    '+' to "            metrics.recordFallback(endpoint, attempt, e.javaClass.simpleName)",
    '+' to "            if (attempt >= maxFallbackRetries) throw e",
    '+' to "            delay(jitteredBackoffMs(attempt))",
    '+' to "        }",
    '+' to "    }",
    ' ' to "}",
)

private const val REALISTIC_MULTIMODAL_ANSWER =
    "1. 端口与光模块状态识别（U28 / U29 核心交换机）\n" +
        "• 左侧 16 路 SFP28 光模块链路指示灯呈稳定绿色常亮，业务收发正常；\n" +
        "• 右侧管理与电源状态区检出 2 枚琥珀色告警灯（U28/U29 右端），建议核查冗余电源模块 PSU-2 供电日志与风扇转速阈值。\n\n" +
        "2. 光纤跳线与物理层风险评估\n" +
        "• 黄色 OS2 单模尾纤与青色 OM4 多模跳线已通过魔术贴束带分区导入右侧理线架，整体走线规范；\n" +
        "• 下层交换机前部青色跳线弧度接近最小弯曲半径（约 32mm），存在长期微弯光衰隐患，建议下个维护窗增加 1U 前置水平理线槽。\n\n" +
        "3. 自动生成运维工单建议\n" +
        "• 工单号：INC-20261121-0418 · 优先级：P2（预防性巡检）\n" +
        "• 建议动作：远程读取 U28/U29 光功率收发光 DOM 数值，复核右侧告警灯并安排理线加固。"

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
        "s1_chat" to "对话助手",
        "s2_coding_agent" to "编码 Agent",
        "s3_multimodal" to "多模态巡检",
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
