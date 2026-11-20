package com.aneb.probe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aneb.probe.data.BasicSpeedResultEntity
import com.aneb.probe.data.RadioSampleEntity
import com.aneb.probe.data.ScenarioResultEntity
import com.aneb.probe.data.TestRun
import com.aneb.probe.data.VoiceResultEntity
import com.aneb.probe.engine.LoadedResponsivenessReport
import com.aneb.probe.ui.components.AnebGradientCard
import com.aneb.probe.ui.components.AnebPageIntro
import com.aneb.probe.ui.components.AnebSectionTitle
import com.aneb.probe.ui.components.AnebSparkline
import com.aneb.probe.ui.components.AnebTopBar
import com.aneb.probe.ui.components.pressable
import com.aneb.probe.ui.theme.AnebShapes
import com.aneb.probe.ui.theme.AnebTheme
import com.aneb.probe.ui.theme.AnebType
import com.aneb.probe.ui.theme.Grade
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** 历史页：真实 Room 数据投影到 `history.html` 的趋势卡与记录行，支持双次配对对比（F13）。 */
@Composable
fun HistoryScreen(
    runs: List<TestRun>,
    basicRuns: List<BasicSpeedResultEntity>,
    onOpen: (String) -> Unit,
    onOpenBasic: (String) -> Unit,
    onGenerateReport: () -> Unit,
    onBack: () -> Unit,
    showBack: Boolean = true,
    onOpenResearch: (() -> Unit)? = null,
    voiceResults: List<VoiceResultEntity> = emptyList(),
    onOpenVoice: (() -> Unit)? = null,
    scenariosByRunId: Map<String, List<ScenarioResultEntity>> = emptyMap(),
    radioSamplesByRunId: Map<String, List<RadioSampleEntity>> = emptyMap(),
    loadedResponsivenessByRunId: Map<String, LoadedResponsivenessReport> = emptyMap(),
    comparisonExportStatus: String? = null,
    comparisonActionBusy: Boolean = false,
    onExportComparisonMarkdown: (PairedComparisonFormat.PairedComparisonResult.Compatible) -> Unit = {},
    onShareComparisonImage: (PairedComparisonFormat.PairedComparisonResult.Compatible) -> Unit = {},
    onClearComparisonStatus: () -> Unit = {},
) {
    val colors = AnebTheme.colors
    val ordered = remember(runs, basicRuns, voiceResults) {
        buildList<HistoryEntry> {
            runs.forEach { add(HistoryEntry.Token(it)) }
            basicRuns.forEach { add(HistoryEntry.Basic(it)) }
            voiceResults.forEach { add(HistoryEntry.Voice(it)) }
        }.sortedByDescending { it.startedAtEpochMs }
    }
    val comparableInputs = remember(ordered, scenariosByRunId, radioSamplesByRunId, loadedResponsivenessByRunId) {
        ordered.map { entry ->
            when (entry) {
                is HistoryEntry.Token -> PairedComparisonFormat.ComparableRunInput.Token(
                    run = entry.run,
                    scenarios = scenariosByRunId[entry.run.runId].orEmpty(),
                    radioSamples = radioSamplesByRunId[entry.run.runId].orEmpty(),
                )
                is HistoryEntry.Basic -> PairedComparisonFormat.ComparableRunInput.Basic(
                    entity = entry.result,
                    responsivenessReport = loadedResponsivenessByRunId[entry.result.runId],
                )
                is HistoryEntry.Voice -> PairedComparisonFormat.ComparableRunInput.Voice(entry.result)
            }
        }
    }
    val comparableByKey = remember(comparableInputs) {
        comparableInputs.associateBy { it.entryKey }
    }
    var compareState by remember { mutableStateOf(PairedComparisonFormat.HistoryComparisonState()) }
    fun updateCompareState(next: PairedComparisonFormat.HistoryComparisonState) {
        if (next.compareMode != compareState.compareMode ||
            next.selectedKeys != compareState.selectedKeys ||
            next.swapped != compareState.swapped
        ) {
            onClearComparisonStatus()
        }
        compareState = next
    }
    val comparisonResult = remember(compareState, comparableByKey) {
        compareState.resolveComparison(comparableByKey)
    }

    val scored = remember(runs) { runs.mapNotNull { it.aqsScore }.filter { it.isFinite() } }
    val avg = scored.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
    val best = scored.maxOrNull()?.roundToInt()
    val worst = scored.minOrNull()?.roundToInt()
    val trend = scored.asReversed().map { (it / 100.0).toFloat().coerceIn(0f, 1f) }

    Column(Modifier.fillMaxSize().background(colors.background).padding(horizontal = 16.dp)) {
        AnebTopBar(showBack = showBack, onBack = onBack, showMenu = !showBack)
        AnebPageIntro(
            eyebrow = "RESULTS",
            title = "测试历史",
            subtitle = if (ordered.isEmpty()) {
                "完成首次测试后，这里会显示真实体验趋势。"
            } else {
                "共 ${ordered.size} 次测试，其中 ${scored.size} 个有效 AI 体验分。"
            },
            modifier = Modifier.padding(top = 3.dp, start = 1.dp, bottom = 14.dp),
        )

        TrendCard(avg = avg, best = best, worst = worst, trend = trend)

        if (onOpenResearch != null) {
            androidx.compose.material3.TextButton(onClick = onOpenResearch) {
                Text("App 研究记录 · 人工来源", color = colors.brand)
            }
        }

        if (ordered.size >= 2) {
            CompareToolbar(
                compareMode = compareState.compareMode,
                onToggleCompareMode = { updateCompareState(compareState.toggleCompareMode()) },
                onQuickWifiVs5g = { updateCompareState(compareState.quickPairWifiVs5g(comparableInputs)) },
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        AnebSectionTitle(
            text = "全部记录",
            action = "生成报告",
            onAction = onGenerateReport,
            modifier = Modifier.padding(top = 14.dp, start = 4.dp, end = 4.dp, bottom = 7.dp),
        )

        if (ordered.isEmpty()) {
            AnebGradientCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("暂无测试记录", fontSize = 13.sp, color = colors.ink)
                    Text("从“测试”页开始一次真实测量", fontSize = 10.sp, color = colors.muted, modifier = Modifier.padding(top = 5.dp))
                }
            }
        } else {
            val listState = rememberLazyListState()
            LaunchedEffect(compareState.compareMode, compareState.selectedKeys, compareState.notice) {
                if (compareState.compareMode && compareState.selectedKeys.size != 1) {
                    listState.scrollToItem(0)
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = if (showBack) 18.dp else 76.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (compareState.compareMode) {
                    item(key = "paired_comparison_card") {
                        when (val result = comparisonResult) {
                            is PairedComparisonFormat.PairedComparisonResult.Compatible -> PairedComparisonCard(
                                result = result,
                                exportStatus = comparisonExportStatus,
                                actionBusy = comparisonActionBusy,
                                onSwap = { updateCompareState(compareState.swap()) },
                                onClear = { updateCompareState(compareState.clearSelection()) },
                                onExportMarkdown = { onExportComparisonMarkdown(result) },
                                onShareImage = { onShareComparisonImage(result) },
                            )
                            is PairedComparisonFormat.PairedComparisonResult.Mismatch -> PairedComparisonMismatchCard(
                                result = result,
                                onClear = { updateCompareState(compareState.clearSelection()) },
                            )
                            null -> CompareSelectionGuidanceCard(
                                state = compareState,
                                comparableByKey = comparableByKey,
                            )
                        }
                    }
                }
                items(count = ordered.size, key = { ordered[it].key }) { index ->
                    val item = ordered[index]
                    val badge = if (!compareState.compareMode) {
                        null
                    } else if (comparisonResult is PairedComparisonFormat.PairedComparisonResult.Compatible) {
                        when (item.key) {
                            comparisonResult.left.entryKey -> "A"
                            comparisonResult.right.entryKey -> "B"
                            else -> null
                        }
                    } else {
                        when (compareState.selectedKeys.indexOf(item.key)) {
                            0 -> "A"
                            1 -> "B"
                            else -> null
                        }
                    }
                    when (item) {
                        is HistoryEntry.Token -> HistoryRecord(
                            run = item.run,
                            compareMode = compareState.compareMode,
                            selectionBadge = badge,
                            onClick = {
                                if (compareState.compareMode) {
                                    updateCompareState(compareState.toggleSelection(item.key))
                                } else {
                                    onOpen(item.run.runId)
                                }
                            },
                        )
                        is HistoryEntry.Basic -> BasicHistoryRecord(
                            result = item.result,
                            compareMode = compareState.compareMode,
                            selectionBadge = badge,
                            onClick = {
                                if (compareState.compareMode) {
                                    updateCompareState(compareState.toggleSelection(item.key))
                                } else {
                                    onOpenBasic(item.result.runId)
                                }
                            },
                        )
                        is HistoryEntry.Voice -> VoiceHistoryRecord(
                            result = item.result,
                            compareMode = compareState.compareMode,
                            selectionBadge = badge,
                            onClick = if (compareState.compareMode) {
                                { updateCompareState(compareState.toggleSelection(item.key)) }
                            } else {
                                onOpenVoice
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompareToolbar(
    compareMode: Boolean,
    onToggleCompareMode: () -> Unit,
    onQuickWifiVs5g: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompareActionPill(
            label = if (compareMode) "退出对比" else "对比两项",
            active = compareMode,
            onClick = onToggleCompareMode,
        )
        CompareActionPill(
            label = "Wi-Fi vs 5G 对比",
            active = false,
            onClick = onQuickWifiVs5g,
        )
    }
}

@Composable
private fun CompareActionPill(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val colors = AnebTheme.colors
    val accent = if (active) colors.brand else colors.ink
    Text(
        text = label,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = if (enabled) accent else colors.faint,
        modifier = Modifier
            .clip(AnebShapes.pill)
            .background(if (active) colors.brand.copy(alpha = 0.14f) else colors.surfaceMuted)
            .border(
                width = 1.dp,
                color = if (active) colors.brand.copy(alpha = 0.45f) else colors.hairline,
                shape = AnebShapes.pill,
            )
            .pressable(onClick = onClick, enabled = enabled)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
private fun CompareSelectionGuidanceCard(
    state: PairedComparisonFormat.HistoryComparisonState,
    comparableByKey: Map<String, PairedComparisonFormat.ComparableRunInput>,
) {
    val colors = AnebTheme.colors
    val firstSummary = state.selectedKeys.firstOrNull()?.let { key ->
        comparableByKey[key]?.let { input ->
            val (_, transport) = PairedComparisonFormat.resolveTransport(input)
            "${input.category.label} · $transport"
        }
    }
    AnebGradientCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(
                "已选 ${state.selectedKeys.size}/2",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.brand,
            )
            val body = when {
                state.notice != null -> state.notice
                firstSummary != null -> "已选 A：$firstSummary，请再选 1 条同类型记录"
                else -> "请点选下方任意 2 条同类型记录，或点击“Wi-Fi vs 5G 对比”自动配对。"
            }
            Text(
                body,
                fontSize = 11.sp,
                color = colors.muted,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
    }
}

@Composable
private fun PairedComparisonMismatchCard(
    result: PairedComparisonFormat.PairedComparisonResult.Mismatch,
    onClear: () -> Unit,
) {
    val colors = AnebTheme.colors
    AnebGradientCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "口径不一致 · 不可直接对比",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.fair,
                    modifier = Modifier.weight(1f),
                )
                CompareActionPill(label = "清除重选", active = false, onClick = onClear)
            }
            Text(
                "${result.leftLabel} vs ${result.rightLabel}",
                fontSize = 11.sp,
                color = colors.ink,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                result.reason,
                fontSize = 10.5.sp,
                color = colors.muted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun PairedComparisonCard(
    result: PairedComparisonFormat.PairedComparisonResult.Compatible,
    exportStatus: String?,
    actionBusy: Boolean,
    onSwap: () -> Unit,
    onClear: () -> Unit,
    onExportMarkdown: () -> Unit,
    onShareImage: () -> Unit,
) {
    val colors = AnebTheme.colors
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.ROOT) }
    AnebGradientCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "配对对比 · ${result.category.label}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.brand,
                    modifier = Modifier.weight(1f),
                )
                CompareActionPill(label = "⇄ 对调", active = false, onClick = onSwap)
                Spacer(Modifier.width(6.dp))
                CompareActionPill(label = "清除", active = false, onClick = onClear)
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ComparisonSideSummaryPill(
                    badge = "A",
                    summary = result.left,
                    timeText = fmt.format(Date(result.left.startedAtEpochMs)),
                    accent = colors.brand,
                    modifier = Modifier.weight(1f),
                )
                ComparisonSideSummaryPill(
                    badge = "B",
                    summary = result.right,
                    timeText = fmt.format(Date(result.right.startedAtEpochMs)),
                    accent = colors.excellent,
                    modifier = Modifier.weight(1f),
                )
            }

            Text(
                result.headline,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = colors.ink,
                modifier = Modifier.padding(top = 10.dp, bottom = 8.dp),
            )

            Row(
                Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceMuted.copy(alpha = 0.55f))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("指标", fontSize = 9.5.sp, color = colors.faint, modifier = Modifier.weight(1.2f))
                Text("A", fontSize = 9.5.sp, color = colors.faint, textAlign = TextAlign.End, modifier = Modifier.weight(0.9f))
                Text("B", fontSize = 9.5.sp, color = colors.faint, textAlign = TextAlign.End, modifier = Modifier.weight(0.9f))
                Text("Δ(B-A)", fontSize = 9.5.sp, color = colors.faint, textAlign = TextAlign.End, modifier = Modifier.weight(1.25f))
            }

            result.rows.forEach { row ->
                val deltaColor = when (row.tone) {
                    PairedComparisonFormat.DeltaTone.IMPROVED -> colors.excellent
                    PairedComparisonFormat.DeltaTone.REGRESSED -> colors.poor
                    PairedComparisonFormat.DeltaTone.NEUTRAL -> colors.muted
                    PairedComparisonFormat.DeltaTone.UNAVAILABLE -> colors.neutral
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        row.label,
                        fontSize = 10.5.sp,
                        color = colors.ink,
                        maxLines = 1,
                        modifier = Modifier.weight(1.2f),
                    )
                    Text(
                        row.formattedA,
                        style = AnebType.StatValue,
                        fontSize = 10.sp,
                        color = colors.muted,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        modifier = Modifier.weight(0.9f),
                    )
                    Text(
                        row.formattedB,
                        style = AnebType.StatValue,
                        fontSize = 10.sp,
                        color = colors.muted,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        modifier = Modifier.weight(0.9f),
                    )
                    Text(
                        row.formattedDelta,
                        style = AnebType.StatValue,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = deltaColor,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        modifier = Modifier.weight(1.25f),
                    )
                }
            }

            result.disclaimers.forEach { note ->
                Text(
                    note,
                    fontSize = 9.sp,
                    color = colors.faint,
                    lineHeight = 13.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (exportStatus != null) {
                Text(
                    exportStatus,
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.brand2,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CompareActionPill(
                    label = "导出 MD",
                    active = false,
                    enabled = !actionBusy,
                    onClick = onExportMarkdown,
                )
                CompareActionPill(
                    label = "分享图片",
                    active = true,
                    enabled = !actionBusy,
                    onClick = onShareImage,
                )
            }
        }
    }
}

@Composable
private fun ComparisonSideSummaryPill(
    badge: String,
    summary: PairedComparisonFormat.ComparisonRunSummary,
    timeText: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val colors = AnebTheme.colors
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceMuted)
            .border(1.dp, accent.copy(alpha = 0.32f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            "$badge · ${summary.transportLabel}",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = accent,
            maxLines = 1,
        )
        Text(
            summary.nodeLabel,
            fontSize = 9.5.sp,
            color = colors.ink,
            maxLines = 1,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            "$timeText · ${summary.statusLabel}",
            fontSize = 9.sp,
            color = colors.muted,
            maxLines = 1,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

private sealed interface HistoryEntry {
    val startedAtEpochMs: Long
    val key: String

    data class Token(val run: TestRun) : HistoryEntry {
        override val startedAtEpochMs = run.startedAtEpochMs
        override val key = "token:${run.runId}"
    }

    data class Basic(val result: BasicSpeedResultEntity) : HistoryEntry {
        override val startedAtEpochMs = result.startedAtEpochMs
        override val key = "basic:${result.runId}"
    }

    data class Voice(val result: VoiceResultEntity) : HistoryEntry {
        override val startedAtEpochMs = result.tsEpochMs
        override val key = "voice:${result.id}"
    }
}

@Composable
private fun TrendCard(avg: Int?, best: Int?, worst: Int?, trend: List<Float>) {
    val colors = AnebTheme.colors
    AnebGradientCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("体验分趋势", fontSize = 11.sp, color = colors.muted)
                Spacer(Modifier.weight(1f))
                Text(
                    if (avg == null) "尚无有效成绩" else "平均 $avg",
                    fontSize = 12.sp,
                    fontWeight = FontWeight(560),
                    color = if (avg == null) colors.muted else colors.excellent,
                )
            }
            AnebSparkline(
                values = trend,
                color = colors.excellent,
                fill = true,
                modifier = Modifier.fillMaxWidth().height(78.dp).padding(top = 10.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 9.dp).border(0.dp, colors.hairline),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf("最佳" to best, "平均" to avg, "最低" to worst).forEachIndexed { index, item ->
                    if (index > 0) Box(Modifier.width(1.dp).height(42.dp).background(colors.hairline))
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(item.first, fontSize = 10.sp, color = colors.muted)
                        Text(
                            item.second?.toString() ?: "—",
                            style = AnebType.StatValue,
                            fontSize = 18.sp,
                            fontWeight = FontWeight(540),
                            color = when (item.first) { "最佳" -> colors.excellent; "最低" -> colors.fair; else -> colors.ink },
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRecord(
    run: TestRun,
    compareMode: Boolean,
    selectionBadge: String?,
    onClick: () -> Unit,
) {
    val colors = AnebTheme.colors
    val score = run.aqsScore
    val grade = score?.let { Grade.fromAqsScore(it) }
    val accent = colors.gradeColor(grade)
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.US) }
    val borderColor = if (selectionBadge != null) colors.brand else colors.hairline
    val borderWidth = if (selectionBadge != null) 1.5.dp else 1.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(61.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xB811162C))
            .border(borderWidth, borderColor, RoundedCornerShape(14.dp))
            .pressable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(41.dp).clip(CircleShape).border(1.dp, accent.copy(alpha = 0.48f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(score?.roundToInt()?.toString() ?: "—", style = AnebType.StatValue, fontSize = 15.sp, color = accent)
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                NetworkLabel.forRun(run).substringBeforeLast(" · "),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = colors.ink,
                maxLines = 1,
            )
            Text(
                "${fmt.format(Date(run.startedAtEpochMs))} · ${grade?.labelFriendly ?: "未完成"}${if (run.aqsLowConfidence == true) " · 低置信" else ""}",
                fontSize = 10.sp,
                color = colors.muted,
                modifier = Modifier.padding(top = 3.dp),
                maxLines = 1,
            )
        }
        HistoryRowTrailing(compareMode = compareMode, selectionBadge = selectionBadge, showChevron = true)
    }
}

@Composable
private fun BasicHistoryRecord(
    result: BasicSpeedResultEntity,
    compareMode: Boolean,
    selectionBadge: String?,
    onClick: () -> Unit,
) {
    val colors = AnebTheme.colors
    val accent = when (result.status) {
        "completed" -> colors.brand
        "partial" -> colors.fair
        else -> colors.poor
    }
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.US) }
    val borderColor = if (selectionBadge != null) colors.brand else colors.hairline
    val borderWidth = if (selectionBadge != null) 1.5.dp else 1.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(61.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xB811162C))
            .border(borderWidth, borderColor, RoundedCornerShape(14.dp))
            .pressable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(41.dp).clip(CircleShape).border(1.dp, accent.copy(alpha = 0.48f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("↕", style = AnebType.StatValue, fontSize = 16.sp, color = accent)
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${if (result.profileId == com.aneb.probe.engine.NetworkSpeedEngine.RESPONSIVENESS_PROFILE_ID) "负载响应性" else "基础测速"} · ${ProbeNodeCatalog.labelForUrl(result.serverBase)}",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = colors.ink,
                maxLines = 1,
            )
            val down = result.downloadMbps?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "—"
            val up = result.uploadMbps?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "—"
            Text(
                "${fmt.format(Date(result.startedAtEpochMs))} · ↓$down ↑$up Mbps",
                fontSize = 10.sp,
                color = colors.muted,
                modifier = Modifier.padding(top = 3.dp),
                maxLines = 1,
            )
        }
        HistoryRowTrailing(compareMode = compareMode, selectionBadge = selectionBadge, showChevron = true)
    }
}

@Composable
private fun VoiceHistoryRecord(
    result: VoiceResultEntity,
    compareMode: Boolean,
    selectionBadge: String?,
    onClick: (() -> Unit)?,
) {
    val colors = AnebTheme.colors
    val accent = if (result.lowConfidence) colors.fair else colors.brand2
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.US) }
    val (mouthEar, _) = VoiceFormat.primaryMouthEar(result.mouthEarProxyMs, result.mouthEarBudgetMs)
    val m7Suffix = result.m7MaxFrameGapMs?.let { " · M7 ${VoiceFormat.msOrDash(it, decimals = 0)}ms" } ?: ""
    val borderColor = if (selectionBadge != null) colors.brand else colors.hairline
    val borderWidth = if (selectionBadge != null) 1.5.dp else 1.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(61.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xB811162C))
            .border(borderWidth, borderColor, RoundedCornerShape(14.dp))
            .then(if (onClick != null) Modifier.pressable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(41.dp).clip(CircleShape).border(1.dp, accent.copy(alpha = 0.48f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("语", style = AnebType.StatValue, fontSize = 14.sp, color = accent)
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "实时语音 · ${VoiceFormat.caliberLabel(result.caliber)}" +
                    if (result.lowConfidence) " · 低置信" else "",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = colors.ink,
                maxLines = 1,
            )
            Text(
                "${fmt.format(Date(result.tsEpochMs))} · 口到耳 ${VoiceFormat.msOrDash(mouthEar, decimals = 0)} ms$m7Suffix",
                fontSize = 10.sp,
                color = colors.muted,
                modifier = Modifier.padding(top = 3.dp),
                maxLines = 1,
            )
        }
        HistoryRowTrailing(compareMode = compareMode, selectionBadge = selectionBadge, showChevron = onClick != null)
    }
}

@Composable
private fun HistoryRowTrailing(
    compareMode: Boolean,
    selectionBadge: String?,
    showChevron: Boolean,
) {
    val colors = AnebTheme.colors
    when {
        selectionBadge != null -> {
            val badgeColor = if (selectionBadge == "A") colors.brand else colors.excellent
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(badgeColor.copy(alpha = 0.18f))
                    .border(1.dp, badgeColor, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(selectionBadge, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = badgeColor)
            }
        }
        compareMode -> {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .border(1.dp, colors.hairline, CircleShape),
            )
        }
        showChevron -> {
            Text("›", fontSize = 18.sp, color = colors.faint)
        }
    }
}
