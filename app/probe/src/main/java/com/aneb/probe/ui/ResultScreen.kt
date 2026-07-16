package com.aneb.probe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aneb.probe.data.ScenarioResultEntity
import com.aneb.probe.data.TestRun
import com.aneb.probe.radio.GeoTrack
import com.aneb.probe.ui.components.GlassChrome
import com.aneb.probe.ui.components.AnebMetric
import com.aneb.probe.ui.components.AnebMetricTrio
import com.aneb.probe.ui.components.AnebPageIntro
import com.aneb.probe.ui.components.AnebScoreRing
import com.aneb.probe.ui.components.AnebTopBar
import com.aneb.probe.ui.components.GradeChip
import com.aneb.probe.ui.components.HalfGauge
import com.aneb.probe.ui.components.KpiBar
import com.aneb.probe.ui.components.ResIcon
import com.aneb.probe.ui.components.SectionLabel
import com.aneb.probe.ui.components.SegmentedControl
import com.aneb.probe.ui.components.StBanner
import com.aneb.probe.ui.components.StResItem
import com.aneb.probe.ui.components.StResults
import com.aneb.probe.ui.components.pressable
import com.aneb.probe.ui.theme.AnebColors
import com.aneb.probe.ui.theme.AnebElevation
import com.aneb.probe.ui.theme.AnebShapes
import com.aneb.probe.ui.theme.AnebTheme
import com.aneb.probe.ui.theme.AnebType
import com.aneb.probe.ui.theme.Grade
import com.aneb.probe.ui.theme.gradeColorByKey
import com.aneb.probe.ui.theme.invalidNeutral
import com.aneb.probe.ui.theme.lowConf
import com.aneb.probe.ui.theme.validityColor
import kotlin.math.roundToInt

/**
 * 结果页（重设计，设计稿 §01 结果双视图）：顶部 简洁/专业 分段控件切换普通/开发者视图。
 * - 普通（[ResultViewMode.Simple]）：脉冲环 + 分数 + 四级中文标签 + [VerdictText] 结论文案
 *   + 三瓦片（响应速度/卡顿/上传）+ 分享成图 + "查看详细数据"切专业；
 * - 开发者（[ResultViewMode.Detailed]）：全量 KPI 明细表（双口径）+ REACH 矩阵 + 连接信息
 *   + 导出 JSON/CSV。
 *
 * 全部数据来自 Room 落库实体（TestEngine 写入口径），本层不重算（D-02 单一事实来源）。
 */

// 分级/有效性色统一走 AnebTheme.colors（theme-aware 单一事实源，见 ui/theme/Color.kt）：
// gradeColorByKey / gradeColor(Grade) / validityColor / invalidNeutral / lowConf。
// 不再在本文件私有写死暗色 hex（浅色主题下不跟随的偏差已消除）。

enum class ResultViewMode(val label: String) { Simple("简洁"), Detailed("专业") }

