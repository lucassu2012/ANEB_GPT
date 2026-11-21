package com.aneb.probe.ui

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 多模态 AI 实战工作台（`aneb-profile-v2`）：
 * - 支持「快速三模态体验（~25s：文本 → 长文档 1MB → 高清图 5MB）」与「完整 20 任务取证轨迹」一键切换；
 * - 实时按 100ms 刷新真实上传字节/速率、真实流式 Token 推进与作业视图预览，彻底消除“干等上传”的卡死感；
 * - 全界面零 emoji，全部采用精密矢量图标与交互式任务检查器。
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
            .padding(horizontal = 16.dp),
    ) {
        AnebTopBar(showBack = true, onBack = onBack, showMenu = false)
        Row(verticalAlignment = Alignment.Bottom) {
            AnebPageIntro(
                eyebrow = "ANEB-PROFILE-V2 · MULTIMODAL",
                title = "多模态 AI 实战",
                subtitle = "节点 $nodeLabel · 真实上传文档/图片并接收流式理解输出",
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

        Spacer(Modifier.height(10.dp))

        // ---- 模式切换：快速三模态体验 vs 完整 20 任务取证 ----
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

        Spacer(Modifier.height(10.dp))

        // ---- 顶部控制与状态卡 ----
        AnebGradientCard(Modifier.fillMaxWidth(), radius = 16.dp) {
            Column(Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            when {
                                running -> "正在执行第 ${(liveProgress?.currentTaskIndex ?: 0) + 1} / ${plannedKinds.size} 项真实业务任务"
                                result != null -> "多模态实战已完成（${result.tasksSucceeded}/${result.tasksExecuted} 通过）"
                                quickMode -> "连续实测：文本问答 → 1MB 长文档 → 5MB 高清图片"
                                else -> "官方校准轨迹：8 文本 + 6 文档 + 6 图片（共 20 项）"
                            },
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.ink,
                        )
                        Text(
                            when {
                                result?.score != null -> "独立多模态得分 ${String.format(Locale.ROOT, "%.1f", result.score)} 分（不混入 v1 Token AQS）"
                                running -> "实时展示上传速率、首字等待与流式输出进度"
                                quickMode -> "适合快速直观体验三类多模态载荷在当前网络下的真实表现"
                                else -> "完整轨迹含 10 MB 大载荷，总时长较长，用于官方 v2 完整取证"
                            },
                            fontSize = 9.5.sp,
                            color = if (result?.score != null) colors.excellent else colors.muted,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    result?.score?.let { sc ->
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(colors.excellent.copy(alpha = 0.14f))
                                .border(1.5.dp, colors.excellent.copy(alpha = 0.6f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("${sc.roundToInt()}", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = colors.excellent)
                        }
                    }
                }

                Spacer(Modifier.height(11.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(43.dp)
                        .clip(RoundedCornerShape(12.dp))
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
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        when {
                            running -> "停止多模态实战"
                            startBlocked -> "其他测试运行中"
                            result != null -> if (quickMode) "再测一次三模态实战" else "重新运行 20 任务轨迹"
                            else -> if (quickMode) "开始快速三模态实战" else "开始 20 任务取证轨迹"
                        },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (startBlocked) colors.muted else Color(0xFF05121A),
                    )
                }

                error?.let {
                    Text(it, fontSize = 10.sp, color = colors.poor, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }

        // ---- 活跃多模态真实作业舞台（实时字节上传扫光 + 真实 Token 流式响应 + 速率波形）----
        if (running && liveProgress != null) {
            Spacer(Modifier.height(12.dp))
            ActiveMultimodalWorkspaceCard(progress = liveProgress)
        }

        // ---- 任务矩阵（支持点击任意已完成任务查看明细）----
        AnebSectionTitle(
            text = if (quickMode) "三模态执行队列（点击已完成项看详情）" else "20 任务多模态执行矩阵（点击看详情）",
            modifier = Modifier.padding(top = 15.dp, start = 4.dp, bottom = 8.dp),
        )
        AnebGradientCard(Modifier.fillMaxWidth(), radius = 16.dp) {
            val cols = if (plannedKinds.size <= 3) plannedKinds.size.coerceAtLeast(1) else 4
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                plannedKinds.chunked(cols).forEachIndexed { rowIdx, rowKinds ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        rowKinds.forEachIndexed { colIdx, kind ->
                            val idx = rowIdx * cols + colIdx
                            val outcome = outcomesByIndex[idx]
                            val isActive = running && liveProgress?.currentTaskIndex == idx && outcome == null
                            val isSelected = selectedTaskIndex == idx
                            MultimodalMatrixCell(
                                index = idx,
                                kind = kind,
                                outcome = outcome,
                                isActive = isActive,
                                isSelected = isSelected,
                                onClick = if (outcome != null) ({ selectedTaskIndex = if (isSelected) null else idx }) else null,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(cols - rowKinds.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // ---- 选中任务明细检查器 ----
        selectedTaskIndex?.let { selIdx ->
            outcomesByIndex[selIdx]?.let { task ->
                Spacer(Modifier.height(9.dp))
                SelectedTaskInspector(task)
            }
        }

        // ---- 核心专业指标与分模态汇总 ----
        AnebSectionTitle(
            text = "专业指标与分模态表现",
            modifier = Modifier.padding(top = 15.dp, start = 4.dp, bottom = 8.dp),
        )
        val b01 = result?.metrics?.get("TOK-B01")
            ?: completedOutcomes.takeIf { it.isNotEmpty() }?.let { list -> list.count { it.succeeded }.toDouble() / list.size }
        val b11 = result?.metrics?.get("TOK-B11")
        val n03 = result?.metrics?.get("TOK-N03") ?: liveProgress?.echoP50Ms
        AnebMetricTrio(
            items = listOf(
                AnebMetric("任务成功率", b01?.let { String.format(Locale.ROOT, "%.0f", it * 100.0) } ?: "—", "%", colors.excellent),
                AnebMetric("流完整度", b11?.let { String.format(Locale.ROOT, "%.0f", it * 100.0) } ?: "—", "%", colors.brand),
                AnebMetric("链路 RTT", n03?.let { String.format(Locale.ROOT, "%.0f", it) } ?: "—", "ms", colors.brand2),
            ),
        )

        if (completedOutcomes.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            ModalityBreakdownCard(completedOutcomes)
        }

        if (logs.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
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
            "口径说明：快速三模态从冻结 golden_trace.jsonl 中抽取各模态代表任务执行真实上行与 SSE 推理流；完整 20 任务轨迹用于官方 aneb-profile-v2 完整取证评分。",
            fontSize = 9.sp,
            lineHeight = 14.sp,
            color = colors.faint,
            modifier = Modifier.padding(top = 14.dp, bottom = 28.dp, start = 4.dp, end = 4.dp),
        )
    }
}

@Composable
private fun ActiveMultimodalWorkspaceCard(progress: BehaviorTraceRunner.LiveTraceProgress) {
    val colors = AnebTheme.colors
    val kind = progress.currentWorkloadKind ?: "text"
    val (glyph, title, accent, fileName) = when (kind) {
        "image" -> Quad(AnebGlyph.Image, "高清图片多模态分析", colors.excellent, "campus_aerial_4k.jpg")
        "document" -> Quad(AnebGlyph.Document, "长文档结构化解析", colors.brand2, "Q4_Architecture_Spec.pdf")
        else -> Quad(AnebGlyph.Chat, "多轮知识问答流", colors.brand, "prompt_context.txt")
    }
    val uploadFrac = if (progress.currentUploadBytes > 0L) {
        (progress.uploadedBytes.toFloat() / progress.currentUploadBytes.toFloat()).coerceIn(0f, 1f)
    } else 1f
    val streamFrac = if (progress.plannedTokens > 0) {
        (progress.streamedTokens.toFloat() / progress.plannedTokens.toFloat()).coerceIn(0f, 1f)
    } else 0f

    val previewText = when (kind) {
        "image" -> MULTIMODAL_IMAGE_STREAM_COPY
        "document" -> MULTIMODAL_DOC_STREAM_COPY
        else -> MULTIMODAL_TEXT_STREAM_COPY
    }
    val revealedChars = if (progress.currentStep == "stream" && progress.streamedTokens > 0) {
        (progress.streamedTokens * 3).coerceAtMost(previewText.length)
    } else if (progress.currentStep == "download" || progress.currentStep == "done") {
        previewText.length
    } else 0

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0A0F1E))
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(16.dp)),
    ) {
        // 顶栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF11182D))
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnebIcon(glyph = glyph, tint = accent, size = 15.dp)
                Spacer(Modifier.width(7.dp))
                Text(
                    "任务 #${progress.currentTaskIndex + 1} · $title",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.ink,
                )
            }
            Text(
                when (progress.currentStep) {
                    "echo" -> "链路 RTT 校准"
                    "upload" -> progress.liveUploadMbps?.let { "上行 ${String.format(Locale.ROOT, "%.1f", it)} Mbps" } ?: "上行启动中"
                    "stream" -> progress.liveStreamRateTps?.let { "生成 ${it.roundToInt()} tok/s" } ?: "等待首字…"
                    "download" -> "产物回传中"
                    else -> "本项完成"
                },
                fontSize = 9.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = accent,
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))

        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // 附件实时上传进度卡片（带真实字节进度与扫描幕布）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF0F162B))
                    .border(1.dp, if (progress.currentStep == "upload") accent.copy(alpha = 0.55f) else colors.hairline, RoundedCornerShape(12.dp))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (kind == "image") {
                    Box(
                        modifier = Modifier
                            .size(width = 74.dp, height = 54.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    ) {
                        ArchitecturalBlueprintThumbnail(accent = accent, modifier = Modifier.fillMaxSize())
                        // 真实上传进度幕布：随 uploadedBytes 实时向上拉起
                        if (uploadFrac < 0.999f) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(54.dp * (1f - uploadFrac))
                                    .align(Alignment.BottomCenter)
                                    .background(Color(0xAA060B18)),
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(accent.copy(alpha = 0.14f))
                            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        AnebIcon(glyph = glyph, tint = accent, size = 20.dp)
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(fileName, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(
                            "${(uploadFrac * 100).roundToInt()}%",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = accent,
                        )
                    }
                    Text(
                        "已上传 ${formatBytes(progress.uploadedBytes)} / ${formatBytes(progress.currentUploadBytes)}" +
                            (progress.liveUploadMbps?.let { " · ${String.format(Locale.ROOT, "%.1f", it)} Mbps" } ?: ""),
                        fontSize = 9.5.sp,
                        color = colors.muted,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(Color(0x55222E4D))) {
                        Box(Modifier.fillMaxWidth(uploadFrac).height(5.dp).clip(CircleShape).background(accent))
                    }
                }
            }

            // 真实流式输出区（随真实 streamedTokens 推进）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF0C1222))
                    .border(1.dp, if (progress.currentStep == "stream") accent.copy(alpha = 0.5f) else colors.hairline, RoundedCornerShape(12.dp))
                    .padding(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("模型流式推理输出", fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, color = accent)
                    Text(
                        "${progress.streamedTokens} / ${progress.plannedTokens} tokens",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colors.muted,
                    )
                }
                Spacer(Modifier.height(5.dp))
                Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Color(0x44222E4D))) {
                    Box(Modifier.fillMaxWidth(streamFrac).height(3.dp).clip(CircleShape).background(colors.brand))
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (revealedChars <= 0) {
                        when (progress.currentStep) {
                            "echo" -> "正在测量节点基础往返时延…"
                            "upload" -> "等待多模态载荷上传完毕后自动触发流式解析…"
                            else -> "已送达节点，正在等待首字返回…"
                        }
                    } else {
                        previewText.substring(0, revealedChars) + if (progress.currentStep == "stream") " |" else ""
                    },
                    fontSize = 10.sp,
                    lineHeight = 15.5.sp,
                    color = if (revealedChars > 0) colors.ink else colors.muted,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // 实时传输/生成速率波动微型示波条
            if (progress.liveSpeedHistory.size >= 2) {
                LiveMiniWave(values = progress.liveSpeedHistory, color = accent, modifier = Modifier.fillMaxWidth().height(26.dp))
            }
        }
    }
}

@Composable
private fun LiveMiniWave(values: List<Double>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val maxV = (values.maxOrNull() ?: 1.0).coerceAtLeast(1.0)
        val path = Path()
        val step = size.width / (values.size - 1).coerceAtLeast(1)
        values.forEachIndexed { idx, v ->
            val x = idx * step
            val y = size.height * (1f - (v / maxV).toFloat().coerceIn(0.08f, 1f))
            if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = color, style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round))
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
            .background(if (active) colors.brand.copy(alpha = 0.16f) else Color.Transparent)
            .border(1.dp, if (active) colors.brand.copy(alpha = 0.35f) else Color.Transparent, RoundedCornerShape(9.dp))
            .pressable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 10.5.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) colors.ink else colors.muted,
            maxLines = 1,
        )
    }
}

