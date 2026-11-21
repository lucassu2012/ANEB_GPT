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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.aneb.probe.engine.BehaviorTraceRunner
import com.aneb.probe.ui.components.AnebGlyph
import com.aneb.probe.ui.components.AnebGradientCard
import com.aneb.probe.ui.components.AnebIcon
import com.aneb.probe.ui.components.AnebMetric
import com.aneb.probe.ui.components.AnebMetricTrio
import com.aneb.probe.ui.components.AnebPageIntro
import com.aneb.probe.ui.components.AnebSectionTitle
import com.aneb.probe.ui.components.AnebTopBar
import com.aneb.probe.ui.components.pressable
import com.aneb.probe.ui.theme.AnebTheme
import com.aneb.probe.ui.theme.LocalReducedMotion
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 多模态 AI 实战工作台（`aneb-profile-v2`）：
 * - 上半区：25Hz 实时多模态遥测与精密双层波形控制台（真实瞬时速率曲线 + 毫秒级分片/Token 到达抖动柱）；
 * - 下半区：严格固定高度（368dp）自动滚动的真实业务播放视窗（4K 深圳湾无人机航拍真实图像多模态解译 /
 *   企业 SLA 长文档结构化解析 / 架构级知识问答流），任务切换与流式吐字绝不改变框体高度；
 * - 全界面零 emoji，支持点击任务队列查看单任务完整网络实测证据。
 */
