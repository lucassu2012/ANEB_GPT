package com.aneb.probe.ui.research

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aneb.probe.research.*
import kotlinx.serialization.json.JsonPrimitive

@Composable
internal fun ResearchAppProfilesPane(
    entries: List<ResearchEntry>,
    selectedProfile: ResearchAppProfile?,
    scope: ResearchRecordScopeFilter,
    onSelect: (ResearchAppProfile) -> Unit,
    onScopeChange: (ResearchRecordScopeFilter) -> Unit,
    onOpenBatch: (ResearchAppProfileBatch) -> Unit,
    conclusionTargets: Map<String, List<ResearchConclusionTarget>>,
    conclusionIndexErrors: Set<String>,
    conclusionIndexLoading: Boolean,
    onOpenConclusion: (ResearchAppProfileBatch, ResearchConclusionTarget) -> Unit,
    onOpenAttempt: (ResearchAppProfileBatch, ResearchAttempt) -> Unit,
    onOpenVideo: (String) -> Unit,
    busy: Boolean,
    modifier: Modifier = Modifier,
) {
    val textDocuments = remember(entries, scope.sourceKind) {
        entries.mapNotNull { it.document }.filter { !it.isVideo && it.matchesSourceKind(scope.sourceKind) }
    }
    val conditionValues = remember(textDocuments, scope.condition) {
        (textDocuments.flatMap { document -> document.records.mapNotNull { it.originalConditionLiteral } } +
            listOfNotNull((scope.condition as? ResearchConditionFilter.Literal)?.value)).distinct()
    }
    val profiles = remember(entries, scope) { entries.appResearchProfiles(scope) }
    val current = selectedProfile?.let { selected -> profiles.firstOrNull { it.appName == selected.appName } }
        ?: profiles.firstOrNull()
    Column(modifier) {
        Text("App 研究索引", style = MaterialTheme.typography.titleLarge)
        Text("按输入声明整理，未核验实际 App/来源；不同批次不自动可比。人工结论卡是原文候选，不是自动汇总或网络归因。")
        Text("来源类型（按批次原声明）", style = MaterialTheme.typography.labelLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ResearchSourceKindSelection.values().toList()) { option ->
                FilterChip(
                    selected = scope.sourceKind == option,
                    onClick = { onScopeChange(scope.copy(sourceKind = option)) },
                    enabled = !busy,
                    label = { Text(option.displayLabel) },
                )
            }
        }
        Text("原始 condition（完整字面匹配）", style = MaterialTheme.typography.labelLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(
                    selected = scope.condition == ResearchConditionFilter.All,
                    onClick = { onScopeChange(scope.copy(condition = ResearchConditionFilter.All)) },
                    enabled = !busy,
                    label = { Text("全部 condition") },
                )
            }
            item {
                FilterChip(
                    selected = scope.condition == ResearchConditionFilter.MissingOrUnrecognized,
                    onClick = { onScopeChange(scope.copy(condition = ResearchConditionFilter.MissingOrUnrecognized)) },
                    enabled = !busy,
                    label = { Text("未提供 / 无效") },
                )
            }
            items(conditionValues) { value ->
                FilterChip(
                    selected = scope.condition == ResearchConditionFilter.Literal(value),
                    onClick = { onScopeChange(scope.copy(condition = ResearchConditionFilter.Literal(value))) },
                    enabled = !busy,
                    label = { Text("condition ${JsonPrimitive(value)}", maxLines = 1) },
                )
            }
        }
        if (scope != ResearchRecordScopeFilter()) {
            TextButton(enabled = !busy, onClick = { onScopeChange(ResearchRecordScopeFilter()) }) { Text("清除来源与 condition 筛选") }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(profiles) { profile ->
                FilterChip(selected = current?.appName == profile.appName, onClick = { onSelect(profile) }, enabled = !busy, label = { Text(profile.label) })
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 88.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (current == null) item {
                Text("当前范围没有匹配的文本批次；可清除筛选。视频与无法索引的原文件仍在下方保持可访问。")
            }
            else if (current.batches.none { it.records.isNotEmpty() }) item {
                Text("当前 App 与来源 / condition 筛选没有匹配记录；不会自动切换 App，可清除筛选或手动选择其他 App。")
            }
            items(current?.batches.orEmpty(), key = { it.sourceId }) { batch ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(batch.filter.label, style = MaterialTheme.typography.titleMedium)
                        batch.summaryLines.forEach { Text(it) }
                        Text("版本、模式与状态均为输入声明（未核验）。", style = MaterialTheme.typography.bodySmall)
                        if (batch.document.recordKind == "OBSERVED") {
                            val targets = conclusionTargets[batch.sourceId].orEmpty()
                            val singleApp = batch.singleAppRawName
                            when {
                                !batch.canShowObservedConclusionEntry -> Text("本批不在当前来源或 condition 筛选范围；整批结论卡入口已隐藏，原始记录仍可查看。", style = MaterialTheme.typography.bodySmall)
                                singleApp == null -> Text("本批混合或缺少 App 身份；不能作为单 App 人工结论入口。原始记录仍可查看。", style = MaterialTheme.typography.bodySmall)
                                conclusionIndexLoading -> Text("正在查找本机人工结论卡…", style = MaterialTheme.typography.bodySmall)
                                batch.sourceId in conclusionIndexErrors -> Text("结论卡索引无法读取；原始记录仍可打开，未显示未经校验的正文。", style = MaterialTheme.typography.bodySmall)
                                targets.isEmpty() -> Text("本批无可定位的单 App 文本人工结论卡；不据此补写结论。", style = MaterialTheme.typography.bodySmall)
                                else -> {
                                    Text("人工观察卡候选 ${targets.size} 张 · 卡片绑定整批原文，不随当前 condition 缩小；不自动选择最新或最佳。点击后重验三份副本。", style = MaterialTheme.typography.bodySmall)
                                    targets.forEach { target ->
                                        SelectionContainer { Text("原文 SHA-256：${target.sourceId}\n分析 SHA-256：${target.analysisId}\n卡片 SHA-256：${target.cardId}", style = MaterialTheme.typography.bodySmall) }
                                        TextButton(enabled = !busy, onClick = { onOpenConclusion(batch, target) }) {
                                            Text("校验并查看这张人工结论卡")
                                        }
                                    }
                                }
                            }
                        } else if (batch.document.recordKind == "SAMPLE") Text("SAMPLE 是虚构样例，不提供实际观察结论入口。", style = MaterialTheme.typography.bodySmall)
                        val recordLines = batch.recordLines
                        batch.records.forEachIndexed { index, attempt ->
                            Column {
                                Text(recordLines[index], style = MaterialTheme.typography.bodySmall)
                                TextButton(enabled = !busy, onClick = { onOpenAttempt(batch, attempt) }) {
                                    Text("定位到这条原始记录")
                                }
                            }
                        }
                        TextButton(enabled = !busy, onClick = { onOpenBatch(batch) }) { Text("查看本 App 原始记录与分析") }
                        Text("筛选只改变当前显示记录；分析仍属整批原副本，不按筛选重算。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            items(entries.filter { it.document?.isVideo == true }, key = { "video-${it.id}" }) { entry ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("视频研究 · 独立入口", style = MaterialTheme.typography.titleMedium)
                        entry.document?.batchSummaryLines?.forEach { Text(it) }
                        TextButton(enabled = !busy, onClick = { onOpenVideo(entry.id) }) { Text("查看视频原批次") }
                    }
                }
            }
            items(entries.filter { it.document == null }, key = { "error-${it.id}" }) { entry ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("无法索引的本地记录 · 原文件保留", style = MaterialTheme.typography.titleMedium)
                        Text("源标识：${entry.id}", style = MaterialTheme.typography.bodySmall)
                        Text(entry.error ?: "无法读取，未归入任何 App。")
                    }
                }
            }
        }
    }
}
