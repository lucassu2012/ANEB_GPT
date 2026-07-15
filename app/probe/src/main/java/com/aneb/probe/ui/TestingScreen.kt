package com.aneb.probe.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aneb.probe.engine.LiveTelemetry
import com.aneb.probe.ui.components.HalfGauge
import com.aneb.probe.ui.components.SectionLabel
import com.aneb.probe.ui.components.SegmentedControl
import com.aneb.probe.ui.components.StBanner
import com.aneb.probe.ui.components.StGraph
import com.aneb.probe.ui.components.StLink
import com.aneb.probe.ui.components.StStep
import com.aneb.probe.ui.components.StepState
import com.aneb.probe.ui.theme.AnebTheme
import com.aneb.probe.ui.theme.AnebType
import com.aneb.probe.ui.theme.Grade
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 测试中屏（Claude Design v2 · SpeedTest 式）：连接横幅 [StBanner] + 阶段步进器 [StStep] +
 * 你↔节点 [StLink] + 180° 半盘 [HalfGauge]（中心 48px 分数/实时核心量）+ phase live 行 +
 * 核心量段控 + 实时吞吐折线 [StGraph] + token 流条 + 分层 livemini。
 *
 * 进度由 [TestProgressParser] 从 TestEngine 既有日志 KEY 行（SCENARIO_START/SCENARIO_KPI/
 * ORDER/AQS/RUN_END）派生——**不改 TestEngine 输出格式**（UI 层只读既有合同字段）。
 * 各实时量取不到显 "…"（[telemetry] 只读观测通道，缺失字段一律 null → 不以 0 顶替，R-10）。
 *
 * @param logs run 日志（append-only，MainActivity 提供）——驱动进度环与阶段名（既有解析）
 * @param telemetry 实时分层遥测（TestEngine.telemetry StateFlow 的最新投影）——驱动仪表/折线/两层实时区。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TestingScreen(
    logs: List<String>,
    telemetry: LiveTelemetry,
) {
    val colors = AnebTheme.colors
    // logs 为 append-only SnapshotStateList，每新增一行都会重组；按行数记忆化避免逐帧全量重扫（O(n²)）
    val progress = remember(logs.size) { TestProgressParser.parse(logs) }

    // 分档色（band）：边测边合成的粗 AQS（run 收尾才有）分级；驱动半盘/步进器/连线/折线的染色。
    val runningGrade = telemetry.aqsRunning?.let { Grade.fromAqsScore(it) }
    // 未合成 AQS 时给"良"档活性色，不发中性灰死盘（band 仅装饰用，不代表已判定）。
    val band = if (runningGrade != null) colors.gradeColor(runningGrade) else colors.good
    // 半盘指针/进度弧由真实测试完成度（progress.fraction 0..1）驱动"边测边扫"，绝不用
    // aqsRunning?:0 顶替缺失读数去驱动可见几何（R-10：缺失值绝不以 0 顶替）。HalfGauge 内部自带扫动动画。
    // token 流条填充同样随 run 进度推进（纯进度指示）。
    val strFill by animateFloatAsState(
        targetValue = progress.fraction,
        animationSpec = tween(500),
        label = "testing-progress",
    )

    // 仪表中心可切换核心量（默认 AQS）；仅把既有 telemetry 字段投影到中心，不改测量/落库。
    var metric by rememberSaveable { mutableStateOf(GaugeMetric.AQS) }
    val centerValue: String = when (metric) {
        GaugeMetric.AQS -> telemetry.aqsRunning?.roundToInt()?.toString() ?: "…"
        GaugeMetric.TTFT -> telemetry.ttftMs?.let { "${it.roundToInt()}" } ?: "…"
        GaugeMetric.ITL -> telemetry.itlMedianMs?.let { "${it.roundToInt()}" } ?: "…"
    }
    val centerLabel: String = when (metric) {
        GaugeMetric.AQS -> "AQS · ${runningGrade?.labelFriendly ?: "测量中"}"
        GaugeMetric.TTFT -> "首字延迟 ms"
        GaugeMetric.ITL -> "ITL 中位 ms"
    }

    // 实时吞吐折线：ITL 越小越顺 → 归一化为"顺滑度"0..1（0=一顿一顿，1=丝滑），无样本只画基线。
    val graphPoints = telemetry.itlRecentMs.map {
        (1.0 - (it / (LiveTelemetry.LIVE_STALL_MS * 2.0)).coerceIn(0.0, 1.0)).toFloat()
    }
    val graphNow = telemetry.tokenRatePerSec?.let { "${it.roundToInt()} tok/s" }
        ?: telemetry.itlMedianMs?.let { "${it.roundToInt()} ms/tok" }
        ?: "…"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Column(modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)) {
            Text("测试中", fontSize = 17.sp, fontWeight = FontWeight.Black, color = colors.ink)
            Text(
                "${progress.scenarioIndex + 1} / ${progress.totalScenarios} · ${progress.phaseName}",
                fontSize = 10.5.sp,
                color = colors.muted,
            )
        }

        // ---- 连接横幅（承载制式；测量进行中）----
        StBanner(
            isp = telemetry.rat ?: "自动选择网络",
            sub = "测量进行中 · ${progress.phaseName}",
            action = "",
            onAction = {},
            dotColor = band,
        )

        Spacer(Modifier.height(16.dp))

        // ---- 阶段步进器（连接/流式/上传/完成，随 progress 推进）----
        StStep(
            labels = listOf("连接", "流式", "上传", "完成"),
            states = testingSteps(progress),
            band = band,
        )

        Spacer(Modifier.height(16.dp))

        // ---- 你 ↔ 节点 ----
        StLink(deviceLabel = "你", nodeLabel = "节点", band = band)

        Spacer(Modifier.height(16.dp))

        // ---- 180° 半盘（AQS 读数）中心放核心量 ----
        HalfGauge(
            fraction = progress.fraction,
            band = band,
            modifier = Modifier.fillMaxWidth().aspectRatio(1.8f),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(centerValue, style = AnebType.DisplayScore, fontSize = 48.sp, color = band)
                Spacer(Modifier.height(2.dp))
                Text(centerLabel, fontSize = 11.sp, color = colors.muted)
            }
        }

        // ---- 阶段实时提示（live ping：心跳点向外扩散淡出）----
        Row(
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LivePingDot(color = colors.good)
            Spacer(Modifier.width(8.dp))
            Text(progress.liveHint, fontSize = 12.5.sp, color = colors.muted)
        }

        // ---- 仪表核心量切换器（AQS / 首字延迟 / ITL）----
        Spacer(Modifier.height(10.dp))
        SegmentedControl(
            options = GaugeMetric.entries,
            selected = metric,
            onSelect = { metric = it },
            label = { it.label },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        // ---- 实时吞吐折线 ----
        Spacer(Modifier.height(16.dp))
        StGraph(title = "实时吞吐", nowValue = graphNow, points = graphPoints, band = band)

        // ---- token 流条（stall 红点）----
        Spacer(Modifier.height(14.dp))
        TokenStreamStrip(fill = strFill, stalls = progress.stallCount)

        // ---------------- AI 业务层（token 生成质量） ----------------
        SectionLabel("AI 业务层", trailing = "token 生成")
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = 2,
        ) {
            LiveMini("首字延迟", ms(telemetry.ttftMs), Modifier.weight(1f))
            LiveMini("ITL 中位", ms(telemetry.itlMedianMs), Modifier.weight(1f))
            LiveMini("卡顿累计", "${telemetry.stallCount} 次", Modifier.weight(1f))
            LiveMini("token 速率", telemetry.tokenRatePerSec?.let { "${it.roundToInt()}/s" } ?: "…", Modifier.weight(1f))
        }

        // ---------------- 移动网络层（承载质量） ----------------
        SectionLabel("移动网络层", trailing = telemetry.rat ?: "…")
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = 2,
        ) {
            LiveMini("RTT", ms(telemetry.rttMs), Modifier.weight(1f))
            LiveMini("抖动", ms(telemetry.jitterMs), Modifier.weight(1f))
            LiveMini("RSRP", telemetry.rsrp?.let { "$it" } ?: "…", Modifier.weight(1f))
            LiveMini("SINR", telemetry.sinr?.let { "$it" } ?: "…", Modifier.weight(1f))
            LiveMini("制式", telemetry.rat ?: "…", Modifier.weight(1f))
            LiveMini("上行", telemetry.upMbps?.let { String.format(Locale.ROOT, "%.1f Mbps", it) } ?: "…", Modifier.weight(1f))
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 阶段步进器状态派生：连接（进屏即完成）/ 流式（s1·s2 进行）/ 上传（s3）/ 完成（RUN_END）。
 * 纯投影既有 [TestProgressParser.LiveProgress]，不新增测量语义。
 */
private fun testingSteps(progress: TestProgressParser.LiveProgress): List<StepState> {
    val idx = progress.scenarioIndex
    val finished = progress.finished
    return listOf(
        StepState.Done, // 连接：进入测试中屏即视为已建连
        if (finished || idx >= 2) StepState.Done else StepState.On, // 流式：s1/s2
        when { // 上传：s3 多模态
            finished -> StepState.Done
            idx >= 2 -> StepState.On
            else -> StepState.Todo
        },
        if (finished) StepState.Done else StepState.Todo, // 完成
    )
}

/**
 * 仪表中心可切换核心量：AQS / 首字延迟(TTFT) / ITL。
 * 纯展示投影——切换只改中心显示的既有 telemetry 字段，不新增/改动测量字段与落库口径。
 */
enum class GaugeMetric(val label: String) {
    AQS("AQS"),
    TTFT("首字延迟"),
    ITL("ITL"),
}

/** 毫秒值格式化：null → "…"（R-10：绝不以 0 顶替缺失） */
private fun ms(v: Double?): String = v?.let { "${it.roundToInt()}ms" } ?: "…"

/**
 * 心跳 live 点（设计稿 .live · ping 1.4s）：实心点 + 向外扩散淡出的环。减弱动效下退化为静态点。
 */
@Composable
private fun LivePingDot(color: androidx.compose.ui.graphics.Color) {
    val reduced = com.aneb.probe.ui.theme.LocalReducedMotion.current
    val ping = if (reduced) {
        0f
    } else {
        val t = rememberInfiniteTransition(label = "live")
        val v by t.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1400, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "live-ping",
        )
        v
    }
    Box(
        modifier = Modifier
            .size(6.dp)
            .drawBehind {
                if (ping > 0f) {
                    val r = (size.minDimension / 2f) * (1f + ping * 1.6f)
                    val a = (0.5f * (1f - ping)).coerceAtLeast(0f)
                    drawCircle(
                        color = color.copy(alpha = a),
                        radius = r,
                        center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f),
                    )
                }
            }
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
private fun TokenStreamStrip(fill: Float, stalls: Int) {
    val colors = AnebTheme.colors
    val total = 40
    val lit = (fill * total).toInt().coerceIn(0, total)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until total) {
            // 卡顿红点：把观测到的 stall 数均匀落在已点亮段内
            val isStall = stalls > 0 && lit > 0 && (i < lit) &&
                ((i + 1) % (lit / stalls.coerceAtLeast(1)).coerceAtLeast(1) == 0) &&
                (i / ((lit / stalls.coerceAtLeast(1)).coerceAtLeast(1)) < stalls)
            val color = when {
                isStall -> colors.poor
                i < lit -> colors.good
                else -> colors.faint
            }
            val dot = if (isStall) 6.dp else 5.dp
            Box(modifier = Modifier.size(dot).clip(CircleShape).background(color))
        }
    }
}