@Composable
fun MultimodalTheaterScreen(
    nodeLabel: String,
    quickMode: Boolean,
    onQuickModeChange: (Boolean) -> Unit,
    running: Boolean,
    otherRunActive: Boolean,
    liveProgress: BehaviorTraceRunner.LiveTraceProgress?,
    result: BehaviorTraceRunner.BehaviorTraceRunResult?,
    logs: List<String>,
    error: String?,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onOpenCatalog: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = AnebTheme.colors
    val view = LocalView.current
    var showRawLogs by remember { mutableStateOf(false) }
    var selectedTaskIndex by remember(quickMode, running) { mutableStateOf<Int?>(null) }
    var runRotation by remember { mutableStateOf(0) }
    var prevRunning by remember { mutableStateOf(running) }
    LaunchedEffect(running) {
        if (running && !prevRunning) runRotation++
        prevRunning = running
    }

    DisposableEffect(view, running) {
        val prev = view.keepScreenOn
        if (running) view.keepScreenOn = true
        onDispose { view.keepScreenOn = prev }
    }

    val plannedKinds = remember(liveProgress, result, quickMode) {
        when {
            liveProgress != null && liveProgress.plannedKinds.isNotEmpty() -> liveProgress.plannedKinds
            result != null && result.tasks.isNotEmpty() -> result.tasks.map { it.workloadKind }
            quickMode -> listOf("text", "document", "image")
            else -> DEFAULT_20_TASK_KINDS
        }
    }
    val completedOutcomes = remember(liveProgress, result) {
        result?.tasks ?: liveProgress?.completedTasks.orEmpty()
    }
    val outcomesByIndex = remember(completedOutcomes) {
        completedOutcomes.associateBy { it.taskIndex }
    }
    val startBlocked = !running && otherRunActive

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp),
    ) {
        AnebTopBar(showBack = true, onBack = onBack, showMenu = false)
        Row(verticalAlignment = Alignment.Bottom) {
            AnebPageIntro(
                eyebrow = "ANEB-PROFILE-V2 · MULTIMODAL",
                title = "多模态 AI 实战",
                subtitle = "节点 $nodeLabel · 真实上传文档与 4K 航拍图并实测多模态推理流",
                modifier = Modifier.weight(1f),
            )
            Text(
                "合同",
                fontSize = 10.sp,
                color = colors.brand,
                modifier = Modifier
                    .clip(CircleShape)
                    .border(1.dp, colors.hairline, CircleShape)
                    .pressable(onClick = onOpenCatalog)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }

        Spacer(Modifier.height(9.dp))

        // ---- 模式切换：三模态实战（快速） vs 20 任务完整取证 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xA80B1020))
                .border(1.dp, colors.hairline, RoundedCornerShape(12.dp))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ModeTogglePill(
                label = "快速三模态（~25s）",
                active = quickMode,
                enabled = !running,
                onClick = { onQuickModeChange(true) },
                modifier = Modifier.weight(1f),
            )
            ModeTogglePill(
                label = "完整 20 任务取证",
                active = !quickMode,
                enabled = !running,
                onClick = { onQuickModeChange(false) },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(9.dp))

        // ---- 紧凑操作控制卡 ----
        AnebGradientCard(Modifier.fillMaxWidth(), radius = 15.dp) {
            Column(Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            when {
                                running -> "执行中 · 任务 ${(liveProgress?.currentTaskIndex ?: 0) + 1} / ${plannedKinds.size}"
                                result != null -> "多模态实战完成（${result.tasksSucceeded}/${result.tasksExecuted} 通过）"
                                quickMode -> "三模态实测：企业问答 → 1MB SLA 文档 → 5MB 4K 航拍图"
                                else -> "官方取证轨迹：8 文本 + 6 文档 + 6 图像（共 20 项）"
                            },
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.ink,
                        )
                        result?.score?.let { sc ->
                            Text(
                                "独立多模态得分 ${String.format(Locale.ROOT, "%.1f", sc)} 分（独立口径，不混入 v1 Token AQS）",
                                fontSize = 9.sp,
                                color = colors.excellent,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                when {
                                    running -> colors.poor
                                    startBlocked -> colors.surfaceMuted
                                    else -> colors.brand
                                },
                            )
                            .pressable(
                                enabled = running || !startBlocked,
                                onClick = { if (running) onCancel() else onStart() },
                            )
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                    ) {
                        Text(
                            when {
                                running -> "停止"
                                startBlocked -> "占用中"
                                result != null -> "重测"
                                else -> "开始实战"
                            },
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (startBlocked) colors.muted else Color(0xFF05121A),
                        )
                    }
                }
                error?.let {
                    Text(it, fontSize = 9.5.sp, color = colors.poor, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // ---- 上半区：25Hz 实时多模态指标与高精度双层示波台（位置互换至任务窗上方！）----
        MultimodalHighFreqTelemetryDeck(
            running = running,
            liveProgress = liveProgress,
            result = result,
        )

        Spacer(Modifier.height(10.dp))

        // ---- 下半区：严格固定高度（368dp）自动滚动真实多模态业务播放视窗 ----
        FixedHeightMultimodalWorkspace(
            running = running,
            liveProgress = liveProgress,
            result = result,
            quickMode = quickMode,
            runRotation = runRotation,
        )

        // ---- 执行队列矩阵（支持点击任意已完成项查看完整明细）----
        AnebSectionTitle(
            text = if (quickMode) "三模态执行队列（点击已完成项看详情）" else "20 任务多模态执行矩阵（点击看详情）",
            modifier = Modifier.padding(top = 14.dp, start = 4.dp, bottom = 7.dp),
        )
        AnebGradientCard(Modifier.fillMaxWidth(), radius = 15.dp) {
            val cols = if (plannedKinds.size <= 3) plannedKinds.size.coerceAtLeast(1) else 4
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                plannedKinds.chunked(cols).forEachIndexed { rowIdx, rowKinds ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        rowKinds.forEachIndexed { colIdx, kind ->
                            val idx = rowIdx * cols + colIdx
                            val outcome = outcomesByIndex[idx]
                            val isActive = running && liveProgress?.currentTaskIndex == idx
                            val isSelected = selectedTaskIndex == idx
                            TaskMatrixCell(
                                index = idx,
                                kind = kind,
                                outcome = outcome,
                                active = isActive,
                                selected = isSelected,
                                onClick = if (outcome != null) {
                                    { selectedTaskIndex = if (isSelected) null else idx }
                                } else null,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(cols - rowKinds.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }

        val inspected = selectedTaskIndex?.let { outcomesByIndex[it] }
        if (inspected != null) {
            Spacer(Modifier.height(8.dp))
            SelectedTaskInspector(outcome = inspected)
        }

        if (result != null && result.tasks.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            ModalityBreakdownCard(result.tasks)
        }

        if (logs.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x8811172B))
                    .border(1.dp, colors.hairline, RoundedCornerShape(12.dp))
                    .pressable(onClick = { showRawLogs = !showRawLogs })
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("工程原始轨迹日志（${logs.size} 条）", fontSize = 10.sp, color = colors.muted)
                Text(if (showRawLogs) "收起" else "展开", fontSize = 10.sp, color = colors.brand)
            }
            if (showRawLogs) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF070B14))
                        .border(1.dp, colors.hairline, RoundedCornerShape(12.dp))
                        .padding(10.dp),
                ) {
                    logs.takeLast(14).forEach { line ->
                        Text(
                            line,
                            fontSize = 8.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colors.faint,
                            modifier = Modifier.padding(vertical = 1.5.dp),
                        )
                    }
                }
            }
        }

        Text(
            "口径说明：快速三模态从冻结 golden_trace.jsonl 抽取各模态代表任务执行真实载荷上行与 SSE 推理流；完整 20 任务轨迹用于官方 aneb-profile-v2 完整取证评分。",
            fontSize = 9.sp,
            lineHeight = 14.sp,
            color = colors.faint,
            modifier = Modifier.padding(top = 12.dp, bottom = 28.dp, start = 4.dp, end = 4.dp),
        )
    }
}