@Composable
fun ResultScreen(
    run: TestRun?,
    scenarios: List<ScenarioResultEntity>,
    hasReportJson: Boolean,
    exportStatus: String?,
    onExportJson: () -> Unit,
    onExportCsv: () -> Unit,
    onBack: () -> Unit,
    trackSummaries: Map<Long, GeoTrack.Summary> = emptyMap(),
    hasTrack: Boolean = false,
    onExportTrack: () -> Unit = {},
    /** 分享成图：ResultScreen 投影出展示态 Model，实际存图/分享由 Activity 承载（需 Context） */
    onShare: (ShareCard.Model) -> Unit = {},
) {
    val colors = AnebTheme.colors
    var viewMode by rememberSaveable { mutableStateOf(ResultViewMode.Simple) }

    Column(modifier = Modifier.fillMaxSize().background(colors.background).padding(horizontal = 20.dp)) {
        AnebTopBar(showBack = true, onBack = onBack, showMenu = true)
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            AnebPageIntro(
                eyebrow = "RESULT",
                title = "测试结果",
                subtitle = run?.let(NetworkLabel::forRun),
                modifier = Modifier.weight(1f),
            )
            SegmentedControl(
                options = ResultViewMode.entries.toList(),
                selected = viewMode,
                onSelect = { viewMode = it },
                label = { it.label },
            )
        }

        if (run == null) {
            Text("run 不存在", color = colors.invalidNeutral, modifier = Modifier.padding(top = 16.dp))
            return
        }

        // 内容层 + 底部玻璃操作区（§4.3/§4.4 chrome-bot）：内容从玻璃条下方滚过，操作区常驻底部。
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when (viewMode) {
                ResultViewMode.Simple -> SimpleResultView(run = run, scenarios = scenarios)
                ResultViewMode.Detailed -> DetailedResultView(
                    run = run,
                    scenarios = scenarios,
                    hasReportJson = hasReportJson,
                    exportStatus = exportStatus,
                    trackSummaries = trackSummaries,
                    hasTrack = hasTrack,
                    onExportTrack = onExportTrack,
                )
            }
            ResultBottomBar(
                viewMode = viewMode,
                onSeeDetails = { viewMode = ResultViewMode.Detailed },
                onShare = { onShare(simpleShareModel(run, scenarios, colors)) },
                onExportJson = onExportJson,
                onExportCsv = onExportCsv,
                exportEnabledJson = hasReportJson,
                exportEnabledCsv = scenarios.isNotEmpty(),
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/**
 * 底部玻璃操作区（§4.3 两按钮 查看详细/分享 · §4.4 两按钮 导出 JSON/CSV）——GlassChrome 承载，
 * 内容从其下方滚过。按视图模式切换按钮组。
 */
@Composable
private fun ResultBottomBar(
    viewMode: ResultViewMode,
    onSeeDetails: () -> Unit,
    onShare: () -> Unit,
    onExportJson: () -> Unit,
    onExportCsv: () -> Unit,
    exportEnabledJson: Boolean,
    exportEnabledCsv: Boolean,
    modifier: Modifier = Modifier,
) {
    GlassChrome(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            when (viewMode) {
                ResultViewMode.Simple -> {
                    ActionButton("查看详细数据", primary = false, modifier = Modifier.weight(1f), onClick = onSeeDetails)
                    ActionButton("分享成绩", primary = true, modifier = Modifier.weight(1f), onClick = onShare)
                }
                ResultViewMode.Detailed -> {
                    ActionButton("导出 JSON", primary = false, enabled = exportEnabledJson, modifier = Modifier.weight(1f), onClick = onExportJson)
                    ActionButton("导出 CSV", primary = false, enabled = exportEnabledCsv, modifier = Modifier.weight(1f), onClick = onExportCsv)
                }
            }
        }
    }
}

/**
 * iOS 风格操作按钮（§4 .btn）：primary=品牌填充白字（+轻抬升）；ghost=幽灵描边 ink 字。
 * 按压反馈走 [pressable]（scale .96 / 减弱动效降级透明度），16 连续圆角。
 */
@Composable
private fun ActionButton(
    text: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = AnebTheme.colors
    val container = if (primary) Color(0xFF071118) else Color.Transparent
    val fg = if (primary) colors.excellent else colors.ink.copy(alpha = 0.78f)
    Box(
        modifier = modifier
            .then(if (primary && enabled) Modifier.shadow(AnebElevation.level2, AnebShapes.button, clip = false) else Modifier)
            .clip(AnebShapes.button)
            .background(if (enabled) container else container.copy(alpha = 0.4f))
            .border(1.dp, if (primary) colors.excellent.copy(alpha = 0.48f) else colors.hairline, AnebShapes.pill)
            .pressable(onClick = onClick, enabled = enabled)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (enabled) fg else fg.copy(alpha = 0.5f),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
}

// ------------------------------------------------------------------
// 普通用户视图
// ------------------------------------------------------------------

@Composable
private fun SimpleResultView(
    run: TestRun,
    scenarios: List<ScenarioResultEntity>,
) {
    val colors = AnebTheme.colors
    val score = run.aqsScore
    val grade = score?.let { Grade.fromAqsScore(it) }
    val band = colors.gradeColor(grade)
    val rows = ResultFormat.runKpiRows(scenarios).associateBy { it.row.id }

    val t1 = rows["T1"]?.row
    val t3 = rows["T3"]?.row
    val u1 = rows["U1"]?.row

    val verdict = simpleVerdict(run, rows)
    val codingScenarios = scenarios.filter { it.profileId == "s2_coding_agent" }
    val codingHasUsable = codingScenarios.any { !it.validity.equals("invalid", ignoreCase = true) }
    val codingValidity = when {
        codingScenarios.isEmpty() -> null
        codingHasUsable -> "valid"
        else -> "invalid"
    }
    val conclusions = OutcomeConclusions.build(
        OutcomeConclusions.Input(
            runStatus = run.status,
            score = run.aqsScore,
            codingValidity = codingValidity,
            codingInvalidReasons = codingScenarios.joinToString(",") { it.invalidReasons },
            ttftMs = t1?.value,
            ttftGrade = t1?.grade,
            stallRate = t3?.value,
            stallGrade = t3?.grade,
            uploadMbps = u1?.value,
            uploadGrade = u1?.grade,
            sessionDropRate = run.aqsV02C1DropRate,
        ),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // 底部留白，让内容从底部玻璃操作区（≈68dp 高）下方滚过而不被压住
            .padding(top = 8.dp, bottom = 88.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ---- 连接横幅（结果态：分档色点 + 网络副行）----
        StBanner(
            isp = "测试完成",
            sub = NetworkLabel.forRun(run),
            action = "",
            onAction = {},
            dotColor = band,
        )

        Spacer(Modifier.height(20.dp))

        // ---- 270° 1:1 结果圆环（与 result-simple.html 同构）----
        AnebScoreRing(
            score = score?.roundToInt(),
            fraction = score?.let { (it / 100.0).toFloat() } ?: 0f,
            accent = band,
            label = grade?.labelFriendly ?: "未完成",
            supporting = "AI 体验分",
            modifier = Modifier.size(206.dp),
        )

        Spacer(Modifier.height(14.dp))
        // ---- 结论句（关键结论小句分档色加粗）----
        Text(
            verdictAnnotated(verdict, band, colors.ink),
            fontSize = 14.5.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 4.dp),
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(18.dp))
        // ---- 结果页三指标；不把缺失值折成 0 ----
        AnebMetricTrio(
            items = listOf(
                AnebMetric("首字响应", t1?.value?.let { String.format(java.util.Locale.ROOT, "%.2f", it / 1_000.0) } ?: "—", "秒", colors.gradeColor(Grade.fromKey(t1?.grade))),
                AnebMetric("Token 间隔", rows["T2"]?.row?.value?.let { "${it.roundToInt()}" } ?: "—", "ms", colors.gradeColor(Grade.fromKey(rows["T2"]?.row?.grade))),
                AnebMetric("卡顿", stallTileValue(t3?.value), "", colors.gradeColor(Grade.fromKey(t3?.grade))),
            ),
        )

        Spacer(Modifier.height(18.dp))
        OutcomeConclusionBlock(conclusions)
    }
}

@Composable
private fun OutcomeConclusionBlock(items: List<OutcomeConclusions.Item>) {
    val colors = AnebTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Text("本次结论", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
        Spacer(Modifier.height(9.dp))
        items.forEachIndexed { index, item ->
            val accent = when (item.evidence) {
                OutcomeConclusions.Evidence.MEASURED -> colors.brand
                OutcomeConclusions.Evidence.ESTIMATED -> colors.fair
                OutcomeConclusions.Evidence.UNAVAILABLE -> colors.muted
            }
            val evidence = when (item.evidence) {
                OutcomeConclusions.Evidence.MEASURED -> "实测"
                OutcomeConclusions.Evidence.ESTIMATED -> "估算"
                OutcomeConclusions.Evidence.UNAVAILABLE -> "待补数据"
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(AnebShapes.xs)
                    .background(Color(0x7A11162A))
                    .border(1.dp, colors.hairline, AnebShapes.xs)
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    evidence,
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                    modifier = Modifier
                        .border(1.dp, accent.copy(alpha = 0.32f), AnebShapes.pill)
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                )
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
                    Spacer(Modifier.height(3.dp))
                    Text(item.body, fontSize = 10.sp, lineHeight = 15.sp, color = colors.muted)
                }
            }
            if (index < items.lastIndex) Spacer(Modifier.height(7.dp))
        }
    }
}