@Composable
private fun MultimodalMatrixCell(
    index: Int,
    kind: String,
    outcome: BehaviorTraceRunner.TaskExecutionOutcome?,
    isActive: Boolean,
    isSelected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = AnebTheme.colors
    val glyph = when (kind) {
        "image" -> AnebGlyph.Image
        "document" -> AnebGlyph.Document
        else -> AnebGlyph.Chat
    }
    val borderColor = when {
        isSelected -> colors.brand
        outcome != null && outcome.succeeded -> colors.excellent.copy(alpha = 0.55f)
        outcome != null -> colors.poor.copy(alpha = 0.65f)
        isActive -> colors.brand
        else -> colors.hairline
    }
    val bgColor = when {
        isSelected -> colors.brand.copy(alpha = 0.20f)
        outcome != null && outcome.succeeded -> colors.excellent.copy(alpha = 0.12f)
        outcome != null -> colors.poor.copy(alpha = 0.14f)
        isActive -> colors.brand.copy(alpha = 0.16f)
        else -> Color(0x660A0F22)
    }
    val tint = when {
        outcome != null && outcome.succeeded -> colors.excellent
        outcome != null -> colors.poor
        isActive -> colors.brand
        else -> colors.muted
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(11.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(11.dp))
            .then(if (onClick != null) Modifier.pressable(onClick = onClick) else Modifier)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            AnebIcon(glyph = glyph, tint = tint, size = 12.dp, strokeDp = 1.3.dp)
            Text(
                String.format(Locale.ROOT, "%02d", index + 1),
                fontSize = 9.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = colors.ink,
            )
        }
        Text(
            when {
                outcome?.e2eTtftMs != null -> "${outcome.e2eTtftMs.roundToInt()}ms"
                outcome != null && outcome.succeeded -> "通过"
                outcome != null -> "失败"
                isActive -> "执行中"
                else -> kindShortLabel(kind)
            },
            fontSize = 8.5.sp,
            color = tint,
            maxLines = 1,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

@Composable
private fun SelectedTaskInspector(task: BehaviorTraceRunner.TaskExecutionOutcome) {
    val colors = AnebTheme.colors
    AnebGradientCard(Modifier.fillMaxWidth(), radius = 14.dp) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "任务 #${task.taskIndex + 1} 明细（${kindShortLabel(task.workloadKind)} · ${if (task.succeeded) "通过" else "异常"}）",
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (task.succeeded) colors.excellent else colors.poor,
            )
            Text(
                "上传 ${formatBytes(task.uploadBytes)}" +
                    (task.uploadGoodputMbps?.let { " (${String.format(Locale.ROOT, "%.1f", it)} Mbps)" } ?: "") +
                    " · 首字 TTFT ${task.e2eTtftMs?.let { "${it.roundToInt()} ms" } ?: "—"}" +
                    " · Token ${task.receivedUniqueTokens}/${task.plannedTokens}",
                fontSize = 10.sp,
                color = colors.ink,
            )
            Text(
                "总耗时 ${String.format(Locale.ROOT, "%.2f", task.actualDurationMs / 1000.0)} s · 准点率 ${task.onTimeRatio?.let { "${(it * 100).roundToInt()}%" } ?: "—"}",
                fontSize = 9.5.sp,
                color = colors.muted,
            )
        }
    }
}