/**
 * 上半区：25Hz 实时多模态遥测与高精度双层波形示波台（固定位于业务视窗上方）。
 */
@Composable
private fun MultimodalHighFreqTelemetryDeck(
    running: Boolean,
    liveProgress: BehaviorTraceRunner.LiveTraceProgress?,
    result: BehaviorTraceRunner.BehaviorTraceRunResult?,
) {
    val colors = AnebTheme.colors
    val step = liveProgress?.currentStep
    val kind = liveProgress?.currentWorkloadKind ?: "image"
    val accent = when (kind) {
        "image" -> colors.excellent
        "document" -> colors.brand2
        else -> colors.brand
    }

    val (rateTitle, rateVal, rateUnit) = when {
        step == "upload" && liveProgress.liveUploadMbps != null -> Triple(
            "多模态载荷实时上行",
            String.format(Locale.ROOT, "%.1f", liveProgress.liveUploadMbps),
            "Mbps",
        )
        step == "stream" && liveProgress.liveStreamRateTps != null -> Triple(
            "多模态 SSE 实时吐字",
            String.format(Locale.ROOT, "%.1f", liveProgress.liveStreamRateTps),
            "tok/s",
        )
        step == "download" -> Triple(
            "推理产物实时回传",
            formatBytes(liveProgress.downloadedBytes),
            "recv",
        )
        step == "echo" && liveProgress.echoP50Ms != null -> Triple(
            "链路 Echo 时延校准",
            String.format(Locale.ROOT, "%.1f", liveProgress.echoP50Ms),
            "ms",
        )
        result != null -> Triple(
            "多模态任务完成率",
            result.metrics["TOK-B01"]?.let { String.format(Locale.ROOT, "%.0f", it * 100.0) } ?: "—",
            "%",
        )
        else -> Triple("实时吞吐 / 推理速率", "—", "Mbps")
    }

    val liveJitter = liveProgress?.liveInstantJitterMs ?: liveProgress?.echoJitterMs ?: result?.metrics?.get("TOK-N04")
    val liveRtt = liveProgress?.echoP50Ms ?: result?.metrics?.get("TOK-N03")
    val speedHist = liveProgress?.liveSpeedHistory.orEmpty()
    val intervalHist = liveProgress?.liveIntervalHistoryMs.orEmpty()
    val peakInterval = intervalHist.maxOrNull()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AnebMetricTrio(
            listOf(
                AnebMetric("链路 RTT", liveRtt?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "—", "ms", colors.brand),
                AnebMetric("微抖动 P90", liveJitter?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "—", "ms", colors.brand2),
                AnebMetric(
                    "流完整度",
                    result?.metrics?.get("TOK-B11")?.let { String.format(Locale.ROOT, "%.0f", it * 100.0) }
                        ?: if (running && liveProgress != null && liveProgress.plannedTokens > 0) {
                            "${((liveProgress.streamedTokens.toFloat() / liveProgress.plannedTokens) * 100f).roundToInt().coerceIn(0, 100)}"
                        } else "—",
                    "%",
                    colors.excellent,
                ),
            ),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF0A1020))
                .border(1.dp, accent.copy(alpha = if (running) 0.45f else 0.22f), RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(rateTitle, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
                    Text(
                        buildString {
                            if (liveProgress != null && liveProgress.currentUploadBytes > 0L) {
                                append("载荷 ${formatBytes(liveProgress.uploadedBytes)} / ${formatBytes(liveProgress.currentUploadBytes)}")
                            } else {
                                append("25Hz 精细波形 · 分片/Token 到达间隔实时捕捉")
                            }
                            peakInterval?.let { append(" · 峰值 ${it.roundToInt()}ms") }
                        },
                        fontSize = 8.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colors.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        rateVal,
                        fontSize = 23.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = accent,
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        rateUnit,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colors.muted,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // 114dp 精密双层示波窗：44 柱毫秒级间隔脉冲 + 自动缩放 48 点高速率发光折线
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(114.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFF050914), Color(0xFF0A1428))))
                    .border(1.dp, accent.copy(alpha = 0.24f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 7.dp, vertical = 6.dp),
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    listOf(0.25f, 0.50f, 0.75f).forEach { frac ->
                        drawLine(
                            color = Color(0x226890C8),
                            start = Offset(0f, h * frac),
                            end = Offset(w, h * frac),
                            strokeWidth = 0.8.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                        )
                    }

                    // 第 1 层：精细毫秒级分片/Token 到达间隔脉冲柱（自动拉伸纵深，放大微抖动！）
                    val bars = intervalHist.takeLast(44)
                    if (bars.isNotEmpty()) {
                        val bMin = (bars.minOrNull() ?: 0.0) * 0.75
                        val bMax = maxOf(bMin + 6.0, (bars.maxOrNull() ?: 20.0) * 1.1)
                        val span = (bMax - bMin).coerceAtLeast(1.0)
                        val gap = 1.8.dp.toPx()
                        val barW = ((w - gap * (bars.size - 1)) / bars.size.coerceAtLeast(1)).coerceIn(2f, 9f)
                        val offsetX = (w - bars.size * (barW + gap) + gap).coerceAtLeast(0f)
                        bars.forEachIndexed { idx, ms ->
                            val norm = ((ms - bMin) / span).toFloat().coerceIn(0.10f, 0.94f)
                            val barH = h * norm
                            val x = offsetX + idx * (barW + gap)
                            val spike = ms >= 100.0
                            val c = if (spike) colors.poor else accent.copy(alpha = 0.45f)
                            drawRoundRect(
                                brush = Brush.verticalGradient(
                                    listOf(c.copy(alpha = 0.88f), c.copy(alpha = 0.12f)),
                                    startY = h - barH,
                                    endY = h,
                                ),
                                topLeft = Offset(x, h - barH),
                                size = Size(barW, barH),
                                cornerRadius = CornerRadius(barW * 0.35f, barW * 0.35f),
                            )
                        }
                    }

                    // 第 2 层：48 点瞬时速率/时延发光主曲线（动态贴合活跃区间 min..max，波动震撼清晰）
                    val series = if (speedHist.size >= 2) speedHist else intervalHist
                    if (series.size >= 2) {
                        val sMin = series.minOrNull() ?: 0.0
                        val sMax = series.maxOrNull() ?: 10.0
                        val pad = maxOf((sMax - sMin) * 0.20, maxOf(sMax * 0.08, 2.0))
                        val lo = (sMin - pad).coerceAtLeast(0.0)
                        val hi = sMax + pad
                        val span = (hi - lo).coerceAtLeast(1.0)
                        val stepX = w / (series.size - 1).coerceAtLeast(1)
                        val strokePath = Path()
                        val fillPath = Path()
                        var lastX = 0f
                        var lastY = h * 0.5f
                        series.forEachIndexed { idx, v ->
                            val x = idx * stepX
                            val y = h * (1f - ((v - lo) / span).toFloat().coerceIn(0.08f, 0.94f))
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
                            brush = Brush.verticalGradient(listOf(accent.copy(alpha = 0.25f), Color.Transparent)),
                        )
                        drawPath(
                            path = strokePath,
                            color = Color(0xFFF4FCFF),
                            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                        )
                        drawCircle(accent.copy(alpha = 0.38f), radius = 6.dp.toPx(), center = Offset(lastX, lastY))
                        drawCircle(Color.White, radius = 2.6.dp.toPx(), center = Offset(lastX, lastY))
                    }
                }

                Text(
                    if (running) "25Hz LIVE WAVEFORM" else "MULTIMODAL OSCILLOSCOPE",
                    fontSize = 7.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = if (running) accent else colors.faint,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xCC060A16))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
                if (speedHist.size < 2 && intervalHist.size < 2) {
                    Text(
                        if (running) "正在采集实时上行与推理到达脉冲…" else "点击「开始实战」后实时绘制毫秒级分片/Token 抖动波形",
                        fontSize = 9.sp,
                        color = colors.faint,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }
    }
}