/**
 * 结论句着色：主结论小句（首个破折号「——」之前的判断词，如"很适合 AI 助手"/"能用但会卡"）
 * 染分档色 + 加粗，其余正文走 [ink]。无破折号（不可计算话术）时整句走正文，不强加分档色。
 */
private fun verdictAnnotated(verdict: String, accent: Color, ink: Color) = buildAnnotatedString {
    val sep = "——"
    val idx = verdict.indexOf(sep)
    if (idx > 0) {
        withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold)) {
            append(verdict.substring(0, idx))
        }
        withStyle(SpanStyle(color = ink)) { append(verdict.substring(idx)) }
    } else {
        withStyle(SpanStyle(color = ink)) { append(verdict) }
    }
}

private fun stallTileValue(t3Rate: Double?): String = when {
    t3Rate == null -> "—"
    t3Rate <= 0.0 -> "0"
    else -> "%.1f%%".format(t3Rate * 100)
}

/** 结论文案（普通视图展示 + 分享卡共用，确定性同源，无重算差异）。 */
private fun simpleVerdict(run: TestRun, rows: Map<String, ResultFormat.RunKpiRow>): String =
    VerdictText.generate(
        VerdictText.Input(
            score = run.aqsScore,
            lowConfidence = run.aqsLowConfidence == true,
            vetoApplied = run.aqsVetoApplied == true,
            notComputableReason = run.aqsNotComputableReason ?: run.status,
            kpiGrades = rows.values.associate { it.row.id to Grade.fromKey(it.row.grade) },
        ),
    )

