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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aneb.probe.engine.LiveTelemetry
import com.aneb.probe.ui.components.AnebGlyph
import com.aneb.probe.ui.components.AnebIcon
import com.aneb.probe.ui.components.AnebMetric
import com.aneb.probe.ui.components.AnebMetricTrio
import com.aneb.probe.ui.components.AnebSparkline
import com.aneb.probe.ui.components.AnebWordmark
import com.aneb.probe.ui.components.pressable
import com.aneb.probe.ui.theme.AnebTheme
import com.aneb.probe.ui.theme.LocalReducedMotion
import java.util.Locale
import kotlin.math.roundToInt

/**
 * AI 对话 / 编码 / 多模态实时测试工作台：
 * 顶栏展示精密链路遥测，中央以真实生产级作业视图（对话助手 / IDE 代码补丁 / 多模态图纸分析）
 * 呈现当前正在运行的场景；所有推进节奏、上传吞吐与停顿警示 100% 由真实 [LiveTelemetry] 驱动（R-16 只读投影）。
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
        Box(Modifier.fillMaxWidth().height(48.dp)) {
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

        // ---- 中央主视窗：真实 AI 作业场景视图（对话 / IDE 补丁 / 多模态图纸上传分析）----
        AuthenticWorkloadViewport(
            profileId = activeProfileId,
            telemetry = telemetry,
            displayedRate = displayedRate,
            phaseName = progress.phaseName,
        )

        Spacer(Modifier.height(10.dp))

        // ---- 精密链路实时波形条 ----
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xC00E1426))
                .border(1.dp, colors.hairline, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LiveDot(if ((telemetry.liveStreamGapMs ?: 0.0) > 250.0 && telemetry.streamActive) colors.poor else colors.brand)
                    Spacer(Modifier.width(7.dp))
                    Text(
                        if (telemetry.streamActive) "SSE 实时到达速率 & ITL 抖动波形" else "正在建立 ${progress.phaseName} 会话",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.ink,
                    )
                }
                Text(
                    displayedRate?.let { "${it.roundToInt()} ev/s" } ?: "采样中",
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.brand,
                )
            }
            Spacer(Modifier.height(7.dp))
            AnebSparkline(
                values = chartPoints,
                color = if ((telemetry.liveStreamGapMs ?: 0.0) > 250.0 && telemetry.streamActive) colors.fair else colors.brand,
                modifier = Modifier.fillMaxWidth().height(34.dp),
                fill = true,
            )
            ItlLiveBars(
                itlMs = telemetry.itlRecentMs,
                modifier = Modifier.fillMaxWidth().height(18.dp).padding(top = 5.dp),
            )
        }

        Spacer(Modifier.height(10.dp))
        AnebMetricTrio(
            items = listOf(
                AnebMetric("首字响应", telemetry.ttftMs?.let { String.format(Locale.ROOT, "%.2f", it / 1_000.0) } ?: "—", "秒", colors.brand),
                AnebMetric("流式速率", displayedRate?.let { "${it.roundToInt()}" } ?: "—", "ev/s", colors.excellent),
                AnebMetric("流式卡顿", telemetry.stallCount.toString(), "次", if (telemetry.stallCount > 0) colors.poor else colors.ink),
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
        Text(
            "界面文案仅作典型作业视图呈现；流式吐字节奏、上行吞吐与停顿告警均 100% 由真实节点流量驱动。",
            fontSize = 8.5.sp,
            color = colors.faint,
            modifier = Modifier.padding(top = 8.dp, bottom = 22.dp, start = 4.dp, end = 4.dp),
        )
    }
}

/**
 * 真实 AI 作业视图：按当前执行的真实场景切换为生产级对话界面、IDE 代码补丁编辑器、
 * 或多模态图纸上传与解析视图（无 emoji、无出戏的测试旁白）。
 */