/**
 * 下半区：严格固定高度（368dp）自动滚动真实多模态业务视窗。
 * 无论是待机预览、文本问答、长文档解析还是 4K 航拍图视觉分析，外框高度始终锁定 368dp，
 * 内部独立平滑跟底滚动，彻底消除高度跳动。
 */
@Composable
private fun FixedHeightMultimodalWorkspace(
    running: Boolean,
    liveProgress: BehaviorTraceRunner.LiveTraceProgress?,
    result: BehaviorTraceRunner.BehaviorTraceRunResult?,
    quickMode: Boolean,
    runRotation: Int,
) {
    val colors = AnebTheme.colors
    val kind = when {
        liveProgress?.currentWorkloadKind != null -> liveProgress.currentWorkloadKind
        result != null -> result.tasks.lastOrNull()?.workloadKind ?: "image"
        else -> "image" // 待机时默认预览最震撼的 4K 航拍多模态真实任务画面
    }
    val taskSlot = liveProgress?.currentTaskIndex ?: 0
    // 每次重新测试或同批多个同模态任务时自动轮换真实业务样本（默认首轮优先展示深圳湾 4K 航拍图）
    val visionCase = RealisticScenarioCatalog.multimodalVisionCases[
        (1 + runRotation + taskSlot / 3) % RealisticScenarioCatalog.multimodalVisionCases.size,
    ]
    val docCase = RealisticScenarioCatalog.multimodalDocCases[
        (runRotation + taskSlot) % RealisticScenarioCatalog.multimodalDocCases.size,
    ]
    val textCase = RealisticScenarioCatalog.multimodalTextCases[
        (runRotation + taskSlot) % RealisticScenarioCatalog.multimodalTextCases.size,
    ]
    val (glyph, title, accent) = when (kind) {
        "image" -> Triple(AnebGlyph.Image, "高清图片多模态分析", colors.excellent)
        "document" -> Triple(AnebGlyph.Document, "长文档结构化解析", colors.brand2)
        else -> Triple(AnebGlyph.Chat, "企业架构知识问答流", colors.brand)
    }

    val totalUpload = liveProgress?.currentUploadBytes ?: when (kind) {
        "image" -> 5_242_880L
        "document" -> 1_048_576L
        else -> 2_048L
    }
    val uploaded = when {
        liveProgress != null -> liveProgress.uploadedBytes
        result != null -> totalUpload
        else -> 0L
    }
    val uploadFrac = if (totalUpload > 0L) (uploaded.toFloat() / totalUpload.toFloat()).coerceIn(0f, 1f) else 1f
    val isUploading = running && liveProgress?.currentStep == "upload"
    val isStreaming = running && liveProgress?.currentStep == "stream"

    val fullCopy = when (kind) {
        "image" -> visionCase.analysisBody
        "document" -> docCase.analysisBody
        else -> textCase.answerBody
    }
    // 按真实 streamedTokens / plannedTokens 1:1 比例同步展开，使吐字节奏与上方实时 tok/s 波形严格一致
    val revealedChars = when {
        liveProgress == null && result != null -> fullCopy.length
        liveProgress == null -> 0
        liveProgress.currentStep == "stream" && liveProgress.streamedTokens > 0 -> {
            val planned = liveProgress.plannedTokens.coerceAtLeast(1)
            val ratio = (liveProgress.streamedTokens.toDouble() / planned).coerceIn(0.0, 1.0)
            (ratio * fullCopy.length).roundToInt().coerceIn(1, fullCopy.length)
        }
        liveProgress.currentStep == "download" || liveProgress.currentStep == "done" -> fullCopy.length
        else -> 0
    }

    val bodyScroll = rememberScrollState()
    LaunchedEffect(revealedChars, kind, liveProgress?.currentTaskIndex) {
        if (revealedChars > 0) {
            bodyScroll.scrollTo(bodyScroll.maxValue)
        } else {
            bodyScroll.scrollTo(0)
        }
    }

    val statusPill = when {
        liveProgress == null && result != null -> "已完成 · ${result.tasksSucceeded}/${result.tasksExecuted}"
        liveProgress == null -> if (quickMode) "待机预览 · 3 模态就绪" else "待机预览 · 20 任务就绪"
        liveProgress.currentStep == "echo" -> "链路 RTT 校准"
        liveProgress.currentStep == "upload" -> liveProgress.liveUploadMbps?.let { "上行 ${String.format(Locale.ROOT, "%.1f", it)} Mbps" } ?: "上行启动"
        liveProgress.currentStep == "stream" -> liveProgress.liveStreamRateTps?.let { "生成 ${it.roundToInt()} tok/s" } ?: "等待首字…"
        liveProgress.currentStep == "download" -> "产物回传中"
        else -> "本项完成"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(368.dp) // 严格固定高度，彻底消除高度跳动
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0A0F1E))
            .border(1.dp, accent.copy(alpha = if (running) 0.50f else 0.28f), RoundedCornerShape(16.dp)),
    ) {
        // ---- 固定顶栏 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF11182D))
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                AnebIcon(glyph = glyph, tint = accent, size = 15.dp)
                Spacer(Modifier.width(7.dp))
                Text(
                    if (liveProgress != null) "任务 #${liveProgress.currentTaskIndex + 1} · $title" else "实战任务视窗 · $title",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                statusPill,
                fontSize = 8.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = accent,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.14f))
                    .border(1.dp, accent.copy(alpha = 0.4f), CircleShape)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))

        // ---- 内部独立滚动工作区 ----
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(bodyScroll)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            // 1) 真实业务载荷媒体卡（4K 航拍/AOI/机房实拍图 / 企业 PDF 文档页 / 架构提问卡片）
            when (kind) {
                "image" -> RealVisionImagePayloadCard(
                    visionCase = visionCase,
                    uploadedBytes = uploaded,
                    totalBytes = totalUpload,
                    uploadFrac = uploadFrac,
                    isUploading = isUploading,
                    showVisionOverlay = !isUploading && (revealedChars > 0 || result != null),
                    liveMbps = liveProgress?.liveUploadMbps,
                    accent = accent,
                )
                "document" -> RealDocumentPayloadCard(
                    docCase = docCase,
                    uploadedBytes = uploaded,
                    totalBytes = totalUpload,
                    uploadFrac = uploadFrac,
                    isUploading = isUploading,
                    liveMbps = liveProgress?.liveUploadMbps,
                    accent = accent,
                )
                else -> RealTextPromptCard(
                    textCase = textCase,
                    uploadedBytes = uploaded,
                    totalBytes = totalUpload,
                    accent = accent,
                )
            }

            // 2) AI 多模态流式解析正文区（自动跟底滚动）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(11.dp))
                    .background(Color(0xFF0E162B))
                    .border(1.dp, colors.hairline, RoundedCornerShape(11.dp))
                    .padding(horizontal = 10.dp, vertical = 9.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        when (kind) {
                            "image" -> visionCase.reportTitle
                            "document" -> docCase.reportTitle
                            else -> textCase.reportTitle
                        },
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (liveProgress != null && liveProgress.plannedTokens > 0) {
                            "${liveProgress.streamedTokens} / ${liveProgress.plannedTokens} tok"
                        } else if (result != null) {
                            "流式输出完成"
                        } else {
                            "等待开始"
                        },
                        fontSize = 8.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colors.muted,
                    )
                }
                Spacer(Modifier.height(5.dp))
                if (revealedChars > 0) {
                    Text(
                        buildAnnotatedString {
                            append(fullCopy.substring(0, revealedChars))
                            if (isStreaming) {
                                withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold)) {
                                    append(" |")
                                }
                            }
                        },
                        fontSize = 10.sp,
                        lineHeight = 15.5.sp,
                        color = colors.ink,
                    )
                } else {
                    Text(
                        when {
                            !running -> "点击上方「开始实战」后，每次测试自动轮换不同真实业务样本，在固定高度内连续实测：企业架构问答 → 1.00 MB 文档审计 → 5.00 MB 4K 实景多模态视觉分析。"
                            isUploading -> "正在上传真实多模态载荷（已传 ${formatBytes(uploaded)} / ${formatBytes(totalUpload)}），上传完毕立即触发流式视觉/语义解析…"
                            else -> "多模态载荷已送达推理节点，正在等待首字 TTFT 返回…"
                        },
                        fontSize = 9.5.sp,
                        lineHeight = 15.sp,
                        color = colors.muted,
                    )
                }
            }
        }
    }
}