/**
 * run+scenarios → 分享卡 Model（底部"分享成绩"按钮用；与展示态同源，零重算差异）。
 * [colors] 由 composable 调用点注入：分享卡走 Canvas 绘制（脱离主题），故在此按当前主题取语义色再 toArgb。
 */
private fun simpleShareModel(
    run: TestRun,
    scenarios: List<ScenarioResultEntity>,
    colors: AnebColors,
): ShareCard.Model {
    val rows = ResultFormat.runKpiRows(scenarios).associateBy { it.row.id }
    val grade = run.aqsScore?.let { Grade.fromAqsScore(it) }
    return buildShareModel(
        run = run,
        verdict = simpleVerdict(run, rows),
        grade = grade,
        colors = colors,
        t1 = rows["T1"]?.row,
        t3 = rows["T3"]?.row,
        u1 = rows["U1"]?.row,
    )
}

/** 结果展示态 → 分享卡 Model（按当前主题 [colors] 取语义色 argb，零重算）。 */
private fun buildShareModel(
    run: TestRun,
    verdict: String,
    grade: Grade?,
    colors: AnebColors,
    t1: ResultFormat.KpiRow?,
    t3: ResultFormat.KpiRow?,
    u1: ResultFormat.KpiRow?,
): ShareCard.Model {
    fun argb(g: String?): Int = colors.gradeColorByKey(g).toArgb()
    val gradeArgb = colors.gradeColor(grade).toArgb()
    return ShareCard.Model(
        score = run.aqsScore?.roundToInt(),
        gradeLabel = grade?.labelFriendly ?: "未完成",
        gradeColorArgb = gradeArgb,
        verdict = verdict,
        tiles = listOf(
            ShareCard.Model.Tile(
                t1?.value?.let { "${it.roundToInt()}ms" } ?: "—", "响应速度", argb(t1?.grade),
            ),
            ShareCard.Model.Tile(stallTileValue(t3?.value), "卡顿", argb(t3?.grade)),
            ShareCard.Model.Tile(
                u1?.value?.let { "%.1f".format(it) } ?: "—", "上传 Mbps", argb(u1?.grade),
            ),
        ),
        networkLine = NetworkLabel.forRun(run),
    )
}

