package com.aneb.probe.ui

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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aneb.probe.engine.BehaviorTraceRunner
import com.aneb.probe.ui.components.AnebGradientCard
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
 * 多模态 AI 行为轨迹实战剧场（`aneb-profile-v2`）：
 * 把 20 个真实混合多模态任务（8 文本 + 6 长文档 + 6 高清图片）的执行过程可视化呈现：
 * - 实时活跃多模态载荷舞台（当前传输的图片/文档/文本载荷大小 + 三步流水线：上传→流式生成→产物回传）；
 * - 4×5 20 格任务矩阵（每格显示模态图标、实测 TTFT 与通过/失败状态）；
 * - 分模态汇总指标与最终多模态得分卡；原始工程日志默认折叠可按需展开。
 */
@Composable
fun MultimodalTheaterScreen(
    nodeLabel: String,
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

    DisposableEffect(view, running) {
        val prev = view.keepScreenOn
        if (running) view.keepScreenOn = true
        onDispose { view.keepScreenOn = prev }
    }

    val plannedKinds = remember(liveProgress, result) {
        when {
            liveProgress != null && liveProgress.plannedKinds.isNotEmpty() -> liveProgress.plannedKinds
            result != null && result.tasks.isNotEmpty() -> result.tasks.map { it.workloadKind }
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
                title = "多模态 AI 轨迹实战",
                subtitle = "节点 $nodeLabel · 真实执行 20 个混合任务（8 文本 · 6 文档 · 6 图片）",
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

        Spacer(Modifier.height(12.dp))

        // ---- 顶部启动/总览卡 ----
        AnebGradientCard(Modifier.fillMaxWidth(), radius = 18.dp) {
            Column(Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            when {
                                running -> "多模态轨迹实时执行中（${completedOutcomes.size}/${plannedKinds.size}）"
                                result != null -> "多模态实战已完成（${result.tasksSucceeded}/${result.tasksExecuted} 通过）"
                                else -> "就绪 · 点击启动 20 任务多模态实测"
                            },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.ink,
                        )
                        Text(
                            when {
                                result?.score != null -> "独立多模态评分 ${String.format(Locale.ROOT, "%.1f", result.score)} 分 · 不混入 v1 Token AQS"
                                running -> "按固定随机种子逐任务发送真实上行、SSE 流式生成与产物回传"
                                else -> "含纯文本对话、长文档上传理解与高清图多模态三类真实负载"
                            },
                            fontSize = 9.5.sp,
                            color = if (result?.score != null) colors.excellent else colors.muted,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    result?.score?.let { sc ->
                        Box(
                            modifier = Modifier
                                .size(50.dp)
                                .clip(CircleShape)
                                .background(colors.excellent.copy(alpha = 0.14f))
                                .border(1.5.dp, colors.excellent.copy(alpha = 0.6f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${sc.roundToInt()}",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.excellent,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(13.dp))
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
                            result != null -> "重新执行多模态 20 任务实战"
                            else -> "开始多模态 20 任务实战"
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

        // ---- 活跃多模态载荷舞台（展示当前究竟在传什么模态、跑到哪一步）----
        if (running && liveProgress != null) {
            Spacer(Modifier.height(12.dp))
            ActiveMultimodalStageCard(progress = liveProgress)
        }

        // ---- 20 格多模态任务可视化矩阵（4 列 × 5 行）----
        AnebSectionTitle(
            text = "20 任务多模态执行矩阵（💬 文本 · 📄 文档 · 🖼️ 图片）",
            modifier = Modifier.padding(top = 16.dp, start = 4.dp, bottom = 8.dp),
        )
        AnebGradientCard(Modifier.fillMaxWidth(), radius = 16.dp) {
            Column(
                modifier = Modifier.padding(11.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                plannedKinds.chunked(4).forEachIndexed { rowIdx, rowKinds ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        rowKinds.forEachIndexed { colIdx, kind ->
                            val idx = rowIdx * 4 + colIdx
                            val outcome = outcomesByIndex[idx]
                            val isActive = running && liveProgress?.currentTaskIndex == idx && outcome == null
                            MultimodalMatrixCell(
                                index = idx,
                                kind = kind,
                                outcome = outcome,
                                isActive = isActive,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(4 - rowKinds.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // ---- 核心实时 / 汇总专业指标 ----
        AnebSectionTitle(
            text = "专业指标与分模态表现",
            modifier = Modifier.padding(top = 16.dp, start = 4.dp, bottom = 8.dp),
        )
        val b01 = result?.metrics?.get("TOK-B01")
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

        // ---- 可折叠原始工程日志（默认收起，保持主界面高颜值）----
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
                Text("工程原始轨迹日志（${logs.size} 条）", fontSize = 10.5.sp, color = colors.muted)
                Text(if (showRawLogs) "收起 ⌃" else "展开 ⌄", fontSize = 10.sp, color = colors.brand)
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
            "口径说明：aneb-profile-v2 行为轨迹仿真向当前 ANEB 节点发送真实上传、SSE 流与产物下载请求，独立计算得分，绝不改写 v1 Token AQS。",
            fontSize = 9.sp,
            lineHeight = 14.sp,
            color = colors.faint,
            modifier = Modifier.padding(top = 14.dp, bottom = 28.dp, start = 4.dp, end = 4.dp),
        )
    }
}

@Composable
private fun ActiveMultimodalStageCard(progress: BehaviorTraceRunner.LiveTraceProgress) {
    val colors = AnebTheme.colors
    val kind = progress.currentWorkloadKind ?: "text"
    val (icon, title, accent, desc) = when (kind) {
        "image" -> Quad("🖼️", "高清图多模态理解", colors.excellent, "正在上传图片特征并等待视觉模型流式返回")
        "document" -> Quad("📄", "长文档解析任务", colors.brand2, "正在上传文档上下文并流式生成结构化摘要")
        else -> Quad("💬", "多轮文本对话任务", colors.brand, "正在发送提示词并监测逐 Token 流式到达节奏")
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(listOf(Color(0xEB18203D), Color(0xF20B1024))))
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(accent.copy(alpha = 0.16f))
                    .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(icon, fontSize = 18.sp)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "正在执行 #${progress.currentTaskIndex + 1} · $title",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.ink,
                )
                Text(desc, fontSize = 9.5.sp, color = colors.muted, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val steps = listOf(
                "upload" to "① 上传 ${formatBytes(progress.currentUploadBytes)}",
                "stream" to "② 流式推理输出",
                "download" to "③ 回传 ${formatBytes(progress.currentArtifactBytes)}",
            )
            steps.forEach { (stepId, label) ->
                val active = progress.currentStep == stepId
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (active) accent.copy(alpha = 0.18f) else Color(0x660B1022))
                        .border(1.dp, if (active) accent.copy(alpha = 0.55f) else colors.hairline, RoundedCornerShape(10.dp))
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        fontSize = 8.5.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (active) accent else colors.faint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun MultimodalMatrixCell(
    index: Int,
    kind: String,
    outcome: BehaviorTraceRunner.TaskExecutionOutcome?,
    isActive: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = AnebTheme.colors
    val icon = when (kind) {
        "image" -> "🖼️"
        "document" -> "📄"
        else -> "💬"
    }
    val borderColor = when {
        outcome != null && outcome.succeeded -> colors.excellent.copy(alpha = 0.5f)
        outcome != null -> colors.poor.copy(alpha = 0.65f)
        isActive -> colors.brand
        else -> colors.hairline
    }
    val bgColor = when {
        outcome != null && outcome.succeeded -> colors.excellent.copy(alpha = 0.12f)
        outcome != null -> colors.poor.copy(alpha = 0.14f)
        isActive -> colors.brand.copy(alpha = 0.18f)
        else -> Color(0x660A0F22)
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(11.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(11.dp))
            .padding(vertical = 7.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("$icon #${index + 1}", fontSize = 9.5.sp, fontWeight = FontWeight.Medium, color = colors.ink, maxLines = 1)
        Text(
            when {
                outcome?.e2eTtftMs != null -> "${outcome.e2eTtftMs.roundToInt()}ms"
                outcome != null && outcome.succeeded -> "通过"
                outcome != null -> "失败"
                isActive -> "执行中"
                else -> kindShortLabel(kind)
            },
            fontSize = 8.5.sp,
            color = when {
                outcome != null && outcome.succeeded -> colors.excellent
                outcome != null -> colors.poor
                isActive -> colors.brand
                else -> colors.faint
            },
            maxLines = 1,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun ModalityBreakdownCard(outcomes: List<BehaviorTraceRunner.TaskExecutionOutcome>) {
    val colors = AnebTheme.colors
    val groups = listOf("text" to "💬 纯文本对话", "document" to "📄 长文档理解", "image" to "🖼️ 图片多模态")
    AnebGradientCard(Modifier.fillMaxWidth(), radius = 16.dp) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            groups.forEachIndexed { idx, (kind, label) ->
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
                    Text(label, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = colors.ink)
                    Text(
                        if (list.isEmpty()) "待执行" else buildString {
                            append("$ok/${list.size} 通过")
                            avgTtft?.let { append(" · 首字 ${it.roundToInt()}ms") }
                            avgUp?.let { append(" · ↑${String.format(Locale.ROOT, "%.1f", it)}M") }
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
    bytes >= 1_048_576L -> String.format(Locale.ROOT, "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1_024L -> "${bytes / 1_024L} KB"
    else -> "$bytes B"
}

private val DEFAULT_20_TASK_KINDS: List<String> =
    List(8) { "text" } + List(6) { "document" } + List(6) { "image" }