@Composable
private fun RealVisionImagePayloadCard(
    visionCase: RealisticScenarioCatalog.MultimodalVisionCase,
    uploadedBytes: Long,
    totalBytes: Long,
    uploadFrac: Float,
    isUploading: Boolean,
    showVisionOverlay: Boolean,
    liveMbps: Double?,
    accent: Color,
) {
    val colors = AnebTheme.colors
    val reducedMotion = LocalReducedMotion.current
    val scanPos = if (isUploading && !reducedMotion) {
        val t = rememberInfiniteTransition(label = "vision-scan")
        t.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1150, easing = LinearEasing), RepeatMode.Reverse),
            label = "vision-scan-y",
        ).value
    } else {
        uploadFrac
    }

    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(148.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(12.dp)),
        ) {
            Image(
                painter = painterResource(id = visionCase.imageRes),
                contentDescription = visionCase.headerTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                if (isUploading) {
                    val splitY = h * uploadFrac.coerceIn(0.04f, 0.98f)
                    drawRect(
                        color = Color(0x99050A16),
                        topLeft = Offset(0f, splitY),
                        size = Size(w, h - splitY),
                    )
                    val laserY = h * scanPos.coerceIn(0.05f, 0.95f)
                    drawLine(accent, Offset(0f, laserY), Offset(w, laserY), strokeWidth = 2.dp.toPx())
                } else if (showVisionOverlay) {
                    val stroke = Stroke(width = 1.5.dp.toPx())
                    visionCase.boxes.forEach { b ->
                        val boxColor = when (b.tone) {
                            "fair" -> colors.fair
                            "brand" -> colors.brand
                            else -> colors.excellent
                        }
                        drawRoundRect(
                            color = boxColor,
                            topLeft = Offset(w * b.x, h * b.y),
                            size = Size(w * b.w, h * b.h),
                            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                            style = stroke,
                        )
                    }
                }
            }
            Text(
                "${visionCase.fileName} · ${formatBytes(totalBytes)}",
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
            Text(
                when {
                    isUploading -> "已上传 ${formatBytes(uploadedBytes)} / ${formatBytes(totalBytes)}" +
                        (liveMbps?.let { " · ${String.format(Locale.ROOT, "%.1f", it)} Mbps" }.orEmpty())
                    showVisionOverlay -> visionCase.detectionSummary
                    else -> "4K 实景多模态样本 · ${visionCase.resolutionTag}"
                },
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = if (isUploading) colors.brand else accent,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(7.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color(0xCC050A16))
                    .padding(horizontal = 6.dp, vertical = 2.5.dp),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color(0x88050A16)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(uploadFrac.coerceIn(0.03f, 1f))
                        .fillMaxHeight()
                        .background(accent),
                )
            }
        }
    }
}

