package com.aneb.probe.ui.research

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.aneb.probe.research.PrivateSdkObservationDocument
import com.aneb.probe.research.PrivateSdkObservationEntry
import com.aneb.probe.research.PrivateSdkObservationStore
import com.aneb.probe.research.ResearchRecordStore
import com.aneb.probe.ui.theme.AnebTheme
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

data class PrivateSdkLiteralField(val path: String, val type: String, val literal: String)

object PrivateSdkObservationPresentation {
    fun fields(root: JsonObject): List<PrivateSdkLiteralField> = buildList {
        fun field(path: String, value: JsonElement?) {
            val type = when (value) {
                null -> "MISSING"
                JsonNull -> "JSON null"
                is JsonPrimitive -> if (value.isString) "JSON string" else if (value.booleanOrNull != null) "JSON boolean" else "JSON number"
                is JsonObject -> "JSON object"
                else -> "JSON array"
            }
            add(PrivateSdkLiteralField(path, type, value?.toString() ?: "MISSING（未提供）"))
        }
        fun section(path: String, value: JsonElement?, expected: List<String>) {
            val fields = value as? JsonObject
            if (fields == null) { field(path, value); return }
            (expected + fields.keys).distinct().forEach { field("$path.$it", fields[it]) }
        }
        listOf("task", "classification", "verdict", "nonce").forEach { field(it, root[it]) }
        val objects = root["objects"] as? JsonObject
        listOf("deep_label", "deep_parent", "search_label", "search_parent").forEach {
            section("objects.$it", objects?.get(it), listOf("checked", "checkable", "visible", "enabled", "window_id"))
        }
        section("literal_fields", root["literal_fields"], listOf("owner", "action_target", "actions"))
        section("sdk_clock", root["sdk_clock"], listOf("domain", "atomic", "ttl"))
        section("limits", root["limits"], listOf("independent_pixel_or_human_witness", "checked_means_mode_on_off", "backend_mode", "phone_import_export_reopen"))
        root.filterKeys { it !in setOf("task", "classification", "verdict", "nonce", "objects", "literal_fields", "sdk_clock", "limits") }
            .forEach { (key, value) -> field(key, value) }
    }
}

data class PrivateSdkObservationReaderState(
    val document: PrivateSdkObservationDocument? = null,
    val saved: Boolean = false,
    val entries: List<PrivateSdkObservationEntry> = emptyList(),
    val notice: String = "尚未选择私有观察文件",
)

/** The same public state flow is consumed by the native page and host behavior tests. */
class PrivateSdkObservationReaderController(private val store: PrivateSdkObservationStore) {
    var state = PrivateSdkObservationReaderState()
        private set

    fun clearSelection(notice: String = "尚未选择私有观察文件"): PrivateSdkObservationReaderState {
        state = state.copy(document = null, saved = false, notice = notice)
        return state
    }