@Composable
private fun LiveMini(key: String, value: String, modifier: Modifier = Modifier) {
    val colors = AnebTheme.colors
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceElevated)
            .border(1.dp, colors.hairline, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(key, fontSize = 11.sp, color = colors.muted)
        Spacer(Modifier.width(6.dp))
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colors.ink)
    }
}

/**
 * run 进度派生（纯逻辑，可单测）。从 TestEngine 既有日志 KEY 行折叠出结构化进度——
 * 不改 TestEngine 输出格式（UI 层解析既有合同字段）。
 */
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
    ) {
        val liveHint: String get() = "正在测：${phaseName}的 token 流是否顺滑"

        /** stall 落在环刻度（60 格）上的下标近似（卡顿缺口位置）。 */
        val stallTickPositions: List<Int>
            get() = if (stallCount <= 0) emptyList() else (1..stallCount).map {
                ((it.toFloat() / (stallCount + 1)) * 60f * fraction).toInt().coerceIn(0, 59)
            }
    }

    private val PROFILE_NAMES = mapOf(
        "s1_chat" to "闲聊对话",
        "s2_coding_agent" to "编码 Agent 流",
        "s3_multimodal" to "多模态上传",
    )

    fun parse(logs: List<String>): LiveProgress {
        var runId: String? = null
        var total = 3 // 快测缺省 3 场景
        var scenarioIndex = 0
        var currentProfile: String? = null
        var completedKpis = 0
        var latestTtft: Double? = null
        var stalls = 0
        var finished = false
        var finishedRunId: String? = null

        for (line in logs) {
            when {
                line.startsWith("RUN_START ") ->
                    runId = field(line, "run_id")
                line.startsWith("ORDER ") -> {
                    // order=s1,s2,s3 → 场景总数（首个 ORDER 即可）
                    field(line, "order")?.let { total = it.split(',').size.coerceAtLeast(1) }
                }
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

        val fraction = ((completedKpis.toFloat() + if (finished) 0f else 0.5f) / total)
            .coerceIn(0f, 1f)
        val phaseName = PROFILE_NAMES[currentProfile] ?: "网络场景"
        return LiveProgress(
            runId = runId,
            scenarioIndex = scenarioIndex.coerceIn(0, (total - 1).coerceAtLeast(0)),
            totalScenarios = total,
            phaseName = phaseName,
            fraction = fraction,
            ttftMs = latestTtft,
            stallCount = stalls,
            finished = finished,
            finishedRunId = finishedRunId,
        )
    }

    /** 从 "key=value" 合同行提取字段（空白分隔；值到下一个空白止）。 */
    private fun field(line: String, key: String): String? =
        Regex("(?:^|\\s)${Regex.escape(key)}=(\\S+)").find(line)?.groupValues?.get(1)
}