@Composable
private fun RealDocumentPayloadCard(
    docCase: RealisticScenarioCatalog.MultimodalDocCase,
    uploadedBytes: Long,
    totalBytes: Long,
    uploadFrac: Float,
    isUploading: Boolean,
    liveMbps: Double?,
    accent: Color,
) {
    val colors = AnebTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF101830))
            .border(1.dp, if (isUploading) accent.copy(alpha = 0.55f) else colors.hairline, RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(width = 44.dp, height = 52.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color(0xFF172242))
                    .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(7.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AnebIcon(glyph = AnebGlyph.Document, tint = accent, size = 18.dp)
                    Text("PDF", fontSize = 7.5.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = accent)
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(docCase.fileName, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${docCase.metaSummary} · ${formatBytes(totalBytes)}",
                    fontSize = 8.5.sp,
                    color = colors.muted,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    if (isUploading) {
                        "已上传 ${formatBytes(uploadedBytes)} / ${formatBytes(totalBytes)}" +
                            (liveMbps?.let { " · ${String.format(Locale.ROOT, "%.1f", it)} Mbps" }.orEmpty())
                    } else {
                        docCase.indexedSummary
                    },
                    fontSize = 8.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = accent,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(CircleShape)
                .background(Color(0x55050A16)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(uploadFrac.coerceIn(0.03f, 1f))
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(accent),
            )
        }
    }
}

