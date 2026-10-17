package com.aneb.probe.ui.research

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aneb.probe.research.*
import com.aneb.probe.ui.theme.AnebTheme
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Part of the existing reader; original viewing is routed to its same image/text/player UI. */
@Composable
internal fun EngineeringObservationReviewScreen(pairId: String, originals: EngineeringObservationStore,
    controller: EngineeringObservationReviewController, onBack: () -> Unit,
    onOriginal: (EngineeringObservationAsset, File) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var state by remember(controller) { mutableStateOf(controller.state) }
    var busy by remember { mutableStateOf(false) }
    var choosingExport by remember { mutableStateOf(false) }
    var confirmSave by remember { mutableStateOf(false) }
    var confirmExport by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<ByteArray?>(null) }
    var originalError by remember { mutableStateOf<String?>(null) }
    val colors = AnebTheme.colors
    val enabled = !busy && !choosingExport

    fun action(block: () -> EngineeringReviewState) {
        if (busy || choosingExport) return
        busy = true; originalError = null
        scope.launch {
            try { state = withContext(Dispatchers.IO) { block() } }
            finally { busy = false }
        }
    }
    fun leave() {
        if (busy || choosingExport) return
        busy = true
        scope.launch {
            try {
                val retained = withContext(Dispatchers.IO) { controller.retainForNavigation() }
                state = controller.state
                if (retained) onBack()
            } finally { busy = false }
        }
    }
    fun view(asset: EngineeringObservationAsset, reference: EngineeringReviewSelection? = null) {
        if (busy || choosingExport) return
        busy = true; originalError = null
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    if (reference == null) originals.originalFile(checkNotNull(state.document), asset.path)
                    else controller.originalFile(reference)
                }
                onOriginal(asset, file)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { originalError = "对应原件缺失／SHA 已改变；未打开，也未显示旧文件。" }
            finally { busy = false }
        }
    }
    val exportContract = remember {
        object : ActivityResultContracts.CreateDocument("application/json") {
            override fun createIntent(context: Context, input: String): Intent =
                super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
        }
    }
    val exporter = rememberLauncherForActivityResult(exportContract) { uri ->
        choosingExport = false
        val bytes = pendingExport
        pendingExport = null
        if (uri == null) state = controller.exportOutcome(null)
        else if (bytes == null) state = controller.exportOutcome(false)
        else {
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { output ->
                            output.write(bytes); output.flush()
                        }
                    }
                    state = controller.exportOutcome(true)
                } catch (e: CancellationException) { state = controller.exportOutcome(false); throw e }
                catch (_: Exception) { state = controller.exportOutcome(false) }
                finally { busy = false }
            }
        }
    }
    BackHandler { leave() }
    LaunchedEffect(controller, pairId) {
        if (controller.state.document?.id != pairId) {
            busy = true
            try { state = withContext(Dispatchers.IO) { controller.openPair(pairId) } }
            finally { busy = false }
        }
    }
    DisposableEffect(controller, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) state = controller.retainDraftForLifecycle()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            // Small bounded local draft, not new collection; no coroutine lost at disposal.
            controller.retainDraftForLifecycle()
        }
    }

    Column(Modifier.fillMaxSize().background(colors.background).padding(horizontal = 16.dp)) {
        TextButton(enabled = enabled, onClick = { leave() }) { Text("返回批次 · 保留未提交草稿") }
        Text("人工复核与证据", style = MaterialTheme.typography.headlineSmall, color = colors.ink)
        Text(EngineeringObservationReviewStore.NOTICE, style = MaterialTheme.typography.bodySmall, color = colors.muted)
        Text(state.notice, color = if (state.failed) colors.fair else colors.ink, modifier = Modifier.padding(vertical = 6.dp))
        if (!enabled) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                SelectionContainer { Text("原始 PAIR SHA-256：$pairId", style = MaterialTheme.typography.bodySmall) }
                Text("人工输入不认证模型／工具行为，不从发送次数、采屏或输入说明计算指标／成功率／网络排名。")
            }
            val document = state.document
            if (document != null) {
                item {
                    Text("HOST 原值：${document.state}", style = MaterialTheme.typography.titleSmall)
                    document.attempts.forEach { a ->
                        Text("${a.condition} · ${a.runId}\n${a.state} · SEND=${a.sendCalls} · owned_cleanup=${a.ownedCleanup}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("HOLD／UNKNOWN／NOT_RUN 原样保留；HOST 摘要不等于 Native 层终态。")
                }
                val review = state.review
                if (review != null) {
                    item {
                        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("已保存的人工声明 · 非工具认证", style = MaterialTheme.typography.titleMedium)
                            Text("App：${review.input.appName.ifBlank { "未提供" }}\n版本：${review.input.appVersion.ifBlank { "未提供" }}\n可见模式说明：${review.input.visibleMode.ifBlank { "未提供；后台模式 UNKNOWN" }}")
                            Text("实际可见事实", style = MaterialTheme.typography.titleSmall)
                            SelectionContainer { Text(review.input.facts) }
                            Text("未确认事项／适用边界", style = MaterialTheme.typography.titleSmall)
                            SelectionContainer { Text(review.input.limitations) }
                            SelectionContainer { Text("复核 SHA-256：${review.id}", style = MaterialTheme.typography.bodySmall) }
                            OutlinedButton(enabled = enabled, onClick = { confirmExport = true }) { Text("导出此复核 JSON · 先确认隐私") }
                        } }
                    }
                    items(review.input.evidence, key = { "reference-${it.path}" }) { selection ->
                        val attempt = document.attempts.single { it.runId == selection.runId }
                        val asset = attempt.assets.single { it.path == selection.path }
                        OutlinedButton(enabled = enabled, onClick = { view(asset, selection) }, modifier = Modifier.fillMaxWidth()) {
                            Text("回证 ${attempt.condition} · ${asset.path.substringAfterLast('/')}\n${asset.bytes} B · SHA ${asset.sha256}")
                        }
                    }
                }
                item {
                    Text("本批次已保存的复核版本", style = MaterialTheme.typography.titleMedium)
                    if (state.entries.isEmpty()) Text("尚未保存人工复核；原件仍可独立查看。")
                }
                items(state.entries, key = { it.id }) { entry ->
                    OutlinedButton(enabled = enabled, onClick = { action {
                        val retained = controller.retainDraft()
                        if (retained.failed) retained else controller.openReview(entry.id)
                    } }, modifier = Modifier.fillMaxWidth()) {
                        Text("打开复核 ${entry.id.take(16)}…${entry.error?.let { " · $it" } ?: " · 打开时核验"}")
                    }
                }
                item {
                    Button(enabled = enabled, onClick = { state = controller.edit(state.input) }) { Text("新建／继续未提交复核") }
                    TextButton(enabled = enabled, onClick = { confirmDiscard = true }) { Text("明确放弃本批次草稿") }
                }
                if (state.editing) {
                    item {
                        Text("未提交输入 · 保存为独立版本，不覆盖已有复核", style = MaterialTheme.typography.titleMedium)
                        ReviewField("App 名称（人工声明，可留空）", state.input.appName, 200, enabled) { state = controller.edit(state.input.copy(appName = it)) }
                        ReviewField("App 版本（未提供则保持未提供）", state.input.appVersion, 200, enabled) { state = controller.edit(state.input.copy(appVersion = it)) }
                        ReviewField("可见模式说明（不推断后台模型）", state.input.visibleMode, 200, enabled) { state = controller.edit(state.input.copy(visibleMode = it)) }
                        ReviewField("本轮实际可见事实 · 必填", state.input.facts, 4000, enabled) { state = controller.edit(state.input.copy(facts = it)) }
                        ReviewField("未确认事项／适用边界 · 必填", state.input.limitations, 4000, enabled) { state = controller.edit(state.input.copy(limitations = it)) }
                        Text("从真实清单选择 1–6 件证据。勾选仅是人工引用声明，文件存在或查看按钮不等于已完整观看。")
                    }
                    document.attempts.forEach { attempt ->
                        val selectable = attempt.assets.filter { a ->
                            val name = a.path.substringAfterLast('/')
                            name.endsWith(".png") || name.endsWith(".xml") || name.endsWith(".mp4") || name == "TERMINAL.json"
                        }
                        items(selectable, key = { "choose-${it.path}" }) { asset ->
                            val selection = EngineeringReviewSelection(attempt.runId, asset.path)
                            val checked = selection in state.input.evidence
                            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(8.dp)) {
                                Row {
                                    Checkbox(checked = checked, enabled = enabled && (checked || state.input.evidence.size < 6), onCheckedChange = { chosen ->
                                        val next = if (chosen) state.input.evidence + selection else state.input.evidence - selection
                                        state = controller.edit(state.input.copy(evidence = next))
                                    })
                                    Text("${attempt.condition} · ${asset.path.substringAfterLast('/')}\n${attempt.runId}", style = MaterialTheme.typography.bodySmall)
                                }
                                TextButton(enabled = enabled, onClick = { view(asset) }) { Text("查看此原件 · 不自动勾选") }
                            } }
                        }
                    }
                    item { Button(enabled = enabled, onClick = { confirmSave = true }) { Text("确认人工声明并另存复核") } }
                }
            }
            originalError?.let { message -> item { Text(message, color = colors.fair) } }
        }
    }
    if (confirmSave) AlertDialog(onDismissRequest = { confirmSave = false }, title = { Text("保存人工复核？") },
        text = { Text("我确认这只是我的输入声明，并已自行判断所选证据的可见事实与局限。工具只核验批次／轮次／原字节关联，不认证内容真实性；不计算指标。修改会另存版本。") },
        confirmButton = { TextButton(onClick = { confirmSave = false; action { controller.confirmSave(true) } }) { Text("确认并另存") } },
        dismissButton = { TextButton(onClick = { confirmSave = false }) { Text("继续编辑") } })
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false }, title = { Text("放弃未提交草稿？") },
        text = { Text("只移除本批次未提交输入；已保存复核及全部原件保持不变。") },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; action { controller.discardDraft() } }) { Text("明确放弃草稿") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("保留") } })
    if (confirmExport) AlertDialog(onDismissRequest = { confirmExport = false; state = controller.exportOutcome(null) }, title = { Text("确认隐私后导出？") },
        text = { Text("JSON 含你的人工输入、App／版本／模式说明、批次和轮次、原件文件名／路径／大小／SHA，可能包含隐私。不会自动附截图、录像或其他原件，本 App 不上传。系统将请求仅本机位置；所选提供方是否另行同步不由本工具认证。") },
        confirmButton = { TextButton(onClick = {
            confirmExport = false; busy = true
            scope.launch {
                try {
                    val bytes = withContext(Dispatchers.IO) { controller.exportBytes(true) }
                    state = controller.state
                    if (bytes != null) {
                        pendingExport = bytes; choosingExport = true
                        try { exporter.launch("ANEB-engineering-review-${state.review!!.id.take(12)}.json") }
                        catch (_: Exception) { pendingExport = null; choosingExport = false; state = controller.exportOutcome(false) }
                    }
                } finally { busy = false }
            }
        }) { Text("确认隐私并选择位置") } },
        dismissButton = { TextButton(onClick = { confirmExport = false; state = controller.exportOutcome(null) }) { Text("不导出") } })
}

@Composable
private fun ReviewField(label: String, value: String, limit: Int, enabled: Boolean, changed: (String) -> Unit) {
    OutlinedTextField(value, onValueChange = { if (it.length <= limit) changed(it) }, enabled = enabled,
        label = { Text(label) }, supportingText = { Text("${value.length}/$limit") }, modifier = Modifier.fillMaxWidth())
}
