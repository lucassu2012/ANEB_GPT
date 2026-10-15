package com.aneb.probe.ui.research

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aneb.probe.research.*
import com.aneb.probe.ui.theme.AnebTheme
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Only named children of an explicitly chosen SAF tree. No URLs, raw paths, or persistent grants. */
internal class SelectedEngineeringTree(private val resolver: ContentResolver, private val tree: Uri) : EngineeringObservationSource {
    private data class Child(val id: String, val directory: Boolean)
    private val children = mutableMapOf<String, Map<String, Child>>()
    override fun open(relativePath: String): InputStream {
        require(DocumentsContract.isTreeUri(tree))
        val parts = relativePath.split('/')
        require(parts.isNotEmpty() && parts.all { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,159}")) })
        var id = DocumentsContract.getTreeDocumentId(tree)
        parts.forEachIndexed { index, name ->
            val child = children.getOrPut(id) { readChildren(id) }[name]
                ?: throw IllegalArgumentException("所选目录缺少清单中的文件")
            require(child.directory == (index < parts.lastIndex)) { "原件路径类型不符" }
            id = child.id
        }
        return checkNotNull(resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, id)))
    }
    private fun readChildren(id: String): Map<String, Child> {
        val result = linkedMapOf<String, Child>()
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, id), columns, null, null, null).use { cursor ->
            checkNotNull(cursor)
            while (cursor.moveToNext()) {
                require(result.size < EngineeringObservationStore.MAX_FILES + 100) { "所选目录项目过多" }
                val name = cursor.getString(1)
                require(name !in result) { "所选目录有重名项目；不自动选择" }
                result[name] = Child(cursor.getString(0), cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR)
            }
        }
        return result
    }
}