@Composable
private fun RealTextPromptCard(
    textCase: RealisticScenarioCatalog.MultimodalTextCase,
    uploadedBytes: Long,
    totalBytes: Long,
    accent: Color,
) {
    val colors = AnebTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF172442))
            .border(1.dp, accent.copy(alpha = 0.30f), RoundedCornerShape(12.dp))
            .padding(horizontal = 11.dp, vertical = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(textCase.roleTitle, fontSize = 8.5.sp, fontWeight = FontWeight.SemiBold, color = accent)
            Text(
                "Prompt ${formatBytes(uploadedBytes.coerceAtLeast(totalBytes))}",
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                color = colors.muted,
            )
        }
        Text(
            textCase.prompt,
            fontSize = 10.sp,
            lineHeight = 14.5.sp,
            color = colors.ink,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun ModeTogglePill(
    label: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AnebTheme.colors
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (active) colors.brand.copy(alpha = 0.18f) else Color.Transparent)
            .border(1.dp, if (active) colors.brand.copy(alpha = 0.45f) else Color.Transparent, RoundedCornerShape(9.dp))
            .pressable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 10.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) colors.ink else colors.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TaskMatrixCell(
    index: Int,
    kind: String,
    outcome: BehaviorTraceRunner.TaskExecutionOutcome?,
    active: Boolean,
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = AnebTheme.colors
    val (glyph, kindShort, accent) = when (kind) {
        "image" -> Triple(AnebGlyph.Image, "航拍图", colors.excellent)
        "document" -> Triple(AnebGlyph.Document, "SLA文档", colors.brand2)
        else -> Triple(AnebGlyph.Chat, "架构问答", colors.brand)
    }
    val cellColor = when {
        active -> colors.brand
        outcome?.succeeded == true -> colors.excellent
        outcome != null -> colors.poor
        else -> colors.faint
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    selected -> accent.copy(alpha = 0.28f)
                    active -> colors.brand.copy(alpha = 0.22f)
                    outcome != null -> cellColor.copy(alpha = 0.13f)
                    else -> Color(0x66090D1C)
                },
            )
            .border(
                if (active || selected) 1.4.dp else 1.dp,
                if (active || outcome != null) cellColor.copy(alpha = 0.65f) else colors.hairline,
                RoundedCornerShape(10.dp),
            )
            .then(if (onClick != null) Modifier.pressable(onClick = onClick) else Modifier)
            .padding(horizontal = 6.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            AnebIcon(glyph = glyph, tint = if (active || outcome != null) accent else colors.faint, size = 12.dp)
            Text(
                String.format(Locale.ROOT, "%02d", index + 1),
                fontSize = 8.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (active || selected) FontWeight.Bold else FontWeight.Normal,
                color = if (active || outcome != null) colors.ink else colors.faint,
            )
        }
        Text(
            when {
                active -> "执行中"
                outcome?.e2eTtftMs != null -> "${outcome.e2eTtftMs.roundToInt()}ms"
                outcome != null -> if (outcome.succeeded) "通过" else "异常"
                else -> kindShort
            },
            fontSize = 8.5.sp,
            fontFamily = if (outcome != null) FontFamily.Monospace else FontFamily.Default,
            color = if (active) colors.brand else if (outcome != null) cellColor else colors.muted,
            maxLines = 1,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

@Composable
private fun SelectedTaskInspector(outcome: BehaviorTraceRunner.TaskExecutionOutcome) {
    val colors = AnebTheme.colors
    val kindLabel = when (outcome.workloadKind) {
        "image" -> "4K 航拍图多模态"
        "document" -> "SLA 长文档解析"
        else -> "企业架构问答"
    }
    AnebGradientCard(Modifier.fillMaxWidth(), radius = 14.dp) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "任务 #${outcome.taskIndex + 1} 明细 · $kindLabel (${outcome.taskId})",
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.ink,
                )
                Text(
                    if (outcome.succeeded) "通过" else "异常",
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (outcome.succeeded) colors.excellent else colors.poor,
                )
            }
            Text(
                "上行载荷 ${formatBytes(outcome.uploadBytes)} · 实测上行 ${outcome.uploadGoodputMbps?.let { String.format(Locale.ROOT, "%.1f Mbps", it) } ?: "—"} · " +
                    "首字 TTFT ${outcome.e2eTtftMs?.let { "${it.roundToInt()} ms" } ?: "—"}",
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = colors.muted,
            )
            Text(
                "Token 到达 ${outcome.receivedUniqueTokens}/${outcome.plannedTokens} · 准时率 ${outcome.onTimeRatio?.let { "${(it * 100.0).roundToInt()}%" } ?: "—"} · " +
                    "总耗时 ${String.format(Locale.ROOT, "%.1fs", outcome.actualDurationMs / 1000.0)}",
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = colors.muted,
            )
        }
    }
}

