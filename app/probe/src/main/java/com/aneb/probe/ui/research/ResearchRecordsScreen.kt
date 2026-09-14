package com.aneb.probe.ui.research

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aneb.probe.data.Exporter
import com.aneb.probe.research.*
import com.aneb.probe.ui.components.AnebPageIntro
import com.aneb.probe.ui.theme.AnebTheme
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Local manual research only. No measurement, metrics calculation, or automatic sharing. */
@Composable
fun ResearchRecordsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { ResearchRecordStore(File(context.noBackupFilesDir, "r1-research")) }
    val analysisStore = remember(context) { ResearchAnalysisStore(File(context.noBackupFilesDir, "r1-research-analysis")) }
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf(emptyList<ResearchEntry>()) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var document by remember { mutableStateOf<ResearchDocument?>(null) }
    var pending by remember { mutableStateOf<ByteArray?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var confirmExport by remember { mutableStateOf(false) }
    var analysisEntries by remember { mutableStateOf(emptyList<ResearchAnalysisEntry>()) }
    var analysis by remember { mutableStateOf<ResearchAnalysis?>(null) }
    var pendingAnalysis by remember { mutableStateOf<ByteArray?>(null) }
    var analysisImportSourceId by rememberSaveable { mutableStateOf<String?>(null) }
    var analysisExportId by remember { mutableStateOf<String?>(null) }
    var manualOpen by rememberSaveable { mutableStateOf(false) }
    var profilesOpen by rememberSaveable { mutableStateOf(false) }
    var profileSelection by remember { mutableStateOf<ResearchAppProfile?>(null) }
    var requestedAppFilter by remember { mutableStateOf<ResearchAppFilter?>(null) }

    if (manualOpen) {
        ManualResearchEntryScreen(store, onBack = { manualOpen = false }, onSaved = { saved ->
            manualOpen = false; selectedId = saved.id; document = saved; pending = null
            requestedAppFilter = null
            notice = "手工记录已保存，可重开或确认导出；不是自动测量或来源核验。"
        })
        return
    }

    suspend fun refresh() { entries = withContext(Dispatchers.IO) { store.list() } }
    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        notice = null
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: ResearchImportException) { notice = e.message }
            catch (_: Exception) { notice = "操作失败。请核对原始记录或分析 JSON、文件权限与可用存储（最多 1 MiB）。未知来源不自动转换，原有记录未覆盖。" }
            finally { busy = false }
        }
    }
    LaunchedEffect(store) {
        try { refresh() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { notice = "无法读取记录列表；原文件保留。" }
    }
    LaunchedEffect(selectedId) {
        analysis = null
        pendingAnalysis = null
        analysisEntries = emptyList()
        val id = selectedId
        if (id != null) {
            try {
                val saved = withContext(Dispatchers.IO) { store.open(id) }
                document = saved
                try { analysisEntries = withContext(Dispatchers.IO) { analysisStore.list(saved) } }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { notice = "分析目录无法读取；原始记录仍可查看和导出，分析副本未删除。" }
            }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { notice = "记录无法打开；原文件保留。"; document = null; selectedId = null }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) action {
            val bytes = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= ResearchRecordStore.MAX_BYTES)
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } ?: error("无法打开所选文件")
            }
            val preview = withContext(Dispatchers.IO) { ResearchRecordStore.decode(bytes) }
            selectedId = null
            requestedAppFilter = null
            pending = bytes
            document = preview
        }
    }
    val analysisPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) action {
            val sourceId = analysisImportSourceId
                ?: throw ResearchImportException("请先打开已保存的原始记录，再选择分析。")
            if (selectedId != sourceId) throw ResearchImportException("原始记录已切换；未关联所选分析，请重新选择。")
            val preview = withContext(Dispatchers.IO) {
                val source = store.open(sourceId)
                val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > ResearchRecordStore.MAX_BYTES) {
                            throw ResearchImportException("分析文件超过 1 MiB，未导入；原文不变。")
                        }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } ?: throw ResearchImportException("无法打开分析文件；原文不变。")
                ResearchAnalysisStore.decode(source, bytes) to bytes
            }
            analysis = preview.first
            pendingAnalysis = preview.second
            notice = "原文 SHA-256 与尝试身份已关联；这不等于数值或媒体已核验。请确认保存独立副本。"
        }
    }
    val back: () -> Unit = {
        if (!busy) {
            if (document != null) {
                selectedId = null; document = null; pending = null; notice = null
                requestedAppFilter = null
                analysis = null; pendingAnalysis = null; analysisEntries = emptyList()
            }
            else if (profilesOpen) profilesOpen = false
            else onBack()
        }
    }
    BackHandler(onBack = back)
    val colors = AnebTheme.colors
    Column(Modifier.fillMaxSize().background(colors.background).padding(horizontal = 16.dp)) {
        TextButton(onClick = back, enabled = !busy) {
            Text(if (document != null && profilesOpen) "返回 App 研究索引" else if (document != null || profilesOpen) "返回研究列表" else "返回测试历史")
        }
        AnebPageIntro("R1 · MANUAL", "App 研究记录", subtitle = "人工来源 · SAMPLE 样例与 OBSERVED 观察声明分批保存，不代表工具已核验。与测速历史、AQS 和 RPI 分开。")
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
        notice?.let { Text(it, color = colors.ink, modifier = Modifier.padding(vertical = 8.dp)) }
        val current = document
        if (current == null) {
            if (profilesOpen) {
                ResearchAppProfilesPane(entries, profileSelection, onSelect = { profileSelection = it }, onOpenBatch = { batch ->
                    requestedAppFilter = batch.filter
                    selectedId = batch.sourceId
                }, onOpenVideo = { id ->
                    requestedAppFilter = null
                    selectedId = id
                }, busy = busy, modifier = Modifier.weight(1f))
            } else {
                OutlinedButton(onClick = { action { refresh(); profilesOpen = true } }, enabled = !busy) { Text("App 研究索引 · 已存研究") }
                Button(onClick = { manualOpen = true }, enabled = !busy) { Text("新建 / 继续手工研究记录") }
                Button(onClick = { picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, enabled = !busy) { Text("导入 JSON 记录") }
                Text("先预览，再保存到本机。未识别的记录类型不作为实测导入。", color = colors.muted)
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 88.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (entries.isEmpty()) item { Text("暂无研究记录。可导入文本 alignment-1 或视频 research-video-1 文件；样例与观察声明分开。", color = colors.muted, modifier = Modifier.padding(vertical = 20.dp)) }
                    items(entries, key = { it.id }) { entry ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                if (entry.document == null) Text("无法读取的本地记录")
                                entry.document?.let { doc ->
                                    ResearchBatchHeading(doc)
                                    Text(doc.sourceNotice, style = MaterialTheme.typography.bodySmall)
                                    if (doc.records.any { it.sourceWarnings.isNotEmpty() }) Text("有记录缺少来源信息，请打开核对。")
                                }
                                if (entry.document == null) Text("输入 SHA-256：${entry.id}", style = MaterialTheme.typography.bodySmall)
                                if (entry.error != null) Text(entry.error)
                                else TextButton(onClick = { requestedAppFilter = null; selectedId = entry.id }, enabled = !busy) { Text("查看记录") }
                            }
                        }
                    }
                }
            }
        } else {
            var appFilter by remember(current.id) { mutableStateOf(requestedAppFilter?.takeIf { it.sourceId == current.id }) }
            val appFilters = remember(current.id) { current.appFilters }
            val visibleRecords = current.recordsForApp(appFilter)
            if (pending != null) {
                Button(enabled = !busy, onClick = {
                    val bytes = pending ?: return@Button
                    action {
                        val alreadySaved = entries.any { it.id == current.id && it.document != null }
                        val saved = withContext(Dispatchers.IO) { store.save(bytes) }
                        pending = null
                        selectedId = saved.id
                        document = saved
                        refresh()
                        notice = if (alreadySaved) "此原文已保存，本次未覆盖或重复添加。" else "已保存到本机，可重新打开或导出。"
                    }
                }) { Text("确认保存 ${current.recordKind}") }
            } else {
                Button(enabled = !busy, onClick = { confirmExport = true }) { Text("导出原始 JSON") }
            }
            if (!current.isVideo) {
                Text("按输入声明 App 查看", style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(selected = appFilter == null, onClick = { appFilter = null }, enabled = !busy, label = { Text("全部") }) }
                    items(appFilters) { option ->
                        FilterChip(selected = appFilter == option, onClick = { appFilter = option }, enabled = !busy, label = { Text(option.label) })
                    }
                }
                Text("显示 ${visibleRecords.size} / ${current.records.size} 条记录；仅筛选下方记录，分析与导出仍属整批。", style = MaterialTheme.typography.bodySmall)
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 88.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    ResearchBatchHeading(current)
                    Text(if (pending != null) "预览，尚未保存" else "已保存", color = colors.brand)
                    Text(current.sourceNotice, color = colors.ink)
                    SelectionContainer { Text("输入 SHA-256：${current.id}", color = colors.muted, style = MaterialTheme.typography.bodySmall) }
                    Text("方法：${researchDeclaredValue(current.methodId) ?: "未提供（见逐条）"}", color = colors.ink)
                    if (current.isVideo) {
                        Text("计划槽：${current.records.size}；已执行：${current.records.count { it.status == "EXECUTED" }}；未执行：${current.records.count { it.status == "NOT_RUN" }}。未执行不计播放失败。", color = colors.ink)
                        Text("30秒从可见打开 V0 开始，包括首次等待；不是播放完成。视频首画面不套用文本 TTFC/T3。", color = colors.muted)
                        Text("原始来源声明：${current.root.text("record_kind")}；PTS 单位为秒，仅展示原标注。", color = colors.muted)
                        SelectionContainer { Text(displayValue(current.root["evidence"]), style = MaterialTheme.typography.bodySmall) }
                    } else {
                        Text("操作：${researchDeclaredValue(current.root.text("action_text")) ?: "未提供（见逐条原记录）"}", color = colors.ink)
                        Text("原始单位：${researchDeclaredValue(current.root.text("time_unit")) ?: "未提供（见逐条时钟）"}。本机不计算派生时长；记录状态不等于网络成功。", color = colors.muted)
                    }
                }
                if (pending == null) item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("派生分析 · 独立副本", style = MaterialTheme.typography.titleMedium)
                            Text("选择 3a 的分析 JSON；按原文 SHA-256 与${if (current.isVideo) "视频 slot" else " attempt_id"}关联，不覆盖原文。重开后可从下列副本中选择。")
                            Button(enabled = !busy, onClick = {
                                analysisImportSourceId = current.id
                                analysis = null; pendingAnalysis = null
                                analysisPicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                            }) { Text("选择分析 JSON") }
                            if (analysisEntries.isEmpty()) Text("尚无保存的分析副本。")
                            analysisEntries.forEach { entry ->
                                if (entry.error != null) Text("${entry.id.take(12)}：${entry.error}")
                                else TextButton(enabled = !busy, onClick = {
                                    action {
                                        analysis = null; pendingAnalysis = null
                                        analysis = withContext(Dispatchers.IO) { analysisStore.open(store.open(current.id), entry.id) }
                                    }
                                }) { Text("打开分析副本 ${entry.id.take(12)}…") }
                            }
                        }
                    }
                }
                val currentAnalysis = analysis?.takeIf { it.sourceId == current.id }
                if (currentAnalysis != null) item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (pendingAnalysis == null) "已保存分析副本" else "分析预览 · 尚未保存", style = MaterialTheme.typography.titleMedium)
                            if (!current.isVideo) Text("整批分析（未按 App 筛选）：以下计数与分组不代表当前 App 的指标；不自动合组或排名。")
                            SelectionContainer { Text("分析 SHA-256：${currentAnalysis.id}\n关联原文：${currentAnalysis.sourceId}", style = MaterialTheme.typography.bodySmall) }
                            currentAnalysis.summaryLines.forEach { Text(it) }
                            if (pendingAnalysis != null) Button(enabled = !busy, onClick = {
                                val bytes = pendingAnalysis ?: return@Button
                                action {
                                    val source = withContext(Dispatchers.IO) { store.open(current.id) }
                                    analysis = withContext(Dispatchers.IO) { analysisStore.save(source, bytes) }
                                    pendingAnalysis = null
                                    analysisEntries = withContext(Dispatchers.IO) { analysisStore.list(source) }
                                    notice = "分析副本已独立保存，可重开；原始记录未更改。"
                                }
                            }) { Text("确认保存分析副本") }
                            else TextButton(enabled = !busy, onClick = { analysisExportId = currentAnalysis.id }) { Text("导出此分析副本") }
                            currentAnalysis.groupLines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                items(visibleRecords, key = { it.attemptId }) { attempt ->
                    if (current.isVideo) ResearchVideoAttemptCard(attempt, currentAnalysis)
                    else ResearchAttemptCard(attempt, currentAnalysis)
                }
            }
        }
    }
    if (confirmExport || analysisExportId != null) AlertDialog(
        onDismissRequest = { confirmExport = false; analysisExportId = null },
        title = { Text(if (analysisExportId != null) "导出独立分析副本？" else "导出原始记录？") },
        text = { Text("${document?.sourceLabel ?: "未确认来源"}\n将${if (analysisExportId != null) "分析原始字节（含原文上下文）" else "原始 JSON"}保存到 Downloads/ANEB，可能包含操作文本和本地证据引用。不会打包录像，也不会自动上传或分享。分析未在本机重算或独立核验。请先确认内容适合导出。") },
        confirmButton = { TextButton(onClick = {
            confirmExport = false
            val derivedId = analysisExportId
            analysisExportId = null
            val id = selectedId
            if (id != null) action {
                val result = withContext(Dispatchers.IO) {
                    val saved = store.open(id)
                    if (derivedId == null) {
                        Exporter.exportToDownloads(context, saved.exportFileName, "application/json") { store.export(id, it) }
                    } else {
                        val derived = analysisStore.open(saved, derivedId)
                        Exporter.exportToDownloads(context, derived.exportFileName, "application/json") { analysisStore.export(saved, derivedId, it) }
                    }
                }
                notice = if (result.ok) "已导出到 Downloads/ANEB（${result.bytes} 字节）。" else "导出失败，未发布不完整文件；本机记录仍保留。"
            }
        }) { Text("确认导出") } },
        dismissButton = { TextButton(onClick = { confirmExport = false; analysisExportId = null }) { Text("取消") } },
    )
}

