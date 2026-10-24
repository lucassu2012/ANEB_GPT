package com.aneb.probe.ui.research

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Local manual research only. No measurement, metrics calculation, or automatic sharing. */
private data class PendingConclusionCard(
    val sourceId: String,
    val analysisId: String,
    val bytes: ByteArray,
    val preview: ResearchConclusion,
)

@Composable
fun ResearchRecordsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { ResearchRecordStore(File(context.noBackupFilesDir, "r1-research")) }
    val manualMediaStore = remember(context, store) { ManualResearchMediaStore(File(context.noBackupFilesDir, "r1-research-media"), store) }
    val manualPlaybackOwner = remember { ManualResearchMediaPlaybackOwner() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(manualPlaybackOwner, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) manualPlaybackOwner.stop()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); manualPlaybackOwner.stop() }
    }
    val analysisStore = remember(context) { ResearchAnalysisStore(File(context.noBackupFilesDir, "r1-research-analysis")) }
    val conclusionStore = remember(context) { ResearchConclusionStore(File(context.noBackupFilesDir, "r1-research-conclusions")) }
    val conclusionIndex = remember(store, analysisStore, conclusionStore) { ResearchConclusionIndex(store, analysisStore, conclusionStore) }
    val offlineReportExporter = remember(store, analysisStore, conclusionStore) { ResearchOfflineReportExporter(store, analysisStore, conclusionStore) }
    val videoReportExporter = remember(store, analysisStore) { ResearchVideoOfflineReportExporter(store, analysisStore) }
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf(emptyList<ResearchEntry>()) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var document by remember { mutableStateOf<ResearchDocument?>(null) }
    var pending by remember { mutableStateOf<ByteArray?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var batchImportPreview by remember { mutableStateOf<ResearchBatchImportPreview?>(null) }
    var batchSaveReceipt by remember { mutableStateOf<ResearchBatchImportSaveReceipt?>(null) }
    var confirmExport by remember { mutableStateOf(false) }
    var analysisEntries by remember { mutableStateOf(emptyList<ResearchAnalysisEntry>()) }
    var analysis by remember { mutableStateOf<ResearchAnalysis?>(null) }
    var pendingAnalysis by remember { mutableStateOf<ByteArray?>(null) }
    var analysisImportSourceId by rememberSaveable { mutableStateOf<String?>(null) }
    var analysisExportId by remember { mutableStateOf<String?>(null) }
    var conclusionImportSourceId by rememberSaveable { mutableStateOf<String?>(null) }
    var conclusionImportAnalysisId by rememberSaveable { mutableStateOf<String?>(null) }
    var loadedConclusionPair by remember { mutableStateOf<Pair<String, String>?>(null) }
    var conclusionEntries by remember { mutableStateOf(emptyList<ResearchConclusionEntry>()) }
    var selectedConclusion by remember { mutableStateOf<ResearchConclusion?>(null) }
    var conclusionRevision by remember { mutableIntStateOf(0) }
    var conclusionLoadError by remember { mutableStateOf<Pair<Pair<String, String>, String>?>(null) }
    var pendingConclusionCard by remember { mutableStateOf<PendingConclusionCard?>(null) }
    var pendingConclusionError by remember { mutableStateOf<String?>(null) }
    var requestedConclusionTarget by remember { mutableStateOf<ResearchConclusionTarget?>(null) }
    var indexedConclusionTargets by remember { mutableStateOf<Map<String, List<ResearchConclusionTarget>>>(emptyMap()) }
    var conclusionIndexErrors by remember { mutableStateOf<Set<String>>(emptySet()) }
    var conclusionIndexLoading by remember { mutableStateOf(false) }
    var conclusionIndexRun by remember { mutableIntStateOf(0) }
    var manualOpen by rememberSaveable { mutableStateOf(false) }
    var privateSdkOpen by rememberSaveable { mutableStateOf(false) }
    var engineeringOpen by rememberSaveable { mutableStateOf(false) }
    var profilesOpen by rememberSaveable { mutableStateOf(false) }
    var profileSelection by remember { mutableStateOf<ResearchAppProfile?>(null) }
    var requestedAppFilter by remember { mutableStateOf<ResearchAppFilter?>(null) }
    var indexScopeFilter by remember { mutableStateOf(ResearchRecordScopeFilter()) }
    var requestedScopeFilter by remember { mutableStateOf(ResearchRecordScopeFilter()) }
    var requestedAttemptLocator by remember { mutableStateOf<ResearchAttemptLocator?>(null) }
    var offlineReportConfirmation by remember { mutableStateOf(ResearchOfflineReportConfirmation()) }
    var videoReportConfirmation by remember { mutableStateOf(ResearchVideoOfflineReportConfirmation()) }
    var businessRawReportId by remember { mutableStateOf<String?>(null) }

    fun selectedOfflineReportTarget(): ResearchOfflineReportTarget? {
        val card = selectedConclusion ?: return null
        if (pending != null || pendingAnalysis != null || selectedId != card.sourceId ||
            document?.id != card.sourceId || analysis?.sourceId != card.sourceId || analysis?.id != card.analysisId ||
            loadedConclusionPair != (card.sourceId to card.analysisId)) return null
        return ResearchOfflineReportTarget(card.sourceId, card.analysisId, card.id)
    }

    fun selectedVideoReportTarget(): ResearchVideoOfflineReportTarget? {
        val source = document ?: return null
        val derived = analysis ?: return null
        if (pending != null || pendingAnalysis != null || selectedId != source.id ||
            !source.isVideo || !derived.isVideo || derived.sourceId != source.id) return null
        return ResearchVideoOfflineReportTarget(source.id, derived.id)
    }

    fun clearAttemptTarget() { requestedAttemptLocator = null }
    fun clearConclusionView() {
        offlineReportConfirmation = offlineReportConfirmation.cancel()
        videoReportConfirmation = videoReportConfirmation.cancel()
        loadedConclusionPair = null
        conclusionEntries = emptyList()
        selectedConclusion = null
        conclusionLoadError = null
        pendingConclusionCard = null
        pendingConclusionError = null
        requestedConclusionTarget = null
    }

    if (engineeringOpen) {
        EngineeringObservationReaderScreen(onBack = { engineeringOpen = false })
        return
    }
    if (privateSdkOpen) {
        PrivateSdkObservationReaderScreen(onBack = { privateSdkOpen = false })
        return
    }

    if (manualOpen) {
        ManualResearchEntryScreen(store, onBack = { manualOpen = false }, onSaved = { saved ->
            manualOpen = false; selectedId = saved.id; document = saved; pending = null
            clearAttemptTarget()
            requestedAppFilter = null
            requestedScopeFilter = ResearchRecordScopeFilter()
            notice = "手工记录已保存，可重开或确认导出；不是自动测量或来源核验。"
        })
        return
    }

    suspend fun refresh() {
        clearAttemptTarget()
        entries = withContext(Dispatchers.IO) { store.list() }
    }
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
    LaunchedEffect(profilesOpen, selectedId, entries, conclusionRevision) {
        val thisRun = ++conclusionIndexRun
        if (!profilesOpen || selectedId != null) {
            conclusionIndexLoading = false
            return@LaunchedEffect
        }
        conclusionIndexLoading = true
        val targets = mutableMapOf<String, List<ResearchConclusionTarget>>()
        val errors = mutableSetOf<String>()
        try {
            withContext(Dispatchers.IO) {
                entries.mapNotNull { it.document }.filter { it.recordKind == "OBSERVED" && !it.isVideo }
                    .map { it.id }.distinct().forEach { id ->
                        try { targets[id] = conclusionIndex.list(id) }
                        catch (_: Exception) { errors.add(id) }
                    }
            }
            if (thisRun == conclusionIndexRun && profilesOpen && selectedId == null) {
                indexedConclusionTargets = targets
                conclusionIndexErrors = errors
            }
        } catch (e: CancellationException) { throw e }
        finally { if (thisRun == conclusionIndexRun) conclusionIndexLoading = false }
    }
    LaunchedEffect(selectedId) {
        analysis = null
        pendingAnalysis = null
        analysisEntries = emptyList()
        val id = selectedId
        val conclusionTarget = requestedConclusionTarget?.takeIf { it.sourceId == id }
        if (requestedConclusionTarget != null && conclusionTarget == null) requestedConclusionTarget = null
        if (id != null) {
            try {
                val saved = withContext(Dispatchers.IO) { store.open(id) }
                document = saved
                try { analysisEntries = withContext(Dispatchers.IO) { analysisStore.list(saved) } }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { notice = "分析目录无法读取；原始记录仍可查看和导出，分析副本未删除。" }
                if (conclusionTarget != null) {
                    try {
                        val opened = withContext(Dispatchers.IO) { conclusionIndex.open(conclusionTarget) }
                        if (selectedId == id && requestedConclusionTarget == conclusionTarget) analysis = opened.analysis
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) {
                        if (selectedId == id && requestedConclusionTarget == conclusionTarget) {
                            requestedConclusionTarget = null
                            notice = "结论卡定位失败：原文、分析或卡片缺失或已变更；未显示未经校验的正文。"
                        }
                    }
                }
            }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                val failedTargetOpen = requestedAttemptLocator?.sourceId == id
                val failedConclusionOpen = conclusionTarget != null
                clearAttemptTarget()
                requestedConclusionTarget = null
                notice = if (failedConclusionOpen) "结论卡定位失败：原文、分析或卡片缺失或已变更；未显示未经校验的正文。"
                    else if (failedTargetOpen) "定位失败：原始来源当前无法打开；未跳转到其他来源。"
                    else "记录无法打开；原文件保留。"
                document = null
                selectedId = null
            }
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
            clearAttemptTarget()
            requestedAppFilter = null
            requestedScopeFilter = ResearchRecordScopeFilter()
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
    val conclusionPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val sourceId = conclusionImportSourceId
        val analysisId = conclusionImportAnalysisId
        conclusionImportSourceId = null
        conclusionImportAnalysisId = null
        if (uri != null) action {
            try {
                if (sourceId == null || analysisId == null || selectedId != sourceId || document?.id != sourceId ||
                    analysis?.id != analysisId || pending != null || pendingAnalysis != null) {
                    throw ResearchImportException("原文或分析已切换；未关联所选结论卡，请重新选择。")
                }
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (output.size() + count > ResearchRecordStore.MAX_BYTES) {
                                throw ResearchImportException("结论卡超过 1 MiB，未保存。")
                            }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    } ?: throw ResearchImportException("无法打开结论卡；原文与分析未更改。")
                }
                if (selectedId != sourceId || document?.id != sourceId || analysis?.id != analysisId ||
                    pending != null || pendingAnalysis != null) {
                    throw ResearchImportException("读取期间原文或分析已切换；结论卡未保存。")
                }
                val preview = withContext(Dispatchers.IO) {
                    val source = store.open(sourceId)
                    val savedAnalysis = analysisStore.open(source, analysisId)
                    conclusionStore.preview(source, savedAnalysis, bytes)
                }
                if (selectedId == sourceId && document?.id == sourceId && analysis?.id == analysisId &&
                    pending == null && pendingAnalysis == null) {
                    pendingConclusionCard = PendingConclusionCard(sourceId, analysisId, bytes, preview)
                    pendingConclusionError = null
                    notice = "已校验卡片与当前原文、分析的绑定；确认前不会保存。"
                } else notice = "预览期间原文或分析已切换；结论卡未保存。"
            } catch (e: CancellationException) { throw e }
            catch (e: ResearchImportException) { notice = e.message }
            catch (_: Exception) { notice = "结论卡无法预览。请核对完整分析 SHA-256、单 App 文本来源、UTF-8、文件权限与 1 MiB 限制；本次未保存。" }
        }
    }
    val batchPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        // Picker cancellation returns no URIs: no preview, Store call, or write is made.
        if (uris.isNotEmpty()) action {
            val files = withContext(Dispatchers.IO) { readResearchBatchFiles(context, uris) }
            batchImportPreview = withContext(Dispatchers.IO) { ResearchBatchImport.preview(files, store, analysisStore) }
            batchSaveReceipt = null
            notice = "已生成本地预览；关闭预览不会保存。原文与分析分别保留，不重算或自动选择分析。"
        }
    }
    val back: () -> Unit = {
        if (!busy) {
            if (document != null) {
                selectedId = null; document = null; pending = null; notice = null
                clearConclusionView()
                clearAttemptTarget()
                requestedAppFilter = null
                requestedScopeFilter = ResearchRecordScopeFilter()
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
        Text(if (document == null) ResearchReaderCopy.title else "研究结果", style = MaterialTheme.typography.titleLarge)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
        notice?.let { Text(it, color = colors.ink, modifier = Modifier.padding(vertical = 8.dp)) }
        val current = document
        if (current == null) {
            if (profilesOpen) {
                ResearchAppProfilesPane(entries, profileSelection, indexScopeFilter, onSelect = { profileSelection = it }, onScopeChange = {
                    indexScopeFilter = it
                }, onOpenBatch = { batch ->
                    clearAttemptTarget()
                    requestedAppFilter = batch.filter
                    requestedScopeFilter = batch.scope
                    selectedId = batch.sourceId
                }, conclusionTargets = indexedConclusionTargets, conclusionIndexErrors = conclusionIndexErrors,
                    conclusionIndexLoading = conclusionIndexLoading, onOpenConclusion = { batch, target ->
                    action {
                        try {
                            val opened = withContext(Dispatchers.IO) { conclusionIndex.open(target) }
                            check(opened.belongsTo(batch))
                            clearAttemptTarget()
                            clearConclusionView()
                            requestedAppFilter = batch.filter
                            requestedScopeFilter = batch.scope
                            requestedConclusionTarget = target
                            selectedId = target.sourceId
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) {
                            notice = "结论卡定位失败：原文、分析或卡片缺失或已变更；未跳转，也未显示未经校验的正文。"
                        }
                    }
                }, onOpenAttempt = { batch, attempt ->
                    requestedAppFilter = batch.filter
                    requestedScopeFilter = batch.scope
                    requestedAttemptLocator = ResearchAttemptLocator(batch.sourceId, attempt.attemptId)
                    selectedId = batch.sourceId
                    notice = null
                }, onOpenVideo = { id ->
                    clearAttemptTarget()
                    requestedAppFilter = null
                    requestedScopeFilter = ResearchRecordScopeFilter()
                    selectedId = id
                }, busy = busy, modifier = Modifier.weight(1f))
            } else {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 88.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Column(Modifier.fillMaxWidth()) {
                            AnebPageIntro("R1 · MANUAL", ResearchReaderCopy.title, subtitle = ResearchReaderCopy.introduction)
                            OutlinedButton(onClick = { action { refresh(); profilesOpen = true } }, enabled = !busy) { Text("App 研究索引 · 已存研究") }
                            Button(onClick = { manualOpen = true }, enabled = !busy) { Text("新建 / 继续手工研究记录") }
                            OutlinedButton(onClick = { privateSdkOpen = true }, enabled = !busy) { Text("私有 SDK 观察 · 独立本机副本") }
                            OutlinedButton(onClick = { engineeringOpen = true }, enabled = !busy) { Text("工程采集原件 · 截图与采屏回看") }
                            Button(onClick = { picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, enabled = !busy) { Text("导入 JSON 记录") }
                            Button(onClick = {
                                batchPicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                            }, enabled = !busy) { Text("批量导入原文与分析 JSON") }
                            Text("每次最多 20 个文件、总计 10 MiB；单份仍限 1 MiB。只在预览中确认后写入本机。", color = colors.muted)
                            Text("先预览，再保存到本机。未识别的记录类型不作为实测导入。", color = colors.muted)
                        }
                    }
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
                                else TextButton(onClick = {
                                    clearAttemptTarget()
                                    requestedAppFilter = null
                                    requestedScopeFilter = ResearchRecordScopeFilter()
                                    selectedId = entry.id
                                }, enabled = !busy) { Text("查看记录") }
                            }
                        }
                    }
                }
            }
        } else if (current.isBusinessVideo) {
            ResearchBusinessVideoRawPane(current, pending != null, busy, Modifier.weight(1f),
                onSave = {
                    val bytes = pending
                    if (bytes != null) action {
                        val saved = withContext(Dispatchers.IO) { store.save(bytes) }
                        pending = null; selectedId = saved.id; document = saved
                        refresh()
                        notice = "采集原文已保存，可重开或导出；播放与计时仍待独立复核。"
                    }
                },
                onExportJson = { confirmExport = true },
                onExportReport = { businessRawReportId = current.id },
            )
        } else {
            var appFilter by remember(current.id) { mutableStateOf(requestedAppFilter?.takeIf { it.sourceId == current.id }) }
            var readerScopeFilter by remember(current.id) { mutableStateOf(requestedScopeFilter) }
            val appFilters = remember(current.id) { current.appFilters }
            val conversations = remember(current.id, appFilter, readerScopeFilter) {
                current.conversationsForScope(appFilter, readerScopeFilter)
            }
            var conversation by remember(current.id, appFilter, readerScopeFilter) { mutableStateOf<ResearchConversation?>(null) }
            val reading = current.readerSelection(appFilter, conversation, analysis, readerScopeFilter)
            val currentAnalysis = reading.analysis
            val conclusionEligible = selectedId == current.id && pending == null && pendingAnalysis == null &&
                currentAnalysis?.let { candidate ->
                    remember(current, candidate) { conclusionStore.canAttach(current, candidate) }
                } == true
            val conclusionPair = if (conclusionEligible) current.id to currentAnalysis!!.id else null
            LaunchedEffect(conclusionPair, conclusionRevision) {
                if (pendingConclusionCard?.let { it.sourceId to it.analysisId } != conclusionPair) {
                    pendingConclusionCard = null
                    pendingConclusionError = null
                }
                val previouslySelectedId = if (loadedConclusionPair == conclusionPair) selectedConclusion?.id else null
                loadedConclusionPair = null
                conclusionEntries = emptyList()
                selectedConclusion = null
                conclusionLoadError = null
                val pair = conclusionPair ?: return@LaunchedEffect
                val requestedId = requestedConclusionTarget?.takeIf {
                    it.sourceId == pair.first && it.analysisId == pair.second
                }?.cardId
                try {
                    val savedEntries = withContext(Dispatchers.IO) {
                        val source = store.open(pair.first)
                        val savedAnalysis = analysisStore.open(source, pair.second)
                        check(conclusionStore.canAttach(source, savedAnalysis))
                        conclusionStore.list(source, savedAnalysis)
                    }
                    if (selectedId == pair.first && document?.id == pair.first && analysis?.id == pair.second &&
                        pending == null && pendingAnalysis == null) {
                        conclusionEntries = savedEntries
                        selectedConclusion = (requestedId ?: previouslySelectedId)?.let { wanted ->
                            val candidate = savedEntries.firstOrNull { it.id == wanted && it.error == null }
                            if (requestedId != null) checkNotNull(candidate) { "所选卡片已缺失" }
                            candidate?.let {
                                withContext(Dispatchers.IO) {
                                    val source = store.open(pair.first)
                                    val savedAnalysis = analysisStore.open(source, pair.second)
                                    conclusionStore.open(source, savedAnalysis, wanted)
                                }
                            }
                        }
                        if (requestedId != null && requestedConclusionTarget?.cardId == requestedId) requestedConclusionTarget = null
                        loadedConclusionPair = pair
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    if (selectedId == pair.first && document?.id == pair.first && analysis?.id == pair.second) {
                        if (requestedId != null && requestedConclusionTarget?.cardId == requestedId) requestedConclusionTarget = null
                        conclusionLoadError = pair to "结论卡目录或所选分析无法读取；本次未删除文件，请核对本机原文、分析与结论卡副本。"
                    }
                }
            }
            var sourceExpanded by remember(current.id) { mutableStateOf(false) }
            var analysisChoicesExpanded by remember(current.id) { mutableStateOf(false) }
            var summaryExpanded by remember(current.id, currentAnalysis?.id) { mutableStateOf(false) }
            val readerListState = rememberLazyListState()
            val attemptListStartIndex = 1 +
                (if (sourceExpanded) 1 else 0) +
                (if (pending == null && analysisChoicesExpanded) 1 else 0) +
                (if (currentAnalysis != null && (summaryExpanded || pendingAnalysis != null)) 1 else 0)
            LaunchedEffect(current.id, appFilter, readerScopeFilter, conversation, currentAnalysis?.id, requestedAttemptLocator) {
                val target = requestedAttemptLocator
                val targetIndex = target?.takeIf { it.sourceId == current.id }?.let(reading::attemptIndex)
                if (target != null) {
                    if (targetIndex == null || current.isVideo) {
                        notice = "当前来源与筛选范围内找不到这条记录；未扩大筛选范围。"
                        clearAttemptTarget()
                        readerListState.scrollToItem(0)
                    } else {
                        readerListState.scrollToItem(attemptListStartIndex + targetIndex)
                    }
                } else {
                    readerListState.scrollToItem(0)
                }
            }
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
            }
            LazyColumn(Modifier.weight(1f), state = readerListState, contentPadding = PaddingValues(bottom = 88.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    if (!current.isVideo) {
                        Text("按输入声明 App 查看", style = MaterialTheme.typography.labelLarge)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item { FilterChip(selected = appFilter == null, onClick = { clearAttemptTarget(); appFilter = null }, enabled = !busy, label = { Text("全部") }) }
                            items(appFilters) { option ->
                                FilterChip(selected = appFilter == option, onClick = { clearAttemptTarget(); appFilter = option }, enabled = !busy, label = { Text(option.label) })
                            }
                        }
                    }
                    Text("${current.sourceLabel} · 批次 ${current.id.take(8)}…", style = MaterialTheme.typography.labelLarge)
                    if (!current.isVideo) ResearchConversationPane(conversations, conversation, onSelect = {
                        clearAttemptTarget()
                        conversation = it
                    }, busy = busy)
                    Text("${reading.scopeLabel} · 当前匹配 ${reading.records.size} 条 / 原批次 ${current.records.size} 条（展示数量，非成功率分母）", style = MaterialTheme.typography.labelLarge)
                    Text(if (currentAnalysis == null) "尚未选择本批分析 · 结果未知，不补 0"
                        else "${if (pendingAnalysis != null) "预览分析（未保存）" else "所选分析"} ${currentAnalysis.id.take(12)}… · 整批原副本，未按当前筛选重算或核验",
                        style = MaterialTheme.typography.bodySmall)
                    if (current.isVideo) {
                        Text("视频报告只打包已保存的原文与匹配分析，不需要人工结论，不是新播放测试。", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(enabled = !busy && selectedVideoReportTarget() != null, onClick = {
                            selectedVideoReportTarget()?.let { videoReportConfirmation = videoReportConfirmation.select(it) }
                        }) { Text(ResearchVideoOfflineReportCopy.title) }
                        if (selectedVideoReportTarget() == null) Text("请先保存视频原文，并选择本批已保存的分析；预览状态不能导出报告。", style = MaterialTheme.typography.bodySmall)
                    }
                    if (!current.isVideo && (readerScopeFilter != ResearchRecordScopeFilter() || appFilter != null || conversation != null)) {
                        TextButton(enabled = !busy, onClick = {
                            clearAttemptTarget()
                            readerScopeFilter = ResearchRecordScopeFilter()
                            requestedScopeFilter = ResearchRecordScopeFilter()
                            indexScopeFilter = ResearchRecordScopeFilter()
                            appFilter = null
                            conversation = null
                        }) { Text("清除 App / 来源 / condition / 会话筛选") }
                    }
                    if (!current.isVideo) Text("筛选只改变原记录的显示范围；已保存分析仍是整批副本，不会按当前筛选重算。", style = MaterialTheme.typography.bodySmall)
                    if (conclusionPair != null) Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            var visibleConclusionCount by remember(conclusionPair) { mutableIntStateOf(50) }
                            Text("人工结论卡 · 整批单 App", style = MaterialTheme.typography.titleMedium)
                            Text("仅关联已保存的原文与分析 SHA-256；当前 App / condition / 会话筛选不改变此卡。卡片是人工原文，不是自动测量、媒体核验或网络性能归因。", style = MaterialTheme.typography.bodySmall)
                            val pairError = conclusionLoadError?.takeIf { it.first == conclusionPair }?.second
                            if (pairError != null) {
                                Text(pairError, color = colors.ink, style = MaterialTheme.typography.bodySmall)
                            } else if (loadedConclusionPair != conclusionPair) {
                                Text("正在检查本机结论卡…", style = MaterialTheme.typography.bodySmall)
                            } else {
                                Button(enabled = !busy, onClick = {
                                    conclusionImportSourceId = conclusionPair.first
                                    conclusionImportAnalysisId = conclusionPair.second
                                    conclusionPicker.launch(arrayOf("text/plain", "text/markdown", "application/octet-stream"))
                                }) { Text("选择并预览人工结论卡（UTF-8）") }
                                if (conclusionEntries.isEmpty()) Text("尚无此原文 / 分析对应的结论卡；未知结论不补写。")
                                if (selectedConclusion == null) Text("导出报告前，请先校验并选择本分析对应的人工结论卡。原文与分析仍可分别导出。", style = MaterialTheme.typography.bodySmall)
                                else Text("列表只读文件名，点击后才校验并读取正文。", style = MaterialTheme.typography.bodySmall)
                                conclusionEntries.take(visibleConclusionCount).forEach { entry ->
                                    if (entry.error != null) Text("${entry.id.take(12)}…：${entry.error}", style = MaterialTheme.typography.bodySmall)
                                    else TextButton(enabled = !busy, onClick = {
                                        val pair = conclusionPair
                                        selectedConclusion = null
                                        action {
                                            try {
                                                val card = withContext(Dispatchers.IO) {
                                                    val source = store.open(pair.first)
                                                    val savedAnalysis = analysisStore.open(source, pair.second)
                                                    conclusionStore.open(source, savedAnalysis, entry.id)
                                                }
                                                if (selectedId == pair.first && document?.id == pair.first && analysis?.id == pair.second &&
                                                    pending == null && pendingAnalysis == null) selectedConclusion = card
                                                else notice = "原文或分析已切换；旧结论卡未显示。"
                                            } catch (e: CancellationException) { throw e }
                                            catch (_: Exception) {
                                                selectedConclusion = null
                                                notice = "结论卡无法重新读取或校验；本次未删除文件，也未显示未经验证的正文。"
                                            }
                                        }
                                    }) {
                                        Text("校验并查看结论卡 ${entry.id.take(12)}…")
                                    }
                                }
                                if (conclusionEntries.size > visibleConclusionCount) TextButton(onClick = {
                                    visibleConclusionCount += 50
                                }) { Text("再显示 50 张 · 共 ${conclusionEntries.size} 张") }
                                selectedConclusion?.takeIf {
                                    it.sourceId == conclusionPair.first && it.analysisId == conclusionPair.second
                                }?.let { card ->
                                    var cardPage by remember(card.id) { mutableIntStateOf(0) }
                                    val pageSize = 8000
                                    val codePointCount = remember(card.id) { card.text.codePointCount(0, card.text.length) }
                                    val pageCount = ((codePointCount + pageSize - 1) / pageSize).coerceAtLeast(1)
                                    val safePage = cardPage.coerceIn(0, pageCount - 1)
                                    val startCodePoint = safePage * pageSize
                                    val startOffset = card.text.offsetByCodePoints(0, startCodePoint)
                                    val endOffset = card.text.offsetByCodePoints(startOffset, minOf(pageSize, codePointCount - startCodePoint))
                                    Text("${card.appName} · ${current.sourceLabel} · 人工结论原文、非网络归因 · 纯文本第 ${safePage + 1}/$pageCount 页；不打开卡片内链接或媒体。", style = MaterialTheme.typography.bodySmall)
                                    SelectionContainer { Text("原文 SHA-256：${card.sourceId}\n分析 SHA-256：${card.analysisId}\n卡片 SHA-256：${card.id}", style = MaterialTheme.typography.bodySmall) }
                                    OutlinedButton(enabled = !busy && selectedOfflineReportTarget() != null, onClick = {
                                        selectedOfflineReportTarget()?.let { offlineReportConfirmation = offlineReportConfirmation.select(it) }
                                    }) { Text(ResearchOfflineReportCopy.title) }
                                    SelectionContainer {
                                        Text(card.text.substring(startOffset, endOffset))
                                    }
                                    if (pageCount > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(enabled = safePage > 0, onClick = { cardPage = safePage - 1 }) { Text("上一页") }
                                        TextButton(enabled = safePage + 1 < pageCount, onClick = { cardPage = safePage + 1 }) { Text("下一页") }
                                    }
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (pending == null) TextButton(onClick = { analysisChoicesExpanded = !analysisChoicesExpanded }, enabled = !busy, modifier = Modifier.weight(1f)) {
                            Text(if (analysisChoicesExpanded) "收起副本" else ResearchReaderCopy.analysisChoiceLabel)
                        }
                        TextButton(onClick = { sourceExpanded = !sourceExpanded }, modifier = Modifier.weight(1f)) { Text(if (sourceExpanded) "收起来源" else "来源 / 导出") }
                        if (currentAnalysis != null) TextButton(onClick = { summaryExpanded = !summaryExpanded }, modifier = Modifier.weight(1f)) { Text(if (summaryExpanded) "收起整批" else "整批摘要") }
                    }
                }
                if (sourceExpanded) item {
                    ResearchBatchHeading(current)
                    Text(if (pending != null) "预览，尚未保存" else "已保存", color = colors.brand)
                    Text(current.sourceNotice, color = colors.ink)
                    Text("只按原标注分组，不是全文聊天回放；缺少有效会话或轮次的记录保留在“未分会话”。逐轮结果随 App / 来源 / condition / 会话筛选切换；整批分析与原文导出不随筛选改变。", style = MaterialTheme.typography.bodySmall)
                    if (pending == null) TextButton(enabled = !busy, onClick = { confirmExport = true }) { Text(ResearchReaderCopy.recordExportLabel) }
                    SelectionContainer { Text("输入 SHA-256：${current.id}", color = colors.muted, style = MaterialTheme.typography.bodySmall) }
                    Text("方法（输入声明）：${researchDeclaredValue(current.methodId) ?: "未提供（见逐条）"}", color = colors.ink)
                    if (current.isVideo) {
                        Text("计划槽：${current.records.size}；已执行：${current.records.count { it.status == "EXECUTED" }}；未执行：${current.records.count { it.status == "NOT_RUN" }}。未执行不计播放失败。", color = colors.ink)
                        Text("30秒从可见打开 V0 开始，包括首次等待；不是播放完成。视频首画面不套用文本 TTFC/T3。", color = colors.muted)
                        Text("原始来源声明：${current.root.text("record_kind")}；PTS 单位为秒，仅展示原标注。", color = colors.muted)
                        SelectionContainer { Text(displayValue(current.root["evidence"]), style = MaterialTheme.typography.bodySmall) }
                    } else {
                        Text("操作（输入声明）：${researchDeclaredValue(current.root.text("action_text")) ?: "未提供（见逐条原记录）"}", color = colors.ink)
                        Text("原始单位：${researchDeclaredValue(current.root.text("time_unit")) ?: "未提供（见逐条时钟）"}。本机不计算派生时长；记录状态不等于网络成功。", color = colors.muted)
                    }
                }
                if (pending == null && analysisChoicesExpanded) item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("派生分析 · 独立副本", style = MaterialTheme.typography.titleMedium)
                            if (currentAnalysis != null) TextButton(enabled = !busy, onClick = { analysis = null; pendingAnalysis = null }) { Text("取消分析选择") }
                            Text(ResearchReaderCopy.analysisGuide(current.isVideo))
                            Button(enabled = !busy, onClick = {
                                analysisImportSourceId = current.id
                                analysis = null; pendingAnalysis = null
                                analysisPicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                            }) { Text("${ResearchReaderCopy.analysisChoiceLabel} JSON") }
                            if (analysisEntries.isEmpty()) Text("尚无保存的分析副本。")
                            analysisEntries.forEach { entry ->
                                if (entry.error != null) Text("${entry.id.take(12)}：${entry.error}")
                                else TextButton(enabled = !busy, onClick = {
                                    action {
                                        analysis = null; pendingAnalysis = null
                                        analysis = withContext(Dispatchers.IO) { analysisStore.open(store.open(current.id), entry.id) }
                                        analysisChoicesExpanded = false
                                    }
                                }) { Text("打开分析副本 ${entry.id.take(12)}…") }
                            }
                        }
                    }
                }
                if (currentAnalysis != null && (summaryExpanded || pendingAnalysis != null)) item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (pendingAnalysis == null) "已保存分析副本" else "分析预览 · 尚未保存", style = MaterialTheme.typography.titleMedium)
                            if (!current.isVideo) Text("整批分析（未按 App / 来源 / condition / 会话筛选）：以下计数与分组不代表当前显示范围的指标；不自动合组或排名。")
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
                            else TextButton(enabled = !busy, onClick = { analysisExportId = currentAnalysis.id }) { Text(ResearchReaderCopy.analysisExportLabel) }
                            currentAnalysis.groupLines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                if (current.isVideo) {
                    items(reading.records, key = { it.attemptId }) { attempt -> ResearchVideoAttemptCard(attempt, currentAnalysis) }
                } else {
                    if (reading.turns.isEmpty()) item {
                        Text("当前筛选没有匹配记录；原始批次、损坏项与视频入口仍保留，可清除筛选后查看。")
                    }
                    items(reading.turns, key = { it.attempt.attemptId }) { turn ->
                        ResearchAttemptCard(
                            turn,
                            currentAnalysis,
                            isNavigationTarget = requestedAttemptLocator?.let {
                                it.sourceId == turn.sourceId && it.attemptId == turn.attempt.attemptId
                            } == true,
                            mediaAttachment = if (selectedId == current.id && pending == null && !current.isVideo && current.recordKind == "OBSERVED") {
                                { ManualResearchMediaAttachment(current.id, turn.attempt, manualMediaStore, manualPlaybackOwner, enabled = !busy) }
                            } else null,
                        )
                    }
                }
            }
        }
    }
    businessRawReportId?.let { sourceId ->
        val sameSavedSource = pending == null && selectedId == sourceId && document?.id == sourceId && document?.isBusinessVideo == true
        AlertDialog(
            onDismissRequest = { if (!busy) businessRawReportId = null },
            title = { Text("导出业务视频采集原文") },
            text = { Text("仅把已保存的原始 JSON、可读说明和 SHA 清单保存到 Downloads/ANEB；不附媒体、不打开引用、不上传。不是旧 V0/VF 分析，也不是播放成功或网络结论。") },
            confirmButton = { TextButton(enabled = !busy && sameSavedSource, onClick = {
                businessRawReportId = null
                action {
                    val result = withContext(Dispatchers.IO) {
                        Exporter.exportToDownloads(context, "ANEB-private-business-raw-${sourceId.take(12)}.zip", "application/zip") {
                            videoReportExporter.writeRawTo(sourceId, it)
                        }
                    }
                    notice = if (result.ok) "采集原文报告已保存到 Downloads/ANEB；未附媒体，未上传。"
                        else "导出失败，未发布不完整文件；原记录仍保留。"
                }
            }) { Text("确认仅本机导出") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { businessRawReportId = null }) { Text("取消") } },
        )
    }
    videoReportConfirmation.pending?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!busy) videoReportConfirmation = videoReportConfirmation.cancel() },
            title = { Text(ResearchVideoOfflineReportCopy.title) },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(ResearchVideoOfflineReportCopy.confirmation(target))
                    if (target != selectedVideoReportTarget()) Text("所选视频原文或分析已改变；请取消后重新选择匹配的已保存副本。")
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && target == selectedVideoReportTarget(), onClick = {
                    if (!busy) videoReportConfirmation = videoReportConfirmation.confirm(selectedVideoReportTarget()) { selected ->
                        action {
                            try {
                                val result = withContext(Dispatchers.IO) {
                                    val exportContext = currentCoroutineContext()
                                    exportContext.ensureActive()
                                    val name = "ANEB-private-video-" + selected.sourceId.take(12) + ".zip"
                                    Exporter.exportToDownloads(context, name, "application/zip") { output ->
                                        videoReportExporter.writeTo(selected.sourceId, selected.analysisId, output)
                                        exportContext.ensureActive()
                                    }
                                }
                                notice = if (result.ok) "已导出私有视频报告 ZIP 到 Downloads/ANEB（" + result.bytes + " 字节）。仅本机保存，未上传或分享。"
                                    else ResearchVideoOfflineReportCopy.failure
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { notice = ResearchVideoOfflineReportCopy.failure }
                        }
                    }
                }) { Text("确认导出视频 ZIP · 仅本机") }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { videoReportConfirmation = videoReportConfirmation.cancel() }) { Text("取消 · 不导出") }
            },
        )
    }
    offlineReportConfirmation.pending?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!busy) offlineReportConfirmation = offlineReportConfirmation.cancel() },
            title = { Text(ResearchOfflineReportCopy.title) },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(ResearchOfflineReportCopy.confirmation(target))
                    if (target != selectedOfflineReportTarget()) {
                        Text("所选原文、分析或结论已改变；请取消后重新打开匹配的人工结论卡。")
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && target == selectedOfflineReportTarget(), onClick = {
                    if (!busy) offlineReportConfirmation = offlineReportConfirmation.confirm(selectedOfflineReportTarget()) { selected ->
                        action {
                            try {
                                val result = withContext(Dispatchers.IO) {
                                    val exportContext = currentCoroutineContext()
                                    exportContext.ensureActive()
                                    val name = "ANEB-private-research-" + selected.sourceId.take(12) + ".zip"
                                    Exporter.exportToDownloads(context, name, "application/zip") { output ->
                                        offlineReportExporter.writeTo(selected.sourceId, selected.analysisId, selected.conclusionId, output)
                                        exportContext.ensureActive()
                                    }
                                }
                                notice = if (result.ok) "已导出私有离线报告 ZIP 到 Downloads/ANEB（" + result.bytes + " 字节）。仅本机保存，未上传或分享。"
                                    else ResearchOfflineReportCopy.failure
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                notice = ResearchOfflineReportCopy.failure
                            }
                        }
                    }
                }) { Text("确认导出 ZIP · 仅本机") }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { offlineReportConfirmation = offlineReportConfirmation.cancel() }) {
                    Text("取消 · 不导出")
                }
            },
        )
    }

    pendingConclusionCard?.takeIf { card ->
        selectedId == card.sourceId && document?.id == card.sourceId && analysis?.id == card.analysisId &&
            pending == null && pendingAnalysis == null
    }?.let { card ->
        AlertDialog(
            onDismissRequest = { if (!busy) { pendingConclusionCard = null; pendingConclusionError = null } },
            title = { Text("确认保存人工结论卡？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    pendingConclusionError?.let { Text(it, color = colors.ink, style = MaterialTheme.typography.bodySmall) }
                    Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${card.preview.appName} · ${card.bytes.size} 字节 · 仅存本机私有目录")
                        SelectionContainer {
                            Text("原文 SHA-256：${card.sourceId}\n分析 SHA-256：${card.analysisId}\n卡片 SHA-256：${card.preview.id}", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("下方仅预览前 1200 字符；确认后保存完整 UTF-8 原文。不自动核验结论真伪、不打开链接或媒体。", style = MaterialTheme.typography.bodySmall)
                        val previewEnd = card.preview.text.offsetByCodePoints(
                            0, minOf(1200, card.preview.text.codePointCount(0, card.preview.text.length))
                        )
                        SelectionContainer { Text(card.preview.text.substring(0, previewEnd), style = MaterialTheme.typography.bodySmall) }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    action {
                        try {
                            if (selectedId != card.sourceId || document?.id != card.sourceId || analysis?.id != card.analysisId ||
                                pending != null || pendingAnalysis != null) {
                                throw ResearchImportException("原文或分析已切换；结论卡未保存。")
                            }
                            val saved = withContext(Dispatchers.IO) {
                                val source = store.open(card.sourceId)
                                val savedAnalysis = analysisStore.open(source, card.analysisId)
                                conclusionStore.save(source, savedAnalysis, card.bytes)
                            }
                            pendingConclusionCard = null
                            pendingConclusionError = null
                            if (selectedId == card.sourceId && document?.id == card.sourceId && analysis?.id == card.analysisId &&
                                pending == null && pendingAnalysis == null) {
                                loadedConclusionPair = card.sourceId to card.analysisId
                                selectedConclusion = saved
                                conclusionRevision++
                                notice = "人工结论卡已保存；它不是自动测量、媒体核验或网络归因。"
                            } else notice = "结论卡已保存到原先选择的批次；当前页面已切换，未显示旧结论。"
                        } catch (e: CancellationException) { throw e }
                        catch (e: ResearchImportException) { pendingConclusionError = e.message }
                        catch (_: Exception) { pendingConclusionError = "结论卡未能保存。请核对本机原文、分析与存储空间；本次未删除文件。" }
                    }
                }) { Text("确认保存") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { pendingConclusionCard = null; pendingConclusionError = null }) { Text("取消 · 不保存") } },
        )
    }
    batchImportPreview?.let { preview ->
        val readyCount = preview.items.count { it.status == ResearchBatchImportItemStatus.READY }
        AlertDialog(
            onDismissRequest = { if (!busy) batchImportPreview = null },
            title = { Text("导入预览 · $readyCount 项可保存") },
            text = {
                Column(
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("配对只按原文 SHA-256 与已有 attempt 身份校验；不会合并原文和分析，也不会自动选择分析。")
                    preview.pairs.forEach { pair ->
                        Text("${pair.rawFileName ?: "本机已有原文"} · ${pair.sourceId.take(12)}…")
                        pair.analysisFileNames.forEach { Text("  ↳ ${it}") }
                    }
                    preview.items.forEach { item ->
                        val kind = ResearchReaderCopy.importKind(item.kind)
                        val status = when (item.status) {
                            ResearchBatchImportItemStatus.READY -> "可保存"
                            ResearchBatchImportItemStatus.INVALID -> "无效"
                            ResearchBatchImportItemStatus.UNMATCHED_SOURCE -> "找不到原文"
                            ResearchBatchImportItemStatus.DUPLICATE -> "重复项"
                        }
                        Text("$kind · $status · ${item.fileName}")
                        item.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.muted) }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && readyCount > 0,
                    onClick = {
                        val selectedPreview = batchImportPreview ?: return@TextButton
                        action {
                            val receipt = withContext(Dispatchers.IO) { selectedPreview.confirmSave() }
                            batchSaveReceipt = receipt
                            batchImportPreview = null
                            refresh()
                            notice = "导入处理完成：${ResearchReaderCopy.saveSummary(receipt)}；未保存项已逐项列出。"
                        }
                    },
                ) { Text("确认保存 $readyCount 项") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { batchImportPreview = null }) { Text("取消 · 不保存") } },
        )
    }
    batchSaveReceipt?.let { receipt ->
        AlertDialog(
            onDismissRequest = { batchSaveReceipt = null },
            title = { Text("导入结果 · ${ResearchReaderCopy.saveSummary(receipt)}") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    receipt.items.forEach { item ->
                        val label = if (item.status == ResearchBatchImportSaveStatus.SAVED) "保存处理成功" else "未保存"
                        Text("$label · ${item.fileName}")
                        item.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.muted) }
                    }
                    Text(ResearchReaderCopy.saveNotice, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { batchSaveReceipt = null }) { Text("完成") } },
        )
    }
    if (confirmExport || analysisExportId != null) AlertDialog(
        onDismissRequest = { confirmExport = false; analysisExportId = null },
        title = { Text(ResearchReaderCopy.exportTitle(analysisExportId != null)) },
        text = { Text(ResearchReaderCopy.exportConfirmation(document?.sourceLabel, document?.id, analysisExportId)) },
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
    if (document.isBusinessVideo) {
        Text("业务视频采集原文 · ${document.root.text("runId")}", style = MaterialTheme.typography.titleMedium)
        Text("${document.root.text("state")} · 未核验，不等于播放成功", style = MaterialTheme.typography.bodySmall)
        return
    }
    document.batchSummaryLines.forEachIndexed { index, line ->
        Text(line, style = if (index == 0) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ResearchBusinessVideoRawPane(
    document: ResearchDocument,
    preview: Boolean,
    busy: Boolean,
    modifier: Modifier,
    onSave: () -> Unit,
    onExportJson: () -> Unit,
    onExportReport: () -> Unit,
) {
    val raw = document.root
    val frames = (raw["frames"] as? JsonArray).orEmpty()
    var showOriginal by remember(document.id) { mutableStateOf(false) }
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 88.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            ResearchBatchHeading(document)
            Text(document.sourceLabel)
            Text(document.sourceNotice)
            Text(if (preview) "预览 · 未保存" else "本机已保存 · 原字节可重开与导出")
            if (preview) Button(enabled = !busy, onClick = onSave) { Text("确认保存未核验采集原文") }
            else {
                OutlinedButton(enabled = !busy, onClick = onExportReport) { Text("导出采集原文报告 ZIP") }
                TextButton(enabled = !busy, onClick = onExportJson) { Text("导出原始 JSON") }
            }
            SelectionContainer { Text("原文 SHA-256：${document.id}", style = MaterialTheme.typography.bodySmall) }
            Text("这是一个运行内的 ${frames.size} 张采样引用，不是 ${frames.size} 个独立样本。")
            listOf("conditionId" to "条件声明", "clockId" to "同机时钟声明", "category" to "执行类别",
                "reason" to "停止原因", "swipeCalls" to "上划调用数", "ownedCleanup" to "自有清理声明").forEach { (key, label) ->
                Text("$label：${displayValue(raw[key])}")
            }
            Text("首次采集器观察播放等待：NA（待独立复核）；播放器首帧：NA；卡顿：not_computed。")
            Text("不是旧 V0/VF；不从录制就绪推算 PTS。App版本、实际网络与内容身份须回到 binding/app-identity/界面及媒体原件核对，不能由本摘要推断。", style = MaterialTheme.typography.bodySmall)
            Text("此入口不附媒体、不自动打开路径、不计算评分或网络归因。", style = MaterialTheme.typography.bodySmall)
        }
        item {
            Text("操作时间原文 · elapsed纳秒，不是MP4 PTS", style = MaterialTheme.typography.titleSmall)
            SelectionContainer { Text(displayValue(raw["events"]), style = MaterialTheme.typography.bodySmall) }
        }
        items(frames) { frame ->
            Card(Modifier.fillMaxWidth()) {
                SelectionContainer { Text(frame.toString(), Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
            }
        }
        item {
            TextButton(onClick = { showOriginal = !showOriginal }) { Text(if (showOriginal) "收起完整原文" else "查看完整原文") }
            if (showOriginal) SelectionContainer { Text(raw.toString(), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun ResearchVideoAttemptCard(attempt: ResearchAttempt, analysis: ResearchAnalysis?) {
    val raw = attempt.raw
    var detailsExpanded by remember(raw) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${attempt.attemptId} · ${attempt.statusLabel}", style = MaterialTheme.typography.titleMedium)
            if (analysis == null) Text("首画面区间与30秒观察窗：尚未关联分析，不补 0、不推算时长。")
            else analysis.attemptLines(attempt.attemptId).forEach { Text(it) }
            TextButton(onClick = { detailsExpanded = !detailsExpanded }) { Text(if (detailsExpanded) "收起视频原标注" else "展开视频原标注") }
            if (detailsExpanded) {
                Text("目标播放声明：${displayValue(raw["visible_target_playback"])}；完整窗口声明：${displayValue(raw["window_complete"])}")
                listOf("V0" to "可见打开 V0", "VF" to "目标首画面 VF", "window_end" to "实际标注观察终点", "reason" to "原始说明").forEach { (key, label) ->
                    Text(label, style = MaterialTheme.typography.labelLarge)
                    SelectionContainer { Text(displayValue(raw[key]), style = MaterialTheme.typography.bodySmall) }
                }
                Text("引用仅作文本保留；未打开录像或核验播放。缓冲 / 暂停未计算，不能据此归因网络。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ResearchAttemptCard(
    turn: ResearchConversationTurn,
    analysis: ResearchAnalysis?,
    isNavigationTarget: Boolean = false,
    mediaAttachment: (@Composable () -> Unit)? = null,
) {
    val attempt = turn.attempt
    var rawExpanded by remember(turn.sourceId, attempt.attemptId) { mutableStateOf(false) }
    val raw = attempt.raw
    val app = raw["app"] as? JsonObject
    val outcome = raw["outcome"] as? JsonObject
    val clock = raw["clock"] as? JsonObject
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = if (isNavigationTarget) BorderStroke(2.dp, AnebTheme.colors.brand) else null,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isNavigationTarget) Text("当前定位记录", color = AnebTheme.colors.brand, style = MaterialTheme.typography.labelLarge)
            Text("${app?.text("name") ?: "UNKNOWN"} · ${attempt.statusLabel}", style = MaterialTheme.typography.titleMedium)
            attempt.statusNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text("${raw.text("record_kind") ?: "未确认来源"} · ${attempt.attemptId}")
            Text("原始 condition：${attempt.originalConditionDisplay}")
            ResearchConversationTurnContent(turn, analysis)
            mediaAttachment?.invoke()
            TextButton(onClick = { rawExpanded = !rawExpanded }) { Text(if (rawExpanded) "收起原始标注与证据引用" else "展开原始标注与证据引用") }
            if (rawExpanded) {
                attempt.sourceWarnings.forEach { Text(it, color = AnebTheme.colors.fair) }
                Text("版本：${app?.text("version") ?: "UNKNOWN"}；模式：${app?.text("model_mode") ?: "UNKNOWN"}")
                Text(ResearchReaderCopy.outcomeSummary(
                    displayValue(outcome?.get("executed")),
                    displayValue(outcome?.get("visible_completion")),
                    displayValue(outcome?.get("instruction_following")),
                ))
                Text(ResearchReaderCopy.outcomeNotice, style = MaterialTheme.typography.bodySmall)
                Text("时钟：${clock?.text("source") ?: "UNKNOWN"}；单位：${clock?.text("unit") ?: "UNKNOWN"}；域：${clock?.text("domain_id") ?: "UNKNOWN"}")
                listOf("context_observation" to "上下文原标注（含未识别字段，不补推断）", "metadata" to "设备、时间与网络", "events" to "原始事件与区间", "observed_intervals" to "原始观察区间", "missing_reasons" to "缺失原因", "evidence" to "证据引用（不打开外部路径）", "completion_observation" to "完成观察依据").forEach { (key, label) ->
                    Text(label, style = MaterialTheme.typography.labelLarge)
                    SelectionContainer { Text(displayValue(raw[key]), style = MaterialTheme.typography.bodySmall) }
                }
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

private fun readResearchBatchFiles(context: Context, uris: List<Uri>): List<ResearchBatchImportFile> {
    if (uris.size > ResearchBatchImport.MAX_FILES) {
        return uris.map { uri ->
            ResearchBatchImportFile(displayName(context, uri), byteArrayOf(), readError = "too_many")
        }
    }
    var totalBytes = 0L
    var batchBudgetReached = false
    return uris.map { uri ->
        val name = displayName(context, uri)
        if (batchBudgetReached || totalBytes >= ResearchBatchImport.MAX_TOTAL_BYTES) {
            batchBudgetReached = true
            return@map ResearchBatchImportFile(name, byteArrayOf(), readError = "batch_limit")
        }
        val remainingBatchBytes = ResearchBatchImport.MAX_TOTAL_BYTES - totalBytes
        try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size().toLong() + count > ResearchRecordStore.MAX_BYTES) throw ResearchFileTooLarge()
                    if (output.size().toLong() + count > remainingBatchBytes) throw ResearchBatchTooLarge()
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: throw IllegalStateException("open failed")
            totalBytes += bytes.size
            ResearchBatchImportFile(name, bytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ResearchFileTooLarge) {
            ResearchBatchImportFile(name, byteArrayOf(), readError = "too_large")
        } catch (e: ResearchBatchTooLarge) {
            batchBudgetReached = true
            ResearchBatchImportFile(name, byteArrayOf(), readError = "batch_limit")
        } catch (_: Exception) {
            ResearchBatchImportFile(name, byteArrayOf(), readError = "unreadable")
        }
    }
}

private fun displayName(context: Context, uri: Uri): String = try {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0)?.take(160) else null
    } ?: uri.lastPathSegment?.take(160) ?: "未命名 JSON"
} catch (_: Exception) {
    "未命名 JSON"
}

private class ResearchFileTooLarge : IllegalArgumentException()
private class ResearchBatchTooLarge : IllegalArgumentException()