@Composable
private fun ModalityBreakdownCard(tasks: List<BehaviorTraceRunner.TaskExecutionOutcome>) {
    val colors = AnebTheme.colors
    val groups = listOf(
        Triple("text", AnebGlyph.Chat, "企业架构问答"),
        Triple("document", AnebGlyph.Document, "SLA 长文档解析"),
        Triple("image", AnebGlyph.Image, "4K 航拍图多模态"),
    )
    AnebGradientCard(Modifier.fillMaxWidth(), radius = 15.dp) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("分模态实测表现对比", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
            groups.forEachIndexed { idx, (kind, glyph, label) ->
                val list = tasks.filter { it.workloadKind == kind }
                if (list.isNotEmpty()) {
                    if (idx > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
                    val ok = list.count { it.succeeded }
                    val avgTtft = list.mapNotNull { it.e2eTtftMs }.takeIf { it.isNotEmpty() }?.average()
                    val avgUp = list.mapNotNull { it.uploadGoodputMbps }.takeIf { it.isNotEmpty() }?.average()
                    val accent = when (kind) {
                        "image" -> colors.excellent
                        "document" -> colors.brand2
                        else -> colors.brand
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AnebIcon(glyph = glyph, tint = accent, size = 14.dp)
                            Spacer(Modifier.width(7.dp))
                            Column {
                                Text(label, fontSize = 10.5.sp, fontWeight = FontWeight.Medium, color = colors.ink)
                                Text("成功 $ok/${list.size}", fontSize = 8.5.sp, color = colors.muted)
                            }
                        }
                        Text(
                            buildString {
                                append("TTFT ${avgTtft?.let { "${it.roundToInt()}ms" } ?: "—"}")
                                avgUp?.let { append(" · 上行 ${String.format(Locale.ROOT, "%.1fM", it)}") }
                            },
                            fontSize = 9.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = accent,
                        )
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576L -> String.format(Locale.ROOT, "%.2f MB", bytes / 1_048_576.0)
    bytes >= 1_024L -> String.format(Locale.ROOT, "%.0f KB", bytes / 1_024.0)
    else -> "$bytes B"
}

private val DEFAULT_20_TASK_KINDS =
    List(8) { "text" } + List(6) { "document" } + List(6) { "image" }