@Composable
private fun AuthenticWorkloadViewport(
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
    val glyph = when (profileId) {
        "s2_coding_agent" -> AnebGlyph.Code
        "s3_multimodal" -> AnebGlyph.Image
        else -> AnebGlyph.Chat
    }
    val headerTitle = when (profileId) {
        "s2_coding_agent" -> "RetryInterceptor.kt · Coding Agent"
        "s3_multimodal" -> "多模态图纸解析 · 视觉理解工作区"
        else -> "AI 对话助手 · 实时流式会话"
    }

    val statusText = when {
        stallingNow -> "流式停顿 ${gapMs?.roundToInt()} ms"
        telemetry.streamActive && displayedRate != null -> "生成中 · ${displayedRate.roundToInt()} ev/s"
        telemetry.streamActive -> "等待首字 TTFT…"
        profileId == "s3_multimodal" -> "上行载荷传输中"
        else -> "$phaseName 握手中"
    }
    val statusColor = when {
        stallingNow -> colors.poor
        telemetry.streamActive -> colors.excellent
        else -> colors.fair
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0A0E1B))
            .border(
                1.dp,
                if (stallingNow) colors.poor.copy(alpha = 0.65f) else stageAccent.copy(alpha = 0.34f),
                RoundedCornerShape(16.dp),
            ),
    ) {
        // ---- 仿真实应用顶栏 ----
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
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                color = statusColor,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(statusColor.copy(alpha = 0.14f))
                    .border(1.dp, statusColor.copy(alpha = 0.38f), CircleShape)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))

        // ---- 场景主体 ----
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
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
    val revealed = revealedCount(telemetry, REALISTIC_CHAT_ANSWER.length)
    val shown = if (revealed > 0) REALISTIC_CHAT_ANSWER.substring(0, revealed) else ""

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Text(
            "帮我排一份周末深圳两天一晚路线，覆盖南山科技园、深圳湾公园和前海石公园，按时间段给出地铁衔接建议。",
            fontSize = 10.5.sp,
            lineHeight = 15.5.sp,
            color = colors.ink,
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .clip(RoundedCornerShape(13.dp, 13.dp, 3.dp, 13.dp))
                .background(Color(0xFF1A2746))
                .border(1.dp, colors.brand.copy(alpha = 0.25f), RoundedCornerShape(13.dp, 13.dp, 3.dp, 13.dp))
                .padding(horizontal = 11.dp, vertical = 8.dp),
        )
    }

    Spacer(Modifier.height(10.dp))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0E1424))
            .border(1.dp, colors.hairline, RoundedCornerShape(12.dp))
            .padding(horizontal = 11.dp, vertical = 10.dp),
    ) {
        Text("AI 助手", fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = colors.brand)
        Spacer(Modifier.height(5.dp))
        if (shown.isEmpty()) {
            WaitingFirstTokenRow(telemetry = telemetry, accent = colors.brand)
        } else {
            Text(
                text = shown + if (telemetry.streamActive) " |" else "",
                fontSize = 10.5.sp,
                lineHeight = 16.5.sp,
                color = colors.ink,
                maxLines = 7,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CodingWorkspaceBody(telemetry: LiveTelemetry) {
    val colors = AnebTheme.colors
    val allLines = REALISTIC_CODE_DIFF
    val totalChars = remember { allLines.sumOf { it.second.length + 1 } }
    val revealed = revealedCount(telemetry, totalChars)

    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("diff --git a/net/RetryInterceptor.kt", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = colors.muted)
        Text(
            if (telemetry.streamActive) "Patch 流式写入" else "Tool Loop 校验",
            fontSize = 8.5.sp,
            fontFamily = FontFamily.Monospace,
            color = colors.brand2,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF060912))
            .border(1.dp, colors.hairline, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (revealed <= 0) {
            WaitingFirstTokenRow(telemetry = telemetry, accent = colors.brand2)
        } else {
            var remaining = revealed
            allLines.forEachIndexed { idx, (kind, line) ->
                if (remaining <= 0) return@forEachIndexed
                val take = remaining.coerceAtMost(line.length)
                val visible = line.substring(0, take)
                remaining -= (line.length + 1)
                val bg = when (kind) {
                    '+' -> Color(0x1E2FE0A0)
                    '-' -> Color(0x1EF55B6E)
                    else -> Color.Transparent
                }
                val fg = when (kind) {
                    '+' -> Color(0xFF7BF2C4)
                    '-' -> Color(0xFFFF8F9C)
                    else -> Color(0xFFC8D5EB)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(bg)
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                ) {
                    Text(
                        String.format(Locale.ROOT, "%2d ", idx + 41),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colors.faint,
                    )
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = fg)) {
                                append(visible)
                                if (remaining <= 0 && telemetry.streamActive) append(" |")
                            }
                        },
                        fontSize = 9.sp,
                        lineHeight = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun MultimodalWorkspaceBody(telemetry: LiveTelemetry) {
    val colors = AnebTheme.colors
    val revealed = revealedCount(telemetry, REALISTIC_MULTIMODAL_ANSWER.length)
    val shown = if (revealed > 0) REALISTIC_MULTIMODAL_ANSWER.substring(0, revealed) else ""
    val uploading = !telemetry.streamActive

    // 真实视觉图纸附件预览卡（精密 Canvas 建筑结构图，非 emoji）
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0E1529))
            .border(1.dp, if (uploading) colors.excellent.copy(alpha = 0.5f) else colors.hairline, RoundedCornerShape(12.dp))
            .padding(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArchitecturalBlueprintThumbnail(
            accent = colors.excellent,
            modifier = Modifier
                .size(width = 72.dp, height = 52.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("floorplan_campus_4k.jpg", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = colors.ink, maxLines = 1)
            Text(
                telemetry.upMbps?.let { "已上传 · 实测上行 ${String.format(Locale.ROOT, "%.1f", it)} Mbps" }
                    ?: if (uploading) "正在执行多模态图像上行突发…" else "上行完成 · 视觉理解流式返回中",
                fontSize = 9.5.sp,
                color = if (uploading) colors.excellent else colors.muted,
                modifier = Modifier.padding(top = 3.dp),
            )
            Spacer(Modifier.height(5.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(Color(0x5524314E)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(if (telemetry.streamActive || telemetry.upMbps != null) 1f else 0.58f)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(colors.excellent),
                )
            }
        }
    }

    Spacer(Modifier.height(9.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0E1424))
            .border(1.dp, colors.hairline, RoundedCornerShape(12.dp))
            .padding(horizontal = 11.dp, vertical = 9.dp),
    ) {
        Text("视觉模型解析结果", fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = colors.excellent)
        Spacer(Modifier.height(4.dp))
        if (shown.isEmpty()) {
            WaitingFirstTokenRow(telemetry = telemetry, accent = colors.excellent)
        } else {
            Text(
                text = shown + if (telemetry.streamActive) " |" else "",
                fontSize = 10.5.sp,
                lineHeight = 16.sp,
                color = colors.ink,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 精密图纸缩略视觉图（供多模态上传视图展示，纯矢量绘制）。 */
@Composable
internal fun ArchitecturalBlueprintThumbnail(accent: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.background(Color(0xFF091224))) {
        val w = size.width
        val h = size.height
        val gridColor = accent.copy(alpha = 0.14f)
        for (i in 1..4) {
            drawLine(gridColor, Offset(w * i / 5f, 0f), Offset(w * i / 5f, h), strokeWidth = 1f)
        }
        for (j in 1..3) {
            drawLine(gridColor, Offset(0f, h * j / 4f), Offset(w, h * j / 4f), strokeWidth = 1f)
        }
        val stroke = Stroke(width = 1.3.dp.toPx())
        drawRoundRect(
            color = accent.copy(alpha = 0.78f),
            topLeft = Offset(w * 0.14f, h * 0.18f),
            size = Size(w * 0.44f, h * 0.62f),
            cornerRadius = CornerRadius(3f, 3f),
            style = stroke,
        )
        drawRoundRect(
            color = Color(0xFF56C2FF).copy(alpha = 0.72f),
            topLeft = Offset(w * 0.58f, h * 0.30f),
            size = Size(w * 0.28f, h * 0.50f),
            cornerRadius = CornerRadius(3f, 3f),
            style = stroke,
        )
        val diag = Path().apply {
            moveTo(w * 0.20f, h * 0.66f)
            lineTo(w * 0.36f, h * 0.36f)
            lineTo(w * 0.52f, h * 0.66f)
        }
        drawPath(diag, accent.copy(alpha = 0.6f), style = stroke)
    }
}

@Composable
private fun WaitingFirstTokenRow(telemetry: LiveTelemetry, accent: Color) {
    val colors = AnebTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        LiveDot(accent)
        Spacer(Modifier.width(7.dp))
        Text(
            if (telemetry.streamActive) {
                val waitMs = telemetry.liveStreamGapMs?.roundToInt()
                if (waitMs != null) "等待首字返回（已等待 $waitMs ms）" else "等待首字返回…"
            } else {
                "正在建立连接与上行握手…"
            },
            fontSize = 10.sp,
            color = colors.muted,
        )
    }
}

private fun revealedCount(telemetry: LiveTelemetry, maxLen: Int): Int = when {
    telemetry.streamActive && telemetry.liveStreamEvents > 0 ->
        (telemetry.liveStreamEvents * 3).coerceAtMost(maxLen)
    !telemetry.streamActive && telemetry.tokensReceived > 0 ->
        (maxLen / 2).coerceAtMost(maxLen)
    else -> 0
}

@Composable
private fun ItlLiveBars(itlMs: List<Double>, modifier: Modifier = Modifier) {
    val colors = AnebTheme.colors
    Canvas(modifier = modifier) {
        if (itlMs.isEmpty()) return@Canvas
        val samples = itlMs.takeLast(36)
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
        "s1_chat" to "01 对话助手",
        "s2_coding_agent" to "02 编码 Agent",
        "s3_multimodal" to "03 多模态上传",
    )
    val activeIdx = steps.indexOfFirst { it.first == activeProfileId }.takeIf { it >= 0 }
        ?: progress.scenarioIndex.coerceIn(0, 2)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
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
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (isActive) colors.brand.copy(alpha = 0.14f) else Color(0x700E1326))
                    .border(
                        1.dp,
                        if (isActive) colors.brand.copy(alpha = 0.45f) else colors.hairline,
                        RoundedCornerShape(9.dp),
                    )
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (isDone) "完成 · ${label.substring(3)}" else label,
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
            if (index > 0) Box(Modifier.width(1.dp).height(31.dp).background(colors.hairline))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(entry.first, fontSize = 9.5.sp, color = colors.muted)
                Text(entry.second, fontSize = 12.sp, fontWeight = FontWeight(560), color = colors.ink, modifier = Modifier.padding(top = 2.dp))
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
        AnebIcon(glyph = AnebGlyph.Pulse, tint = colors.brand, size = 16.dp)
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.ink)
            Text(subtitle, fontSize = 10.sp, color = colors.muted, maxLines = 1)
        }
        Text("节点", fontSize = 9.5.sp, color = colors.brand, modifier = Modifier.border(1.dp, colors.brand.copy(alpha = 0.22f), CircleShape).padding(horizontal = 9.dp, vertical = 5.dp))
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

private const val REALISTIC_CHAT_ANSWER =
    "Day 1 上午（南山科技园 → 人才公园）\n" +
        "09:30 地铁 1 号线高新园站出站，步行游览科技园核心区与大沙河生态长廊，中午在万象天地用餐。\n" +
        "Day 1 傍晚（深圳湾公园日落）\n" +
        "16:00 沿人才公园海滨栈道步行或骑行至深圳湾公园，避开晚高峰车流，18:00 前抵达日出剧场观日落。\n" +
        "Day 2 上午（前海石公园）\n" +
        "09:30 乘地铁 5 号线至前湾站换乘微巴 B990，直达前海石公园海滨草坪，全天交通均以轨道衔接为主。"

private val REALISTIC_CODE_DIFF = listOf(
    ' ' to "override fun intercept(chain: Interceptor.Chain): Response {",
    ' ' to "    val request = chain.request()",
    '-' to "    return chain.proceed(request)",
    '+' to "    var attempt = 0",
    '+' to "    while (true) {",
    '+' to "        try { return chain.proceed(request) } catch (e: IOException) {",
    '+' to "            if (++attempt > maxRetries || !isIdempotent(request)) throw e",
    '+' to "            Thread.sleep(backoffMs(attempt))",
    '+' to "        }",
    '+' to "    }",
    ' ' to "}",
)

private const val REALISTIC_MULTIMODAL_ANSWER =
    "1. 空间分区识别：图纸左侧为主办公区（占比约 62%），右侧为设备机房与弱电竖井，动静分区清晰。\n" +
        "2. 无线覆盖建议：核心承重墙位于中轴线，建议在走廊交汇处部署双频吸顶 AP，避免 5GHz 穿墙衰减。\n" +
        "3. 风险点标注：西南角会议室距离弱电井较远，建议预留双路六类网线与独立电源回路。"

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