    fun preview(open: (() -> InputStream?)?): PrivateSdkObservationReaderState {
        clearSelection()
        if (open == null) return clearSelection("已取消选择；没有保存或显示旧预览")
        try {
            val bytes = checkNotNull(open()).use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= ResearchRecordStore.MAX_BYTES)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            state = state.copy(document = store.preview(bytes), saved = false,
                notice = "仅预览，尚未保存；关闭后需要重新选择原文件")
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            clearSelection("无法预览：请核对读取权限、UTF-8 私有 SDK 格式与 1 MiB 上限；原文件未修改")
        }
        return state
    }

    fun confirmSave(): PrivateSdkObservationReaderState {
        val preview = state.document ?: return clearSelection("请先选择并预览私有观察文件")
        try {
            val saved = store.save(preview.originalBytes())
            state = state.copy(document = saved, saved = true,
                notice = "本机原字节副本已保存；SHA 只标识文件，不认证观察内容")
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { clearSelection("保存失败：请检查本机存储；未覆盖已有副本，也未显示旧成功") }
        return state
    }

    fun showList(): PrivateSdkObservationReaderState {
        clearSelection()
        try { state = state.copy(entries = store.list(), notice = "本机私有副本 · 点击后重新读盘并核对 SHA") }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { state = state.copy(entries = emptyList(), notice = "无法读取本机列表；原文件保留") }
        return state
    }

    fun open(id: String): PrivateSdkObservationReaderState {
        clearSelection()
        try {
            state = state.copy(document = store.open(id), saved = true,
                notice = "已从本机磁盘重新读取原字节，SHA 一致；不是观察真实性或跨进程验收")
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { clearSelection("无法重开：副本缺失、损坏或超限；未显示旧记录，原文件保留") }
        return state
    }
}

/** Native, local-only reader. No links, collection, measurement, export, or upload. */
@Composable
fun PrivateSdkObservationReaderScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val controller = remember(context) {
        PrivateSdkObservationReaderController(PrivateSdkObservationStore(File(context.noBackupFilesDir, "private-sdk-observations")))
    }
    var state by remember(controller) { mutableStateOf(controller.state) }
    var busy by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    var confirmSave by remember { mutableStateOf(false) }
    var discardAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val scope = rememberCoroutineScope()
    fun action(preservePreview: Boolean = false, block: () -> PrivateSdkObservationReaderState) {
        if (busy) return
        busy = true
        if (!preservePreview) state = controller.clearSelection("正在处理本机文件…")
        scope.launch {
            try { state = withContext(Dispatchers.IO) { block() } }
            catch (e: CancellationException) {
                state = controller.clearSelection("操作已取消；未显示旧记录")
                throw e
            } finally { busy = false }
        }
    }
    fun leave(next: () -> Unit) {
        if (busy || choosing) return
        if (state.document != null && !state.saved) discardAction = next else next()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        choosing = false
        action { controller.preview(uri?.let { { context.contentResolver.openInputStream(it) } }) }
    }
    LaunchedEffect(controller) {
        busy = true
        try { state = withContext(Dispatchers.IO) { controller.showList() } }
        finally { busy = false }
    }
    BackHandler { leave(onBack) }
    val colors = AnebTheme.colors
    Column(Modifier.fillMaxSize().background(colors.background).padding(horizontal = 16.dp)) {
        Row {
            TextButton(onClick = { leave(onBack) }, enabled = !busy && !choosing) { Text("返回研究记录") }
            TextButton(onClick = { leave { action { controller.showList() } } }, enabled = !busy && !choosing) { Text("本机列表") }
        }
        Text("私有 SDK 观察", style = MaterialTheme.typography.headlineSmall, color = colors.ink)
        Text("历史私有输入／本机副本 · 输入声明未核验 · 不是正式网络结论", color = colors.muted,
            style = MaterialTheme.typography.bodySmall)
        Text(state.notice, color = colors.ink, modifier = Modifier.padding(vertical = 8.dp))
        if (busy || choosing) LinearProgressIndicator(Modifier.fillMaxWidth())
        OutlinedButton(enabled = !busy && !choosing, onClick = {
            leave {
                state = controller.clearSelection("等待单文件选择；不会自动保存")
                choosing = true
                try { picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
                catch (_: Exception) {
                    choosing = false
                    state = controller.clearSelection("无法打开文件选择器；请重试，未显示旧记录")
                }
            }
        }) { Text("选择私有 SDK JSON · 最多 1 MiB") }
        val document = state.document
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("阅读边界", style = MaterialTheme.typography.titleSmall)
                        Text("SDK checked=false/true 是字面字段，不判定模式 ON/OFF、backend 或网络表现；不计算 TTFT、RPI 或其它指标。", style = MaterialTheme.typography.bodySmall)
                        Text("本页未取得独立现场截图或人工见证（NOT_OBSERVED）。阅读器截图不能补历史同步见证；包内路径仅作文本，不打开媒体。", style = MaterialTheme.typography.bodySmall)
                        Text("只读外部文件；保存仅写独立本机目录。不采集、导出、分享、上传或授予动作资格。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (document == null) {
                if (state.entries.isEmpty()) item { Text("暂无已保存的私有副本。先选择文件预览，再确认保存。", color = colors.muted) }
                items(state.entries, key = { it.id }) { entry ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(if (entry.error == null) "已保存私有副本" else "无法读取的私有副本", style = MaterialTheme.typography.titleSmall)
                            SelectionContainer { Text("SHA-256：${entry.id}", style = MaterialTheme.typography.bodySmall) }
                            entry.byteCount?.let { Text("原字节：$it B", style = MaterialTheme.typography.bodySmall) }
                            entry.error?.let { Text(it, color = colors.fair) }
                            TextButton(onClick = { action { controller.open(entry.id) } }, enabled = !busy && !choosing) { Text("从磁盘重开") }
                        }
                    }
                }
            } else {
                item {
                    Text(if (state.saved) "已保存本机副本" else "未保存预览", style = MaterialTheme.typography.titleMedium)
                    SelectionContainer { Text("文件 SHA-256：${document.id}", style = MaterialTheme.typography.bodySmall) }
                    Text("原字节：${document.byteCount} B；SHA 不认证输入声明。", style = MaterialTheme.typography.bodySmall)
                    if (!state.saved) Button(onClick = { confirmSave = true }, enabled = !busy && !choosing) { Text("确认保存原字节到本机") }
                }
                // Display paths are labels, not unique IDs (unknown JSON keys may contain dots).
                items(PrivateSdkObservationPresentation.fields(document.root)) { field ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("${field.path} · ${field.type}", style = MaterialTheme.typography.titleSmall)
                            SelectionContainer {
                                Text(field.literal.take(4000), fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            if (field.literal.length > 4000) Text("本段只显示前 4000 字符，完整原字节保持不变。", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
    if (confirmSave) AlertDialog(onDismissRequest = { confirmSave = false },
        title = { Text("保存私有副本？") },
        text = { Text("只保存本次预览的原始字节到本机独立目录；不改原文件，不认证观察、模式或动作资格。") },
        confirmButton = { TextButton(onClick = {
            confirmSave = false
            action(preservePreview = true) { controller.confirmSave() }
        }) { Text("确认保存") } },
        dismissButton = { TextButton(onClick = { confirmSave = false }) { Text("暂不保存") } })
    discardAction?.let { next ->
        AlertDialog(onDismissRequest = { discardAction = null }, title = { Text("关闭未保存预览？") },
            text = { Text("本次只是预览。关闭后需要重新选择原文件；不会自动保存。") },
            confirmButton = { TextButton(onClick = { discardAction = null; next() }) { Text("关闭预览") } },
            dismissButton = { TextButton(onClick = { discardAction = null }) { Text("继续阅读") } })
    }
}