@Composable
private fun ResearchBatchHeading(document: ResearchDocument) {
    document.batchSummaryLines.forEachIndexed { index, line ->
        Text(line, style = if (index == 0) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ResearchVideoAttemptCard(attempt: ResearchAttempt, analysis: ResearchAnalysis?) {
    val raw = attempt.raw
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${attempt.attemptId} · ${attempt.statusLabel}", style = MaterialTheme.typography.titleMedium)
            Text("目标播放声明：${displayValue(raw["visible_target_playback"])}；完整窗口声明：${displayValue(raw["window_complete"])}")
            if (analysis == null) Text("首画面区间与30秒观察窗：尚未关联分析，不补 0、不推算时长。")
            else analysis.attemptLines(attempt.attemptId).forEach { Text(it) }
            listOf("V0" to "可见打开 V0", "VF" to "目标首画面 VF", "window_end" to "实际标注观察终点", "reason" to "原始说明").forEach { (key, label) ->
                Text(label, style = MaterialTheme.typography.labelLarge)
                SelectionContainer { Text(displayValue(raw[key]), style = MaterialTheme.typography.bodySmall) }
            }
            Text("引用仅作文本保留；未打开录像或核验播放。缓冲 / 暂停未计算，不能据此归因网络。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ResearchAttemptCard(attempt: ResearchAttempt, analysis: ResearchAnalysis?) {
    val raw = attempt.raw
    val app = raw["app"] as? JsonObject
    val outcome = raw["outcome"] as? JsonObject
    val clock = raw["clock"] as? JsonObject
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${app?.text("name") ?: "UNKNOWN"} · ${attempt.statusLabel}", style = MaterialTheme.typography.titleMedium)
            attempt.statusNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text("${raw.text("record_kind") ?: "未确认来源"} · ${attempt.attemptId}")
            attempt.sourceWarnings.forEach { Text(it, color = AnebTheme.colors.fair) }
            Text("版本：${app?.text("version") ?: "UNKNOWN"}；模式：${app?.text("model_mode") ?: "UNKNOWN"}")
            Text("已发送：${displayValue(outcome?.get("executed"))}；可见完成：${displayValue(outcome?.get("visible_completion"))}；遵循指令：${displayValue(outcome?.get("instruction_following"))}")
            Text("时钟：${clock?.text("source") ?: "UNKNOWN"}；单位：${clock?.text("unit") ?: "UNKNOWN"}；域：${clock?.text("domain_id") ?: "UNKNOWN"}")
            if (analysis == null) Text("TTFR / TTFC / Completion：尚未选择分析结果（非 0）。证据引用未在本机核验。")
            else {
                Text("导入分析 · 未在本机重算", style = MaterialTheme.typography.labelLarge)
                analysis.attemptLines(attempt.attemptId).forEach { Text(it) }
            }
            listOf("metadata" to "设备、时间与网络", "events" to "原始事件与区间", "observed_intervals" to "原始观察区间", "missing_reasons" to "缺失原因", "evidence" to "证据引用（不打开外部路径）", "completion_observation" to "完成观察依据").forEach { (key, label) ->
                Text(label, style = MaterialTheme.typography.labelLarge)
                SelectionContainer { Text(displayValue(raw[key]), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

private val displayJson = Json { prettyPrint = true }

private fun displayValue(value: JsonElement?): String = when (value) {
    null, JsonNull -> "NA（未记录或不适用）"
    is JsonPrimitive -> when (value.content) { "true", "yes" -> "是"; "false", "no" -> "否"; "uncertain" -> "不确定"; else -> value.content }
    else -> displayJson.encodeToString(JsonElement.serializer(), value)
}