@Composable
private fun ModalityBreakdownCard(outcomes: List<BehaviorTraceRunner.TaskExecutionOutcome>) {
    val colors = AnebTheme.colors
    val groups = listOf(
        Triple("text", AnebGlyph.Chat, "纯文本对话"),
        Triple("document", AnebGlyph.Document, "长文档理解"),
        Triple("image", AnebGlyph.Image, "高清图片多模态"),
    )
    AnebGradientCard(Modifier.fillMaxWidth(), radius = 16.dp) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            groups.forEachIndexed { idx, (kind, glyph, label) ->
                val list = outcomes.filter { it.workloadKind == kind }
                if (idx > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
                val ok = list.count { it.succeeded }
                val avgTtft = list.mapNotNull { it.e2eTtftMs }.takeIf { it.isNotEmpty() }?.average()
                val avgUp = list.mapNotNull { it.uploadGoodputMbps }.takeIf { it.isNotEmpty() }?.average()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AnebIcon(glyph = glyph, tint = colors.brand, size = 14.dp)
                        Spacer(Modifier.width(7.dp))
                        Text(label, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = colors.ink)
                    }
                    Text(
                        if (list.isEmpty()) "待执行" else buildString {
                            append("$ok/${list.size} 通过")
                            avgTtft?.let { append(" · 首字 ${it.roundToInt()}ms") }
                            avgUp?.let { append(" · 上行 ${String.format(Locale.ROOT, "%.1f", it)}M") }
                        },
                        fontSize = 9.5.sp,
                        color = if (list.isNotEmpty() && ok == list.size) colors.excellent else colors.muted,
                    )
                }
            }
        }
    }
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