// ------------------------------------------------------------------
// 开发者视图（原 P1-C07 全量内容 + REACH 矩阵 + 连接信息）
// ------------------------------------------------------------------

@Composable
private fun DetailedResultView(
    run: TestRun,
    scenarios: List<ScenarioResultEntity>,
    hasReportJson: Boolean,
    exportStatus: String?,
    trackSummaries: Map<Long, GeoTrack.Summary>,
    hasTrack: Boolean,
    onExportTrack: () -> Unit,
) {
    val colors = AnebTheme.colors
    // 主导出（JSON/CSV）已上移到底部玻璃操作区；此处 contentPadding.bottom 让末尾内容从其下方滚过。
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 84.dp),
    ) {
        item { AqsHeadline(run) }
        item { AqsSubScoreBars(scenarios) }
        item { ReachMatrix(run) }
        item { ConnectionInfo(scenarios) }
        item { RunMeta(run) }
        item {
            SectionLabel("KPI 总表（AQS 输入映射：N←S1 / T,U2←S2 / U1←S3）")
            Column { ResultFormat.runKpiRows(scenarios).forEach { RunKpiRowLine(it) } }
        }
        item { SectionLabel("场景明细") }
        items(count = scenarios.size, key = { i -> scenarios[i].id }) { i ->
            ScenarioCard(scenarios[i], trackSummaries[scenarios[i].id])
        }
        item {
            SectionLabel("导出轨迹与状态")
            if (hasTrack) {
                Button(enabled = true, onClick = onExportTrack) { Text("导出轨迹") }
            }
            if (!hasReportJson) {
                Text(
                    "该 run 未生成上报体（早退/失败），JSON 不可导出",
                    fontSize = 11.sp, color = colors.invalidNeutral,
                )
            }
            exportStatus?.let { Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = colors.invalidNeutral) }
        }
        item { ClaimScopeFooter(run) }
    }
}

/**
 * AQS 子分横条：用 run 级 KPI（AqsInputMapper 映射视图）的分级 + 权重标注渲染。
 * 横条填充按分级色语义呈现（值不是 0–100 子分——子分需 AqsScorer，不落 Room；此处以
 * 分级映射为主，避免展示层重算 AqsScorer 内部子分，D-02）。T4 否决在头条已标注。
 */
@Composable
private fun AqsSubScoreBars(scenarios: List<ScenarioResultEntity>) {
    val weights = mapOf(
        "T1" to "20%", "T3" to "20%", "T2" to "15%", "U1" to "15%",
        "U2" to "10%", "N1" to "10%", "N2" to "10%",
    )
    val rows = ResultFormat.runKpiRows(scenarios).associateBy { it.row.id }
    SectionLabel("AQS 子分与权重")
    weights.forEach { (id, w) ->
        val r = rows[id]?.row
        val grade = Grade.fromKey(r?.grade)
        // 分级 → 条填充占比（优 1.0 / 良 0.75 / 可 0.5 / 差 0.25 / 缺失 0），语义近似非精确子分
        val frac = when (grade) {
            Grade.Excellent -> 1.0f
            Grade.Good -> 0.75f
            Grade.Fair -> 0.5f
            Grade.Poor -> 0.25f
            null -> 0f
        }
        KpiBar(
            label = "$id $w",
            fraction = frac,
            grade = grade,
            valueText = r?.let { ResultFormat.gradeLabel(it.grade) } ?: "—",
        )
    }
}

