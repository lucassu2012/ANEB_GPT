package com.aneb.probe.ui.research

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.aneb.probe.research.PrivateSdkObservationDocument
import com.aneb.probe.research.PrivateSdkObservationEntry
import com.aneb.probe.research.PrivateSdkObservationImage
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
        if (root["objects"] is JsonObject) {
            val objects = root["objects"] as JsonObject
            listOf("deep_label", "deep_parent", "search_label", "search_parent").forEach {
                section("objects.$it", objects[it], listOf("checked", "checkable", "visible", "enabled", "window_id"))
            }
            section("literal_fields", root["literal_fields"], listOf("owner", "action_target", "actions"))
            section("sdk_clock", root["sdk_clock"], listOf("domain", "atomic", "ttl"))
            section("limits", root["limits"], listOf("independent_pixel_or_human_witness", "checked_means_mode_on_off", "backend_mode", "phone_import_export_reopen"))
        }
        fun historicalField(path: String, value: JsonElement) {
            if (value is JsonObject && value.isNotEmpty()) value.forEach { (key, child) -> historicalField("$path.$key", child) }
            else field(path, value)
        }
        listOf("visible_witness", "sdk", "time_boundaries").forEach { key ->
            root[key]?.let { historicalField(key, it) }
        }
        val legacyKeys = if (root["objects"] is JsonObject) setOf("objects", "literal_fields", "sdk_clock", "limits") else emptySet()
        root.filterKeys { it !in legacyKeys && it !in setOf("task", "classification", "verdict", "nonce", "visible_witness", "sdk", "time_boundaries") }
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
            val saved = store.save(preview)
            state = state.copy(document = saved, saved = true,
                notice = "本机原字节副本已保存；SHA 只标识文件，不认证观察内容")
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { clearSelection("保存失败：请检查本机存储；未覆盖已有副本，也未显示旧成功") }
        return state
    }

    fun attachPng(open: (() -> InputStream?)?): PrivateSdkObservationReaderState {
        clearImage()
        val withoutImage = state.document ?: return state
        if (open == null) return state.copy(notice = "已取消选图；图片未附／NOT_OBSERVED_BY_READER").also { state = it }
        try {
            val bytes = checkNotNull(open()).use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= PrivateSdkObservationImage.MAX_BYTES)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            state = state.copy(document = store.attachPng(withoutImage, bytes),
                notice = "所选 PNG 符合输入声明；不认证历史观察或同步性")
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            state = state.copy(notice = "图片未附：所选 PNG 不匹配、不可读或超限；旧图已清除，原文件未修改")
        }
        return state
    }

    fun clearImage(notice: String = "图片未附／NOT_OBSERVED_BY_READER"): PrivateSdkObservationReaderState {
        val current = state.document ?: return clearSelection("请先选择 JSON，再明确选择 PNG")
        state = state.copy(document = PrivateSdkObservationDocument(current.id, current.root,
            current.originalBytes(), requiresImage = current.requiresImage), saved = false, notice = notice)
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
        catch (_: Exception) { clearSelection("无法重开：JSON、PNG 或关联清单缺失、损坏或超限；未显示旧记录和旧图，原文件保留") }
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
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        choosing = false
        action(preservePreview = true) { controller.attachPng(uri?.let { { context.contentResolver.openInputStream(it) } }) }
    }
    val attachment = state.document?.image
    var displayedImage by remember(attachment) { mutableStateOf<ImageBitmap?>(null) }
    var decodingImage by remember(attachment) { mutableStateOf(attachment != null) }
    LaunchedEffect(attachment) {
        if (attachment != null) {
            try {
                displayedImage = withContext(Dispatchers.IO) {
                    val bytes = attachment.originalBytes()
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { displayedImage = null }
            finally { decodingImage = false }
        }
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
        Text("私有历史观察卡", style = MaterialTheme.typography.headlineSmall, color = colors.ink)
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
                        Text("非原子历史观察：取得时点来自输入声明，时间域不合并。semantic/backend 仍为 UNKNOWN；不能从 getter 或图片推断模式、owner 或同步见证。", style = MaterialTheme.typography.bodySmall)
                        Text("包内路径／网址仅作声明文字，不自动打开。未附图表示 NOT_OBSERVED_BY_READER，不表示历史从未采集；阅读器截图不能倒填旧采集见证。", style = MaterialTheme.typography.bodySmall)
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
                    if (!state.saved) Button(onClick = { confirmSave = true },
                        enabled = !busy && !choosing && (!document.requiresImage || document.image != null)) { Text("确认保存原字节到本机") }
                    if (document.requiresImage && document.image == null) Text("先选择与声明匹配的 PNG，才能保存这张观察卡。", color = colors.muted)
                }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("历史原图 · 由你明确选取", style = MaterialTheme.typography.titleMedium)
                            if (attachment == null) {
                                Text("图片未附／NOT_OBSERVED_BY_READER", color = colors.muted)
                            } else {
                                Text("所选字节符合输入声明，不认证源真或历史同步。", style = MaterialTheme.typography.bodySmall)
                                SelectionContainer { Text("PNG SHA-256：${attachment.sha256}", style = MaterialTheme.typography.bodySmall) }
                                Text("${attachment.byteCount} B · ${attachment.width} × ${attachment.height} · 按比例显示，原字节未改", style = MaterialTheme.typography.bodySmall)
                                val picture = displayedImage
                                if (picture != null) Image(picture, "当前私有观察声明匹配的 PNG",
                                    Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 640.dp), contentScale = ContentScale.Fit)
                                else Text(if (decodingImage) "正在解码显示…" else "显示解码失败；没有展示旧图。", color = colors.muted)
                            }
                            OutlinedButton(enabled = !busy && !choosing && document.root["visible_witness"] is JsonObject, onClick = {
                                state = controller.clearImage("等待明确选择 PNG；旧图已清除，不会自动保存")
                                choosing = true
                                try { imagePicker.launch(arrayOf("image/png")) }
                                catch (_: Exception) {
                                    choosing = false
                                    state = controller.clearImage("无法打开选图器；图片未附，JSON 保留，可重试")
                                }
                            }) { Text("选择 PNG · 最多 8 MiB／400 万像素") }
                            Text("SDK 字段以 JSON 解析值及类型显示；历史时点、非原子和未知边界均保留输入声明。", style = MaterialTheme.typography.bodySmall)
                        }
                    }
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
        text = { Text("只保存本次 JSON 与已匹配 PNG 的原始字节到本机私有目录；无图旧格式仍保存 JSON。原外部文件不改，不分享或上传，不认证观察、模式或动作资格。") },
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