private fun kindShortLabel(kind: String): String = when (kind) {
    "image" -> "图片"
    "document" -> "文档"
    else -> "文本"
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "0 B"
    bytes >= 1_048_576L -> String.format(Locale.ROOT, "%.2f MB", bytes / 1_048_576.0)
    bytes >= 1_024L -> "${bytes / 1_024L} KB"
    else -> "$bytes B"
}

private val DEFAULT_20_TASK_KINDS: List<String> =
    List(8) { "text" } + List(6) { "document" } + List(6) { "image" }

private const val MULTIMODAL_TEXT_STREAM_COPY =
    "要点总结：针对移动弱网场景下的多轮问答，优先降低首包往返时延与 TLS 握手开销，其次确保流式分片间隔均匀，避免路由器队列堆积导致的中段停顿。"

private const val MULTIMODAL_DOC_STREAM_COPY =
    "文档解析摘要：第 1 章明确端到端应用层测量边界；第 3 章定义 1 MB 上行文档切片与流式返回时序；第 5 章给出 Wi-Fi 与 5G 蜂窝网络对比归因口径。"

private const val MULTIMODAL_IMAGE_STREAM_COPY =
    "视觉理解输出：已识别航拍建筑群主干道与 3 处高层楼宇轮廓，光照均匀、边缘清晰；依据建筑密度推测该片区处于高负荷商业基站覆盖区间。"