/** REACH 连接可达性矩阵（SNI 域名 / bare-IP × 握手结果）——数据取自 TestRun 既有列。 */
@Composable
private fun ReachMatrix(run: TestRun) {
    val colors = AnebTheme.colors
    SectionLabel("连接可达性 REACH")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceElevated),
    ) {
        ReachRow("SNI 域名", run.sniReachable, run.sniReachMs, header = false)
        HorizontalDivider(color = colors.hairline)
        ReachRow("bare-IP", run.ipReachable, run.ipReachMs, header = false)
    }
    if (run.sniReachable == null && run.ipReachable == null) {
        Text("（本 run 未做 SNI 双通道探测）", fontSize = 11.sp, color = colors.invalidNeutral, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun ReachRow(label: String, result: String?, ms: Long?, header: Boolean) {
    val colors = AnebTheme.colors
    val ok = result == "ok"
    val color = when {
        result == null -> colors.muted
        ok -> colors.excellent
        else -> colors.poor
    }
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
        Text(label, fontSize = 12.sp, color = colors.muted, modifier = Modifier.width(90.dp))
        Text(
            when {
                result == null -> "未探测"
                ok -> "OK ${ms?.let { "${it}ms" } ?: ""}"
                else -> result.uppercase()
            },
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = color,
        )
    }
}

/** 连接信息：transport / 协商地址 / offset drift（取首个场景快照，零重算）。 */
@Composable
private fun ConnectionInfo(scenarios: List<ScenarioResultEntity>) {
    val s = scenarios.firstOrNull() ?: return
    val colors = AnebTheme.colors
    SectionLabel("连接信息")
    Text(
        "transport=${s.netTransport ?: "—"}  addr=${s.serverObservedAddr ?: "—"}\n" +
            "offset drift=${s.offsetDriftPpm?.let { "%.2f ppm".format(it) } ?: "—"}" +
            (if (s.offsetSuspect) " (suspect)" else "") +
            (ResultFormat.bufferingLabel(s)?.let { "\n$it" } ?: ""),
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        color = colors.muted,
    )
}

// ---- 以下为原 P1-C07 详情组件（保留内部实现，专业视图复用）----

@Composable
private fun AqsHeadline(run: TestRun) {
    val colors = AnebTheme.colors
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        val score = run.aqsScore
        if (score != null) {
            val grade = ResultFormat.aqsGrade(score)
            Row(verticalAlignment = Alignment.Bottom) {
                Text("%.1f".format(score), fontSize = 44.sp, fontWeight = FontWeight.Black, color = colors.gradeColorByKey(grade))
                Spacer(Modifier.width(10.dp))
                Text(
                    "AQS ${ResultFormat.gradeLabel(grade)}",
                    fontSize = 18.sp, color = colors.gradeColorByKey(grade),
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            if (run.aqsVetoApplied == true) {
                Text("T4 一票否决生效（封顶 54）", color = colors.poor, fontSize = 13.sp)
            }
            if (run.aqsLowConfidence == true) {
                Text(
                    "⚠ ${ResultFormat.LOW_CONFIDENCE_LABEL}：证据不完整，本分数仅供参考",
                    color = colors.lowConf, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                )
            }
        } else {
            Text("AQS —", fontSize = 44.sp, fontWeight = FontWeight.Black, color = colors.invalidNeutral)
            Text(
                "不可计算：${run.aqsNotComputableReason ?: run.status ?: "unknown"}",
                color = colors.invalidNeutral, fontSize = 14.sp,
            )
        }
        ResultFormat.aqsV02Lines(run)?.let { lines ->
            Text(lines[0], fontSize = 15.sp, fontWeight = FontWeight.Bold, color = colors.ink)
            Text(lines[1], fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = colors.invalidNeutral)
        }
    }
}

@Composable
private fun RunMeta(run: TestRun) {
    val colors = AnebTheme.colors
    Text(
        "run=${run.runId}\nmode=${run.mode} transport=${run.transport} status=${run.status ?: "?"} " +
            "report=${run.reportStatus ?: "—"}\norder=${run.scenarioOrder}",
        fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = colors.muted,
    )
    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp), color = colors.hairline)
}