@Composable
fun EngineeringObservationReaderScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val controller = remember(context) {
        EngineeringObservationReaderController(EngineeringObservationStore(File(context.noBackupFilesDir, "engineering-observations")))
    }
    var state by remember(controller) { mutableStateOf(controller.state) }
    var busy by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    var confirmSave by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf<(() -> Unit)?>(null) }
    var selected by remember { mutableStateOf<Pair<EngineeringObservationAsset, File>?>(null) }
    var mediaNotice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun action(block: () -> EngineeringObservationReaderState) {
        if (busy || choosing) return
        selected = null; mediaNotice = null; busy = true
        state = state.copy(notice = "正在读取／复制并核对工程文件；大批原件可能需要数分钟，请勿中断。")
        scope.launch {
            try { state = withContext(Dispatchers.IO) { block() } }
            catch (e: CancellationException) { state = controller.clear("操作已取消；未显示旧记录"); throw e }
            finally { busy = false }
        }
    }
    fun leave(next: () -> Unit) {
        if (busy || choosing) return
        if (state.document != null && !state.saved) discard = next else next()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        choosing = false
        action { controller.preview(uri?.let { SelectedEngineeringTree(context.contentResolver, it) }) }
    }
    LaunchedEffect(controller) {
        busy = true
        try { state = withContext(Dispatchers.IO) { controller.showList() } }
        finally { busy = false }
    }
    BackHandler {
        if (selected != null) selected = null else leave(onBack)
    }
    val colors = AnebTheme.colors
    selected?.let { (asset, file) ->
        Column(Modifier.fillMaxSize().background(colors.background).padding(horizontal = 16.dp)) {
            TextButton(onClick = { selected = null }) { Text("关闭原件查看 · 返回对应批次") }
            Text(asset.path.substringAfterLast('/'), style = MaterialTheme.typography.headlineSmall, color = colors.ink)
            SelectionContainer { Text(asset.path, style = MaterialTheme.typography.bodySmall) }
            SelectionContainer { Text("${asset.bytes} B · SHA-256 ${asset.sha256}", style = MaterialTheme.typography.bodySmall) }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 80.dp)) {
                item {
                    when {
                        asset.path.endsWith(".png") -> EngineeringPng(file)
                        asset.path.endsWith(".mp4") -> EngineeringCapturePlayback(file)
                        else -> EngineeringOriginalText(file)
                    }
                }
            }
        }
        return
    }
    Column(Modifier.fillMaxSize().background(colors.background).padding(horizontal = 16.dp)) {
        Row {
            TextButton(enabled = !busy && !choosing, onClick = { leave(onBack) }) { Text("返回研究记录") }
            TextButton(enabled = !busy && !choosing, onClick = { leave { action { controller.showList() } } }) { Text("本机工程列表") }
        }
        Text("工程采集原件", style = MaterialTheme.typography.headlineSmall, color = colors.ink)
        Text("私有工程材料 · 不是正式网络体验结论", color = colors.muted)
        Text(state.notice, style = MaterialTheme.typography.bodySmall, color = colors.ink,
            modifier = Modifier.padding(vertical = 6.dp))
        if (busy || choosing) LinearProgressIndicator(Modifier.fillMaxWidth())
        OutlinedButton(enabled = !busy && !choosing, onClick = {
            leave {
                selected = null; mediaNotice = null
                state = controller.clear("请选择直接包含 PAIR_RESULT.json 的 HOST 目录；不会自动保存")
                choosing = true
                try { picker.launch(null) }
                catch (_: Exception) { choosing = false; state = controller.clear("无法打开目录选择器；未显示旧记录") }
            }
        }) { Text("选择工程采集目录") }
        val document = state.document
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("TTFR / Completion / Stall / RPI：NA／未计算")
                        Text("保留 HOST 与 Native 的输入声明，不从录像时戳、帧数或回复内容推算指标／网络因果。正式新增 0，reference 未接受。", style = MaterialTheme.typography.bodySmall)
                        Text("只读取明确选取目录中清单引用的原件；确认后复制到本机。不上网、不上传、不采集或发送。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (document == null) {
                if (state.entries.isEmpty()) item { Text("暂无工程副本。可预览 HOST 原目录，确认后保存；旧 JSON / SDK 观察入口不变。", color = colors.muted) }
                items(state.entries, key = { it.id }) { entry ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            SelectionContainer { Text("清单 SHA-256：${entry.id}", style = MaterialTheme.typography.bodySmall) }
                            entry.error?.let { Text(it, color = colors.fair) }
                            TextButton(enabled = !busy && !choosing, onClick = { action { controller.open(entry.id) } }) { Text("从本机重开工程原件") }
                        }
                    }
                }
            } else {
                item {
                    Text(if (state.saved) "已保存本机完整副本" else "未保存：仅清单预览", style = MaterialTheme.typography.titleMedium)
                    Text("HOST 输入状态：${document.state}（不是正式验收通过）", style = MaterialTheme.typography.bodySmall)
                    SelectionContainer { Text("清单 SHA-256：${document.id}", style = MaterialTheme.typography.bodySmall) }
                    Text("${document.attempts.sumOf { it.assets.size }} 个原件 · ${document.attempts.sumOf { a -> a.assets.sumOf { it.bytes } }} B", style = MaterialTheme.typography.bodySmall)
                    if (!state.saved) Button(enabled = !busy && !choosing, onClick = { confirmSave = true }) { Text("确认复制全部原件到本机") }
                    Text("SHA 一致只证明复制关系，不认证源真。未保存预览不能打开原件。", style = MaterialTheme.typography.bodySmall)
                }
                document.attempts.forEach { attempt ->
                    item(key = attempt.runId) {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text("${attempt.condition} · ${if (attempt.condition == "W1") "Wi-Fi 条件" else "移动网络条件"}", style = MaterialTheme.typography.titleMedium)
                                SelectionContainer { Text(attempt.runId, style = MaterialTheme.typography.bodySmall) }
                                Text("HOST 轮次摘要：${attempt.state} · SEND 原值 ${attempt.sendCalls} · owned_cleanup=${attempt.ownedCleanup}", style = MaterialTheme.typography.bodySmall)
                                Text("Native 原值请打开 TERMINAL.json；HOST 后置检查与 Native 清场不是同一层。", style = MaterialTheme.typography.bodySmall)
                                Text("HOLD／UNKNOWN／NOT_RUN 不转换为成功；本机复制与原始执行是两回事。", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    val visible = attempt.assets.filter { asset ->
                        val name = asset.path.substringAfterLast('/')
                        name in setOf("TERMINAL.json", "SEND_INTENT.json", "FINAL_MEDIA.json", "video.mp4") || name.endsWith(".png") || name.endsWith(".xml")
                    }
                    items(visible, key = { it.path }) { asset ->
                        OutlinedButton(enabled = state.saved && !busy && !choosing, onClick = {
                            selected = null; mediaNotice = null; busy = true
                            scope.launch {
                                try { selected = asset to withContext(Dispatchers.IO) { controller.originalFile(asset.path) } }
                                catch (e: CancellationException) { throw e }
                                catch (_: Exception) { mediaNotice = "原件缺失或 SHA 已改变；未打开，也未显示旧文件。" }
                                finally { busy = false }
                            }
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("${attempt.condition} · ${if (asset.path.endsWith(".mp4")) "播放本次采屏录像" else "查看"} ${asset.path.substringAfterLast('/')}")
                        }
                    }
                }
            }
            mediaNotice?.let { message -> item { Text(message, color = colors.fair) } }
        }
    }
    if (confirmSave) AlertDialog(onDismissRequest = { confirmSave = false }, title = { Text("复制全部工程原件？") },
        text = { Text("只复制清单引用的原字节到独立本机目录，全部核对大小与 SHA 后才显示已保存。外部文件不改；不上传、不评分。最多 6000 件／总计 128 MiB，单件 64 MiB。") },
        confirmButton = { TextButton(onClick = { confirmSave = false; action { controller.confirmSave() } }) { Text("确认保存") } },
        dismissButton = { TextButton(onClick = { confirmSave = false }) { Text("暂不保存") } })
    discard?.let { next -> AlertDialog(onDismissRequest = { discard = null }, title = { Text("关闭未保存预览？") },
        text = { Text("这次只读了清单，没有保存。关闭后需要重新选择目录。") },
        confirmButton = { TextButton(onClick = { discard = null; next() }) { Text("关闭预览") } },
        dismissButton = { TextButton(onClick = { discard = null }) { Text("继续阅读") } }) }
}

