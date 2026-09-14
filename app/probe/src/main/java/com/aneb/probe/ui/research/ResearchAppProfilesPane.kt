package com.aneb.probe.ui.research

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aneb.probe.research.*

@Composable
internal fun ResearchAppProfilesPane(
    entries: List<ResearchEntry>,
    selectedProfile: ResearchAppProfile?,
    onSelect: (ResearchAppProfile) -> Unit,
    onOpenBatch: (ResearchAppProfileBatch) -> Unit,
    onOpenVideo: (String) -> Unit,
    busy: Boolean,
    modifier: Modifier = Modifier,
) {
    val profiles = remember(entries) { entries.appResearchProfiles() }
    val current = selectedProfile?.let { selected -> profiles.firstOrNull { it.appName == selected.appName } }
        ?: profiles.firstOrNull()
    Column(modifier) {
        Text("App 研究索引", style = MaterialTheme.typography.titleLarge)
        Text("按输入声明整理，未核验实际 App/来源；不同批次不自动可比。这里只查看已存研究，不是汇总结论。")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(profiles) { profile ->
                FilterChip(selected = current?.appName == profile.appName, onClick = { onSelect(profile) }, enabled = !busy, label = { Text(profile.label) })
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 88.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (current == null) item { Text("暂无可索引的文本批次；未知 App 仍保留，视频在独立入口查看。") }
            items(current?.batches.orEmpty(), key = { it.sourceId }) { batch ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(batch.filter.label, style = MaterialTheme.typography.titleMedium)
                        batch.summaryLines.forEach { Text(it) }
                        Text("版本、模式与状态均为输入声明（未核验）。", style = MaterialTheme.typography.bodySmall)
                        batch.recordLines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                        TextButton(enabled = !busy, onClick = { onOpenBatch(batch) }) { Text("查看本 App 原始记录与分析") }
                        Text("进入原批次后可切换全部；分析仍属整批，多个副本由你选择。", style = MaterialTheme.typography.bodySmall)
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