@Composable
private fun RunKpiRowLine(r: ResultFormat.RunKpiRow) = KpiLine(r.row, prefix = "[${r.source}] ")

@Composable
internal fun KpiLine(row: ResultFormat.KpiRow, prefix: String = "") {
    val colors = AnebTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
        Box(
            modifier = Modifier.width(6.dp).height(28.dp)
                .background(if (row.value == null) colors.invalidNeutral else colors.gradeColorByKey(row.grade)),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("$prefix${row.id} ${row.label}", fontSize = 12.sp, color = colors.ink)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ResultFormat.formatValue(row),
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                    color = colors.ink,
                )
                Spacer(Modifier.width(8.dp))
                GradeChip(Grade.fromKey(row.grade))
                if (row.lowConfidence) {
                    Spacer(Modifier.width(8.dp))
                    Text(ResultFormat.LOW_CONFIDENCE_LABEL, fontSize = 11.sp, color = colors.lowConf)
                }
            }
        }
    }
}

@Composable
private fun ScenarioCard(s: ScenarioResultEntity, track: GeoTrack.Summary?) {
    val colors = AnebTheme.colors
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.width(10.dp).height(10.dp).background(colors.validityColor(s.validity)))
            Spacer(Modifier.width(6.dp))
            Text("${s.profileId}#${s.repeatIndex} (${s.profileVersion})", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.ink)
            Spacer(Modifier.width(8.dp))
            Text(s.validity, fontSize = 12.sp, color = colors.validityColor(s.validity))
        }
        if (s.validity == "invalid") {
            Text(
                "无效原因: ${s.invalidReasons.ifEmpty { "unknown" }}（KPI 已抑制，原始事件保留）",
                fontSize = 11.sp, color = colors.invalidNeutral,
            )
        }
        Text(
            "漂移率 drift=${s.offsetDriftPpm?.let { "%.2f ppm".format(it) } ?: "—"}" +
                (if (s.offsetSuspect) " (offset_suspect)" else "") +
                "  net=${s.netTransport ?: "—"}  addr=${s.serverObservedAddr ?: "—"}",
            fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = colors.muted,
        )
        ResultFormat.bufferingLabel(s)?.let {
            Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = colors.invalidNeutral)
        }
        if (track != null && track.points > 0) {
            Text(
                "轨迹 ${track.points} 点  起终点距离 " +
                    (track.startEndMeters?.let { "%.1f m".format(it) } ?: "—（<2 点）"),
                fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = colors.muted,
            )
        }
        ResultFormat.kpiRows(s).forEach { KpiLine(it) }
        HorizontalDivider(modifier = Modifier.padding(top = 4.dp), color = colors.hairline)
    }
}

@Composable
private fun ClaimScopeFooter(run: TestRun) {
    val colors = AnebTheme.colors
    Column(modifier = Modifier.padding(top = 12.dp, bottom = 24.dp)) {
        HorizontalDivider(color = colors.hairline)
        Text(ResultFormat.CLAIM_SCOPE_TEXT, fontSize = 11.sp, color = colors.invalidNeutral, modifier = Modifier.padding(top = 6.dp))
        Text(ResultFormat.AQS_DISCLAIMER_TEXT, fontSize = 11.sp, color = colors.invalidNeutral)
        Text(
            "kpi_set=${run.kpiSet} aqs=${run.aqsVersion} schema=${run.schemaVersion} profiles=${run.profileVersions}",
            fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = colors.invalidNeutral,
        )
    }
}