@Composable
private fun EngineeringPng(file: File) {
    var bitmap by remember(file) { mutableStateOf<Bitmap?>(null) }
    var message by remember(file) { mutableStateOf("正在解码本机原图…") }
    LaunchedEffect(file) {
        var decoded: Bitmap? = null
        try {
            withContext(Dispatchers.IO) {
                require(file.length() <= 8 * 1024 * 1024)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                require(bounds.outMimeType == "image/png" && bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096 &&
                    bounds.outWidth.toLong() * bounds.outHeight <= 4_000_000)
                decoded = checkNotNull(BitmapFactory.decodeFile(file.path))
            }
            bitmap = decoded; decoded = null
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { message = "原件保留，但 PNG 不可解码或超过 8 MiB／400 万像素显示上限；未显示旧图。" }
        finally { decoded?.recycle() }
    }
    DisposableEffect(bitmap) { val owned = bitmap; onDispose { owned?.recycle() } }
    val picture = bitmap
    if (picture == null) Text(message, style = MaterialTheme.typography.bodySmall)
    else {
        Text("${picture.width} × ${picture.height} · 按比例显示，原文件未缩改", style = MaterialTheme.typography.bodySmall)
        Image(picture.asImageBitmap(), "本次对应轮的工程原始截图", Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 640.dp), contentScale = ContentScale.Fit)
    }
}

@Composable
private fun EngineeringOriginalText(file: File) {
    var text by remember(file) { mutableStateOf("正在读取原文…") }
    LaunchedEffect(file) {
        text = try {
            withContext(Dispatchers.IO) {
                val shown = StringBuilder()
                java.io.InputStreamReader(file.inputStream(), Charsets.UTF_8.newDecoder()).use { reader ->
                    val buffer = CharArray(4096)
                    while (shown.length <= 32_000) {
                        val count = reader.read(buffer, 0, minOf(buffer.size, 32_001 - shown.length))
                        if (count < 0) break
                        shown.append(buffer, 0, count)
                    }
                }
                shown.take(32_000).toString() + if (shown.length > 32_000) "\n［仅显示前 32000 字符，完整原件已保存］" else ""
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { "无法按 UTF-8 显示；原件保留，没有显示旧文本。" }
    }
    SelectionContainer { Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun EngineeringCapturePlayback(file: File) {
    val lifecycle = LocalLifecycleOwner.current
    var video by remember(file) { mutableStateOf<VideoView?>(null) }
    var notice by remember(file) { mutableStateOf("正在打开本机采屏；尚未确认画面渲染。") }
    DisposableEffect(lifecycle, file) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { video?.stopPlayback(); notice = "已随页面后台停止播放器；重新选择录像可再次播放。" }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); video?.stopPlayback(); video = null }
    }
    Text("本次聊天采屏回看，不是目标视频播放体验；播放器进度不用于研究计时。", style = MaterialTheme.typography.bodySmall)
    Text(notice, style = MaterialTheme.typography.bodySmall)
    AndroidView(modifier = Modifier.fillMaxWidth().height(360.dp), factory = { context ->
        VideoView(context).also { view ->
            video = view
            view.setMediaController(MediaController(context).apply { setAnchorView(view) })
            view.setOnPreparedListener { view.start() }
            view.setOnInfoListener { _, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) notice = "播放器报告首画面已渲染；不表示整段覆盖或研究验收。"
                false
            }
            view.setOnCompletionListener { notice = "本机播放器到达结尾；这不是原始观察完整性或性能结论。" }
            view.setOnErrorListener { _, _, _ -> notice = "设备无法播放此原件；文件保留，未显示旧录像。"; view.stopPlayback(); true }
            view.setVideoPath(file.path)
        }
    })
}
